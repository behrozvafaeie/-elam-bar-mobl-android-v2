-- Explicitly deny anonymous-authenticated sessions from the new private chat bucket.
create policy chat_room_storage_deny_anonymous
on storage.objects as restrictive
for all to authenticated
using (
  bucket_id <> 'chat-room-attachments'
  or coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false
)
with check (
  bucket_id <> 'chat-room-attachments'
  or coalesce(((select auth.jwt())->>'is_anonymous')::boolean,false)=false
);
