-- Keep profile photos available inside authenticated app screens without exposing public object URLs.
-- Apply this migration only after deploying the Android build that requests signed URLs.
update storage.buckets
set public = false
where id = 'profile-photos';

drop policy if exists profile_photos_public_read on storage.objects;

drop policy if exists profile_photos_select_authorized_accounts on storage.objects;
create policy profile_photos_select_authorized_accounts
on storage.objects
for select
to authenticated
using (
    bucket_id = 'profile-photos'
    and (
        (storage.foldername(name))[1] = (select auth.uid())::text
        or private.is_super_admin()
        or exists (
            select 1
            from public.companies c
            where c.id = (select auth.uid())
              and c.status = 'approved'
        )
        or exists (
            select 1
            from public.drivers d
            where d.id = (select auth.uid())
              and d.status = 'approved'
        )
        or exists (
            select 1
            from public.admin_users a
            where a.user_id = (select auth.uid())
              and a.is_blocked = false
              and (a.access_expires_at is null or a.access_expires_at > now())
        )
    )
);
