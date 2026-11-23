package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

private data class AdminDashboardStats(
    val companiesTotal: Long,
    val companiesPending: Long,
    val driversTotal: Long,
    val driversPending: Long,
    val documentsPending: Long,
    val loadsPublished: Long,
    val loadsInTransit: Long,
    val loadsDelivered: Long
)

@Composable
internal fun AdminDashboard(
    accessToken: String,
    onOpenPanel: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<AdminDashboardStats?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("برای دریافت آمار، دکمه بروزرسانی را بزنید.") }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchAdminDashboardStats(accessToken) }
            stats = result.first
            message = result.second
            loading = false
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("داشبورد مدیر ارشد", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text("آمار زنده سامانه • زمان محاسبه: تهران", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { refresh() }, enabled = !loading) {
                    Text("بروزرسانی")
                }
            }

            if (loading) {
                CircularProgressIndicator()
            } else {
                val value = stats
                if (value != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("کل شرکت‌ها", value.companiesTotal, Modifier.weight(1f))
                        DashboardMetric("شرکت‌های در انتظار", value.companiesPending, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("کل رانندگان", value.driversTotal, Modifier.weight(1f))
                        DashboardMetric("رانندگان در انتظار", value.driversPending, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("مدارک در انتظار", value.documentsPending, Modifier.weight(1f))
                        DashboardMetric("بارهای منتشرشده", value.loadsPublished, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMetric("بارهای در حال حمل", value.loadsInTransit, Modifier.weight(1f))
                        DashboardMetric("بارهای تحویل‌شده", value.loadsDelivered, Modifier.weight(1f))
                    }
                    Text("دسترسی سریع", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenPanel("مدیریت رانندگان") }, modifier = Modifier.weight(1f)) { Text("رانندگان") }
                        OutlinedButton(onClick = { onOpenPanel("مدیریت شرکت‌ها") }, modifier = Modifier.weight(1f)) { Text("شرکت‌ها") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenPanel("مدیریت مدارک") }, modifier = Modifier.weight(1f)) { Text("مدارک") }
                        OutlinedButton(onClick = { onOpenPanel("مدیریت بارها") }, modifier = Modifier.weight(1f)) { Text("بارها") }
                    }
                }
            }
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: Long, modifier: Modifier = Modifier) {
    val formatter = remember { NumberFormat.getIntegerInstance(Locale("fa", "IR")) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatter.format(value), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun fetchAdminDashboardStats(accessToken: String): Pair<AdminDashboardStats?, String> {
    if (accessToken.isBlank()) return null to "نشست مدیر ارشد معتبر نیست؛ دوباره وارد شوید."
    val (code, body) = adminRpc(accessToken, "admin_get_dashboard_stats", JSONObject())
    if (code !in 200..299) return null to "دریافت آمار انجام نشد؛ سطح دسترسی یا اتصال سامانه را بررسی کنید."
    return runCatching {
        val rows = JSONArray(body)
        if (rows.length() == 0) return null to "هنوز آمار داشبورد از سرور دریافت نشده است."
        val row = rows.getJSONObject(0)
        AdminDashboardStats(
            companiesTotal = row.optLong("companies_total"),
            companiesPending = row.optLong("companies_pending"),
            driversTotal = row.optLong("drivers_total"),
            driversPending = row.optLong("drivers_pending"),
            documentsPending = row.optLong("documents_pending"),
            loadsPublished = row.optLong("loads_published"),
            loadsInTransit = row.optLong("loads_in_transit"),
            loadsDelivered = row.optLong("loads_delivered")
        ) to "آمار با موفقیت از سرور دریافت شد."
    }.getOrElse {
        null to "پاسخ آماری سرور قابل خواندن نیست."
    }
}
