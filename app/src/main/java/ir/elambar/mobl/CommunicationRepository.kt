package ir.elambar.mobl

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Secure participant-scoped messaging access for the existing Supabase schema.
 *
 * All access is performed with the signed-in user's access token. Supabase RLS remains
 * authoritative; this class deliberately does not use a service-role key or bypass RLS.
 * The current database policies restrict conversations
 * to the assigned company/driver pair.
 */
internal data class ChatConversation(
    val id: String,
    val loadId: String,
    val companyId: String,
    val driverId: String,
    val lastMessageAt: String
)

internal data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val type: String,
    val body: String,
    val createdAt: String,
    val attachmentPath: String = ""
)

internal class CommunicationRepository(
    private val accessToken: String,
    private val supabaseUrl: String = "https://sckcxtceybwtiecyxqsy.supabase.co",
    private val publishableKey: String = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"
) {
    fun conversationsFor(userId: String, isSuperAdmin: Boolean = false): Pair<String?, List<ChatConversation>> {
        if (accessToken.isBlank() || userId.isBlank()) return "نشست معتبر نیست." to emptyList()
        if (isSuperAdmin) {
            val (code, body) = request(
                "GET",
                "conversations?select=id,load_id,company_id,driver_id,last_message_at&order=last_message_at.desc.nullslast&limit=100"
            )
            if (code !in 200..299) return "دریافت گفتگوها برای مدیر ارشد توسط سیاست امنیتی سامانه رد شد." to emptyList()
            return parseConversations(listOf(body))
        }
        val companyRows = request(
            "GET",
            "conversations?select=id,load_id,company_id,driver_id,last_message_at&company_id=eq.${enc(userId)}&order=last_message_at.desc.nullslast&limit=100"
        )
        val driverRows = request(
            "GET",
            "conversations?select=id,load_id,company_id,driver_id,last_message_at&driver_id=eq.${enc(userId)}&order=last_message_at.desc.nullslast&limit=100"
        )
        if (companyRows.first !in 200..299 || driverRows.first !in 200..299) {
            return "دریافت گفتگوها توسط سیاست امنیتی سامانه رد شد." to emptyList()
        }
        return parseConversations(listOf(companyRows.second, driverRows.second))
    }

    private fun parseConversations(rawResponses: List<String>): Pair<String?, List<ChatConversation>> =
        runCatching {
            val combined = linkedMapOf<String, ChatConversation>()
            rawResponses.forEach { raw ->
                val rows = JSONArray(raw)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val item = ChatConversation(
                        row.optString("id"), row.optString("load_id"),
                        row.optString("company_id"), row.optString("driver_id"),
                        row.optString("last_message_at")
                    )
                    combined[item.id] = item
                }
            }
            null to combined.values.sortedByDescending { it.lastMessageAt }
        }.getOrElse { "پاسخ گفتگوها قابل خواندن نیست." to emptyList() }

    fun messages(conversationId: String): Pair<String?, List<ChatMessage>> {
        if (accessToken.isBlank() || conversationId.isBlank()) return "نشست یا شناسه گفتگو معتبر نیست." to emptyList()
        val (code, body) = request(
            "GET",
            "messages?select=id,conversation_id,sender_user_id,message_type,body,created_at,attachment_path&conversation_id=eq.${enc(conversationId)}&order=created_at.asc&limit=300"
        )
        if (code !in 200..299) return "دسترسی به پیام‌ها توسط سیاست امنیتی سامانه رد شد." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                ChatMessage(
                    row.optString("id"), row.optString("conversation_id"),
                    row.optString("sender_user_id"), row.optString("message_type"),
                    row.optString("body"), row.optString("created_at"), row.optString("attachment_path")
                )
            }
        }.getOrElse { "پاسخ پیام‌ها قابل خواندن نیست." to emptyList() }
    }

    fun sendText(conversationId: String, senderUserId: String, text: String): String {
        val clean = text.trim()
        if (accessToken.isBlank() || conversationId.isBlank() || senderUserId.isBlank()) return "نشست یا مشخصات گفتگو معتبر نیست."
        if (clean.isEmpty()) return "متن پیام خالی است."
        if (clean.length > 4000) return "حداکثر طول پیام ۴۰۰۰ نویسه است."
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("sender_user_id", senderUserId)
            .put("message_type", "text")
            .put("body", clean)
        val (code, body) = request("POST", "messages", payload)
        return if (code in 200..299) "پیام ارسال شد."
        else "ارسال پیام توسط سامانه رد شد؛ عضویت در گفتگو و مجوز حساب بررسی شود."
    }

    fun sendVoiceFile(conversationId: String, senderUserId: String, file: java.io.File): String {
        if (accessToken.isBlank() || conversationId.isBlank() || senderUserId.isBlank()) return "نشست یا گفتگو معتبر نیست."
        if (!file.exists() || file.length() !in 1..(8L * 1024L * 1024L)) return "فایل ویس خالی است یا از ۸ مگابایت بیشتر است."
        val path = "$conversationId/$senderUserId/${java.util.UUID.randomUUID()}.m4a"
        var upload: HttpURLConnection? = null
        return try {
            upload = (URL("$supabaseUrl/storage/v1/object/chat-attachments/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "audio/mp4")
                setRequestProperty("x-upsert", "false")
            }
            file.inputStream().use { input -> upload.outputStream.use { output -> input.copyTo(output) } }
            val uploadCode = upload.responseCode
            if (uploadCode !in 200..299) return "بارگذاری ویس توسط سیاست امنیتی فضای گفتگو رد شد (کد $uploadCode)."
            val payload = JSONObject().put("conversation_id", conversationId).put("sender_user_id", senderUserId)
                .put("message_type", "voice").put("body", "پیام صوتی").put("attachment_path", path)
            val (code, _) = request("POST", "messages", payload)
            if (code in 200..299) "پیام صوتی ارسال شد." else {
                var cleanup: HttpURLConnection? = null
                try {
                    cleanup = (URL("$supabaseUrl/storage/v1/object/chat-attachments/$path").openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"; connectTimeout = 10000; readTimeout = 10000
                        setRequestProperty("apikey", publishableKey)
                        setRequestProperty("Authorization", "Bearer $accessToken")
                    }
                    runCatching { cleanup.responseCode }
                } finally { cleanup?.disconnect() }
                "فایل بارگذاری شد اما ثبت پیام توسط سیاست امنیتی سامانه رد شد؛ فایل موقت پاک شد."
            }
        } catch (_: Exception) { "ارتباط برای ارسال پیام صوتی برقرار نشد." }
        finally { upload?.disconnect() }
    }

    fun downloadVoice(path: String): ByteArray? {
        if (accessToken.isBlank() || path.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val safePath = path.split("/").joinToString("/") { enc(it) }
            connection = (URL("$supabaseUrl/storage/v1/object/authenticated/chat-attachments/$safePath").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 25000
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    fun createAssignedLoadConversation(
        loadId: String,
        companyUserId: String,
        driverUserId: String,
        creatorUserId: String
    ): Pair<String?, String?> {
        if (accessToken.isBlank()) return "نشست معتبر نیست." to null
        if (listOf(loadId, companyUserId, driverUserId, creatorUserId).any { it.isBlank() }) {
            return "اطلاعات بار یا طرفین گفتگو ناقص است." to null
        }
        val payload = JSONObject()
            .put("load_id", loadId)
            .put("company_id", companyUserId)
            .put("driver_id", driverUserId)
            .put("created_by", creatorUserId)
        val (code, body) = request("POST", "conversations", payload)
        if (code !in 200..299) return "ایجاد گفتگو مجاز نیست؛ باید بار به همین شرکت و راننده تخصیص یافته باشد." to null
        return runCatching {
            val rows = JSONArray(body)
            null to if (rows.length() > 0) rows.getJSONObject(0).optString("id") else null
        }.getOrElse { "شناسه گفتگو از پاسخ سرور دریافت نشد." to null }
    }

    private fun request(method: String, path: String, payload: JSONObject? = null): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$supabaseUrl/rest/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=representation")
                }
            }
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: Exception) {
            -1 to (error.message ?: "network_error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
