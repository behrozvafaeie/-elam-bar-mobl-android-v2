-- Apply granular chat permissions to managers and filter admin contacts by their chat access.
create or replace function private.user_has_admin_section_permission(p_user_id uuid,p_section_key text,p_action text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when p_user_id is null or p_action not in ('view','create','edit','delete') then false
    when exists (
      select 1 from public.admin_users au
      where au.user_id=p_user_id and au.role='super_admin'
        and au.is_blocked=false
        and (au.access_expires_at is null or au.access_expires_at>pg_catalog.now())
    ) then true
    else exists (
      select 1
      from public.admin_users au
      join public.admin_section_permissions sp on sp.admin_user_id=au.user_id
      where au.user_id=p_user_id
        and au.role in ('senior_admin','admin')
        and au.is_blocked=false
        and (au.access_expires_at is null or au.access_expires_at>pg_catalog.now())
        and sp.section_key=p_section_key
        and case p_action
          when 'view' then sp.can_view
          when 'create' then sp.can_create
          when 'edit' then sp.can_edit
          when 'delete' then sp.can_delete
          else false
        end
    )
  end;
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
    or (
      private.chat_user_kind(auth.uid())='admin'
      and private.has_admin_section_permission('chat','create')
      and exists (
        select 1 from public.chat_rooms r
        where r.id=p_room_id and r.room_type='private' and r.created_by=auth.uid()
          and private.chat_user_kind(p_user_id) is not null
          and (p_user_id=auth.uid() or (
            (private.chat_user_kind(p_user_id)<>'admin')
            or private.user_has_admin_section_permission(p_user_id,'chat','view')
          ))
          and not exists (select 1 from public.chat_room_members m where m.room_id=r.id and m.user_id=p_user_id)
          and (select count(*) from public.chat_room_members m where m.room_id=r.id)<2
      )
    )
    or (
      private.chat_user_kind(auth.uid())='company'
      and exists (
        select 1 from public.chat_rooms r
        where r.id=p_room_id and r.room_type='private' and r.created_by=auth.uid()
          and private.chat_user_kind(p_user_id) in ('company','admin')
          and (private.chat_user_kind(p_user_id)<>'admin' or private.user_has_admin_section_permission(p_user_id,'chat','view'))
          and not exists (select 1 from public.chat_room_members m where m.room_id=r.id and m.user_id=p_user_id)
          and (select count(*) from public.chat_room_members m where m.room_id=r.id)<2
      )
    )
    or (
      private.chat_user_kind(auth.uid())='driver'
      and exists (
        select 1 from public.chat_rooms r
        where r.id=p_room_id and r.room_type='private' and r.created_by=auth.uid()
          and private.chat_user_kind(p_user_id)='admin'
          and private.user_has_admin_section_permission(p_user_id,'chat','view')
          and not exists (select 1 from public.chat_room_members m where m.room_id=r.id and m.user_id=p_user_id)
          and (select count(*) from public.chat_room_members m where m.room_id=r.id)<2
      )
    )
  );
$function$;

create or replace function private.search_chat_contacts(p_query text)
returns table(user_id uuid,display_name text,account_type text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_kind text;
  v_query text := pg_catalog.btrim(coalesce(p_query,''));
  v_phone text;
begin
  if auth.uid() is null
     or coalesce(((auth.jwt())->>'is_anonymous')::boolean,false)
     or pg_catalog.length(v_query) < 2
     or pg_catalog.length(v_query) > 80 then
    return;
  end if;

  v_kind := private.chat_user_kind(auth.uid());
  if v_kind is null then return; end if;
  if v_kind='admin' and not (
    private.has_admin_section_permission('chat','view')
    or private.has_admin_section_permission('chat','create')
  ) then return; end if;

  v_phone := pg_catalog.translate(
    pg_catalog.regexp_replace(v_query,'[^0-9۰-۹٠-٩]','','g'),
    '۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩',
    '01234567890123456789'
  );

  return query
  select c.id,c.company_name,'company'::text
  from public.companies c
  where v_kind in ('company','admin')
    and c.id <> auth.uid()
    and c.status='approved'
    and (c.company_name ilike '%'||v_query||'%' or (v_phone<>'' and c.mobile like '%'||v_phone||'%'))

  union all

  select d.id,d.full_name,'driver'::text
  from public.drivers d
  where v_kind='admin'
    and d.id <> auth.uid()
    and d.status='approved'
    and (d.full_name ilike '%'||v_query||'%' or (v_phone<>'' and d.mobile like '%'||v_phone||'%'))

  union all

  select au.user_id,
    case au.role when 'super_admin' then 'مدیر ارشد اصلی' when 'senior_admin' then 'ادمین ارشد' else 'ادمین سامانه' end,
    'admin'::text
  from public.admin_users au
  join auth.users u on u.id=au.user_id
  where v_kind in ('company','driver','admin')
    and au.user_id <> auth.uid()
    and au.is_blocked=false
    and (au.access_expires_at is null or au.access_expires_at > pg_catalog.now())
    and private.user_has_admin_section_permission(au.user_id,'chat','view')
    and (v_query ilike '%مدیر%' or v_query ilike '%ادمین%' or (v_phone<>'' and coalesce(u.phone,'') like '%'||v_phone||'%'))
  order by 2
  limit 20;
end;
$function$;

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

  if v_actor_kind='admin' and not private.has_admin_section_permission('chat','create') then
    raise exception 'chat_create_permission_required' using errcode='42501';
  end if;
  if v_target_kind='admin' and not private.user_has_admin_section_permission(p_target_user_id,'chat','view') then
    raise exception 'target_admin_not_available_for_chat' using errcode='42501';
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
  values (v_room_id,v_actor,true,true,v_actor),(v_room_id,p_target_user_id,true,true,v_actor);
  return v_room_id;
end;
$function$;

revoke all on function private.user_has_admin_section_permission(uuid,text,text) from public,anon;
grant execute on function private.user_has_admin_section_permission(uuid,text,text) to authenticated;
