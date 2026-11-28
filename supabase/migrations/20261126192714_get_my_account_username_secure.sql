-- Allow each authenticated admin to read only their own username.
-- The account_usernames table remains inaccessible to client roles.
create or replace function private.get_my_account_username()
returns table(username text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  return query
  select au.username
  from public.account_usernames au
  where au.user_id = auth.uid()
  limit 1;
end;
$function$;

revoke all on function private.get_my_account_username() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.get_my_account_username() to authenticated;

create or replace function public.get_my_account_username()
returns table(username text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.get_my_account_username();
$function$;

revoke all on function public.get_my_account_username() from public, anon;
grant execute on function public.get_my_account_username() to authenticated;
