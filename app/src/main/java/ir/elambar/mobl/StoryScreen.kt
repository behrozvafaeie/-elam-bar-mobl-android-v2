package ir.elambar.mobl

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private const val STORY_SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val STORY_SUPABASE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class StoryCardData(
    val id: String,
    val userId: String,
    val role: String,
    val caption: String,
    val mediaPath: String,
    val imageUrl: String,
    val expiresAt: String,
    val isMine: Boolean,
    val comments: List<StoryCommentData> = emptyList()
)
private data class StoryCommentData(val id: String, val body: String, val userId: String)

@Composable
internal fun StoryScreen(accessToken: String, role: String, isSuperAdmin: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stories by remember { mutableStateOf<List<StoryCardData>>(emptyList()) }
    var caption by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("استوری‌ها برای ۲۴ ساعت در نمایش عمومی هستند و سپس به آرشیو صاحبشان منتقل می‌شوند.") }
    val myUserId = remember(accessToken) { storyUserId(accessToken) }

    fun refresh() {
        scope.launch {
            loading = true
            val loaded = withContext(Dispatchers.IO) { fetchStories(accessToken, myUserId) }
            stories = loaded
            loading = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            message = "در حال بارگذاری استوری..."
            scope.launch {
                val result = withContext(Dispatchers.IO) { uploadStory(context, uri, accessToken, role, caption) }
                message = result
                if (result.startsWith("استوری منتشر شد")) {
                    caption = ""
                    refresh()
                }
                busy = false
            }
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("استوری‌ها", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBack) { Text("برگشت") }
        }
        Text("همه حساب‌های فعال می‌توانند استوری منتشر کنند و برای استوری‌ها نظر بگذارند.")
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it.take(500) },
            label = { Text("متن استوری (اختیاری)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        Button(enabled = !busy && accessToken.isNotBlank(), onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "در حال انتشار..." else "انتشار عکس استوری")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !loading, onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("بروزرسانی استوری‌ها") }
        if (loading) CircularProgressIndicator()
        if (stories.isEmpty() && !loading) Text("هنوز استوری فعالی وجود ندارد؛ استوری‌های قدیمی شما در آرشیو همین بخش نمایش داده می‌شوند.")
        stories.forEach { story ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (story.role) { "driver" -> "استوری راننده"; "company" -> "استوری شرکت"; else -> "استوری مدیر" } +
                            if (story.isMine && story.expiresAt.isNotBlank()) " | آرشیو من / استوری من" else "",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (story.imageUrl.isNotBlank()) {
                        AsyncImage(model = story.imageUrl, contentDescription = "تصویر استوری", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    } else {
                        Text("تصویر استوری در دسترس نیست.")
                    }
                    if (story.caption.isNotBlank()) Text(story.caption)
                    if (story.isMine) Text("این استوری در آرشیو شما باقی می‌ماند.", style = MaterialTheme.typography.bodySmall)
                    story.comments.forEach { comment ->
                        Text("نظر: ${comment.body}", style = MaterialTheme.typography.bodySmall)
                    }
                    var draft by remember(story.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(1000) },
                        label = { Text("نظر شما") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                    Button(
                        enabled = !busy && draft.isNotBlank() && runCatching { java.time.Instant.parse(story.expiresAt).isAfter(java.time.Instant.now()) }.getOrDefault(false),
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { addStoryComment(accessToken, story.id, myUserId, draft) }
                                message = result
                                if (result == "نظر ثبت شد.") refresh()
                                busy = false
                                if (result == "نظر ثبت شد.") draft = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ثبت نظر") }
                    if (isSuperAdmin) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { deleteStory(accessToken, story.id, story.mediaPath) }
                                    refresh()
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("حذف استوری نامناسب") }
                    }
                }
            }
        }
    }
}

private fun storyUserId(token: String): String {
    return try {
        val part = token.split(".").getOrNull(1) ?: return ""
        val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
    } catch (_: Exception) { "" }
}

private fun fetchStories(token: String, userId: String): List<StoryCardData> {
    if (token.isBlank()) return emptyList()
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("$STORY_SUPABASE_URL/rest/v1/user_stories?select=id,user_id,user_role,caption,media_path,expires_at&order=created_at.desc&limit=100").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 25000
            setRequestProperty("apikey", STORY_SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        if (code !in 200..299) return emptyList()
        val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val path = row.optString("media_path")
            val signed = signedStoryUrl(token, path)
            StoryCardData(
                row.optString("id"), row.optString("user_id"), row.optString("user_role"),
                row.optString("caption"), path, signed, row.optString("expires_at"),
                row.optString("user_id") == userId,
                fetchStoryComments(token, row.optString("id"))
            )
        }
    } catch (_: Exception) { emptyList() }
    finally { connection?.disconnect() }
}

