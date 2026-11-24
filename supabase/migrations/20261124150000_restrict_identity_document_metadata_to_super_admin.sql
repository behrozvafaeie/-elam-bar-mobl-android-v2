drop policy if exists company_documents_select_scoped on public.company_documents;
create policy company_documents_select_super_admin_only
on public.company_documents
for select
to authenticated
using (private.is_super_admin());

drop policy if exists driver_documents_select_combined on public.driver_documents;
create policy driver_documents_select_super_admin_only
on public.driver_documents
for select
to authenticated
using (private.is_super_admin());
