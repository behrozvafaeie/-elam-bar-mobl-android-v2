-- Additive foundation for the v1.5.1.1 multi-section load announcement form.
-- Existing loads, rows, policies, and RPCs remain intact.
create table if not exists public.load_cargo_items (
  id uuid primary key default gen_random_uuid(),
  load_id uuid not null references public.loads(id) on delete cascade,
  cargo_type text not null check (cargo_type in ('مبل','سرویس خواب','اثاث منزل','تی‌وی کنسول')),
  quantity numeric(12,2) not null check (quantity > 0),
  unit text not null check (unit in ('دست','عدد','واحد','بسته')),
  created_at timestamptz not null default now(),
  constraint load_cargo_items_unique_type_per_load unique(load_id,cargo_type)
);
create index if not exists load_cargo_items_load_id_idx on public.load_cargo_items(load_id);

alter table public.load_cargo_items enable row level security;
revoke all on table public.load_cargo_items from anon, authenticated;
grant select on table public.load_cargo_items to authenticated;
drop policy if exists load_cargo_items_select_related_load on public.load_cargo_items;
create policy load_cargo_items_select_related_load on public.load_cargo_items
for select to authenticated
using (
  exists (
    select 1 from public.loads l
    where l.id = load_cargo_items.load_id
      and (
        l.publisher_user_id = (select auth.uid())
        or l.assigned_driver_id = (select auth.uid())
        or private.has_admin_section_permission('loads','view')
      )
  )
);

