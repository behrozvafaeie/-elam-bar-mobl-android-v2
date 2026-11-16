-- Allow the primary super admin to revoke every active device session for a driver,
-- company, or non-primary admin. This does not alter account data or profile status.
create or replace function private.admin_force_logout_member(p_target_user_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_deleted integer := 0;
begin
  if v_actor is null or not private.is_super_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;
  if p_target_user_id is null or p_target_user_id = v_actor then
    raise exception 'cannot_force_logout_self' using errcode = '42501';
  end if;
  if not exists (
    select 1 from auth.users u
    where u.id = p_target_user_id
      and (
        exists (select 1 from public.drivers d where d.id = u.id)
        or exists (select 1 from public.companies c where c.id = u.id)
        or exists (select 1 from public.admin_users a where a.user_id = u.id and a.role <> 'super_admin')
      )
  ) then
    raise exception 'target_member_not_found_or_protected' using errcode = '42501';
  end if;
  delete from auth.sessions where user_id = p_target_user_id;
  get diagnostics v_deleted = row_count;
  insert into public.admin_audit_logs(actor_user_id,target_user_id,action,details)
  values (v_actor,p_target_user_id,'admin_force_logout_member',
    jsonb_build_object('sessions_revoked',v_deleted,'scope','all_devices'));
  return v_deleted;
end;
$$;
revoke all on function private.admin_force_logout_member(uuid) from public, anon;
grant execute on function private.admin_force_logout_member(uuid) to authenticated;

create or replace function public.admin_force_logout_member(p_target_user_id uuid)
returns integer
language sql
security invoker
set search_path = ''
as $$ select private.admin_force_logout_member(p_target_user_id); $$;
revoke all on function public.admin_force_logout_member(uuid) from public, anon;
grant execute on function public.admin_force_logout_member(uuid) to authenticated;
