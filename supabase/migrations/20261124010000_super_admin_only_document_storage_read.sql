-- Restrict direct access to identity documents to the primary super administrator.
-- Keep document metadata and upload/update workflows unchanged; this controls object reads only.

drop policy if exists company_documents_storage_select_owner_admin on storage.objects;

drop policy if exists driver_documents_storage_admin_manage_read on storage.objects;
drop policy if exists driver_documents_storage_select_for_approved_companies_and_admi on storage.objects;
drop policy if exists driver_documents_storage_select_scoped on storage.objects;

create policy identity_documents_storage_read_super_admin_only
on storage.objects
for select
to authenticated
using (
  bucket_id in ('driver-documents', 'company-documents')
  and private.is_super_admin()
);
