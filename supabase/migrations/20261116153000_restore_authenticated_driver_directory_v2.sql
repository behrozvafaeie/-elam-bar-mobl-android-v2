-- The public invoker wrapper delegates to this private helper.
-- Keep it available to authenticated users; the helper itself enforces role checks.
grant execute on function private.list_driver_directory_v2() to authenticated;
