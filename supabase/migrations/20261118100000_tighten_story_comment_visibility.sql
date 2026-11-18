-- Restrict story comments to the same active account classes that can view stories.
-- This is an additive policy correction; no existing rows or tables are removed.
drop policy if exists user_story_comments_select_visible on public.user_story_comments;

create policy user_story_comments_select_visible
on public.user_story_comments for select to authenticated
using (
  coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and exists (
    select 1
    from public.user_stories s
    where s.id = user_story_comments.story_id
      and (
        s.user_id = (select auth.uid())
        or private.is_super_admin()
        or (
          s.expires_at > now()
          and (
            exists (
              select 1 from public.drivers d
              where d.id = (select auth.uid())
                and d.status not in ('blocked','rejected')
            )
            or exists (
              select 1 from public.companies c
              where c.id = (select auth.uid())
                and c.status not in ('blocked','rejected')
            )
            or exists (
              select 1 from public.admin_users a
              where a.user_id = (select auth.uid())
                and a.is_blocked = false
                and (a.access_expires_at is null or a.access_expires_at > now())
            )
          )
        )
      )
  )
);