create table if not exists public.load_change_logs (
  id bigint generated always as identity primary key,
  load_id uuid not null references public.loads(id) on delete cascade,
  actor_user_id uuid not null references auth.users(id),
  action text not null check (action in ('created','edited','cancelled','driver_withdrew','company_cancelled')),
  before_data jsonb not null default '{}'::jsonb,
  after_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists load_change_logs_load_id_created_at_idx
  on public.load_change_logs(load_id, created_at desc);
alter table public.load_change_logs enable row level security;
revoke all on table public.load_change_logs from anon, authenticated;
grant select on table public.load_change_logs to authenticated;
drop policy if exists load_change_logs_select_owner_or_admin on public.load_change_logs;
create policy load_change_logs_select_owner_or_admin on public.load_change_logs
for select to authenticated
using (
  exists (
    select 1 from public.loads l
    where l.id = load_change_logs.load_id
      and (
        l.publisher_user_id = (select auth.uid())
        or private.has_admin_section_permission('loads','view')
      )
  )
);

create or replace function private.publish_tehran_load_advanced_v1(
  p_items jsonb,
  p_publisher_type text,
  p_company_id uuid default null
)
returns setof public.loads
language plpgsql
security definer
set search_path = ''
as $function$
declare
  u uuid := auth.uid();
  c_id uuid;
  batch_id uuid := gen_random_uuid();
  item jsonb;
  cargo_item jsonb;
  i integer := 0;
  dest text;
  vehicle text;
  loading text;
  descr text;
  fare bigint;
  price_mode text;
  cargo_summary text;
  cargo_name text;
  cargo_qty numeric;
  cargo_unit text;
  new_load public.loads%rowtype;
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
    if p_company_id is null or not exists (
      select 1 from public.companies c where c.id = p_company_id and c.status = 'approved'
    ) then
      raise exception 'شرکت انتخاب‌شده تأیید نشده است' using errcode = '42501';
    end if;
    c_id := p_company_id;
  else
    raise exception 'روش انتشار بار معتبر نیست' using errcode = '22023';
  end if;

  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) < 1 then
    raise exception 'حداقل یک مقصد برای ثبت لازم است' using errcode = '22023';
  end if;

  for item in select value from jsonb_array_elements(p_items) loop
    i := i + 1;
    if jsonb_typeof(item) <> 'object' then
      raise exception 'اطلاعات مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;

    dest := btrim(coalesce(item->>'destination',''));
    vehicle := btrim(coalesce(item->>'vehicle_type',''));
    loading := btrim(coalesce(item->>'loading_type','روباری'));
    descr := nullif(btrim(coalesce(item->>'description','')),'');
    price_mode := coalesce(item->>'price_mode','fixed');
    if price_mode not in ('fixed','negotiable') then
      raise exception 'روش تعیین کرایه مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if price_mode = 'negotiable' then
      fare := 0;
    else
      if coalesce(item->>'freight_amount','') !~ '^[0-9]{1,18}$' then
        raise exception 'کرایه مقصد شماره % معتبر نیست', i using errcode = '22023';
      end if;
      fare := (item->>'freight_amount')::bigint;
      if fare <= 0 then
        raise exception 'کرایه مقصد شماره % باید بیشتر از صفر باشد', i using errcode = '22023';
      end if;
    end if;

    if length(dest) < 2 or length(dest) > 100 then
      raise exception 'مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if vehicle not in ('وانت مبلی','کامیونت مبلی','کامیون ۶ متری','کامیون دربستی کار','خاور مبلی پتو دار') then
      raise exception 'نوع خودرو برای مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if loading not in ('روباری','دربست','نیم بار','نیم‌بار') then
      raise exception 'نوع بارگیری برای مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;
    if length(coalesce(descr,'')) > 3000 then
      raise exception 'توضیحات مقصد شماره % بیش از حد مجاز است', i using errcode = '22023';
    end if;

    if jsonb_typeof(item->'cargo_items') <> 'array' or jsonb_array_length(item->'cargo_items') < 1 then
      raise exception 'برای مقصد شماره % حداقل یک نوع بار انتخاب کنید', i using errcode = '22023';
    end if;

    select string_agg(x->>'cargo_type', '، ' order by ord)
      into cargo_summary
    from jsonb_array_elements(item->'cargo_items') with ordinality as e(x,ord);

    if length(coalesce(cargo_summary,'')) < 2 or length(cargo_summary) > 160 then
      raise exception 'فهرست نوع بار مقصد شماره % معتبر نیست', i using errcode = '22023';
    end if;

    insert into public.loads(
      company_id,publisher_type,publisher_user_id,origin,destination,cargo_type,
      vehicle_type,freight_amount,description,status,loading_type,
      announcement_batch_id,batch_position
    )
    values (
      c_id,p_publisher_type,u,'تهران',dest,cargo_summary,vehicle,fare,descr,'published',
      case when loading = 'نیم‌بار' then 'نیم بار' else loading end,batch_id,i
    )
    returning * into new_load;

    for cargo_item in select value from jsonb_array_elements(item->'cargo_items') loop
      cargo_name := btrim(coalesce(cargo_item->>'cargo_type',''));
      if cargo_name not in ('مبل','سرویس خواب','اثاث منزل','تی‌وی کنسول') then
        raise exception 'نوع قلم بار در مقصد شماره % معتبر نیست', i using errcode = '22023';
      end if;
      if coalesce(cargo_item->>'quantity','') !~ '^[0-9]+([.][0-9]{1,2})?$' then
        raise exception 'تعداد قلم بار در مقصد شماره % معتبر نیست', i using errcode = '22023';
      end if;
      cargo_qty := (cargo_item->>'quantity')::numeric;
      cargo_unit := coalesce(cargo_item->>'unit', case when cargo_name in ('مبل','سرویس خواب') then 'دست' when cargo_name='اثاث منزل' then 'واحد' else 'عدد' end);
      if cargo_qty <= 0 or cargo_unit not in ('دست','عدد','واحد','بسته') then
        raise exception 'تعداد یا واحد قلم بار در مقصد شماره % معتبر نیست', i using errcode = '22023';
      end if;
      insert into public.load_cargo_items(load_id,cargo_type,quantity,unit)
      values(new_load.id,cargo_name,cargo_qty,cargo_unit);
    end loop;

    insert into public.load_change_logs(load_id,actor_user_id,action,after_data)
    values(new_load.id,u,'created',jsonb_build_object(
      'destination',dest,'cargo_items',item->'cargo_items','vehicle_type',vehicle,
      'loading_type',loading,'freight_amount',fare,'price_mode',price_mode
    ));
  end loop;

  return query
  select l.* from public.loads l
  where l.announcement_batch_id = batch_id
  order by l.batch_position;
end;
$function$;

create or replace function public.publish_tehran_load_advanced_v1(
  p_items jsonb,
  p_publisher_type text,
  p_company_id uuid default null
)
returns setof public.loads
language sql
security definer
set search_path = ''
as $function$
  select * from private.publish_tehran_load_advanced_v1(p_items,p_publisher_type,p_company_id);
$function$;

revoke all on function private.publish_tehran_load_advanced_v1(jsonb,text,uuid) from public, anon, authenticated;
revoke all on function public.publish_tehran_load_advanced_v1(jsonb,text,uuid) from public, anon;
grant execute on function public.publish_tehran_load_advanced_v1(jsonb,text,uuid) to authenticated;
