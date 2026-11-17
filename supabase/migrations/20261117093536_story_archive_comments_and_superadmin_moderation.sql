-- Story archive, open comments, and super-admin-only moderation.
-- Existing stories and storage objects are preserved.
CREATE TABLE public.user_story_comments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  story_id uuid NOT NULL REFERENCES public.user_stories(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  body text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 1000),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_story_comments_story_created_idx
  ON public.user_story_comments (story_id, created_at);
ALTER TABLE public.user_story_comments ENABLE ROW LEVEL SECURITY;

CREATE POLICY user_story_comments_select_visible ON public.user_story_comments
FOR SELECT TO authenticated USING (
  COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
  AND EXISTS (SELECT 1 FROM public.user_stories s WHERE s.id = user_story_comments.story_id
    AND (s.user_id = auth.uid() OR (s.expires_at > now() AND (
      EXISTS (SELECT 1 FROM public.drivers d WHERE d.id = auth.uid() AND d.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.companies c WHERE c.id = auth.uid() AND c.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = auth.uid()
        AND a.is_blocked = false AND (a.access_expires_at IS NULL OR a.access_expires_at > now()))
    )))
  )
);
CREATE POLICY user_story_comments_insert_approved ON public.user_story_comments
FOR INSERT TO authenticated WITH CHECK (
  user_id = auth.uid()
  AND char_length(btrim(body)) BETWEEN 1 AND 1000
  AND COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
  AND (
    EXISTS (SELECT 1 FROM public.drivers d WHERE d.id = auth.uid() AND d.status = 'approved')
    OR EXISTS (SELECT 1 FROM public.companies c WHERE c.id = auth.uid() AND c.status = 'approved')
    OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = auth.uid()
      AND a.is_blocked = false AND (a.access_expires_at IS NULL OR a.access_expires_at > now()))
  )
  AND EXISTS (SELECT 1 FROM public.user_stories s WHERE s.id = user_story_comments.story_id AND s.expires_at > now())
);
CREATE POLICY user_story_comments_delete_super_admin ON public.user_story_comments
FOR DELETE TO authenticated USING (
  COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false AND private.is_super_admin()
);

DROP POLICY IF EXISTS user_stories_delete_owner_or_superadmin ON public.user_stories;
CREATE POLICY user_stories_delete_super_admin_only ON public.user_stories
FOR DELETE TO authenticated USING (
  COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false AND private.is_super_admin()
);
DROP POLICY IF EXISTS user_stories_select_active ON public.user_stories;
CREATE POLICY user_stories_select_active_or_owner_archive ON public.user_stories
FOR SELECT TO authenticated USING (
  COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
  AND (user_id = auth.uid() OR (
    expires_at > now() AND (
      EXISTS (SELECT 1 FROM public.drivers d WHERE d.id = auth.uid() AND d.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.companies c WHERE c.id = auth.uid() AND c.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = auth.uid()
        AND a.is_blocked = false AND (a.access_expires_at IS NULL OR a.access_expires_at > now()))
    )
  ) OR private.is_super_admin()
);

DROP POLICY IF EXISTS user_stories_storage_delete_owner_or_superadmin ON storage.objects;
CREATE POLICY user_stories_storage_delete_super_admin_only ON storage.objects
FOR DELETE TO authenticated USING (
  bucket_id = 'user-stories' AND COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
  AND private.is_super_admin()
);
DROP POLICY IF EXISTS user_stories_storage_select_active ON storage.objects;
CREATE POLICY user_stories_storage_select_active_or_owner_archive ON storage.objects
FOR SELECT TO authenticated USING (
  bucket_id = 'user-stories' AND COALESCE((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
  AND EXISTS (SELECT 1 FROM public.user_stories s WHERE s.media_path = objects.name
    AND (s.user_id = auth.uid() OR (s.expires_at > now() AND (
      EXISTS (SELECT 1 FROM public.drivers d WHERE d.id = auth.uid() AND d.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.companies c WHERE c.id = auth.uid() AND c.status = 'approved')
      OR EXISTS (SELECT 1 FROM public.admin_users a WHERE a.user_id = auth.uid()
        AND a.is_blocked = false AND (a.access_expires_at IS NULL OR a.access_expires_at > now()))
    )) OR private.is_super_admin())
  )
);
