alter table public.notifications
  drop constraint if exists notifications_notification_type_check;

alter table public.notifications
  add constraint notifications_notification_type_check
  check (notification_type = any (array[
    'new_message'::text,
    'call'::text,
    'load_update'::text,
    'system'::text,
    'matching_load_available'::text
  ]));
