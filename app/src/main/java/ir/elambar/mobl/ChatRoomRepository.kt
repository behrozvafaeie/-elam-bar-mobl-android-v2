package ir.elambar.mobl

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal data class ChatRoomItem(
    val id: String,
    val type: String,
    val title: String,
    val locked: Boolean
)

internal data class ChatContactItem(val id: String, val name: String, val accountType: String)
internal data class ChatDriverAccess(val id: String, val name: String, val canRead: Boolean)
internal data class ChatRoomCall(val id: String, val roomId: String, val callerId: String, val receiverId: String, val callType: String, val status: String)
internal data class ChatRoomCallSignal(val id: String, val senderId: String, val recipientId: String, val type: String, val payload: JSONObject)

internal data class ChatRoomMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val type: String,
    val body: String,
    val attachmentPath: String,
    val createdAt: String,
    val pinned: Boolean
)

internal data class ChatCompanyItem(
    val id: String,
    val name: String,
    val muted: Boolean,
    val readOnly: Boolean,
    val canSendImages: Boolean,
    val canSendVoice: Boolean
)

/** All access uses the caller's JWT. Database RLS is the final authorization boundary. */
internal class ChatRoomRepository(private val token: String) {
    private val base = "https://sckcxtceybwtiecyxqsy.supabase.co"
    private val key = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

