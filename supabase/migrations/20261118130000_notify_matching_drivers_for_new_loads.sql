-- In-app notifications for newly published loads that match approved drivers.
-- Safe to apply more than once; no existing rows or business data are deleted.
-- This migration intentionally does not send SMS or push notifications.

create or replace function private.notify_matching_drivers_for_load()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if new.status <> 'published' then
    return new;
  end if;

  if tg_op = 'UPDATE'
     and old.status is not distinct from new.status
     and old.vehicle_type is not distinct from new.vehicle_type
     and old.destination is not distinct from new.destination then
    return new;
  end if;

  insert into public.notifications (
    user_id,
    notification_type,
    title,
    body,
    data
  )
  select
    d.id,
    'matching_load_available',
    'بار جدید متناسب با خودروی شما',
    'بار جدید از تهران به ' || new.destination || ' برای خودروی شما ثبت شد.',
    pg_catalog.jsonb_build_object(
      'load_id', new.id,
      'origin', new.origin,
      'destination', new.destination,
      'vehicle_type', new.vehicle_type,
      'cargo_type', new.cargo_type,
      'loading_type', new.loading_type
    )
  from public.drivers as d
  where d.status = 'approved'
    and d.vehicle_type = new.vehicle_type
    and (
      new.destination = any(coalesce(d.destinations, array[]::text[]))
      or new.destination = d.primary_destination
    )
    and not exists (
      select 1
      from public.notifications as existing
      where existing.user_id = d.id
        and existing.notification_type = 'matching_load_available'
        and existing.data ->> 'load_id' = new.id::text
    );

  return new;
end;
$function$;

revoke all on function private.notify_matching_drivers_for_load() from public;
revoke all on function private.notify_matching_drivers_for_load() from anon;
revoke all on function private.notify_matching_drivers_for_load() from authenticated;

drop trigger if exists trg_notify_matching_drivers_for_load on public.loads;

create trigger trg_notify_matching_drivers_for_load
after insert or update of status, vehicle_type, destination
on public.loads
for each row
execute function private.notify_matching_drivers_for_load();
