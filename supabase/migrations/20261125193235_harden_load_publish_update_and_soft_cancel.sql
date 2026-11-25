begin;

-- Companies and drivers must use the validated RPCs for load creation and assignment.
-- Keep only the limited, intended direct updates used by the Android edit panels.
revoke insert, delete, update on table public.loads from authenticated;
grant update (destination, cargo_type, vehicle_type, loading_type, freight_amount, description, loading_date, status)
  on table public.loads to authenticated;

-- Defense in depth: even if INSERT is granted again in the future, a company
-- cannot insert a pre-assigned, reserved, or non-published load directly.
drop policy if exists loads_insert_scoped on public.loads;
create policy loads_insert_scoped
on public.loads
for insert
to authenticated
with check (
  (
    publisher_type = 'company'
    and company_id = (select auth.uid())
    and publisher_user_id = (select auth.uid())
    and origin = 'تهران'
    and status = 'published'
    and assigned_driver_id is null
    and driver_selected_at is null
    and contact_reserved_by is null
    and contact_reserved_at is null
    and exists (
      select 1 from public.companies c
      where c.id = (select auth.uid()) and c.status = 'approved'
    )
  )
  or
  (
    publisher_user_id = (select auth.uid())
    and private.has_admin_section_permission('loads', 'create')
    and origin = 'تهران'
    and status = 'published'
    and assigned_driver_id is null
    and driver_selected_at is null
    and contact_reserved_by is null
    and contact_reserved_at is null
    and (
      (publisher_type = 'admin_system' and company_id is null)
      or
      (publisher_type = 'admin_company' and company_id is not null and exists (
        select 1 from public.companies c
        where c.id = loads.company_id and c.status = 'approved'
      ))
    )
  )
);

-- Preserve load identity and the Tehran origin. Companies may edit an open
-- load, or soft-cancel it; they may not alter its publisher, origin, assignment,
-- reservation, creation time, or batch identity.
create or replace function private.guard_load_update()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
  if auth.uid() is null then
    return new;
  end if;

  if private.has_admin_section_permission('loads', 'edit') then
    return new;
  end if;

  if auth.uid() = old.company_id then
    if old.status <> 'published' or old.assigned_driver_id is not null then
      raise exception 'پس از انتخاب راننده، تغییر اطلاعات بار فقط با مجوز مدیریت ممکن است'
        using errcode = '42501';
    end if;

    if new.id is distinct from old.id
       or new.company_id is distinct from old.company_id
       or new.publisher_user_id is distinct from old.publisher_user_id
       or new.publisher_type is distinct from old.publisher_type
       or new.origin is distinct from old.origin
       or new.created_at is distinct from old.created_at
       or new.announcement_batch_id is distinct from old.announcement_batch_id
       or new.batch_position is distinct from old.batch_position
       or new.assigned_driver_id is distinct from old.assigned_driver_id
       or new.driver_selected_at is distinct from old.driver_selected_at
       or new.contact_reserved_by is distinct from old.contact_reserved_by
       or new.contact_reserved_at is distinct from old.contact_reserved_at then
      raise exception 'هویت بار، مبدأ تهران و اطلاعات رزرو قابل تغییر نیست'
        using errcode = '42501';
    end if;

    if new.status is distinct from old.status then
      if new.status <> 'cancelled'
         or (pg_catalog.to_jsonb(new) - 'status') is distinct from (pg_catalog.to_jsonb(old) - 'status') then
        raise exception 'شرکت فقط می‌تواند بار آزاد را لغو کند'
          using errcode = '42501';
      end if;
      return new;
    end if;

    if old.contact_reserved_by is not null
       and old.contact_reserved_at > pg_catalog.now() - interval '15 minutes' then
      raise exception 'تا پایان مهلت رزرو تماس راننده، جزئیات بار قابل ویرایش نیست'
        using errcode = '42501';
    end if;

    return new;
  end if;

  raise exception 'اجازه تغییر این بار را ندارید' using errcode = '42501';
end;
$function$;

commit;
