-- Keep the authenticated RPC contract while moving privileged execution
-- into the non-exposed private schema.
create or replace function private.admin_set_member_status(
  p_table text,
  p_user_id uuid,
  p_status text,
  p_reason text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_actor uuid := auth.uid();
  v_section text;
  v_old_status text;
  v_rows integer;
begin
  if v_actor is null or not private.is_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;

  if p_table = 'drivers' then
    v_section := 'drivers';
  elsif p_table = 'companies' then
    v_section := 'companies';
  else
    raise exception 'invalid_member_table' using errcode = '22023';
  end if;

  if p_user_id is null or p_status not in ('approved','rejected','blocked') then
    raise exception 'invalid_input' using errcode = '22023';
  end if;

  if p_status = 'blocked' and (p_reason is null or length(pg_catalog.btrim(p_reason)) < 3) then
    raise exception 'block_reason_required' using errcode = '22023';
  end if;

  if not private.has_admin_section_permission(v_section, 'edit') then
    raise exception 'section_edit_permission_required' using errcode = '42501';
  end if;

  if p_table = 'drivers' then
    select status into v_old_status from public.drivers where id = p_user_id for update;
  else
    select status into v_old_status from public.companies where id = p_user_id for update;
  end if;

  if not found then
    raise exception 'member_not_found' using errcode = 'P0002';
  end if;

  if p_table = 'drivers' then
    update public.drivers set status = p_status where id = p_user_id;
  else
    update public.companies set status = p_status where id = p_user_id;
  end if;

  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'member_status_update_failed' using errcode = 'P0001';
  end if;

  insert into public.admin_audit_logs(actor_user_id,target_user_id,action,details)
  values (
    v_actor,
    p_user_id,
    'member_status_changed',
    pg_catalog.jsonb_build_object(
      'table',p_table,
      'old_status',v_old_status,
      'new_status',p_status,
      'reason',case when p_status = 'blocked' then pg_catalog.btrim(p_reason) else null end
    )
  );
end;
$function$;

revoke all on function private.admin_set_member_status(text,uuid,text,text) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.admin_set_member_status(text,uuid,text,text) to authenticated;

create or replace function public.admin_set_member_status(
  p_table text,
  p_user_id uuid,
  p_status text,
  p_reason text default null
)
returns void
language sql
security invoker
set search_path = ''
as $function$
  select private.admin_set_member_status(p_table,p_user_id,p_status,p_reason);
$function$;

revoke all on function public.admin_set_member_status(text,uuid,text,text) from public, anon;
grant execute on function public.admin_set_member_status(text,uuid,text,text) to authenticated;
