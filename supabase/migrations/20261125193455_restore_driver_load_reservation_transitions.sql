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

  -- Driver acceptance must be the exact reserved row transition.
  if old.status = 'published'
     and old.assigned_driver_id is null
     and new.status = 'driver_selected'
     and new.assigned_driver_id = auth.uid()
     and new.driver_selected_at is not null
     and old.contact_reserved_by = auth.uid()
     and old.contact_reserved_at > pg_catalog.now() - interval '15 minutes'
     and new.contact_reserved_by is null
     and new.contact_reserved_at is null
     and exists (
       select 1 from public.drivers d
       where d.id = auth.uid() and d.status = 'approved'
     )
     and (pg_catalog.to_jsonb(new) - 'status' - 'assigned_driver_id' - 'driver_selected_at' - 'contact_reserved_by' - 'contact_reserved_at')
         = (pg_catalog.to_jsonb(old) - 'status' - 'assigned_driver_id' - 'driver_selected_at' - 'contact_reserved_by' - 'contact_reserved_at') then
    return new;
  end if;

  -- Drivers may reserve, release, or clear an expired reservation, but may
  -- not modify any other load fields through direct table updates.
  if old.status = 'published'
     and old.assigned_driver_id is null
     and new.status = 'published'
     and new.assigned_driver_id is null
     and exists (
       select 1 from public.drivers d
       where d.id = auth.uid() and d.status = 'approved'
     )
     and (pg_catalog.to_jsonb(new) - 'contact_reserved_by' - 'contact_reserved_at')
         = (pg_catalog.to_jsonb(old) - 'contact_reserved_by' - 'contact_reserved_at')
     and (
       (
         new.contact_reserved_by = auth.uid()
         and new.contact_reserved_at is not null
         and new.contact_reserved_at between pg_catalog.now() - interval '1 minute' and pg_catalog.now() + interval '1 minute'
         and (
           old.contact_reserved_by is null
           or old.contact_reserved_at <= pg_catalog.now() - interval '15 minutes'
           or (old.contact_reserved_by = auth.uid() and new.contact_reserved_at = old.contact_reserved_at)
         )
       )
       or
       (
         new.contact_reserved_by is null
         and new.contact_reserved_at is null
         and old.contact_reserved_by is not null
         and (old.contact_reserved_by = auth.uid() or old.contact_reserved_at <= pg_catalog.now() - interval '15 minutes')
       )
     ) then
    return new;
  end if;

  raise exception 'اجازه تغییر این بار را ندارید' using errcode = '42501';
end;
$function$;
