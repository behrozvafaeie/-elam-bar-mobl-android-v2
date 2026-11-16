package ir.elambar.mobl

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val CHAT_ROOM_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val CHAT_ROOM_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class PublicRoomMessage(val id: String, val senderId: String, val body: String, val createdAt: String, val pinned: Boolean)

@Composable
internal fun ChatRoomScreen(accessToken: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val userId = remember(accessToken) {
        runCatching {
            val part = accessToken.split(".").getOrNull(1) ?: ""
            val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        }.getOrDefault("")
    }
    var roomId by remember { mutableStateOf("") }
    var roomTitle by remember { mutableStateOf("اتاق گفتگوی عمومی") }
    var messages by remember { mutableStateOf<List<PublicRoomMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("در حال اتصال به اتاق گفتگو...") }
    var busy by remember { mutableStateOf(false) }
    var canSend by remember { mutableStateOf(false) }

    fun loadMessages() {
        val id = roomId
        if (id.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchPublicRoomMessages(accessToken, id) }
            if (result.first == null) messages = result.second else status = result.first.orEmpty()
        }
    }
    fun loadRoom() {
        if (accessToken.isBlank() || userId.isBlank()) {
            status = "نشست ورود معتبر نیست؛ یک‌بار خارج و دوباره وارد شوید."
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchPublicRoom(accessToken) }
            roomId = result.roomId
            roomTitle = result.title.ifBlank { "اتاق گفتگوی عمومی" }
            canSend = if (result.roomId.isNotBlank() && result.error == null) {
                withContext(Dispatchers.IO) { canUserSendPublicRoomMessage(accessToken, userId, result.roomId) }
            } else false
            status = result.error ?: if (roomId.isBlank()) "اتاق عمومی هنوز توسط مدیر ارشد ایجاد نشده است." else ""
            busy = false
            if (roomId.isNotBlank()) loadMessages()
        }
    }
    fun sendMessage() {
        val clean = draft.trim()
        if (clean.isBlank() || roomId.isBlank() || !canSend || busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { sendPublicRoomMessage(accessToken, roomId, userId, clean) }
            status = result
            if (result == "پیام ارسال شد.") {
                draft = ""
                loadMessages()
            }
            busy = false
        }
    }

    LaunchedEffect(accessToken) { loadRoom() }
    LaunchedEffect(roomId, accessToken) {
        if (roomId.isBlank()) return@LaunchedEffect
        while (true) {
            delay(4000)
            val result = withContext(Dispatchers.IO) { fetchPublicRoomMessages(accessToken, roomId) }
            if (result.first == null) messages = result.second else status = result.first.orEmpty()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(roomTitle, style = MaterialTheme.typography.titleLarge)
                    Text("گفتگوی عمومی شرکت‌ها و مدیران", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose) { Text("برگشت") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                messages.forEach { item ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = if (item.senderId == userId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (item.pinned) Text("پیام سنجاق‌شده", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(if (item.senderId == userId) "شما" else "عضو اتاق", style = MaterialTheme.typography.labelSmall)
                            Text(item.body, style = MaterialTheme.typography.bodyLarge)
                            Text(item.createdAt, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (messages.isEmpty() && roomId.isNotBlank()) Text("هنوز پیامی در اتاق ثبت نشده است.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            if (canSend) {
                OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.fillMaxWidth(), label = { Text("پیام خود را بنویسید") }, maxLines = 4)
                Button(onClick = { sendMessage() }, enabled = !busy && draft.isNotBlank() && roomId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("ارسال پیام") }
            } else if (roomId.isNotBlank()) {
                Text("حساب شما اجازه ارسال پیام ندارد؛ در صورت نیاز از مدیر ارشد درخواست دسترسی کنید.", style = MaterialTheme.typography.bodySmall)
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { loadRoom() }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("بروزرسانی اتاق") }
        }
      }
    }
}

private data class PublicRoomResult(val roomId: String, val title: String, val canSend: Boolean, val error: String?)

private fun fetchPublicRoom(accessToken: String): PublicRoomResult {
    var c: HttpURLConnection? = null
    return try {
        c = (URL("$CHAT_ROOM_URL/rest/v1/chat_rooms?select=id,title,is_locked&room_type=eq.public&order=created_at.asc&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", CHAT_ROOM_KEY); setRequestProperty("Authorization", "Bearer $accessToken"); setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return PublicRoomResult("", "", false, "دریافت اتاق توسط مجوزهای امنیتی رد شد (کد $code).")
        val rows = JSONArray(raw)
        if (rows.length() == 0) return PublicRoomResult("", "", false, null)
        val room = rows.getJSONObject(0)
        val locked = room.optBoolean("is_locked", false)
        PublicRoomResult(room.optString("id"), room.optString("title"), !locked, if (locked) "اتاق گفتگو موقتاً توسط مدیر قفل شده است." else null)
    } catch (_: Exception) { PublicRoomResult("", "", false, "ارتباط با اتاق گفتگو برقرار نشد؛ اینترنت را بررسی کنید.") }
    finally { c?.disconnect() }
}

private fun fetchPublicRoomMessages(accessToken: String, roomId: String): Pair<String?, List<PublicRoomMessage>> {
    var c: HttpURLConnection? = null
    return try {
        val encoded = URLEncoder.encode(roomId, "UTF-8")
        c = (URL("$CHAT_ROOM_URL/rest/v1/chat_room_messages?select=id,sender_user_id,body,created_at,is_pinned&room_id=eq.$encoded&deleted_at=is.null&order=created_at.asc&limit=300").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", CHAT_ROOM_KEY); setRequestProperty("Authorization", "Bearer $accessToken"); setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "خواندن پیام‌ها مجاز نیست (کد $code). عضویت و مجوز اتاق را بررسی کنید." to emptyList()
        val rows = JSONArray(raw)
        null to (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            PublicRoomMessage(row.optString("id"), row.optString("sender_user_id"), row.optString("body"), row.optString("created_at"), row.optBoolean("is_pinned"))
        }
    } catch (_: Exception) { "دریافت پیام‌ها ناموفق بود؛ اتصال اینترنت را بررسی کنید." to emptyList() }
    finally { c?.disconnect() }
}


private fun canUserSendPublicRoomMessage(accessToken: String, userId: String, roomId: String): Boolean {
    if (accessToken.isBlank() || userId.isBlank() || roomId.isBlank()) return false
    fun get(path: String): JSONArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$CHAT_ROOM_URL/rest/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 10000; readTimeout = 12000
                setRequestProperty("apikey", CHAT_ROOM_KEY)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            if (c.responseCode !in 200..299) null else JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null }
        finally { c?.disconnect() }
    }
    val encodedUser = URLEncoder.encode(userId, "UTF-8")
    val encodedRoom = URLEncoder.encode(roomId, "UTF-8")
    val companies = get("companies?select=status&id=eq.$encodedUser&limit=1")
    if (companies != null && companies.length() > 0) {
        if (companies.getJSONObject(0).optString("status") != "approved") return false
        val settings = get("chat_room_company_settings?select=is_muted,read_only&room_id=eq.$encodedRoom&company_id=eq.$encodedUser&limit=1")
        if (settings != null && settings.length() > 0) {
            val row = settings.getJSONObject(0)
            if (row.optBoolean("is_muted") || row.optBoolean("read_only")) return false
        }
        return true
    }
    val admins = get("admin_users?select=role,is_blocked,access_expires_at&user_id=eq.$encodedUser&limit=1")
    if (admins != null && admins.length() > 0) {
        val admin = admins.getJSONObject(0)
        if (admin.optBoolean("is_blocked")) return false
        val role = admin.optString("role")
        if (role == "super_admin") return true
        val permissions = get("admin_section_permissions?select=can_create&admin_user_id=eq.$encodedUser&section_key=eq.chat&limit=1")
        return permissions != null && permissions.length() > 0 && permissions.getJSONObject(0).optBoolean("can_create")
    }
    // Drivers remain read-only in the public room unless server policy grants a different membership.
    return false
}

private fun sendPublicRoomMessage(accessToken: String, roomId: String, userId: String, body: String): String {
    var c: HttpURLConnection? = null
    return try {
        val payload = JSONObject().put("room_id", roomId).put("sender_user_id", userId).put("message_type", "text").put("body", body)
        c = (URL("$CHAT_ROOM_URL/rest/v1/chat_room_messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", CHAT_ROOM_KEY); setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code in 200..299) "پیام ارسال شد."
        else {
            val detail = runCatching {
                val json = JSONObject(c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
                json.optString("message").ifBlank { json.optString("details") }
            }.getOrDefault("")
            "ارسال پیام انجام نشد (کد $code). " + detail.ifBlank {
                if (code == 401 || code == 403) "مجوز ارسال برای حساب شما فعال نیست؛ وضعیت عضویت و مجوز اتاق را بررسی کنید."
                else "لطفاً دوباره تلاش کنید."
            }
        }
    } catch (_: Exception) { "ارتباط برای ارسال پیام برقرار نشد." }
    finally { c?.disconnect() }
}
