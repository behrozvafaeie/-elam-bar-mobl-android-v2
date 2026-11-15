-- Give the primary super-admin full read/reply access to existing load conversations.
-- Ordinary managers do not inherit this policy and remain governed by explicit membership/permissions.

create policy conversations_select_super_admin_all
on public.conversations
for select to authenticated
using (private.is_super_admin());

create policy messages_select_super_admin_all
on public.messages
for select to authenticated
using (
  private.is_super_admin()
  and exists (select 1 from public.conversations c where c.id=messages.conversation_id)
);

create policy messages_insert_super_admin_reply
on public.messages
for insert to authenticated
with check (
  sender_user_id=auth.uid()
  and private.is_super_admin()
  and exists (select 1 from public.conversations c where c.id=messages.conversation_id)
  and (
    message_type='text'
    or (
      attachment_path like conversation_id::text || '/' || auth.uid()::text || '/%'
      and exists (
        select 1 from storage.objects o
        where o.bucket_id='chat-attachments' and o.name=messages.attachment_path
      )
    )
  )
);

create policy chat_attachments_select_super_admin
on storage.objects
for select to authenticated
using (bucket_id='chat-attachments' and private.is_super_admin());

create policy chat_attachments_insert_super_admin
on storage.objects
for insert to authenticated
with check (
  bucket_id='chat-attachments'
  and (storage.foldername(name))[2]=(select auth.uid())::text
  and private.is_super_admin()
);

create policy chat_attachments_delete_super_admin
on storage.objects
for delete to authenticated
using (bucket_id='chat-attachments' and private.is_super_admin());
