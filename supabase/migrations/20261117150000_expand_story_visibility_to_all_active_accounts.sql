-- Active stories and their private media are visible to all non-blocked app accounts.
-- Owners retain access to their expired stories for their private archive.
drop policy if exists user_stories_select_active_or_owner_archive on public.user_stories;
create policy user_stories_select_active_or_owner_archive
on public.user_stories for select to authenticated
using (
  coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and (
    user_id = (select auth.uid())
    or private.is_super_admin()
    or (
      expires_at > now()
      and (
        exists (select 1 from public.drivers d where d.id = (select auth.uid()) and d.status not in ('blocked','rejected'))
        or exists (select 1 from public.companies c where c.id = (select auth.uid()) and c.status not in ('blocked','rejected'))
        or exists (
          select 1 from public.admin_users a
          where a.user_id = (select auth.uid()) and a.is_blocked = false
            and (a.access_expires_at is null or a.access_expires_at > now())
        )
      )
    )
  )
);

drop policy if exists user_stories_storage_select_active_or_owner_archive on storage.objects;
create policy user_stories_storage_select_active_or_owner_archive
on storage.objects for select to authenticated
using (
  bucket_id = 'user-stories'
  and coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and exists (
    select 1 from public.user_stories s
    where s.media_path = objects.name
      and (
        s.user_id = (select auth.uid())
        or private.is_super_admin()
        or (
          s.expires_at > now()
          and (
            exists (select 1 from public.drivers d where d.id = (select auth.uid()) and d.status not in ('blocked','rejected'))
            or exists (select 1 from public.companies c where c.id = (select auth.uid()) and c.status not in ('blocked','rejected'))
            or exists (
              select 1 from public.admin_users a
              where a.user_id = (select auth.uid()) and a.is_blocked = false
                and (a.access_expires_at is null or a.access_expires_at > now())
            )
          )
        )
      )
  )
);