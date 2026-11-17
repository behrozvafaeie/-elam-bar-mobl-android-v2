-- Session termination is tied to account deletion only; remove the standalone
-- authenticated API for forced logout. Account deletion workflow will revoke sessions
-- by deleting the auth user after preserving business records.
revoke execute on function public.admin_force_logout_member(uuid) from authenticated;
revoke execute on function public.admin_force_logout_all(uuid) from authenticated;