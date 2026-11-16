-- Read and update the existing global admin-menu visibility setting.
-- Only the primary super admin may change it; authenticated admins may read it.
create or replace function private.admin_get_menu_visibility()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (select s.setting_value = 'true'::jsonb
     from public.app_settings s
     where s.setting_key = 'admin_menu_visible_to_other_admins'),
    true
  );
$$;
revoke all on function private.admin_get_menu_visibility() from public, anon;
grant execute on function private.admin_get_menu_visibility() to authenticated;

create or replace function public.admin_get_menu_visibility()
returns boolean
language sql
security invoker
set search_path = ''
as $$ select private.admin_get_menu_visibility(); $$;
revoke all on function public.admin_get_menu_visibility() from public, anon;
grant execute on function public.admin_get_menu_visibility() to authenticated;

create or replace function private.admin_set_menu_visibility(p_visible boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare v_actor uuid := auth.uid();
begin
  if v_actor is null or not private.is_super_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;
  insert into public.app_settings(setting_key,setting_value,updated_at,updated_by)
  values ('admin_menu_visible_to_other_admins',to_jsonb(p_visible),now(),v_actor)
  on conflict (setting_key) do update
    set setting_value = excluded.setting_value,
        updated_at = excluded.updated_at,
        updated_by = excluded.updated_by;
  insert into public.admin_audit_logs(actor_user_id,action,details)
  values (v_actor,'admin_menu_visibility_changed',jsonb_build_object('visible_to_other_admins',p_visible));
end;
$$;
revoke all on function private.admin_set_menu_visibility(boolean) from public, anon;
grant execute on function private.admin_set_menu_visibility(boolean) to authenticated;

create or replace function public.admin_set_menu_visibility(p_visible boolean)
returns void
language sql
security invoker
set search_path = ''
as $$ select private.admin_set_menu_visibility(p_visible); $$;
revoke all on function public.admin_set_menu_visibility(boolean) from public, anon;
grant execute on function public.admin_set_menu_visibility(boolean) to authenticated;
