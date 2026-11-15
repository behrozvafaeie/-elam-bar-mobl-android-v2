-- Add secure one-to-one audio/video call signaling for private chat rooms.
-- Media transport is peer-to-peer WebRTC; this schema stores only call state and SDP/ICE signals.

create table if not exists public.chat_room_call_sessions (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.chat_rooms(id) on delete cascade,
  caller_user_id uuid not null references auth.users(id) on delete restrict,
  receiver_user_id uuid not null references auth.users(id) on delete restrict,
  call_type text not null check (call_type in ('audio','video')),
  status text not null default 'ringing' check (status in ('ringing','accepted','rejected','ended','missed','cancelled')),
  created_at timestamptz not null default now(),
  answered_at timestamptz,
  ended_at timestamptz,
  constraint chat_room_call_distinct_users check (caller_user_id<>receiver_user_id)
);

create table if not exists public.chat_room_call_signals (
  id uuid primary key default gen_random_uuid(),
  call_session_id uuid not null references public.chat_room_call_sessions(id) on delete cascade,
  sender_user_id uuid not null references auth.users(id) on delete restrict,
  recipient_user_id uuid not null references auth.users(id) on delete restrict,
  signal_type text not null check (signal_type in ('offer','answer','ice_candidate','hangup')),
  payload jsonb not null default '{}'::jsonb check (octet_length(payload::text)<=12000),
  created_at timestamptz not null default now()
);

create index if not exists chat_room_call_sessions_receiver_status_idx
  on public.chat_room_call_sessions(receiver_user_id,status,created_at desc);
create index if not exists chat_room_call_sessions_room_created_idx
  on public.chat_room_call_sessions(room_id,created_at desc);
create index if not exists chat_room_call_signals_session_created_idx
  on public.chat_room_call_signals(call_session_id,created_at);
create index if not exists chat_room_call_signals_recipient_created_idx
  on public.chat_room_call_signals(recipient_user_id,created_at);

