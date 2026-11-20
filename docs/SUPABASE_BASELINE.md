# Supabase زنده — موجودی اولیه فقط‌خواندنی

Project: behrozvafaeie's Project
Project ref: sckcxtceybwtiecyxqsy
Region: ap-southeast-1
Database: PostgreSQL 17.11
Snapshot checked: 2026-11-20

## وضعیت
این سند از فهرست جداول، migration history، Edge Functions، توابع و advisorهای پروژه زنده استخراج شده است. هیچ تغییری در پروژه اعمال نشده است.

## موجودیت‌های اصلی مشاهده‌شده
- companies, drivers, driver_documents
- loads, load_cargo_items, load_change_logs, load_favorites
- admin_users, admin_section_permissions, admin_audit_logs
- conversations, messages, notifications, personal_contacts
- chat_rooms, chat_room_members, chat_room_messages, chat_room_company_settings
- call_sessions / call_signals و جداول تماس اتاق گفتگو
- driver_locations, load_complaints, load_ratings
- app_settings و جداول/توابع مرتبط با کیف پول پیامک

جزئیات کامل ستون‌ها و روابط باید هنگام پیاده‌سازی هر repository مجدداً از schema خوانده شود؛ این فهرست قرارداد نهایی API نیست.

## توابع سمت سرور مهم مشاهده‌شده
- private.publish_tehran_load_advanced_v1(jsonb,text,uuid)
- private.publish_tehran_load_batch_v2(jsonb,text,uuid)
- private.driver_accept_load(uuid)
- private.search_approved_drivers_v2(text,text)
- private.admin_get_dashboard_stats()
- private.admin_list_users()
- private.admin_set_section_permission(uuid,text,boolean,boolean,boolean,boolean)
- private.create_private_chat_room(uuid,text)
- private.can_read_chat_room(uuid)
- private.can_send_chat_room_message(uuid,text)
- private.sms_get_my_wallet()
- private.sms_list_my_messages(integer,timestamptz)

توابع بالا فقط وجودشان در schema مشاهده شده است؛ ورودی/خروجی، grant، منطق داخلی و سازگاری هر کدام باید قبل از فراخوانی از کد/تعریف تابع بررسی شود.

## Edge Functions فعال مشاهده‌شده
- device-register (verify_jwt=false)
- username-auth (verify_jwt=false)
- username-auth-v2 (verify_jwt=false)
- sms-admin-status (verify_jwt=true)

به‌ویژه دو تابع auth با verify_jwt=false نیازمند بررسی دقیق rate limit، اعتبارسنجی ورودی، محدودیت سوءاستفاده و منطق صدور/تأیید نشست هستند؛ از وجودشان به‌تنهایی امن‌بودن نتیجه نمی‌شود.

## نکته مهم در داده بار
جدول loads هنوز ستون‌های announcement_batch_id و batch_position را دارد که توصیف قدیمی پنج‌قلمی دارند. در عین حال migration مربوط به unlimited_load_batch_registration و تابع advanced load نیز ثبت شده‌اند. بنابراین نباید فرض کنیم هر RPC قدیمی محدودیت پنج‌قلمی را رفع کرده است؛ ابتدا تعریف تابع و تست مرزی تعداد اقلام لازم است.

## هشدارهای advisor در snapshot
- هشدار Anonymous Access Policies برای تعدادی از جدول‌ها و Storage policies؛ باید بررسی شود آیا anonymous sign-in واقعاً فعال است یا policyها به شکلی نوشته شده‌اند که advisor آن‌ها را عمومی تشخیص می‌دهد.
- هشدار Leaked Password Protection Disabled؛ با گفته قبلی کاربر مبنی بر روشن‌کردن این گزینه مغایرت دارد و وضعیت فعلی Dashboard باید مجدداً تأیید شود.
این هشدارها در این مرحله تغییر داده نشده‌اند.

## محدودیت ایمنی
- هیچ migration جدیدی روی پروژه زنده اجرا نشود تا قرارداد API و اثر آن بر داده موجود روشن شود.
- کلیدهای محرمانه یا مقادیر secrets در این مخزن ثبت نشوند.
- شناسه پروژه به‌تنهایی secret نیست، اما هیچ کلید دسترسی یا token در مستندات قرار نگیرد.
