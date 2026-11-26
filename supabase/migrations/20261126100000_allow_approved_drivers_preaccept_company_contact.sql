-- Expose company contact details only to approved drivers, and only for
-- currently published loads that have not yet been assigned.
-- Privileged implementation stays in the private schema; public RPC is invoker-only.
-- This is additive: existing tables, rows, and assignment-gated RPC remain unchanged.

create or replace function private.get_published_load_company_contacts()
returns table (
  load_id uuid,
  company_name text,
  manager_name text,
  mobile text
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null
     or coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) then
    raise exception 'ورود معتبر الزامی است' using errcode = '28000';
  end if;

  if not exists (
    select 1 from public.drivers d
    where d.id = v_uid and d.status = 'approved'
  ) then
    raise exception 'فقط رانندگان تأییدشده به اطلاعات تماس بار دسترسی دارند'
      using errcode = '42501';
  end if;

  return query
  select l.id, c.company_name, c.manager_name, c.mobile
  from public.loads l
  join public.companies c on c.id = l.company_id
  where l.status = 'published'
    and l.assigned_driver_id is null
    and l.company_id is not null;
end;
$function$;

revoke all on function private.get_published_load_company_contacts() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.get_published_load_company_contacts() to authenticated;

create or replace function public.get_published_load_company_contacts()
returns table (
  load_id uuid,
  company_name text,
  manager_name text,
  mobile text
)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.get_published_load_company_contacts();
$function$;

revoke all on function public.get_published_load_company_contacts() from public, anon;
grant execute on function public.get_published_load_company_contacts() to authenticated;