create or replace function private.can_access_chat_room_call_session(p_call_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select auth.uid() is not null and exists (
    select 1 from public.chat_room_call_sessions cs
    where cs.id=p_call_id
      and (cs.caller_user_id=auth.uid() or cs.receiver_user_id=auth.uid() or private.is_super_admin())
      and private.can_read_chat_room(cs.room_id)
  );
$function$;

create or replace function private.create_chat_room_call_session(p_room_id uuid,p_receiver_user_id uuid,p_call_type text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_caller uuid := auth.uid();
  v_call_id uuid;
  v_room_type text;
begin
  if v_caller is null or coalesce(((auth.jwt())->>'is_anonymous')::boolean,false) then
    raise exception 'authentication_required' using errcode='42501';
  end if;
  if p_receiver_user_id is null or p_receiver_user_id=v_caller or p_call_type not in ('audio','video') then
    raise exception 'invalid_call_parameters' using errcode='22023';
  end if;

  select r.room_type into v_room_type from public.chat_rooms r where r.id=p_room_id;
  if v_room_type is distinct from 'private' then
    raise exception 'calls_require_private_chat' using errcode='42501';
  end if;
  if not private.can_read_chat_room(p_room_id) then
    raise exception 'not_a_chat_participant' using errcode='42501';
  end if;
  if not private.is_super_admin() and not exists (
    select 1 from public.chat_room_members m
    where m.room_id=p_room_id and m.user_id=v_caller and m.can_read=true and m.can_send=true
  ) then
    raise exception 'caller_cannot_start_call' using errcode='42501';
  end if;
  if not exists (
    select 1 from public.chat_room_members m
    where m.room_id=p_room_id and m.user_id=p_receiver_user_id and m.can_read=true and m.can_send=true
  ) then
    raise exception 'receiver_not_a_call_participant' using errcode='42501';
  end if;
  if exists (
    select 1 from public.chat_room_call_sessions cs
    where cs.room_id=p_room_id
      and cs.status in ('ringing','accepted')
      and cs.created_at > pg_catalog.now()-interval '2 hours'
  ) then
    raise exception 'call_already_active' using errcode='23505';
  end if;

  insert into public.chat_room_call_sessions(room_id,caller_user_id,receiver_user_id,call_type,status)
  values(p_room_id,v_caller,p_receiver_user_id,p_call_type,'ringing')
  returning id into v_call_id;
  return v_call_id;
end;
$function$;

create or replace function private.update_chat_room_call_status(p_call_id uuid,p_new_status text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_user uuid := auth.uid();
  v_count integer;
begin
  if v_user is null or coalesce(((auth.jwt())->>'is_anonymous')::boolean,false) then
    raise exception 'authentication_required' using errcode='42501';
  end if;
  if p_new_status not in ('accepted','rejected','ended','missed','cancelled') then
    raise exception 'invalid_call_status' using errcode='22023';
  end if;

  update public.chat_room_call_sessions cs
  set status=p_new_status,
      answered_at=case when p_new_status='accepted' then coalesce(cs.answered_at,pg_catalog.now()) else cs.answered_at end,
      ended_at=case when p_new_status in ('rejected','ended','missed','cancelled') then coalesce(cs.ended_at,pg_catalog.now()) else cs.ended_at end
  where cs.id=p_call_id
    and (
      (p_new_status in ('accepted','rejected','missed') and cs.receiver_user_id=v_user and cs.status='ringing')
      or (p_new_status='cancelled' and cs.caller_user_id=v_user and cs.status='ringing')
      or (p_new_status='ended' and (cs.caller_user_id=v_user or cs.receiver_user_id=v_user or private.is_super_admin()) and cs.status='accepted')
    );
  get diagnostics v_count = row_count;
  return v_count=1;
end;
$function$;

create or replace function public.create_chat_room_call_session(p_room_id uuid,p_receiver_user_id uuid,p_call_type text)
returns uuid
language sql
security invoker
set search_path = ''
as $function$
  select private.create_chat_room_call_session(p_room_id,p_receiver_user_id,p_call_type);
$function$;

create or replace function public.update_chat_room_call_status(p_call_id uuid,p_new_status text)
returns boolean
language sql
security invoker
set search_path = ''
as $function$
  select private.update_chat_room_call_status(p_call_id,p_new_status);
$function$;

revoke all on function private.can_access_chat_room_call_session(uuid) from public,anon;
revoke all on function private.create_chat_room_call_session(uuid,uuid,text) from public,anon;
revoke all on function private.update_chat_room_call_status(uuid,text) from public,anon;
revoke all on function public.create_chat_room_call_session(uuid,uuid,text) from public,anon;
revoke all on function public.update_chat_room_call_status(uuid,text) from public,anon;
grant execute on function private.can_access_chat_room_call_session(uuid) to authenticated;
grant execute on function private.create_chat_room_call_session(uuid,uuid,text) to authenticated;
grant execute on function private.update_chat_room_call_status(uuid,text) to authenticated;
grant execute on function public.create_chat_room_call_session(uuid,uuid,text) to authenticated;
grant execute on function public.update_chat_room_call_status(uuid,text) to authenticated;

alter table public.chat_room_call_sessions enable row level security;
alter table public.chat_room_call_signals enable row level security;

create policy chat_room_call_sessions_select_participants
on public.chat_room_call_sessions for select to authenticated
using (private.can_access_chat_room_call_session(id));

create policy chat_room_call_signals_select_participants
on public.chat_room_call_signals for select to authenticated
using (
  sender_user_id=auth.uid()
  or recipient_user_id=auth.uid()
  or private.is_super_admin()
);

create policy chat_room_call_signals_insert_sender
on public.chat_room_call_signals for insert to authenticated
with check (
  sender_user_id=auth.uid()
  and sender_user_id<>recipient_user_id
  and signal_type in ('offer','answer','ice_candidate','hangup')
  and private.can_access_chat_room_call_session(call_session_id)
  and exists (
    select 1 from public.chat_room_call_sessions cs
    where cs.id=call_session_id
      and (
        (cs.caller_user_id=sender_user_id and cs.receiver_user_id=recipient_user_id)
        or (cs.receiver_user_id=sender_user_id and cs.caller_user_id=recipient_user_id)
      )
      and cs.status in ('ringing','accepted')
  )
);

grant select on public.chat_room_call_sessions to authenticated;
grant select,insert on public.chat_room_call_signals to authenticated;

create policy chat_room_call_sessions_deny_anonymous
on public.chat_room_call_sessions as restrictive for all to authenticated
using (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false)
with check (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false);

create policy chat_room_call_signals_deny_anonymous
on public.chat_room_call_signals as restrictive for all to authenticated
using (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false)
with check (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false);
