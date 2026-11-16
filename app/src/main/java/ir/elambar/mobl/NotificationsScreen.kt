package ir.elambar.mobl

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
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
import java.util.Date

private const val NOTIFICATIONS_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val NOTIFICATIONS_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val type: String,
    val createdAt: String,
    val readAt: String?
)

@Composable
internal fun NotificationsScreen(accessToken: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var status by remember { mutableStateOf("در حال دریافت اعلان‌ها...") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (accessToken.isBlank()) {
            status = "نشست ورود معتبر نیست؛ دوباره وارد شوید."
            return
        }
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchNotifications(accessToken) }
            items = result.second
            status = result.first ?: if (items.isEmpty()) "اعلانی برای حساب شما ثبت نشده است." else ""
            loading = false
        }
    }

    LaunchedEffect(accessToken) { refresh() }
    LaunchedEffect(accessToken) {
        while (true) {
            delay(30000)
            val result = withContext(Dispatchers.IO) { fetchNotifications(accessToken) }
            if (result.first == null) items = result.second
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("اعلان‌ها", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("برگشت") }
                }
                HorizontalDivider()
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                            containerColor = if (item.readAt == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    if (item.readAt == null) Text("جدید", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(item.body, style = MaterialTheme.typography.bodyMedium)
                                Text(formatNotificationDate(item.createdAt), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("بروزرسانی اعلان‌ها") }
                Text("اعلان‌های خوانده‌نشده با رنگ متفاوت نمایش داده می‌شوند.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun fetchNotifications(accessToken: String): Pair<String?, List<AppNotification>> {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("$NOTIFICATIONS_URL/rest/v1/notifications?select=id,title,body,notification_type,created_at,read_at&order=created_at.desc&limit=100").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", NOTIFICATIONS_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = if (code == 401 || code == 403) "دسترسی اعلان‌ها مجاز نیست؛ نشست ورود را بررسی کنید." else "دریافت اعلان‌ها ناموفق بود (کد $code)."
            return message to emptyList()
        }
        val array = JSONArray(response)
        null to (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            AppNotification(
                id = row.optString("id"),
                title = row.optString("title"),
                body = row.optString("body"),
                type = row.optString("notification_type"),
                createdAt = row.optString("created_at"),
                readAt = row.optString("read_at").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    } catch (_: Exception) {
        "ارتباط با سرویس اعلان‌ها برقرار نشد؛ اینترنت را بررسی کنید." to emptyList()
    } finally {
        connection?.disconnect()
    }
}

private fun formatNotificationDate(value: String): String {
    return try {
        val instant = java.time.Instant.parse(value)
        val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm", ULocale("fa_IR@calendar=persian"))
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tehran")
        formatter.format(Date.from(instant))
    } catch (_: Exception) { value }
}
