create or replace function private.get_available_load_company_contact(p_load_id uuid)
returns table (company_name text, manager_name text, mobile text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_company_id uuid;
begin
  if v_uid is null or coalesce(((auth.jwt() ->> 'is_anonymous')::boolean), false) then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;

  select l.company_id
    into v_company_id
  from public.loads l
  where l.id = p_load_id
    and l.status = 'published'
    and l.assigned_driver_id is null;

  if not found or v_company_id is null then
    return;
  end if;

  -- Any approved driver may contact the company for any currently available
  -- published load, regardless of the driver's vehicle or saved destinations.
  if not private.is_super_admin() and not exists (
    select 1
    from public.drivers d
    where d.id = v_uid
      and d.status = 'approved'
  ) then
    raise exception 'فقط راننده تأییدشده می‌تواند برای این بار با شرکت تماس بگیرد'
      using errcode = '42501';
  end if;

  return query
  select c.company_name, c.manager_name, c.mobile
  from public.companies c
  where c.id = v_company_id
    and c.status = 'approved';
end;
$function$;

revoke all on function private.get_available_load_company_contact(uuid) from public, anon;
grant execute on function private.get_available_load_company_contact(uuid) to authenticated;

create or replace function public.get_available_load_company_contact(p_load_id uuid)
returns table (company_name text, manager_name text, mobile text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.get_available_load_company_contact(p_load_id);
$function$;

revoke all on function public.get_available_load_company_contact(uuid) from public, anon;
grant execute on function public.get_available_load_company_contact(uuid) to authenticated;
