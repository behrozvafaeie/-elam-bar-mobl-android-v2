package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val NOTIFICATION_SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val NOTIFICATION_PUBLISHABLE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class AppNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val createdAt: String,
    val readAt: String?
)

private class NotificationInboxRepository(private val token: String) {
    fun list(): Pair<String?, List<AppNotification>> {
        val (code, body) = request(
            "GET",
            "notifications?select=id,notification_type,title,body,created_at,read_at&order=created_at.desc&limit=100"
        )
        if (code !in 200..299) return "دریافت اعلان‌ها ناموفق بود؛ اتصال و مجوز حساب را بررسی کنید." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AppNotification(
                    row.optString("id"),
                    row.optString("notification_type"),
                    row.optString("title"),
                    row.optString("body"),
                    row.optString("created_at"),
                    row.optString("read_at").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }.getOrElse { "ساختار پاسخ اعلان‌ها معتبر نیست." to emptyList() }
    }

    fun markRead(id: String): Boolean {
        val payload = JSONObject().put("p_notification_id", id)
        val (code, _) = request("POST", "rpc/mark_notification_read", payload)
        return code in 200..299
    }

    private fun request(method: String, path: String, payload: JSONObject? = null): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$NOTIFICATION_SUPABASE_URL/rest/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", NOTIFICATION_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (payload != null) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: Exception) {
            -1 to (error.message ?: "network_error")
        } finally {
            connection?.disconnect()
        }
    }
}

@Composable
internal fun NotificationInboxScreen(accessToken: String, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (accessToken.isBlank() || loading) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { NotificationInboxRepository(accessToken).list() }
            items = result.second
            message = result.first.orEmpty()
            loading = false
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("اعلان‌های من", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("بارهای جدید متناسب با نوع خودرو و مقصدهای ثبت‌شده در این بخش نمایش داده می‌شوند.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = { refresh() }) { Text(if (loading) "در حال دریافت..." else "بروزرسانی") }
            OutlinedButton(onClick = onBack) { Text("برگشت") }
        }
        if (loading && items.isEmpty()) CircularProgressIndicator()
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        if (!loading && items.isEmpty() && message.isBlank()) Text("هنوز اعلانی دریافت نکرده‌اید.")
        items.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.body)
                    if (item.type == "matching_load_available") {
                        Text("شناسه بار: " + runCatching { JSONObject(itemDataPlaceholder(item.id)).optString("load_id") }.getOrDefault(item.id),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text(if (item.readAt == null) "خوانده‌نشده" else "خوانده‌شده", style = MaterialTheme.typography.labelSmall,
                        color = if (item.readAt == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.readAt == null) {
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { NotificationInboxRepository(accessToken).markRead(item.id) }
                                if (ok) {
                                    items = items.map { if (it.id == item.id) it.copy(readAt = "read") else it }
                                } else message = "ثبت وضعیت خوانده‌شدن اعلان ناموفق بود."
                            }
                        }) { Text("علامت‌گذاری به‌عنوان خوانده‌شده") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun itemDataPlaceholder(id: String): String = JSONObject().put("load_id", id).toString()