private fun signedStoryUrl(token: String, path: String): String {
    if (path.isBlank()) return ""
    var c: HttpURLConnection? = null
    return try {
        val safePath = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        c = (URL("$STORY_SUPABASE_URL/storage/v1/object/sign/user-stories/$safePath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 15000; doOutput = true
            setRequestProperty("apikey", STORY_SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("expiresIn", 3600).toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode !in 200..299) return ""
        val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("signedURL")
        when {
            result.startsWith("http") -> result
            result.startsWith("/storage/v1/") -> STORY_SUPABASE_URL + result
            result.startsWith("/") -> STORY_SUPABASE_URL + "/storage/v1" + result
            result.isNotBlank() -> "$STORY_SUPABASE_URL/storage/v1/$result"
            else -> ""
        }
    } catch (_: Exception) { "" }
    finally { c?.disconnect() }
}

private fun fetchStoryComments(token: String, storyId: String): List<StoryCommentData> {
    var c: HttpURLConnection? = null
    return try {
        val q = URLEncoder.encode(storyId, "UTF-8")
        c = (URL("$STORY_SUPABASE_URL/rest/v1/user_story_comments?select=id,user_id,body&story_id=eq.$q&order=created_at.asc&limit=100").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode !in 200..299) return emptyList()
        val rows = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            StoryCommentData(row.optString("id"), row.optString("body"), row.optString("user_id"))
        }
    } catch (_: Exception) { emptyList() }
    finally { c?.disconnect() }
}

private fun uploadStory(context: Context, uri: Uri, token: String, role: String, caption: String): String {
    val userId = storyUserId(token)
    if (userId.isBlank()) return "شناسه حساب دریافت نشد."
    var upload: HttpURLConnection? = null
    var insert: HttpURLConnection? = null
    return try {
        val mime = context.contentResolver.getType(uri) ?: return "نوع تصویر قابل تشخیص نیست."
        if (mime !in listOf("image/jpeg", "image/png", "image/webp")) return "فقط JPG، PNG و WEBP مجاز است."
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن تصویر انجام نشد."
        if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return "حجم تصویر باید کمتر از ۱۰ مگابایت باشد."
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val path = "$userId/${UUID.randomUUID()}.$ext"
        upload = (URL("$STORY_SUPABASE_URL/storage/v1/object/user-stories/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", mime); setRequestProperty("x-upsert", "false")
        }
        upload.outputStream.use { it.write(bytes) }
        if (upload.responseCode !in 200..299) return "بارگذاری تصویر استوری مجاز نشد."
        val userRole = when (role.lowercase()) { "driver" -> "driver"; "company" -> "company"; else -> "admin" }
        val row = JSONObject().put("user_id", userId).put("user_role", userRole)
            .put("media_path", path).put("caption", caption.trim())
        insert = (URL("$STORY_SUPABASE_URL/rest/v1/user_stories").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        insert.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
        if (insert.responseCode in 200..299) "استوری منتشر شد؛ پس از ۲۴ ساعت از نمایش عمومی خارج می‌شود."
        else {
            deleteStoryObject(token, path)
            "تصویر بارگذاری شد اما ثبت استوری مجاز نشد."
        }
    } catch (_: Exception) { "ارتباط با فضای استوری برقرار نشد." }
    finally { upload?.disconnect(); insert?.disconnect() }
}

private fun addStoryComment(token: String, storyId: String, userId: String, body: String): String {
    if (userId.isBlank() || body.isBlank()) return "متن نظر را وارد کنید."
    var c: HttpURLConnection? = null
    return try {
        c = (URL("$STORY_SUPABASE_URL/rest/v1/user_story_comments").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 15000; doOutput = true
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        val row = JSONObject().put("story_id", storyId).put("user_id", userId).put("body", body.trim())
        c.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode in 200..299) "نظر ثبت شد." else "ثبت نظر مجاز نشد؛ ممکن است استوری منقضی شده باشد."
    } catch (_: Exception) { "ارتباط برای ثبت نظر برقرار نشد." }
    finally { c?.disconnect() }
}

private fun deleteStory(token: String, storyId: String, path: String): String {
    var c: HttpURLConnection? = null
    return try {
        val q = URLEncoder.encode(storyId, "UTF-8")
        c = (URL("$STORY_SUPABASE_URL/rest/v1/user_stories?id=eq.$q").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"; connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode !in 200..299) return "حذف استوری مجاز نیست؛ فقط مدیر ارشد اصلی اجازه دارد."
        deleteStoryObject(token, path)
        "استوری حذف شد."
    } catch (_: Exception) { "ارتباط برای حذف استوری برقرار نشد." }
    finally { c?.disconnect() }
}

private fun deleteStoryObject(token: String, path: String) {
    var c: HttpURLConnection? = null
    try {
        val safe = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        c = (URL("$STORY_SUPABASE_URL/storage/v1/object/user-stories/$safe").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("apikey", STORY_SUPABASE_KEY); setRequestProperty("Authorization", "Bearer $token")
        }
        c.responseCode
    } catch (_: Exception) { }
    finally { c?.disconnect() }
}
