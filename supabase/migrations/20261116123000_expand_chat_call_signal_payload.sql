-- Video SDP can exceed the original 12 KB signaling payload limit.
alter table public.chat_room_call_signals
  drop constraint if exists chat_room_call_signals_payload_check;

alter table public.chat_room_call_signals
  add constraint chat_room_call_signals_payload_check
  check (octet_length(payload::text) <= 32000);
