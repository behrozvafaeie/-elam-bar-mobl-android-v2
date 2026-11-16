package ir.elambar.mobl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CONTACTS_SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val CONTACTS_PUBLISHABLE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class ChatContact(val userId: String, val displayName: String, val accountType: String)

@Composable
internal fun ContactsScreen(
    accessToken: String,
    onClose: () -> Unit,
    onOpenPrivateChat: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ChatContact>>(emptyList()) }
    var status by remember { mutableStateOf("برای یافتن مخاطب، نام یا شماره را جست‌وجو کنید.") }
    var loading by remember { mutableStateOf(false) }

    fun search() {
        if (query.trim().length < 2) {
            status = "حداقل دو نویسه برای جست‌وجو وارد کنید."
            contacts = emptyList()
            return
        }
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { searchChatContacts(accessToken, query.trim()) }
            contacts = result.second
            status = result.first
            loading = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("مخاطبین گفتگو", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("برگشت") }
                }
                Text("فقط حساب‌های تأییدشده و مخاطبان مجاز نمایش داده می‌شوند.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("نام یا شماره تماس") },
                    singleLine = true
                )
                Button(onClick = { search() }, enabled = !loading && query.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
                    Text("جست‌وجوی مخاطب")
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    contacts.forEach { contact ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(when (contact.accountType) {
                                    "company" -> "شرکت باربری"
                                    "driver" -> "راننده"
                                    else -> "مدیر سامانه"
                                }, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    enabled = !loading,
                                    onClick = {
                                        loading = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                createPrivateChatRoom(accessToken, contact.userId, "گفتگو با " + contact.displayName)
                                            }
                                            loading = false
                                            if (result.first.isNotBlank()) onOpenPrivateChat(result.first, "گفتگو با " + contact.displayName)
                                            else status = result.second
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("شروع گفتگوی خصوصی") }
                            }
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private fun searchChatContacts(accessToken: String, query: String): Pair<String, List<ChatContact>> {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست." to emptyList()
    var c: HttpURLConnection? = null
    return try {
        c = (URL("$CONTACTS_SUPABASE_URL/rest/v1/rpc/search_chat_contacts").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", CONTACTS_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("p_query", query).toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "جست‌وجوی مخاطبان مجاز نیست (کد $code)." to emptyList()
        val rows = JSONArray(raw)
        val items = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            ChatContact(row.optString("user_id"), row.optString("display_name"), row.optString("account_type"))
        }
        (if (items.isEmpty()) "مخاطب مجازی با این مشخصات پیدا نشد." else "تعداد مخاطبان پیدا‌شده: \${items.size}") to items
    } catch (_: Exception) { "ارتباط با فهرست مخاطبین برقرار نشد." to emptyList() }
    finally { c?.disconnect() }
}

private fun createPrivateChatRoom(accessToken: String, targetUserId: String, title: String): Pair<String, String> {
    if (accessToken.isBlank() || targetUserId.isBlank()) return "" to "اطلاعات مخاطب یا نشست ورود معتبر نیست."
    var c: HttpURLConnection? = null
    return try {
        c = (URL("$CONTACTS_SUPABASE_URL/rest/v1/rpc/create_private_chat_room").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", CONTACTS_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val payload = JSONObject().put("p_target_user_id", targetUserId).put("p_title", title)
        c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            return "" to ("ایجاد گفتگوی خصوصی انجام نشد (کد $code). " + detail)
        }
        val id = raw.trim().removeSurrounding("\"")
        if (id.length < 30) "" to "شناسه گفتگوی خصوصی از سرور دریافت نشد." else id to ""
    } catch (_: Exception) { "" to "ارتباط برای ایجاد گفتگوی خصوصی برقرار نشد." }
    finally { c?.disconnect() }
}
