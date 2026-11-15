-- Atomically create one-to-one private conversations for the supported role pairs.
create or replace function private.create_private_chat_room(p_target_user_id uuid,p_title text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_actor uuid := auth.uid();
  v_actor_kind text;
  v_target_kind text;
  v_room_id uuid;
  v_title text := pg_catalog.left(pg_catalog.btrim(coalesce(p_title,'')),160);
begin
  if v_actor is null or coalesce(((auth.jwt())->>'is_anonymous')::boolean,false) then
    raise exception 'authentication_required' using errcode='42501';
  end if;
  if p_target_user_id is null or p_target_user_id=v_actor then
    raise exception 'invalid_chat_target' using errcode='22023';
  end if;

  v_actor_kind := private.chat_user_kind(v_actor);
  v_target_kind := private.chat_user_kind(p_target_user_id);
  if v_actor_kind is null or v_target_kind is null then
    raise exception 'chat_target_not_active' using errcode='42501';
  end if;

  if not (
    private.is_super_admin()
    or (v_actor_kind='company' and v_target_kind in ('company','admin'))
    or (v_actor_kind='driver' and v_target_kind='admin')
    or (v_actor_kind='admin' and v_target_kind in ('company','driver','admin'))
  ) then
    raise exception 'private_chat_pair_not_allowed' using errcode='42501';
  end if;

  if v_title='' then v_title := 'گفتگوی خصوصی'; end if;
  insert into public.chat_rooms(room_type,title,created_by)
  values ('private',v_title,v_actor)
  returning id into v_room_id;

  insert into public.chat_room_members(room_id,user_id,can_read,can_send,added_by)
  values
    (v_room_id,v_actor,true,true,v_actor),
    (v_room_id,p_target_user_id,true,true,v_actor);

  return v_room_id;
end;
$function$;

create or replace function public.create_private_chat_room(p_target_user_id uuid,p_title text)
returns uuid
language sql
security invoker
set search_path = ''
as $function$
  select private.create_private_chat_room(p_target_user_id,p_title);
$function$;

revoke all on function private.create_private_chat_room(uuid,text) from public,anon;
grant execute on function private.create_private_chat_room(uuid,text) to authenticated;
revoke all on function public.create_private_chat_room(uuid,text) from public,anon;
grant execute on function public.create_private_chat_room(uuid,text) to authenticated;
