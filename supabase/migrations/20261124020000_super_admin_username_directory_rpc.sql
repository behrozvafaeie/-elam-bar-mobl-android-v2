-- Secure username directory: the exposed public wrapper is SECURITY INVOKER.
-- The private SECURITY DEFINER function performs a strict live-session check
-- through private.is_super_admin() before reading account_usernames.

create or replace function private.admin_list_account_usernames()
returns table(user_id uuid, username text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null or not private.is_super_admin() then
    raise exception 'فقط مدیر ارشد اصلی به فهرست نام‌های کاربری دسترسی دارد'
      using errcode = '42501';
  end if;

  return query
  select au.user_id, au.username
  from public.account_usernames au
  order by au.username;
end;
$function$;

revoke all on function private.admin_list_account_usernames() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.admin_list_account_usernames() to authenticated;

create or replace function public.admin_list_account_usernames()
returns table(user_id uuid, username text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.admin_list_account_usernames();
$function$;

revoke all on function public.admin_list_account_usernames() from public, anon;
grant execute on function public.admin_list_account_usernames() to authenticated;
