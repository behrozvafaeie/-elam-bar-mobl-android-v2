-- Atomic 15-minute driver reservation for load contact and acceptance.
-- Existing load rows, statuses and history are preserved.
alter table public.loads
  add column if not exists contact_reserved_by uuid references public.drivers(id) on delete set null,
  add column if not exists contact_reserved_at timestamptz;

create index if not exists loads_active_contact_reservation_idx
  on public.loads (contact_reserved_at)
  where status = 'published' and assigned_driver_id is null and contact_reserved_by is not null;

create or replace function private.driver_reserve_load_contact(p_load_id uuid)
returns table(company_name text, manager_name text, mobile text, reservation_expires_at timestamptz)
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_load public.loads%rowtype;
begin
  if v_uid is null or coalesce(((auth.jwt() ->> 'is_anonymous')::boolean), false) then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;

  if not exists (
    select 1 from public.drivers d
    where d.id = v_uid and d.status = 'approved'
  ) then
    raise exception 'فقط راننده تأییدشده می‌تواند برای بار اقدام کند' using errcode = '42501';
  end if;

  update public.loads l
  set contact_reserved_by = null,
      contact_reserved_at = null
  where l.status = 'published'
    and l.assigned_driver_id is null
    and l.contact_reserved_at <= pg_catalog.now() - interval '15 minutes';

  select l.* into v_load
  from public.loads l
  where l.id = p_load_id
    and l.status = 'published'
    and l.assigned_driver_id is null
  for update;

  if not found then
    raise exception 'این بار دیگر در دسترس نیست' using errcode = 'P0001';
  end if;

  if v_load.company_id is null then
    raise exception 'برای این بار شماره شرکت ثبت نشده است' using errcode = 'P0001';
  end if;

  if v_load.contact_reserved_by is not null
     and v_load.contact_reserved_by <> v_uid
     and v_load.contact_reserved_at > pg_catalog.now() - interval '15 minutes' then
    raise exception 'راننده دیگری زودتر برای این بار اقدام کرده است' using errcode = '55P03';
  end if;

  if v_load.contact_reserved_by is distinct from v_uid
     or v_load.contact_reserved_at is null
     or v_load.contact_reserved_at <= pg_catalog.now() - interval '15 minutes' then
    update public.loads l
    set contact_reserved_by = v_uid,
        contact_reserved_at = pg_catalog.now()
    where l.id = p_load_id
    returning l.* into v_load;
  end if;

  return query
  select c.company_name, c.manager_name, c.mobile,
         v_load.contact_reserved_at + interval '15 minutes'
  from public.companies c
  where c.id = v_load.company_id
    and c.status = 'approved';
end;
$function$;

revoke all on function private.driver_reserve_load_contact(uuid) from public, anon;
grant execute on function private.driver_reserve_load_contact(uuid) to authenticated;

create or replace function public.driver_reserve_load_contact(p_load_id uuid)
returns table(company_name text, manager_name text, mobile text, reservation_expires_at timestamptz)
language sql
volatile
security invoker
set search_path = ''
as $function$
  select * from private.driver_reserve_load_contact(p_load_id);
$function$;

revoke all on function public.driver_reserve_load_contact(uuid) from public, anon;
grant execute on function public.driver_reserve_load_contact(uuid) to authenticated;

create or replace function private.driver_decline_load_reservation(p_load_id uuid)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_count integer;
begin
  if v_uid is null or coalesce(((auth.jwt() ->> 'is_anonymous')::boolean), false) then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;
  if not exists (select 1 from public.drivers d where d.id = v_uid and d.status = 'approved') then
    raise exception 'فقط راننده تأییدشده می‌تواند بار را رد کند' using errcode = '42501';
  end if;

  update public.loads
  set contact_reserved_by = null,
      contact_reserved_at = null
  where id = p_load_id
    and status = 'published'
    and assigned_driver_id is null
    and contact_reserved_by = v_uid;
  get diagnostics v_count = row_count;
  return v_count = 1;
end;
$function$;

revoke all on function private.driver_decline_load_reservation(uuid) from public, anon;
grant execute on function private.driver_decline_load_reservation(uuid) to authenticated;

create or replace function public.driver_decline_load_reservation(p_load_id uuid)
returns boolean
language sql
volatile
security invoker
set search_path = ''
as $function$
  select private.driver_decline_load_reservation(p_load_id);
$function$;

