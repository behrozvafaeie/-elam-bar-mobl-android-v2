-- Defense in depth: anonymous-authenticated sessions must not read or mutate
-- any RLS-protected public application table or private storage object.
do $$
declare r record;
begin
  for r in
    select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='public' and c.relkind='r' and c.relrowsecurity
  loop
    execute format(
      'drop policy if exists deny_anonymous_authenticated_sessions on %I.%I',
      r.schema_name, r.table_name
    );
    execute format(
      'create policy deny_anonymous_authenticated_sessions on %I.%I as restrictive for all to authenticated using (coalesce(((select auth.jwt())->>''is_anonymous'')::boolean,false)=false) with check (coalesce(((select auth.jwt())->>''is_anonymous'')::boolean,false)=false)',
      r.schema_name, r.table_name
    );
  end loop;
end $$;

drop policy if exists deny_anonymous_authenticated_sessions on storage.objects;
create policy deny_anonymous_authenticated_sessions
on storage.objects as restrictive
for all to authenticated
using (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false)
with check (coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false);