    fun listRooms(): Pair<String?, List<ChatRoomItem>> {
        val (code, body) = request("GET", "chat_rooms?select=id,room_type,title,is_locked&order=created_at.asc&limit=100")
        if (code !in 200..299) return "دریافت گفتگوها مجاز نشد (کد $code)." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                ChatRoomItem(r.optString("id"), r.optString("room_type"), r.optString("title"), r.optBoolean("is_locked"))
            }
        }.getOrElse { "ساختار فهرست گفتگوها قابل خواندن نیست." to emptyList() }
    }

    fun searchContacts(query: String): Pair<String?, List<ChatContactItem>> {
        val clean = query.trim()
        if (clean.length !in 2..80) return "برای جستجو حداقل دو نویسه وارد کنید." to emptyList()
        val (code, body) = request("POST", "rpc/search_chat_contacts", JSONObject().put("p_query", clean))
        if (code !in 200..299) return "جستجوی مخاطب مجاز نشد؛ نقش حساب یا اتصال را بررسی کنید." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                ChatContactItem(r.optString("user_id"), r.optString("display_name"), r.optString("account_type"))
            }
        }.getOrElse { "پاسخ جستجوی مخاطبان قابل خواندن نیست." to emptyList() }
    }

    fun createPrivateRoom(targetUserId: String, title: String): Pair<String?, String?> {
        if (targetUserId.isBlank()) return "مخاطب معتبر نیست." to null
        val payload = JSONObject().put("p_target_user_id", targetUserId).put("p_title", title.take(160))
        val (code, body) = request("POST", "rpc/create_private_chat_room", payload)
        if (code !in 200..299) return "ایجاد گفتگوی خصوصی مجاز نشد؛ نوع حساب یا وضعیت مخاطب را بررسی کنید." to null
        val id = body.trim().removeSurrounding("\"").takeIf { it.length == 36 }
        return if (id != null) null to id else "شناسه گفتگوی خصوصی از سرور دریافت نشد." to null
    }

    fun roomMemberIds(roomId: String): List<String> {
        val (code, body) = request("GET", "chat_room_members?select=user_id&room_id=eq.${enc(roomId)}")
        if (code !in 200..299) return emptyList()
        return runCatching {
            val rows = JSONArray(body)
            (0 until rows.length()).map { rows.getJSONObject(it).optString("user_id") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun createCall(roomId: String, receiverId: String, callType: String): Pair<String?, ChatRoomCall?> {
        if (callType !in setOf("audio","video")) return "نوع تماس نامعتبر است." to null
        val payload = JSONObject().put("p_room_id", roomId).put("p_receiver_user_id", receiverId).put("p_call_type", callType)
        val (code, body) = request("POST", "rpc/create_chat_room_call_session", payload)
        if (code !in 200..299) return "شروع تماس مجاز نشد؛ گفتگو باید خصوصی و مخاطب فعال باشد." to null
        val id = body.trim().removeSurrounding("\"").takeIf { it.length == 36 }
            ?: return "شناسه تماس از سرور دریافت نشد." to null
        return getCall(id)
    }

    fun incomingCall(userId: String): ChatRoomCall? {
        val path = "chat_room_call_sessions?select=id,room_id,caller_user_id,receiver_user_id,call_type,status&receiver_user_id=eq.${enc(userId)}&status=eq.ringing&order=created_at.desc&limit=1"
        val (code, body) = request("GET", path)
        if (code !in 200..299) return null
        return parseCallArray(body).firstOrNull()
    }

    fun getCall(callId: String): Pair<String?, ChatRoomCall?> {
        val (code, body) = request("GET", "chat_room_call_sessions?select=id,room_id,caller_user_id,receiver_user_id,call_type,status&id=eq.${enc(callId)}&limit=1")
        if (code !in 200..299) return "دریافت وضعیت تماس ناموفق بود." to null
        return null to parseCallArray(body).firstOrNull()
    }

    fun callSignals(callId: String): List<ChatRoomCallSignal> {
        val (code, body) = request("GET", "chat_room_call_signals?select=id,sender_user_id,recipient_user_id,signal_type,payload&call_session_id=eq.${enc(callId)}&order=created_at.asc&limit=200")
        if (code !in 200..299) return emptyList()
        return runCatching {
            val rows = JSONArray(body)
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                ChatRoomCallSignal(row.optString("id"),row.optString("sender_user_id"),row.optString("recipient_user_id"),row.optString("signal_type"),row.optJSONObject("payload") ?: JSONObject())
            }
        }.getOrDefault(emptyList())
    }

    fun sendCallSignal(callId: String, senderId: String, recipientId: String, type: String, payload: JSONObject): String {
        val row = JSONObject().put("call_session_id",callId).put("sender_user_id",senderId)
            .put("recipient_user_id",recipientId).put("signal_type",type).put("payload",payload)
        val (code, _) = request("POST","chat_room_call_signals",row,"return=minimal")
        return if (code in 200..299) "" else "ارسال سیگنال تماس مجاز نشد."
    }

    fun updateCallStatus(callId: String, status: String): String {
        val (code, body) = request("POST","rpc/update_chat_room_call_status",JSONObject().put("p_call_id",callId).put("p_new_status",status))
        if (code !in 200..299) return "تغییر وضعیت تماس مجاز نشد."
        return if (body.trim()=="true") "" else "وضعیت تماس تغییر نکرد."
    }

    private fun parseCallArray(body: String): List<ChatRoomCall> = runCatching {
        val rows = JSONArray(body)
        (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            ChatRoomCall(r.optString("id"),r.optString("room_id"),r.optString("caller_user_id"),r.optString("receiver_user_id"),r.optString("call_type"),r.optString("status"))
        }
    }.getOrDefault(emptyList())

    fun messages(roomId: String): Pair<String?, List<ChatRoomMessage>> {
        val path = "chat_room_messages?select=id,room_id,sender_user_id,message_type,body,attachment_path,created_at,is_pinned&room_id=eq.${enc(roomId)}&deleted_at=is.null&order=created_at.asc&limit=300"
        val (code, body) = request("GET", path)
        if (code !in 200..299) return "دسترسی به پیام‌های این اتاق مجاز نیست (کد $code)." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                ChatRoomMessage(
                    r.optString("id"), r.optString("room_id"), r.optString("sender_user_id"),
                    r.optString("message_type"), r.optString("body"), r.optString("attachment_path"),
                    r.optString("created_at"), r.optBoolean("is_pinned")
                )
            }
        }.getOrElse { "ساختار پیام‌ها قابل خواندن نیست." to emptyList() }
    }

    fun sendText(roomId: String, userId: String, text: String): String {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > 4000) return "متن پیام خالی یا بیش از ۴۰۰۰ نویسه است."
        val payload = JSONObject().put("room_id", roomId).put("sender_user_id", userId)
            .put("message_type", "text").put("body", clean)
        val (code, _) = request("POST", "chat_room_messages", payload)
        return if (code in 200..299) "پیام ارسال شد." else "ارسال پیام مجاز نشد؛ نقش یا محدودیت اتاق را بررسی کنید."
    }

    fun sendAttachment(roomId: String, userId: String, type: String, bytes: ByteArray, mime: String, extension: String): String {
        if (type !in setOf("image", "voice")) return "نوع فایل پشتیبانی نمی‌شود."
        if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return "حجم فایل خالی یا بیشتر از ۱۰ مگابایت است."
        val path = "$roomId/$userId/$type/${UUID.randomUUID()}.$extension"
        var upload: HttpURLConnection? = null
        return try {
            upload = (URL("$base/storage/v1/object/chat-room-attachments/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", mime)
                setRequestProperty("x-upsert", "false")
            }
            upload.outputStream.use { it.write(bytes) }
            val uploadCode = upload.responseCode
            if (uploadCode !in 200..299) return "بارگذاری فایل رد شد (کد $uploadCode)."
            val payload = JSONObject().put("room_id", roomId).put("sender_user_id", userId)
                .put("message_type", type).put("body", if (type == "voice") "پیام صوتی" else "تصویر")
                .put("attachment_path", path)
            val (code, _) = request("POST", "chat_room_messages", payload)
            if (code in 200..299) "فایل ارسال شد." else {
                deleteStorage(path)
                "فایل بارگذاری شد اما ثبت پیام مجاز نشد؛ فایل موقت پاک شد."
            }
        } catch (_: Exception) {
            "ارتباط برای ارسال فایل برقرار نشد."
        } finally {
            upload?.disconnect()
        }
    }

    fun downloadAttachment(path: String): ByteArray? {
        if (path.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val safe = path.split("/").joinToString("/") { enc(it) }
            connection = (URL("$base/storage/v1/object/authenticated/chat-room-attachments/$safe").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    fun companiesForModeration(roomId: String): Pair<String?, List<ChatCompanyItem>> {
        val (companyCode, companyBody) = request("GET", "companies?select=id,company_name&status=eq.approved&order=company_name.asc&limit=300")
        if (companyCode !in 200..299) return "دریافت فهرست شرکت‌ها مجاز نیست." to emptyList()
        val (settingsCode, settingsBody) = request("GET", "chat_room_company_settings?select=company_id,is_muted,read_only,can_send_images,can_send_voice&room_id=eq.${enc(roomId)}")
        if (settingsCode !in 200..299) return "دریافت تنظیمات گفتگوی شرکت‌ها مجاز نیست." to emptyList()
        return runCatching {
            val settings = mutableMapOf<String, JSONObject>()
            val rows = JSONArray(settingsBody)
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                settings[item.optString("company_id")] = item
            }
            val companies = JSONArray(companyBody)
            null to (0 until companies.length()).map { i ->
                val company = companies.getJSONObject(i)
                val id = company.optString("id")
                val setting = settings[id]
                ChatCompanyItem(
                    id, company.optString("company_name"),
                    setting?.optBoolean("is_muted") ?: false,
                    setting?.optBoolean("read_only") ?: false,
                    setting?.optBoolean("can_send_images") ?: false,
                    setting?.optBoolean("can_send_voice") ?: true
                )
            }
        }.getOrElse { "فهرست شرکت‌ها قابل پردازش نیست." to emptyList() }
    }

    fun driversForModeration(roomId: String): Pair<String?, List<ChatDriverAccess>> {
        val (driverCode, driverBody) = request("GET", "drivers?select=id,full_name&status=eq.approved&order=full_name.asc&limit=500")
        if (driverCode !in 200..299) return "دریافت فهرست رانندگان مجاز نشد." to emptyList()
        val (memberCode, memberBody) = request("GET", "chat_room_members?select=user_id,can_read,can_send&room_id=eq.${enc(roomId)}")
        if (memberCode !in 200..299) return "دریافت مجوز مشاهده رانندگان مجاز نشد." to emptyList()
        return runCatching {
            val members = mutableMapOf<String, Boolean>()
            val memberRows = JSONArray(memberBody)
            for (i in 0 until memberRows.length()) {
                val row = memberRows.getJSONObject(i)
                members[row.optString("user_id")] = row.optBoolean("can_read") && !row.optBoolean("can_send")
            }
            val rows = JSONArray(driverBody)
            null to (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val id = row.optString("id")
                ChatDriverAccess(id, row.optString("full_name"), members[id] == true)
            }
        }.getOrElse { "فهرست دسترسی رانندگان قابل پردازش نیست." to emptyList() }
    }

    fun saveDriverReadPermission(roomId: String, driverId: String, enabled: Boolean): String {
        if (enabled) {
            val payload = JSONObject().put("room_id", roomId).put("user_id", driverId)
                .put("can_read", true).put("can_send", false)
            val (code, _) = request("POST", "chat_room_members?on_conflict=room_id,user_id", payload, "resolution=merge-duplicates,return=minimal")
            return if (code in 200..299) "مجوز مشاهده فقط‌خواندنی راننده فعال شد." else "فعال‌سازی دسترسی راننده مجاز نشد."
        }
        val path = "chat_room_members?room_id=eq.${enc(roomId)}&user_id=eq.${enc(driverId)}"
        val (code, _) = request("DELETE", path)
        return if (code in 200..299) "دسترسی راننده برداشته شد." else "حذف دسترسی راننده مجاز نشد."
    }

    fun saveCompanySetting(roomId: String, item: ChatCompanyItem): String {
        val payload = JSONObject().put("room_id", roomId).put("company_id", item.id)
            .put("is_muted", item.muted).put("read_only", item.readOnly)
            .put("can_send_images", item.canSendImages).put("can_send_voice", item.canSendVoice)
        val (code, _) = request("POST", "chat_room_company_settings?on_conflict=room_id,company_id", payload, "resolution=merge-duplicates,return=minimal")
        return if (code in 200..299) "دسترسی شرکت ذخیره شد." else "ذخیره دسترسی شرکت مجاز نشد."
    }

    fun setPinned(messageId: String, pinned: Boolean): String {
        val payload = JSONObject().put("is_pinned", pinned)
        val (code, _) = request("PATCH", "chat_room_messages?id=eq.${enc(messageId)}", payload)
        return if (code in 200..299) "وضعیت سنجاق پیام تغییر کرد." else "تغییر پیام مجاز نشد."
    }

    fun deleteMessage(messageId: String, userId: String): String {
        val payload = JSONObject().put("deleted_at", utcNow()).put("deleted_by", userId)
        val (code, _) = request("PATCH", "chat_room_messages?id=eq.${enc(messageId)}", payload)
        return if (code in 200..299) "پیام حذف شد." else "حذف پیام مجاز نشد."
    }

    fun setRoomLocked(roomId: String, locked: Boolean): String {
        val payload = JSONObject().put("is_locked", locked).put("updated_at", java.time.Instant.now().toString())
        val (code, _) = request("PATCH", "chat_rooms?id=eq.${enc(roomId)}", payload)
        return if (code in 200..299) "وضعیت اتاق ذخیره شد." else "تغییر وضعیت اتاق مجاز نشد."
    }

    private fun deleteStorage(path: String) {
        var c: HttpURLConnection? = null
        try {
            c = (URL("$base/storage/v1/object/chat-room-attachments/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $token")
            }
            c.responseCode
        } catch (_: Exception) { }
        finally { c?.disconnect() }
    }

    private fun request(method: String, path: String, payload: JSONObject? = null, prefer: String = "return=representation"): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/rest/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", prefer)
                }
            }
            if (payload != null) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) { -1 to (e.message ?: "network_error") }
        finally { connection?.disconnect() }
    }

    private fun utcNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