revoke all on function public.driver_decline_load_reservation(uuid) from public, anon;
grant execute on function public.driver_decline_load_reservation(uuid) to authenticated;

create or replace function private.driver_accept_load(p_load_id uuid)
returns public.loads
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_driver public.drivers%rowtype;
  v_load public.loads%rowtype;
begin
  if v_uid is null or coalesce(((auth.jwt() ->> 'is_anonymous')::boolean), false) then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;

  select * into v_driver
  from public.drivers d
  where d.id = v_uid and d.status = 'approved';
  if not found then
    raise exception 'فقط راننده تأییدشده می‌تواند بار قبول کند' using errcode = '42501';
  end if;

  select * into v_load
  from public.loads l
  where l.id = p_load_id
    and l.status = 'published'
    and l.assigned_driver_id is null
  for update;

  if not found then
    raise exception 'این بار دیگر در دسترس نیست' using errcode = 'P0001';
  end if;

  if v_load.contact_reserved_by is distinct from v_uid
     or v_load.contact_reserved_at is null
     or v_load.contact_reserved_at <= pg_catalog.now() - interval '15 minutes' then
    raise exception 'مهلت رزرو تمام شده یا این بار به نام شما رزرو نیست؛ دوباره اقدام کنید' using errcode = 'P0001';
  end if;

  if v_load.company_id is not null and not exists (
    select 1 from public.companies c
    where c.id = v_load.company_id and c.status = 'approved'
  ) then
    raise exception 'بار متعلق به شرکت تأییدشده نیست و قابل دریافت نیست' using errcode = '42501';
  end if;

  update public.loads l
  set assigned_driver_id = v_uid,
      status = 'driver_selected',
      driver_selected_at = pg_catalog.now(),
      contact_reserved_by = null,
      contact_reserved_at = null
  where l.id = p_load_id
    and l.status = 'published'
    and l.assigned_driver_id is null
    and l.contact_reserved_by = v_uid
    and l.contact_reserved_at > pg_catalog.now() - interval '15 minutes'
  returning l.* into v_load;

  if not found then
    raise exception 'این بار دیگر در دسترس نیست' using errcode = 'P0001';
  end if;
  return v_load;
end;
$function$;

revoke all on function private.driver_accept_load(uuid) from public, anon;
grant execute on function private.driver_accept_load(uuid) to authenticated;

create or replace function public.driver_accept_load(p_load_id uuid)
returns public.loads
language sql
volatile
security invoker
set search_path = ''
as $function$
  select private.driver_accept_load(p_load_id);
$function$;

revoke all on function public.driver_accept_load(uuid) from public, anon;
grant execute on function public.driver_accept_load(uuid) to authenticated;

create or replace function private.get_available_load_company_contact(p_load_id uuid)
returns table(company_name text, manager_name text, mobile text)
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
  if not exists (select 1 from public.drivers d where d.id = v_uid and d.status = 'approved') then
    raise exception 'فقط راننده تأییدشده می‌تواند شماره شرکت را ببیند' using errcode = '42501';
  end if;

  select l.company_id into v_company_id
  from public.loads l
  where l.id = p_load_id
    and l.status = 'published'
    and l.assigned_driver_id is null
    and l.contact_reserved_by = v_uid
    and l.contact_reserved_at > pg_catalog.now() - interval '15 minutes';

  if not found or v_company_id is null then
    return;
  end if;

  return query
  select c.company_name, c.manager_name, c.mobile
  from public.companies c
  where c.id = v_company_id and c.status = 'approved';
end;
$function$;

revoke all on function private.get_available_load_company_contact(uuid) from public, anon;
grant execute on function private.get_available_load_company_contact(uuid) to authenticated;

create or replace function public.get_available_load_company_contact(p_load_id uuid)
returns table(company_name text, manager_name text, mobile text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.get_available_load_company_contact(p_load_id);
$function$;

revoke all on function public.get_available_load_company_contact(uuid) from public, anon;
grant execute on function public.get_available_load_company_contact(uuid) to authenticated;

create or replace function public.get_public_load_hall()
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
    and (l.contact_reserved_by is null or l.contact_reserved_at <= pg_catalog.now() - interval '15 minutes')
    and (l.company_id is null or c.status = 'approved')
  order by l.created_at desc
  limit 200;
end;
$function$;

revoke all on function public.get_public_load_hall() from public, anon;
grant execute on function public.get_public_load_hall() to authenticated;
