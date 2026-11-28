CREATE OR REPLACE FUNCTION private.admin_set_member_status(
  p_table text,
  p_user_id uuid,
  p_status text,
  p_reason text DEFAULT NULL::text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_section text;
  v_old_status text;
  v_rows integer;
BEGIN
  IF v_actor IS NULL THEN
    RAISE EXCEPTION 'not_authorized' USING errcode = '42501';
  END IF;

  IF p_status = 'blocked' THEN
    IF NOT private.is_super_admin() THEN
      RAISE EXCEPTION 'only_primary_super_admin_can_block_members' USING errcode = '42501';
    END IF;
  ELSIF NOT private.is_admin() THEN
    RAISE EXCEPTION 'not_authorized' USING errcode = '42501';
  END IF;

  IF p_table = 'drivers' THEN
    v_section := 'drivers';
  ELSIF p_table = 'companies' THEN
    v_section := 'companies';
  ELSE
    RAISE EXCEPTION 'invalid_member_table' USING errcode = '22023';
  END IF;

  IF p_user_id IS NULL OR p_status NOT IN ('approved','rejected','blocked') THEN
    RAISE EXCEPTION 'invalid_input' USING errcode = '22023';
  END IF;

  IF p_status = 'blocked' AND (p_reason IS NULL OR length(pg_catalog.btrim(p_reason)) < 3) THEN
    RAISE EXCEPTION 'block_reason_required' USING errcode = '22023';
  END IF;

  IF NOT private.is_super_admin()
     AND NOT private.has_admin_section_permission(v_section, 'edit') THEN
    RAISE EXCEPTION 'section_edit_permission_required' USING errcode = '42501';
  END IF;

  IF p_table = 'drivers' THEN
    SELECT status INTO v_old_status FROM public.drivers WHERE id = p_user_id FOR UPDATE;
  ELSE
    SELECT status INTO v_old_status FROM public.companies WHERE id = p_user_id FOR UPDATE;
  END IF;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'member_not_found' USING errcode = 'P0002';
  END IF;

  IF p_table = 'drivers' THEN
    UPDATE public.drivers SET status = p_status WHERE id = p_user_id;
  ELSE
    UPDATE public.companies SET status = p_status WHERE id = p_user_id;
  END IF;

  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows <> 1 THEN
    RAISE EXCEPTION 'member_status_update_failed' USING errcode = 'P0001';
  END IF;

  INSERT INTO public.admin_audit_logs(actor_user_id,target_user_id,action,details)
  VALUES (
    v_actor,
    p_user_id,
    'member_status_changed',
    pg_catalog.jsonb_build_object(
      'table',p_table,
      'old_status',v_old_status,
      'new_status',p_status,
      'reason',CASE WHEN p_status = 'blocked' THEN pg_catalog.btrim(p_reason) ELSE NULL END
    )
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_force_logout_member(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_force_logout_member(uuid) TO authenticated;
