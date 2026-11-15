-- Private-schema RPCs must never inherit EXECUTE from PUBLIC/anon.
-- Existing explicit authenticated grants remain intact.
do $$
declare r record;
begin
  for r in
    select p.oid::regprocedure as signature
    from pg_proc p
    join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='private'
  loop
    execute format('revoke all on function %s from public, anon', r.signature);
  end loop;
end $$;
