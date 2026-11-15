-- Enable safe, role-aware discovery and creation of private one-to-one chats.
-- Contact lookup returns names and account IDs only; it never returns phone numbers.

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
      select 1
      from public.chat_rooms r
      where r.id=p_room_id
        and r.room_type='private'
        and r.created_by=auth.uid()
        and private.chat_user_kind(auth.uid()) is not null
        and private.chat_user_kind(p_user_id) is not null
        and (
          p_user_id=auth.uid()
          or (
            private.chat_user_kind(auth.uid())='company'
            and private.chat_user_kind(p_user_id) in ('company','admin')
          )
          or (
            private.chat_user_kind(auth.uid())='driver'
            and private.chat_user_kind(p_user_id)='admin'
          )
          or (
            private.chat_user_kind(auth.uid())='admin'
            and private.chat_user_kind(p_user_id) in ('company','driver','admin')
          )
        )
        and not exists (
          select 1 from public.chat_room_members m
          where m.room_id=r.id and m.user_id=p_user_id
        )
        and (select count(*) from public.chat_room_members m where m.room_id=r.id) < 2
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
  if v_kind is null then
    return;
  end if;

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
    and (
      c.company_name ilike '%'||v_query||'%'
      or (v_phone<>'' and c.mobile like '%'||v_phone||'%')
    )

  union all

  select d.id,d.full_name,'driver'::text
  from public.drivers d
  where v_kind='admin'
    and d.id <> auth.uid()
    and d.status='approved'
    and (
      d.full_name ilike '%'||v_query||'%'
      or (v_phone<>'' and d.mobile like '%'||v_phone||'%')
    )

  union all

  select au.user_id,
    case au.role
      when 'super_admin' then 'مدیر ارشد اصلی'
      when 'senior_admin' then 'ادمین ارشد'
      else 'ادمین سامانه'
    end,
    'admin'::text
  from public.admin_users au
  join auth.users u on u.id=au.user_id
  where v_kind in ('company','driver','admin')
    and au.user_id <> auth.uid()
    and au.is_blocked=false
    and (au.access_expires_at is null or au.access_expires_at > pg_catalog.now())
    and (
      v_query ilike '%مدیر%'
      or v_query ilike '%ادمین%'
      or (v_phone<>'' and coalesce(u.phone,'') like '%'||v_phone||'%')
    )
  order by 2
  limit 20;
end;
$function$;

create or replace function public.search_chat_contacts(p_query text)
returns table(user_id uuid,display_name text,account_type text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.search_chat_contacts(p_query);
$function$;

revoke all on function private.search_chat_contacts(text) from public,anon;
grant execute on function private.search_chat_contacts(text) to authenticated;
revoke all on function public.search_chat_contacts(text) from public,anon;
grant execute on function public.search_chat_contacts(text) to authenticated;
