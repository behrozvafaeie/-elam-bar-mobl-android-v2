-- Additive story comments and assignment-gated company contact.
-- Existing tables, rows, buckets and unrelated policies are preserved.

create table if not exists public.user_story_comments (
  id uuid primary key default gen_random_uuid(),
  story_id uuid not null references public.user_stories(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 1000),
  created_at timestamptz not null default now()
);

create index if not exists user_story_comments_story_created_idx
  on public.user_story_comments (story_id, created_at asc);
create index if not exists user_story_comments_user_created_idx
  on public.user_story_comments (user_id, created_at desc);

alter table public.user_story_comments enable row level security;
revoke all on table public.user_story_comments from anon, public;
grant select, insert, delete on table public.user_story_comments to authenticated;

drop policy if exists user_story_comments_select_visible on public.user_story_comments;
create policy user_story_comments_select_visible
on public.user_story_comments for select to authenticated
using (
  coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and exists (
    select 1 from public.user_stories s
    where s.id = user_story_comments.story_id
      and (s.user_id = (select auth.uid()) or s.expires_at > now() or private.is_super_admin())
  )
);

drop policy if exists user_story_comments_insert_active on public.user_story_comments;
create policy user_story_comments_insert_active
on public.user_story_comments for insert to authenticated
with check (
  user_id = (select auth.uid())
  and coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and exists (
    select 1 from public.user_stories s
    where s.id = user_story_comments.story_id and s.expires_at > now()
  )
  and (
    exists (select 1 from public.drivers d where d.id = (select auth.uid()) and d.status not in ('blocked','rejected'))
    or exists (select 1 from public.companies c where c.id = (select auth.uid()) and c.status not in ('blocked','rejected'))
    or exists (
      select 1 from public.admin_users a
      where a.user_id = (select auth.uid()) and a.is_blocked = false
        and (a.access_expires_at is null or a.access_expires_at > now())
    )
  )
);

drop policy if exists user_story_comments_delete_super_admin_only on public.user_story_comments;
create policy user_story_comments_delete_super_admin_only
on public.user_story_comments for delete to authenticated
using (
  coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and private.is_super_admin()
);

-- Let every non-blocked signed-in account with an app profile publish a story.
-- user_role remains constrained to the existing driver/company/admin values.
drop policy if exists user_stories_insert_own on public.user_stories;
create policy user_stories_insert_own
on public.user_stories for insert to authenticated
with check (
  user_id = (select auth.uid())
  and coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and (
    (user_role = 'driver' and exists (
      select 1 from public.drivers d
      where d.id = (select auth.uid()) and d.status not in ('blocked','rejected')
    ))
    or (user_role = 'company' and exists (
      select 1 from public.companies c
      where c.id = (select auth.uid()) and c.status not in ('blocked','rejected')
    ))
    or (user_role = 'admin' and exists (
      select 1 from public.admin_users a
      where a.user_id = (select auth.uid()) and a.is_blocked = false
        and (a.access_expires_at is null or a.access_expires_at > now())
    ))
  )
);

-- Keep the bucket private; allow the same registered account classes to upload
-- only into their own UUID folder. Existing read/delete policies stay intact.
drop policy if exists user_stories_storage_insert_own on storage.objects;
create policy user_stories_storage_insert_own
on storage.objects for insert to authenticated
with check (
  bucket_id = 'user-stories'
  and (storage.foldername(name))[1] = (select auth.uid())::text
  and coalesce(((select auth.jwt()) ->> 'is_anonymous')::boolean, false) = false
  and (
    exists (select 1 from public.drivers d where d.id = (select auth.uid()) and d.status not in ('blocked','rejected'))
    or exists (select 1 from public.companies c where c.id = (select auth.uid()) and c.status not in ('blocked','rejected'))
    or exists (
      select 1 from public.admin_users a
      where a.user_id = (select auth.uid()) and a.is_blocked = false
        and (a.access_expires_at is null or a.access_expires_at > now())
    )
  )
);

-- Company identity and phone are returned only to the driver assigned to this
-- load, or to the primary super admin. No company data is added to load listings.
create or replace function private.get_assigned_load_company_contact(p_load_id uuid)
returns table (company_name text, manager_name text, mobile text)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := auth.uid();
  v_company_id uuid;
  v_assigned_driver_id uuid;
begin
  if v_uid is null then
    raise exception 'ورود به سامانه الزامی است' using errcode = '28000';
  end if;

  select l.company_id, l.assigned_driver_id
    into v_company_id, v_assigned_driver_id
  from public.loads l
  where l.id = p_load_id;

  if not found or v_company_id is null then
    return;
  end if;

  if not private.is_super_admin() then
    if v_assigned_driver_id is distinct from v_uid
       or not exists (
         select 1 from public.drivers d
         where d.id = v_uid and d.status = 'approved'
       ) then
      raise exception 'فقط راننده‌ای که این بار را گرفته به تماس شرکت دسترسی دارد'
        using errcode = '42501';
    end if;
  end if;

  return query
  select c.company_name, c.manager_name, c.mobile
  from public.companies c
  where c.id = v_company_id;
end;
$function$;

revoke all on function private.get_assigned_load_company_contact(uuid) from public, anon;
grant execute on function private.get_assigned_load_company_contact(uuid) to authenticated;

create or replace function public.get_assigned_load_company_contact(p_load_id uuid)
returns table (company_name text, manager_name text, mobile text)
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from private.get_assigned_load_company_contact(p_load_id);
$function$;

revoke all on function public.get_assigned_load_company_contact(uuid) from public, anon;
grant execute on function public.get_assigned_load_company_contact(uuid) to authenticated;
