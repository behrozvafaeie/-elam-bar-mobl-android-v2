package ir.elambar.mobl

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class UserStory(
    val id: String,
    val userId: String,
    val role: String,
    val mediaPath: String,
    val caption: String,
    val createdAt: String,
    val expiresAt: String,
    val signedUrl: String
)

internal data class UserStoryComment(
    val id: String,
    val userId: String,
    val body: String,
    val createdAt: String
)

internal class StoryRepository(private val token: String) {
    private val base = "https://sckcxtceybwtiecyxqsy.supabase.co"
    private val key = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

    private fun request(method: String, path: String, payload: ByteArray? = null, contentType: String = "application/json"): Pair<Int, String> {
        val conn = URL("$base/$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(payload) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    fun currentUserId(): String? {
        val (code, body) = request("GET", "auth/v1/user")
        return if (code in 200..299) runCatching { JSONObject(body).optString("id").takeIf { it.isNotBlank() } }.getOrNull() else null
    }

    private fun signedUrl(path: String): String {
        val encoded = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val (code, body) = request("POST", "storage/v1/object/sign/user-stories/$encoded", JSONObject().put("expiresIn", 3600).toString().toByteArray())
        if (code !in 200..299) return ""
        val signed = runCatching { JSONObject(body).optString("signedURL") }.getOrDefault("")
        return when {
            signed.startsWith("http") -> signed
            signed.startsWith("/") -> "$base/storage/v1$signed"
            signed.isNotBlank() -> "$base/storage/v1/$signed"
            else -> ""
        }
    }

    fun listStories(): Pair<String?, List<UserStory>> {
        val (code, body) = request("GET", "rest/v1/user_stories?select=id,user_id,user_role,media_path,caption,created_at,expires_at&order=created_at.desc&limit=100")
        if (code !in 200..299) return "دریافت استوری‌ها ناموفق بود (کد $code)." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                val path = r.optString("media_path")
                UserStory(r.optString("id"), r.optString("user_id"), r.optString("user_role"), path,
                    r.optString("caption"), r.optString("created_at"), r.optString("expires_at"), signedUrl(path))
            }
        }.getOrElse { "خواندن فهرست استوری‌ها ناموفق بود." to emptyList() }
    }

    fun publishStory(context: Context, uri: Uri, userId: String, role: String, caption: String): String {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (!mime.startsWith("image/")) return "فعلاً فقط تصویر برای استوری پشتیبانی می‌شود."
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن تصویر ناموفق بود."
        if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) return "حجم تصویر باید کمتر از ۱۲ مگابایت باشد."
        val ext = when (mime.lowercase()) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val path = "$userId/${UUID.randomUUID()}.$ext"
        val encoded = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val (uploadCode, uploadBody) = request("POST", "storage/v1/object/user-stories/$encoded", bytes, mime)
        if (uploadCode !in 200..299) return "بارگذاری تصویر انجام نشد (کد $uploadCode)."
        val row = JSONObject().put("user_id", userId).put("user_role", role).put("media_path", path).put("caption", caption.trim().take(500))
        val (insertCode, _) = request("POST", "rest/v1/user_stories", row.toString().toByteArray())
        if (insertCode !in 200..299) {
            request("DELETE", "storage/v1/object/user-stories", JSONObject().put("prefixes", JSONArray().put(path)).toString().toByteArray())
            return "ثبت استوری در پایگاه داده ناموفق بود (کد $insertCode)."
        }
        return "استوری منتشر شد و پس از ۲۴ ساعت از نمایش عمومی خارج می‌شود."
    }

    fun comments(storyId: String): Pair<String?, List<UserStoryComment>> {
        val (code, body) = request("GET", "rest/v1/user_story_comments?select=id,user_id,body,created_at&story_id=eq.$storyId&order=created_at.asc&limit=100")
        if (code !in 200..299) return "دریافت نظرها ناموفق بود." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                UserStoryComment(r.optString("id"), r.optString("user_id"), r.optString("body"), r.optString("created_at"))
            }
        }.getOrElse { "خواندن نظرها ناموفق بود." to emptyList() }
    }

    fun addComment(storyId: String, userId: String, body: String): String {
        val clean = body.trim()
        if (clean.isEmpty() || clean.length > 1000) return "نظر باید بین ۱ تا ۱۰۰۰ نویسه باشد."
        val row = JSONObject().put("story_id", storyId).put("user_id", userId).put("body", clean)
        val (code, _) = request("POST", "rest/v1/user_story_comments", row.toString().toByteArray())
        return if (code in 200..299) "نظر ثبت شد." else "ثبت نظر ناموفق بود (کد $code)."
    }

    fun deleteStory(story: UserStory): String {
        val path = story.mediaPath.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        request("DELETE", "storage/v1/object/user-stories", JSONObject().put("prefixes", JSONArray().put(story.mediaPath)).toString().toByteArray())
        val (code, _) = request("DELETE", "rest/v1/user_stories?id=eq.${story.id}")
        return if (code in 200..299) "استوری توسط مدیر ارشد حذف شد." else "حذف استوری مجاز نشد (کد $code)."
    }
}
