CREATE POLICY "companies_delete_own_unassigned_published_loads"
ON public.loads
FOR DELETE
TO authenticated
USING (
  (select auth.uid()) IS NOT NULL
  AND company_id = (select auth.uid())
  AND publisher_user_id = (select auth.uid())
  AND publisher_type = 'company'
  AND status = 'published'
  AND assigned_driver_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM public.companies c
    WHERE c.id = (select auth.uid())
      AND c.status = 'approved'
  )
);
