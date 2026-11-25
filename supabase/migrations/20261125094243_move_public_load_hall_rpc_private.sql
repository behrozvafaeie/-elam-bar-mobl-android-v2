-- Keep privileged hall cleanup in the non-exposed private schema.
-- The public RPC is an invoker wrapper with authenticated-only execution.
create or replace function private.get_public_load_hall()
returns table(
  id uuid, origin text, destination text, cargo_type text, vehicle_type text,
  freight_amount bigint, description text, loading_type text,
  company_name text, company_mobile text, created_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if not (
    exists (select 1 from public.drivers d where d.id = auth.uid() and d.status = 'approved')
    or private.has_admin_section_permission('loads', 'view')
  ) then
    raise exception 'Driver approval required to view the public load hall' using errcode = '42501';
  end if;

  update public.loads
  set contact_reserved_by = null, contact_reserved_at = null
  where status = 'published'
    and assigned_driver_id is null
    and contact_reserved_at <= pg_catalog.now() - interval '15 minutes';

  return query
  select l.id, l.origin, l.destination, l.cargo_type, l.vehicle_type,
         l.freight_amount, coalesce(l.description, ''), l.loading_type,
         coalesce(c.company_name, 'مدیریت سامانه'),
         case when private.has_admin_section_permission('loads', 'view') then coalesce(c.mobile, '') else '' end,
         l.created_at
  from public.loads l
  left join public.companies c on c.id = l.company_id
  where l.status = 'published'
    and l.assigned_driver_id is null
    and l.contact_reserved_by is null
    and (l.company_id is null or c.status = 'approved')
  order by l.created_at desc
  limit 200;
end;
$function$;

revoke all on function private.get_public_load_hall() from public, anon;
grant execute on function private.get_public_load_hall() to authenticated;

create or replace function public.get_public_load_hall()
returns table(
  id uuid, origin text, destination text, cargo_type text, vehicle_type text,
  freight_amount bigint, description text, loading_type text,
  company_name text, company_mobile text, created_at timestamptz
)
language sql
volatile
security invoker
set search_path = ''
as $function$
  select * from private.get_public_load_hall();
$function$;

revoke all on function public.get_public_load_hall() from public, anon;
grant execute on function public.get_public_load_hall() to authenticated;
