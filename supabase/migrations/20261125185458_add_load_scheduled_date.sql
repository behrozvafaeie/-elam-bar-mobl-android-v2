-- Add an optional planned loading date. Existing rows remain NULL and untouched.
alter table public.loads add column if not exists loading_date date;
create index if not exists loads_loading_date_idx
  on public.loads (loading_date desc, created_at desc);

-- Keep the existing RPC signature and permission model; accept an optional
-- ISO/Gregorian date in each JSON item so older clients remain compatible.
create or replace function private.publish_tehran_load_batch_v2(
  p_items jsonb,
  p_publisher_type text,
  p_company_id uuid default null
)
returns table(
  id uuid, company_id uuid, origin text, destination text, cargo_type text,
  vehicle_type text, freight_amount bigint, description text, status text,
  created_at timestamptz, assigned_driver_id uuid, driver_selected_at timestamptz,
  loading_type text, announcement_batch_id uuid, batch_position integer
)
language plpgsql
security definer
set search_path to ''
as $function$
declare
  u uuid := auth.uid();
  c_id uuid;
  b_id uuid := gen_random_uuid();
  item jsonb;
  i integer := 0;
  dest text;
  cargo text;
  vehicle text;
  loading text;
  descr text;
  fare bigint;
  dest_lat numeric;
  dest_lon numeric;
  scheduled_date date;
begin
  if u is null or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;
  if p_publisher_type = 'company' then
    c_id := u;
    if (p_company_id is not null and p_company_id <> u)
       or not exists (select 1 from public.companies c where c.id = u and c.status = 'approved') then
      raise exception 'فقط شرکت تأییدشده می‌تواند به نام خودش بار اعلام کند' using errcode = '42501';
    end if;
  elsif p_publisher_type = 'admin_system' then
    if p_company_id is not null or not private.has_admin_section_permission('loads','create') then
      raise exception 'مجوز انتشار به نام مدیریت سامانه را ندارید' using errcode = '42501';
    end if;
    c_id := null;
  elsif p_publisher_type = 'admin_company' then
    if not private.has_admin_section_permission('loads','create') then
      raise exception 'مجوز انتشار به نمایندگی از شرکت را ندارید' using errcode = '42501';
    end if;
    if p_company_id is null or not exists (select 1 from public.companies c where c.id = p_company_id and c.status = 'approved') then
      raise exception 'شرکت انتخاب‌شده تأیید نشده است' using errcode = '42501';
    end if;
    c_id := p_company_id;
  else
    raise exception 'روش انتشار بار معتبر نیست' using errcode = '22023';
  end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) < 1 then
    raise exception 'حداقل یک بار برای ثبت لازم است' using errcode = '22023';
  end if;
  for item in select value from jsonb_array_elements(p_items) loop
    i := i + 1;
    if jsonb_typeof(item) <> 'object' then
      raise exception 'اطلاعات بار معتبر نیست' using errcode = '22023';
    end if;
    dest := btrim(coalesce(item->>'destination',''));
    cargo := btrim(coalesce(item->>'cargo_type',''));
    vehicle := btrim(coalesce(item->>'vehicle_type',''));
    loading := btrim(coalesce(item->>'loading_type','روباری'));
    descr := nullif(btrim(coalesce(item->>'description','')),'');
    dest_lat := nullif(item->>'destination_latitude','')::numeric;
    dest_lon := nullif(item->>'destination_longitude','')::numeric;
    scheduled_date := nullif(btrim(coalesce(item->>'loading_date','')),'')::date;
    if length(dest) < 2 or length(dest) > 100 then
      raise exception 'مقصد بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if length(cargo) < 2 or length(cargo) > 160 then
      raise exception 'نوع بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if vehicle not in ('وانت مبلی','کامیونت مبلی','کامیونت روباری کار','کامیون ۶ متری',
                       'وانت','کامیون دربستی کار','خاور مبلی پتو دار','خاور مبلی پتو‌دار','کامیون بزرگ','سایر خودروهای مبلی') then
      raise exception 'نوع خودرو برای بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if loading not in ('روباری','دربست','نیم بار') then
      raise exception 'نوع بارگیری برای بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if length(coalesce(descr,'')) > 3000 then
      raise exception 'توضیحات بار شماره % بیش از حد مجاز است', i using errcode = '22023';
    end if;
    if coalesce(item->>'freight_amount','') !~ '^[0-9]{1,18}$' then
      raise exception 'کرایه بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if (dest_lat is null) <> (dest_lon is null) or
       (dest_lat is not null and (dest_lat < -90 or dest_lat > 90 or dest_lon < -180 or dest_lon > 180)) then
      raise exception 'مختصات مقصد بار شماره % معتبر نیست', i using errcode = '22023';
    end if;
    fare := (item->>'freight_amount')::bigint;
    insert into public.loads(
      company_id,publisher_type,publisher_user_id,origin,destination,cargo_type,
      vehicle_type,freight_amount,description,status,loading_type,announcement_batch_id,
      batch_position,destination_latitude,destination_longitude,loading_date
    )
    values (
      c_id,p_publisher_type,u,'تهران',dest,cargo,vehicle,fare,descr,'published',
      loading,b_id,i,dest_lat,dest_lon,scheduled_date
    );
  end loop;
  return query
  select l.id,l.company_id,l.origin,l.destination,l.cargo_type,l.vehicle_type,
    l.freight_amount,l.description,l.status,l.created_at,l.assigned_driver_id,
    l.driver_selected_at,l.loading_type,l.announcement_batch_id,l.batch_position
  from public.loads l
  where l.announcement_batch_id=b_id
  order by l.batch_position;
end;
$function$;
