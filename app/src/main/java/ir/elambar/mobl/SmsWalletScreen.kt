package ir.elambar.mobl

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val SMS_WALLET_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val SMS_WALLET_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class SmsLedgerRow(
    val type: String,
    val direction: String,
    val amount: Long,
    val balance: Long,
    val createdAt: String
)
private data class SmsTopupRow(
    val amount: Long,
    val status: String,
    val tracking: String,
    val createdAt: String
)

private fun smsWalletRpc(token: String, function: String, payload: JSONObject): String {
    val connection = (URL("$SMS_WALLET_URL/rest/v1/rpc/$function").openConnection() as HttpURLConnection)
    return try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("apikey", SMS_WALLET_KEY)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val result = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) throw IllegalStateException(
            runCatching { JSONObject(result).optString("message") }.getOrDefault("ارتباط با کیف پول انجام نشد.")
        )
        result
    } finally {
        connection.disconnect()
    }
}

private fun uploadSmsTopupReceipt(context: Context, token: String, userId: String, uri: Uri): String {
    val mime = context.contentResolver.getType(uri) ?: throw IllegalArgumentException("نوع فایل رسید مشخص نیست.")
    val ext = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> throw IllegalArgumentException("فقط تصویر JPG، PNG یا WEBP مجاز است.")
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("خواندن تصویر رسید ممکن نشد.")
    if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) throw IllegalArgumentException("حجم تصویر باید کمتر از ۵ مگابایت باشد.")
    val objectPath = "$userId/${UUID.randomUUID()}.$ext"
    val encodedPath = objectPath.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    val connection = URL("$SMS_WALLET_URL/storage/v1/object/sms-topup-receipts/$encodedPath").openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("apikey", SMS_WALLET_KEY)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", mime)
        connection.setRequestProperty("x-upsert", "false")
        connection.outputStream.use { it.write(bytes) }
        val response = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = response?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("بارگذاری رسید ناموفق بود.")
            throw IllegalStateException(message)
        }
        objectPath
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun SmsWalletScreen(accessToken: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var balance by remember { mutableLongStateOf(0L) }
    var ledger by remember { mutableStateOf<List<SmsLedgerRow>>(emptyList()) }
    var topups by remember { mutableStateOf<List<SmsTopupRow>>(emptyList()) }
    var amount by remember { mutableStateOf("100000") }
    var tracking by remember { mutableStateOf("") }
    var receiptPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            message = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    val wallet = JSONArray(smsWalletRpc(accessToken, "sms_get_my_wallet", JSONObject()))
                    val ledgerRows = JSONArray(smsWalletRpc(accessToken, "sms_list_my_wallet_ledger", JSONObject().put("p_limit", 30).put("p_before", JSONObject.NULL)))
                    val topupRows = JSONArray(smsWalletRpc(accessToken, "sms_list_my_topup_requests", JSONObject()))
                    Triple(
                        wallet.optJSONObject(0)?.optLong("balance_toman") ?: 0L,
                        (0 until ledgerRows.length()).mapNotNull { i ->
                            ledgerRows.optJSONObject(i)?.let { row ->
                                SmsLedgerRow(row.optString("entry_type"), row.optString("direction"), row.optLong("amount_toman"), row.optLong("balance_after_toman"), row.optString("created_at"))
                            }
                        },
                        (0 until topupRows.length()).mapNotNull { i ->
                            topupRows.optJSONObject(i)?.let { row ->
                                SmsTopupRow(row.optLong("amount_toman"), row.optString("status"), row.optString("bank_tracking_number"), row.optString("created_at"))
                            }
                        }
                    )
                }
                balance = result.first
                ledger = result.second
                topups = result.third
            } catch (e: Exception) {
                message = e.message ?: "دریافت اطلاعات کیف پول ناموفق بود."
            } finally {
                loading = false
            }
        }
    }

    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            message = "در حال بارگذاری امن رسید..."
            scope.launch {
                try {
                    val userId = accessToken.split(".").getOrNull(1)?.let { part ->
                        val normalized = part.replace('-', '+').replace('_', '/')
                        val padded = normalized.padEnd(((normalized.length + 3) / 4) * 4, '=')
                        JSONObject(String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)).optString("sub")
                    }.orEmpty()
                    if (userId.isBlank()) throw IllegalStateException("شناسه حساب از نشست معتبر دریافت نشد.")
                    receiptPath = withContext(Dispatchers.IO) { uploadSmsTopupReceipt(context, accessToken, userId, uri) }
                    message = "رسید با موفقیت و به‌صورت خصوصی بارگذاری شد."
                } catch (e: Exception) {
                    message = e.message ?: "بارگذاری رسید انجام نشد."
                    receiptPath = ""
                } finally { busy = false }
            }
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("کیف پول پیامک", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBack) { Text("برگشت") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("موجودی قابل استفاده", style = MaterialTheme.typography.titleMedium)
                Text("%,d تومان".format(balance), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("هزینه پیامک از کیف پول فرستنده کسر می‌شود؛ در صورت نبود موجودی، ارسال متوقف خواهد شد.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("درخواست شارژ کیف پول", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = amount, onValueChange = { amount = it.filter(Char::isDigit).take(10) },
            label = { Text("مبلغ به تومان (۱۰۰ هزار تا ۵ میلیون)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tracking, onValueChange = { tracking = it.take(100) },
            label = { Text("شماره پیگیری واریز بانکی") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(enabled = !busy, onClick = { receiptPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (receiptPath.isBlank()) "انتخاب تصویر رسید پرداخت" else "رسید بارگذاری شد؛ تغییر تصویر")
        }
        if (receiptPath.isNotBlank()) Text("رسید خصوصی آماده ارسال است.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Button(
            enabled = !busy && !loading && amount.toLongOrNull() in 100000L..5000000L && tracking.trim().length >= 4 && receiptPath.isNotBlank(),
            onClick = {
                busy = true
                message = "در حال ثبت درخواست شارژ..."
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            smsWalletRpc(accessToken, "sms_create_manual_topup_request", JSONObject()
                                .put("p_amount_toman", amount.toLong())
                                .put("p_bank_tracking_number", tracking.trim())
                                .put("p_receipt_storage_path", receiptPath))
                        }
                        message = "درخواست شارژ ثبت شد و پس از بررسی مدیر، نتیجه در همین صفحه نمایش داده می‌شود."
                        tracking = ""
                        receiptPath = ""
                        refresh()
                    } catch (e: Exception) {
                        message = e.message ?: "ثبت درخواست شارژ انجام نشد."
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "لطفاً صبر کنید..." else "ثبت درخواست شارژ") }

        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("تراکنش‌های اخیر", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !loading, onClick = { refresh() }) { Text("بروزرسانی") }
        }
        if (loading) CircularProgressIndicator()
        if (!loading && ledger.isEmpty()) Text("هنوز تراکنشی ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
        ledger.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(when (row.type) { "topup" -> "شارژ کیف پول"; "sms_debit" -> "هزینه پیامک"; "sms_refund" -> "بازپرداخت پیامک"; else -> "تراکنش کیف پول" }, style = MaterialTheme.typography.titleSmall)
                    Text((if (row.direction == "credit") "+" else "−") + "%,d تومان".format(row.amount))
                    Text("مانده پس از تراکنش: %,d تومان".format(row.balance), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("درخواست‌های شارژ", style = MaterialTheme.typography.titleMedium)
        if (topups.isEmpty()) Text("درخواستی ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
        topups.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("%,d تومان".format(row.amount), style = MaterialTheme.typography.titleSmall)
                    Text("وضعیت: " + when (row.status) { "pending" -> "در انتظار بررسی"; "approved" -> "تأیید شده"; "rejected" -> "رد شده"; else -> row.status })
                    if (row.tracking.isNotBlank()) Text("پیگیری: " + row.tracking, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("شارژ پس از تأیید مدیر ثبت می‌شود. تصویر رسید فقط در فضای خصوصی حساب شما نگهداری می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
