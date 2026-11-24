-- Return account usernames only to the primary super administrator.
-- Do not relax the account_usernames table RLS policy for normal clients.

create or replace function public.admin_list_account_usernames()
returns table(user_id uuid, username text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  if not private.is_super_admin() then
    raise exception 'فقط مدیر ارشد اصلی به فهرست نام‌های کاربری دسترسی دارد'
      using errcode = '42501';
  end if;

  return query
  select au.user_id, au.username::text
  from public.account_usernames au
  order by au.username;
end;
$function$;

revoke all on function public.admin_list_account_usernames() from public, anon;
grant execute on function public.admin_list_account_usernames() to authenticated;
