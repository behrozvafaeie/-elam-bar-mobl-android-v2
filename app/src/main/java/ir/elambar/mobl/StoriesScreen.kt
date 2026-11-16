package ir.elambar.mobl

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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

private const val STORY_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val STORY_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class UserStory(val id: String, val ownerId: String, val caption: String, val imageUrl: String, val createdAt: String)

@Composable
internal fun StoriesScreen(accessToken: String, userRole: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by remember { mutableStateOf("") }
    var stories by remember { mutableStateOf<List<UserStory>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("استوری‌ها پس از بارگذاری نمایش داده می‌شوند.") }

    fun refresh() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchActiveStories(accessToken) }
            stories = result.second
            status = result.first
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            loading = true
            status = "در حال بارگذاری استوری..."
            scope.launch {
                val result = withContext(Dispatchers.IO) { uploadUserStory(context, uri, accessToken, userRole, caption.trim()) }
                status = result
                loading = false
                if (result.startsWith("استوری شما منتشر شد")) { caption = ""; refresh() }
            }
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("استوری‌های سامانه") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("استوری تصویری پس از انتشار تا ۲۴ ساعت قابل مشاهده است.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = caption, onValueChange = { caption = it.take(180) }, label = { Text("متن کوتاه استوری (اختیاری)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(onClick = { picker.launch("image/*") }, enabled = !loading && accessToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("انتخاب عکس و انتشار استوری") }
                OutlinedButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("بروزرسانی استوری‌ها") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (stories.isEmpty()) {
                    Text("هنوز استوری فعالی ثبت نشده است.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    stories.forEach { story ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(model = story.imageUrl, contentDescription = "استوری", modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp))
                                if (story.caption.isNotBlank()) Text(story.caption)
                                Text("زمان انتشار: ${story.createdAt}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("برگشت") } }
    )
}

private fun storyUserId(token: String): String {
    return try {
        val part = token.split(".").getOrNull(1) ?: ""
        val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
    } catch (_: Exception) { "" }
}

private fun storyHeaders(connection: HttpURLConnection, token: String) {
    connection.setRequestProperty("apikey", STORY_KEY)
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Accept", "application/json")
}

private fun fetchActiveStories(token: String): Pair<String, List<UserStory>> {
    if (token.isBlank()) return "نشست ورود معتبر نیست." to emptyList()
    var connection: HttpURLConnection? = null
    return try {
        val url = "$STORY_URL/rest/v1/user_stories?select=id,user_id,caption,media_path,created_at&order=created_at.desc&limit=60"
        connection = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000; storyHeaders(this, token) }
        val code = connection.responseCode
        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت استوری‌ها ممکن نشد (کد $code)." to emptyList()
        val rows = JSONArray(raw)
        val items = mutableListOf<UserStory>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val path = row.optString("media_path")
            val signed = createStorySignedUrl(token, path)
            if (signed.isNotBlank()) items.add(UserStory(row.optString("id"), row.optString("user_id"), row.optString("caption"), signed, row.optString("created_at")))
        }
        (if (items.isEmpty()) "استوری فعالی برای نمایش وجود ندارد." else "${items.size} استوری فعال") to items
    } catch (_: Exception) { "ارتباط با استوری‌ها برقرار نشد." to emptyList() }
    finally { connection?.disconnect() }
}

private fun createStorySignedUrl(token: String, path: String): String {
    if (token.isBlank() || path.isBlank()) return ""
    var connection: HttpURLConnection? = null
    return try {
        val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        connection = (URL("$STORY_URL/storage/v1/object/sign/user-stories/$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 15000; doOutput = true
            storyHeaders(this, token); setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(JSONObject().put("expiresIn", 3600).toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return ""
        val signed = JSONObject(raw).optString("signedURL")
        if (signed.isBlank()) "" else if (signed.startsWith("http")) signed else STORY_URL + signed
    } catch (_: Exception) { "" }
    finally { connection?.disconnect() }
}

private fun uploadUserStory(context: Context, uri: Uri, token: String, role: String, caption: String): String {
    val userId = storyUserId(token)
    if (userId.isBlank() || token.isBlank()) return "نشست ورود معتبر نیست."
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> return "فعلاً فقط عکس JPG، PNG یا WEBP برای استوری پشتیبانی می‌شود." }
    val bytes = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن عکس انتخاب‌شده ممکن نشد." }
    catch (_: Exception) { return "خواندن عکس انتخاب‌شده ممکن نشد." }
    if (bytes.isEmpty() || bytes.size > 25 * 1024 * 1024) return "حجم عکس باید کمتر از ۲۵ مگابایت باشد."
    val normalizedRole = when (role) { "driver" -> "driver"; "company" -> "company"; else -> "admin" }
    val path = "$userId/${UUID.randomUUID()}.$extension"
    var upload: HttpURLConnection? = null
    var insert: HttpURLConnection? = null
    return try {
        upload = (URL("$STORY_URL/storage/v1/object/user-stories/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            storyHeaders(this, token); setRequestProperty("Content-Type", mime); setRequestProperty("x-upsert", "false")
        }
        upload.outputStream.use { it.write(bytes) }
        val uploadCode = upload.responseCode
        if (uploadCode !in 200..299) return "بارگذاری عکس استوری انجام نشد (کد $uploadCode)."
        insert = (URL("$STORY_URL/rest/v1/user_stories").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            storyHeaders(this, token); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        val row = JSONObject().put("user_id", userId).put("user_role", normalizedRole).put("media_path", path).put("caption", caption)
        insert.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
        val insertCode = insert.responseCode
        if (insertCode !in 200..299) { deleteUploadedStoryObject(token, path); return "عکس بارگذاری شد اما ثبت استوری مجاز نبود (کد $insertCode)." }
        "استوری شما منتشر شد و تا ۲۴ ساعت نمایش داده می‌شود."
    } catch (_: Exception) { "ارتباط برای انتشار استوری برقرار نشد." }
    finally { upload?.disconnect(); insert?.disconnect() }
}

private fun deleteUploadedStoryObject(token: String, path: String) {
    var connection: HttpURLConnection? = null
    try {
        val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        connection = (URL("$STORY_URL/storage/v1/object/user-stories/$encoded").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; connectTimeout = 10000; readTimeout = 10000; storyHeaders(this, token) }
        connection.responseCode
    } catch (_: Exception) { }
    finally { connection?.disconnect() }
}
