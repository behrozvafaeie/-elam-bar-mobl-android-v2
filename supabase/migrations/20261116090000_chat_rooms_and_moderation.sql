
-- Additive chat foundation for public company chat and private conversations.
-- Existing load conversations and their data remain untouched.

create table if not exists public.chat_rooms (
  id uuid primary key default gen_random_uuid(),
  room_type text not null check (room_type in ('public','private')),
  title text not null check (length(btrim(title)) between 2 and 160),
  created_by uuid references auth.users(id) on delete set null,
  is_locked boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.chat_room_members (
  room_id uuid not null references public.chat_rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  can_read boolean not null default true,
  can_send boolean not null default true,
  added_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  primary key (room_id,user_id)
);

create table if not exists public.chat_room_company_settings (
  room_id uuid not null references public.chat_rooms(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  is_muted boolean not null default false,
  read_only boolean not null default false,
  can_send_images boolean not null default false,
  can_send_voice boolean not null default true,
  updated_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  primary key (room_id,company_id)
);

create table if not exists public.chat_room_messages (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.chat_rooms(id) on delete cascade,
  sender_user_id uuid not null references auth.users(id) on delete restrict,
  message_type text not null check (message_type in ('text','image','voice')),
  body text,
  attachment_path text,
  is_pinned boolean not null default false,
  deleted_at timestamptz,
  deleted_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint chat_room_message_content_check check (
    (message_type='text' and body is not null and length(btrim(body)) between 1 and 4000 and attachment_path is null)
    or
    (message_type in ('image','voice') and attachment_path is not null and length(btrim(attachment_path)) between 3 and 700)
  )
);

create index if not exists chat_room_members_user_room_idx on public.chat_room_members(user_id,room_id);
create index if not exists chat_room_messages_room_created_idx on public.chat_room_messages(room_id,created_at desc);
create index if not exists chat_room_messages_sender_created_idx on public.chat_room_messages(sender_user_id,created_at desc);

create or replace function private.chat_user_kind(p_user_id uuid)
returns text
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when exists (
      select 1 from public.admin_users au
      where au.user_id=p_user_id and au.is_blocked=false
        and (au.access_expires_at is null or au.access_expires_at > pg_catalog.now())
    ) then 'admin'
    when exists (select 1 from public.companies c where c.id=p_user_id and c.status='approved') then 'company'
    when exists (select 1 from public.drivers d where d.id=p_user_id and d.status='approved') then 'driver'
    else null
  end;
$function$;

create or replace function private.can_read_chat_room(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select auth.uid() is not null and exists (
    select 1
    from public.chat_rooms r
    where r.id=p_room_id
      and (
        (r.room_type='public' and (
          exists (select 1 from public.companies c where c.id=auth.uid() and c.status='approved')
          or private.has_admin_section_permission('chat','view')
          or exists (select 1 from public.chat_room_members m where m.room_id=r.id and m.user_id=auth.uid() and m.can_read)
        ))
        or
        (r.room_type='private' and (
          private.is_super_admin()
          or exists (select 1 from public.chat_room_members m where m.room_id=r.id and m.user_id=auth.uid() and m.can_read)
        ))
      )
  );
$function$;

create or replace function private.can_send_chat_room_message(p_room_id uuid,p_message_type text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select auth.uid() is not null and exists (
    select 1
    from public.chat_rooms r
    where r.id=p_room_id and r.is_locked=false
      and p_message_type in ('text','image','voice')
      and (
        private.is_super_admin()
        or (
          r.room_type='public'
          and private.has_admin_section_permission('chat','create')
        )
        or (
          r.room_type='public'
          and exists (
            select 1 from public.companies c
            where c.id=auth.uid() and c.status='approved'
          )
          and not exists (
            select 1 from public.chat_room_company_settings s
            where s.room_id=r.id and s.company_id=auth.uid()
              and (s.is_muted=true or s.read_only=true)
          )
          and (
            p_message_type='text'
            or exists (
              select 1 from public.chat_room_company_settings s
              where s.room_id=r.id and s.company_id=auth.uid()
                and ((p_message_type='image' and s.can_send_images=true)
                  or (p_message_type='voice' and s.can_send_voice=true))
            )
          )
        )
        or (
          r.room_type='private'
          and exists (
            select 1 from public.chat_room_members m
            where m.room_id=r.id and m.user_id=auth.uid()
              and m.can_read=true and m.can_send=true
          )
        )
      )
  );
$function$;

create or replace function private.can_add_chat_room_member(p_room_id uuid,p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select auth.uid() is not null and (
    private.is_super_admin()
    or exists (
      select 1 from public.chat_rooms r
      where r.id=p_room_id and r.room_type='private'
        and r.created_by=auth.uid()
        and private.chat_user_kind(auth.uid()) is not null
        and private.chat_user_kind(p_user_id) is not null
        and not exists (
          select 1 from public.chat_room_members m
          where m.room_id=r.id and m.user_id=p_user_id
        )
        and (select count(*) from public.chat_room_members m where m.room_id=r.id) < 2
    )
  );
$function$;

revoke all on function private.chat_user_kind(uuid) from public, anon;
revoke all on function private.can_read_chat_room(uuid) from public, anon;
revoke all on function private.can_send_chat_room_message(uuid,text) from public, anon;
revoke all on function private.can_add_chat_room_member(uuid,uuid) from public, anon;
grant execute on function private.chat_user_kind(uuid) to authenticated;
grant execute on function private.can_read_chat_room(uuid) to authenticated;
grant execute on function private.can_send_chat_room_message(uuid,text) to authenticated;
grant execute on function private.can_add_chat_room_member(uuid,uuid) to authenticated;

alter table public.chat_rooms enable row level security;
alter table public.chat_room_members enable row level security;
alter table public.chat_room_company_settings enable row level security;
alter table public.chat_room_messages enable row level security;

drop policy if exists chat_rooms_select_authorized on public.chat_rooms;
create policy chat_rooms_select_authorized on public.chat_rooms
for select to authenticated using (private.can_read_chat_room(id));

drop policy if exists chat_rooms_insert_private_creator on public.chat_rooms;
create policy chat_rooms_insert_private_creator on public.chat_rooms
for insert to authenticated
with check (room_type='private' and created_by=auth.uid() and private.chat_user_kind(auth.uid()) is not null);

drop policy if exists chat_rooms_update_super_admin on public.chat_rooms;
create policy chat_rooms_update_super_admin on public.chat_rooms
for update to authenticated
using (private.is_super_admin())
with check (private.is_super_admin());

drop policy if exists chat_rooms_delete_super_admin on public.chat_rooms;
create policy chat_rooms_delete_super_admin on public.chat_rooms
for delete to authenticated using (private.is_super_admin());

drop policy if exists chat_room_members_select_room_access on public.chat_room_members;
create policy chat_room_members_select_room_access on public.chat_room_members
for select to authenticated using (private.can_read_chat_room(room_id));

drop policy if exists chat_room_members_insert_controlled on public.chat_room_members;
create policy chat_room_members_insert_controlled on public.chat_room_members
for insert to authenticated
with check (private.can_add_chat_room_member(room_id,user_id));

drop policy if exists chat_room_members_update_super_admin on public.chat_room_members;
create policy chat_room_members_update_super_admin on public.chat_room_members
for update to authenticated
using (private.is_super_admin())
with check (private.is_super_admin());

drop policy if exists chat_room_members_delete_super_admin on public.chat_room_members;
create policy chat_room_members_delete_super_admin on public.chat_room_members
for delete to authenticated using (private.is_super_admin());

drop policy if exists chat_room_company_settings_super_admin_all on public.chat_room_company_settings;
create policy chat_room_company_settings_super_admin_all on public.chat_room_company_settings
for all to authenticated
using (private.is_super_admin())
with check (private.is_super_admin());

drop policy if exists chat_room_messages_select_room_access on public.chat_room_messages;
create policy chat_room_messages_select_room_access on public.chat_room_messages
for select to authenticated using (private.can_read_chat_room(room_id));

drop policy if exists chat_room_messages_insert_authorized_sender on public.chat_room_messages;
create policy chat_room_messages_insert_authorized_sender on public.chat_room_messages
for insert to authenticated
with check (
  sender_user_id=auth.uid()
  and private.can_send_chat_room_message(room_id,message_type)
  and (
    (message_type='text' and attachment_path is null)
    or
    (message_type in ('image','voice')
      and attachment_path like room_id::text || '/' || auth.uid()::text || '/' || message_type || '/%'
      and exists (
        select 1 from storage.objects o
        where o.bucket_id='chat-room-attachments' and o.name=attachment_path
      ))
  )
);

drop policy if exists chat_room_messages_moderate_super_admin on public.chat_room_messages;
create policy chat_room_messages_moderate_super_admin on public.chat_room_messages
for update to authenticated
using (private.is_super_admin())
with check (private.is_super_admin());

drop policy if exists chat_room_messages_delete_super_admin on public.chat_room_messages;
create policy chat_room_messages_delete_super_admin on public.chat_room_messages
for delete to authenticated using (private.is_super_admin());

grant select,insert,update,delete on public.chat_rooms to authenticated;
grant select,insert,update,delete on public.chat_room_members to authenticated;
grant select,insert,update,delete on public.chat_room_company_settings to authenticated;
grant select,insert,update,delete on public.chat_room_messages to authenticated;

insert into public.chat_rooms(room_type,title,created_by)
select 'public','اتاق عمومی شرکت‌های باربری',null
where not exists (select 1 from public.chat_rooms where room_type='public');

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values (
  'chat-room-attachments','chat-room-attachments',false,10485760,
  array['image/jpeg','image/png','image/webp','audio/mp4','audio/aac','audio/m4a']
)
on conflict (id) do nothing;

drop policy if exists chat_room_storage_upload_authorized on storage.objects;
create policy chat_room_storage_upload_authorized on storage.objects
for insert to authenticated
with check (
  bucket_id='chat-room-attachments'
  and split_part(name,'/',2)=auth.uid()::text
  and split_part(name,'/',3) in ('image','voice')
  and private.can_send_chat_room_message(
    split_part(name,'/',1)::uuid,
    case when split_part(name,'/',3)='image' then 'image' else 'voice' end
  )
);

drop policy if exists chat_room_storage_read_authorized on storage.objects;
create policy chat_room_storage_read_authorized on storage.objects
for select to authenticated
using (
  bucket_id='chat-room-attachments'
  and private.can_read_chat_room(split_part(name,'/',1)::uuid)
);

drop policy if exists chat_room_storage_delete_authorized on storage.objects;
create policy chat_room_storage_delete_authorized on storage.objects
for delete to authenticated
using (
  bucket_id='chat-room-attachments'
  and (
    split_part(name,'/',2)=auth.uid()::text
    or private.is_super_admin()
  )
);

-- Restrict all new tables against anonymous sign-ins, matching the existing project policy.
do $$
declare t text;
begin
  foreach t in array array['chat_rooms','chat_room_members','chat_room_company_settings','chat_room_messages'] loop
    execute format(
      'drop policy if exists %I on public.%I',
      'chat_deny_anonymous_'||t,t
    );
    execute format(
      'create policy %I on public.%I as restrictive for all to authenticated using (coalesce(((select auth.jwt())->>''is_anonymous'')::boolean,false)=false) with check (coalesce(((select auth.jwt())->>''is_anonymous'')::boolean,false)=false)',
      'chat_deny_anonymous_'||t,t
    );
  end loop;
end $$;
