package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

private const val CHAT_SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val CHAT_PUBLISHABLE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class ChatRoomMessage(
    val id: String,
    val sender: String,
    val body: String,
    val createdAt: String
)

private data class ChatRoomSnapshot(
    val roomId: String,
    val title: String,
    val messages: List<ChatRoomMessage>,
    val error: String = ""
)

@Composable
internal fun ChatPanel(accessToken: String, modifier: Modifier = Modifier) {
    var snapshot by remember(accessToken) { mutableStateOf(ChatRoomSnapshot("", "گفت‌وگوی عمومی شرکت‌ها", emptyList())) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (accessToken.isBlank() || loading) return
        loading = true
        scope.launch {
            snapshot = withContext(Dispatchers.IO) { loadPublicChat(accessToken) }
            loading = false
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(snapshot.title, style = MaterialTheme.typography.titleMedium)
        Text("پیام‌رسان متنی امن؛ دسترسی ارسال پیام را سرور بر اساس نقش و مجوز حساب کنترل می‌کند.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !loading, onClick = { refresh() }) {
                Text(if (loading) "در حال دریافت..." else "بروزرسانی پیام‌ها")
            }
        }
        if (loading && snapshot.messages.isEmpty()) CircularProgressIndicator()
        if (snapshot.error.isNotBlank()) Text(snapshot.error, color = MaterialTheme.colorScheme.error)
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 340.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            snapshot.messages.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("فرستنده: ${item.sender.take(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!loading && snapshot.messages.isEmpty() && snapshot.error.isBlank()) {
                Text("هنوز پیامی در اتاق عمومی ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(2000) },
            label = { Text("پیام شما") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5
        )
        Button(
            enabled = !sending && draft.trim().isNotEmpty() && snapshot.roomId.isNotBlank(),
            onClick = {
                sending = true
                val message = draft.trim()
                scope.launch {
                    val result = withContext(Dispatchers.IO) { sendPublicChatMessage(accessToken, snapshot.roomId, message) }
                    sending = false
                    if (result.isBlank()) {
                        draft = ""
                        refresh()
                    } else {
                        snapshot = snapshot.copy(error = result)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (sending) "در حال ارسال..." else "ارسال پیام") }
    }
}

private fun loadPublicChat(accessToken: String): ChatRoomSnapshot {
    if (accessToken.isBlank()) return ChatRoomSnapshot("", "گفت‌وگوی عمومی شرکت‌ها", emptyList(), "نشست ورود معتبر نیست.")
    var roomConnection: HttpURLConnection? = null
    var messageConnection: HttpURLConnection? = null
    return try {
        roomConnection = (URL("$CHAT_SUPABASE_URL/rest/v1/chat_rooms?select=id,title&room_type=eq.public&order=created_at.asc&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", CHAT_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val roomCode = roomConnection.responseCode
        val roomStream = if (roomCode in 200..299) roomConnection.inputStream else roomConnection.errorStream
        val rooms = JSONArray(roomStream?.bufferedReader()?.use { it.readText() }.orEmpty())
        if (roomCode !in 200..299 || rooms.length() == 0) {
            return ChatRoomSnapshot("", "گفت‌وگوی عمومی شرکت‌ها", emptyList(), "اتاق عمومی برای این حساب قابل مشاهده نیست یا دسترسی چت فعال نشده است.")
        }
        val room = rooms.getJSONObject(0)
        val roomId = room.optString("id")
        val title = room.optString("title").ifBlank { "گفت‌وگوی عمومی شرکت‌ها" }
        messageConnection = (URL("$CHAT_SUPABASE_URL/rest/v1/chat_room_messages?select=id,sender_user_id,message_type,body,created_at,deleted_at&room_id=eq.$roomId&message_type=eq.text&deleted_at=is.null&order=created_at.asc&limit=100").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", CHAT_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val messageCode = messageConnection.responseCode
        val messageStream = if (messageCode in 200..299) messageConnection.inputStream else messageConnection.errorStream
        val raw = messageStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (messageCode !in 200..299) return ChatRoomSnapshot(roomId, title, emptyList(), "دریافت پیام‌ها مجاز نیست یا اتصال برقرار نشد (کد $messageCode).")
        val rows = JSONArray(raw)
        val messages = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            ChatRoomMessage(row.optString("id"), row.optString("sender_user_id"), row.optString("body"), row.optString("created_at"))
        }
        ChatRoomSnapshot(roomId, title, messages)
    } catch (_: Exception) {
        ChatRoomSnapshot("", "گفت‌وگوی عمومی شرکت‌ها", emptyList(), "ارتباط با پیام‌رسان برقرار نشد. دوباره تلاش کنید.")
    } finally {
        roomConnection?.disconnect()
        messageConnection?.disconnect()
    }
}

private fun sendPublicChatMessage(accessToken: String, roomId: String, body: String): String {
    if (accessToken.isBlank() || roomId.isBlank() || body.isBlank() || body.length > 2000) return "متن پیام یا نشست ورود معتبر نیست."
    var connection: HttpURLConnection? = null
    return try {
        val tokenPart = accessToken.split(".").getOrNull(1) ?: return "نشست ورود معتبر نیست."
        val normalized = tokenPart.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val claims = JSONObject(String(Base64.getDecoder().decode(normalized), Charsets.UTF_8))
        val senderId = claims.optString("sub")
        if (senderId.isBlank()) return "شناسه فرستنده از نشست دریافت نشد."
        connection = (URL("$CHAT_SUPABASE_URL/rest/v1/chat_room_messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("apikey", CHAT_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "return=minimal")
        }
        val payload = JSONObject().put("room_id", roomId).put("sender_user_id", senderId).put("message_type", "text").put("body", body)
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code in 200..299) "" else "ارسال پیام انجام نشد (کد $code). ممکن است حساب شما فقط‌خواندنی یا بی‌مجوز باشد."
    } catch (_: Exception) {
        "ارتباط با پیام‌رسان برقرار نشد."
    } finally {
        connection?.disconnect()
    }
}
