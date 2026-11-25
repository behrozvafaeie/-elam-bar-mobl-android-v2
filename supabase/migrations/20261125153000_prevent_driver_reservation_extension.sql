-- Prevent direct REST updates from extending a driver's 15-minute reservation.
create or replace function private.guard_load_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null then return new; end if;
  if private.has_admin_section_permission('loads', 'edit') then return new; end if;

  if auth.uid() = old.company_id then
    if old.status <> 'published'
       or new.id is distinct from old.id
       or new.company_id is distinct from old.company_id
       or new.status is distinct from old.status
       or new.assigned_driver_id is distinct from old.assigned_driver_id
       or new.driver_selected_at is distinct from old.driver_selected_at
       or new.contact_reserved_by is distinct from old.contact_reserved_by
       or new.contact_reserved_at is distinct from old.contact_reserved_at then
      raise exception 'پس از قبول بار، تغییر اطلاعات آن فقط با مجوز مدیریت ممکن است' using errcode = '42501';
    end if;
    return new;
  end if;

  if old.status = 'published'
     and old.assigned_driver_id is null
     and new.status = 'driver_selected'
     and new.assigned_driver_id = auth.uid()
     and new.driver_selected_at is not null
     and old.contact_reserved_by = auth.uid()
     and old.contact_reserved_at > pg_catalog.now() - interval '15 minutes'
     and new.contact_reserved_by is null
     and new.contact_reserved_at is null
     and exists (select 1 from public.drivers d where d.id=auth.uid() and d.status='approved')
     and (pg_catalog.to_jsonb(new) - 'status' - 'assigned_driver_id' - 'driver_selected_at' - 'contact_reserved_by' - 'contact_reserved_at')
         = (pg_catalog.to_jsonb(old) - 'status' - 'assigned_driver_id' - 'driver_selected_at' - 'contact_reserved_by' - 'contact_reserved_at') then
    return new;
  end if;

  if old.status = 'published'
     and old.assigned_driver_id is null
     and new.status = 'published'
     and new.assigned_driver_id is null
     and exists (select 1 from public.drivers d where d.id=auth.uid() and d.status='approved')
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
