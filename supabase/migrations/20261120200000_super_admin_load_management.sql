-- Additive, server-authorized load management for the primary super admin.
-- This migration is intentionally NOT applied to the live Supabase project by this commit.
-- Existing load rows are preserved; delete is implemented as a soft cancellation.

create or replace function private.admin_update_load(
  p_load_id uuid,
  p_destination text,
  p_cargo_type text,
  p_vehicle_type text,
  p_loading_type text,
  p_freight_amount bigint,
  p_description text,
  p_status text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_actor uuid := auth.uid();
  v_old_status text;
  v_rows integer;
  v_new_status text;
begin
  if v_actor is null or not private.is_super_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;

  if p_load_id is null
     or p_destination is null or length(pg_catalog.btrim(p_destination)) not between 2 and 100
     or p_cargo_type is null or length(pg_catalog.btrim(p_cargo_type)) not between 2 and 160
     or p_vehicle_type is null or length(pg_catalog.btrim(p_vehicle_type)) not between 2 and 100
     or p_loading_type not in ('دربست','روباری','نیم بار')
     or p_freight_amount is null or p_freight_amount < 0
     or length(coalesce(p_description,'')) > 3000 then
    raise exception 'invalid_load_input' using errcode = '22023';
  end if;

  if p_status is not null and p_status not in ('published','driver_selected','loading','delivered','cancelled') then
    raise exception 'invalid_load_status' using errcode = '22023';
  end if;

  select l.status into v_old_status
  from public.loads l
  where l.id = p_load_id
  for update;

  if not found then
    raise exception 'load_not_found' using errcode = 'P0002';
  end if;

  v_new_status := coalesce(p_status, v_old_status);

  update public.loads
  set destination = pg_catalog.btrim(p_destination),
      cargo_type = pg_catalog.btrim(p_cargo_type),
      vehicle_type = pg_catalog.btrim(p_vehicle_type),
      loading_type = p_loading_type,
      freight_amount = p_freight_amount,
      description = pg_catalog.btrim(coalesce(p_description,'')),
      status = v_new_status
  where id = p_load_id;

  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'load_update_failed' using errcode = 'P0001';
  end if;

  insert into public.admin_audit_logs(actor_user_id, action, details)
  values (
    v_actor,
    'admin_load_updated',
    pg_catalog.jsonb_build_object(
      'load_id', p_load_id,
      'old_status', v_old_status,
      'new_status', v_new_status
    )
  );
end;
$function$;

create or replace function private.admin_cancel_load(p_load_id uuid, p_reason text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_actor uuid := auth.uid();
  v_old_status text;
  v_rows integer;
begin
  if v_actor is null or not private.is_super_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;
  if p_load_id is null or length(coalesce(p_reason,'')) > 500 then
    raise exception 'invalid_input' using errcode = '22023';
  end if;

  select status into v_old_status
  from public.loads where id = p_load_id for update;
  if not found then raise exception 'load_not_found' using errcode = 'P0002'; end if;

  update public.loads set status = 'cancelled' where id = p_load_id;
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then raise exception 'load_cancel_failed' using errcode = 'P0001'; end if;

  insert into public.admin_audit_logs(actor_user_id, action, details)
  values (
    v_actor,
    'admin_load_cancelled',
    pg_catalog.jsonb_build_object(
      'load_id', p_load_id,
      'old_status', v_old_status,
      'new_status', 'cancelled',
      'reason', pg_catalog.nullif(pg_catalog.btrim(coalesce(p_reason,'')),'')
    )
  );
end;
$function$;

revoke all on function private.admin_update_load(uuid,text,text,text,text,bigint,text,text) from public, anon;
revoke all on function private.admin_cancel_load(uuid,text) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.admin_update_load(uuid,text,text,text,text,bigint,text,text) to authenticated;
grant execute on function private.admin_cancel_load(uuid,text) to authenticated;

create or replace function public.admin_update_load(
  p_load_id uuid,
  p_destination text,
  p_cargo_type text,
  p_vehicle_type text,
  p_loading_type text,
  p_freight_amount bigint,
  p_description text,
  p_status text default null
)
returns void
language sql
security invoker
set search_path = ''
as $function$
  select private.admin_update_load(
    p_load_id,p_destination,p_cargo_type,p_vehicle_type,p_loading_type,
    p_freight_amount,p_description,p_status
  );
$function$;

create or replace function public.admin_cancel_load(p_load_id uuid, p_reason text default null)
returns void
language sql
security invoker
set search_path = ''
as $function$
  select private.admin_cancel_load(p_load_id,p_reason);
$function$;

revoke all on function public.admin_update_load(uuid,text,text,text,text,bigint,text,text) from public, anon;
revoke all on function public.admin_cancel_load(uuid,text) from public, anon;
grant execute on function public.admin_update_load(uuid,text,text,text,text,bigint,text,text) to authenticated;
grant execute on function public.admin_cancel_load(uuid,text) to authenticated;
