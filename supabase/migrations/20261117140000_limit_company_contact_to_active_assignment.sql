create or replace function private.get_assigned_load_company_contact(p_load_id uuid)
returns table (company_name text, manager_name text, mobile text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_company_id uuid;
  v_assigned_driver_id uuid;
begin
  if v_uid is null then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;

  select l.company_id, l.assigned_driver_id
    into v_company_id, v_assigned_driver_id
  from public.loads l
  where l.id = p_load_id
    and l.status = 'driver_selected';

  if not found or v_company_id is null then
    return;
  end if;

  if not private.is_super_admin() then
    if v_assigned_driver_id is distinct from v_uid
       or not exists (
         select 1 from public.drivers d
         where d.id = v_uid and d.status = 'approved'
       ) then
      raise exception 'فقط راننده‌ای که این بار را گرفته به تماس شرکت دسترسی دارد'
        using errcode = '42501';
    end if;
  end if;

  return query
  select c.company_name, c.manager_name, c.mobile
  from public.companies c
  where c.id = v_company_id;
end;
$function$;

revoke all on function private.get_assigned_load_company_contact(uuid) from public, anon;
grant execute on function private.get_assigned_load_company_contact(uuid) to authenticated;