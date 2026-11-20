package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val ADMIN_LOADS_API = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val ADMIN_LOADS_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class AdminLoadItem(
    val id: String,
    val origin: String,
    val destination: String,
    val cargoType: String,
    val vehicleType: String,
    val loadingType: String,
    val freightAmount: Long,
    val description: String,
    val status: String,
    val assignedDriverId: String?,
    val publisherType: String
)

private data class AdminLoadDraft(
    val destination: String,
    val cargoType: String,
    val vehicleType: String,
    val loadingType: String,
    val freightAmount: String,
    val description: String
)

private class AdminLoadRepository(private val token: String) {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$ADMIN_LOADS_API/rest/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", ADMIN_LOADS_KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (method == "PATCH" || method == "DELETE") setRequestProperty("Prefer", "return=representation")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            -1 to (e.message ?: "network_error")
        } finally {
            connection?.disconnect()
        }
    }

    fun list(): Pair<String?, List<AdminLoadItem>> {
        if (token.isBlank()) return "نشست مدیر معتبر نیست؛ دوباره وارد شوید." to emptyList()
        val query = "loads?select=id,origin,destination,cargo_type,vehicle_type,loading_type,freight_amount,description,status,assigned_driver_id,publisher_type&order=created_at.desc&limit=200"
        val (code, body) = request("GET", query)
        if (code !in 200..299) return "دریافت فهرست بارها ناموفق بود (کد $code). مجوز مشاهده بارها را بررسی کنید." to emptyList()
        return runCatching {
            val rows = JSONArray(body)
            null to (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AdminLoadItem(
                    id = row.optString("id"),
                    origin = row.optString("origin", "تهران"),
                    destination = row.optString("destination"),
                    cargoType = row.optString("cargo_type"),
                    vehicleType = row.optString("vehicle_type"),
                    loadingType = row.optString("loading_type"),
                    freightAmount = row.optLong("freight_amount"),
                    description = row.optString("description").takeUnless { it == "null" }.orEmpty(),
                    status = row.optString("status"),
                    assignedDriverId = row.optString("assigned_driver_id").takeIf { it.isNotBlank() && it != "null" },
                    publisherType = row.optString("publisher_type")
                )
            }
        }.getOrElse { "پاسخ فهرست بارها قابل خواندن نیست." to emptyList() }
    }

    fun update(item: AdminLoadItem, draft: AdminLoadDraft): String {
        if (item.status != "published" || item.assignedDriverId != null) return "ویرایش جزئیات فقط پیش از انتخاب راننده مجاز است."
        if (draft.destination.trim().length !in 2..100 || draft.cargoType.trim().length !in 2..160) return "مقصد یا نوع بار معتبر نیست."
        val vehicles = setOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون ۶متری", "کامیون دربستی کار", "خاور مبلی پتو دار", "کامیون مبلی", "وانت", "کامیون بزرگ", "سایر خودروهای مبلی")
        if (draft.vehicleType !in vehicles) return "نوع خودرو معتبر نیست."
        if (draft.loadingType !in setOf("روباری", "دربست", "نیم بار")) return "نوع بارگیری معتبر نیست."
        val digits = buildString {
            draft.freightAmount.forEach { ch ->
                append(when (ch) {
                    in '۰'..'۹' -> ('0'.code + (ch - '۰')).toChar()
                    in '٠'..'٩' -> ('0'.code + (ch - '٠')).toChar()
                    else -> ch
                })
            }
        }
        val amount = if (digits.isBlank()) 0L else digits.toLongOrNull() ?: return "مبلغ کرایه معتبر نیست."
        if (amount < 0) return "مبلغ کرایه معتبر نیست."
        val body = JSONObject()
            .put("destination", draft.destination.trim())
            .put("cargo_type", draft.cargoType.trim())
            .put("vehicle_type", draft.vehicleType)
            .put("loading_type", draft.loadingType)
            .put("freight_amount", amount)
            .put("description", draft.description.trim().take(3000))
        val (code, response) = request("PATCH", "loads?id=eq." + enc(item.id) + "&status=eq.published&assigned_driver_id=is.null", body)
        if (code !in 200..299) return "ویرایش از طرف سرور رد شد (کد $code)."
        return if (runCatching { JSONArray(response).length() }.getOrDefault(0) > 0) "تغییرات بار ذخیره شد." else "بار برای ویرایش پیدا نشد یا وضعیت آن تغییر کرده است."
    }

    fun delete(item: AdminLoadItem): String {
        if (item.status != "published" || item.assignedDriverId != null) return "فقط بار آزاد و منتشرشده قابل حذف است؛ بار دارای راننده باید لغو شود."
        val (code, response) = request("DELETE", "loads?id=eq." + enc(item.id) + "&status=eq.published&assigned_driver_id=is.null")
        if (code !in 200..299) return "حذف بار از طرف سرور رد شد (کد $code)."
        return if (runCatching { JSONArray(response).length() }.getOrDefault(0) > 0) "بار حذف شد."
        else "بار پیدا نشد یا مجوز حذف وجود ندارد."
    }

    fun changeStatus(item: AdminLoadItem, nextStatus: String): String {
        val allowed = setOf("published", "driver_selected", "in_transit", "delivered", "settled", "cancelled")
        if (nextStatus !in allowed) return "وضعیت انتخاب‌شده معتبر نیست."
        if (item.status == "cancelled" && nextStatus != "cancelled") return "بار لغوشده از این پنل دوباره فعال نمی‌شود."
        if (nextStatus == "published" && item.assignedDriverId != null) return "بار دارای راننده انتخاب‌شده دوباره آزاد نمی‌شود."
        val body = JSONObject().put("status", nextStatus)
        val (code, response) = request("PATCH", "loads?id=eq." + enc(item.id), body)
        if (code !in 200..299) return "تغییر وضعیت از طرف سرور رد شد (کد $code)."
        return if (runCatching { JSONArray(response).length() }.getOrDefault(0) > 0) "وضعیت بار تغییر کرد." else "بار پیدا نشد یا مجوز تغییر وضعیت وجود ندارد."
    }
}

private fun adminLoadStatusLabel(status: String): String = when (status) {
    "published" -> "در انتظار راننده"
    "driver_selected" -> "راننده انتخاب شد"
    "in_transit" -> "بارگیری شد / در مسیر"
    "delivered" -> "تحویل شد"
    "settled" -> "تسویه شد"
    "cancelled" -> "لغوشده"
    else -> status
}

@Composable
internal fun AdminLoadManagement(accessToken: String) {
    val scope = rememberCoroutineScope()
    val repository = remember(accessToken) { AdminLoadRepository(accessToken) }
    var items by remember { mutableStateOf<List<AdminLoadItem>>(emptyList()) }
    var message by remember { mutableStateOf("در حال دریافت بارها...") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminLoadItem?>(null) }
    var draft by remember { mutableStateOf(AdminLoadDraft("", "", "", "روباری", "", "")) }
    var statusItem by remember { mutableStateOf<AdminLoadItem?>(null) }
    var deleting by remember { mutableStateOf<AdminLoadItem?>(null) }
    var selectedStatus by remember { mutableStateOf("") }
    var statusMenu by remember { mutableStateOf(false) }

    fun refresh() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.list() }
            items = result.second
            message = result.first ?: if (result.second.isEmpty()) "هنوز باری ثبت نشده است." else "بارها به‌روز هستند."
            busy = false
        }
    }
    LaunchedEffect(accessToken) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("فهرست همه بارها", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { refresh() }) { Text("بروزرسانی") }
        }
        Text("لغو بار در این بخش به‌صورت نرم‌افزاری انجام می‌شود و سابقه آن حفظ خواهد شد.", style = MaterialTheme.typography.bodySmall)
        if (busy) CircularProgressIndicator()
        items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.origin + " ← " + item.destination, style = MaterialTheme.typography.titleMedium)
                    Text(item.cargoType + " | " + item.vehicleType + " | " + item.loadingType)
                    Text(if (item.freightAmount == 0L) "کرایه: توافقی" else "کرایه: " + item.freightAmount + " تومان")
                    Text("وضعیت: " + adminLoadStatusLabel(item.status))
                    Text(if (item.assignedDriverId == null) "راننده: انتخاب نشده" else "راننده: اختصاص یافته")
                    Text(if (item.publisherType == "admin_system") "ثبت‌کننده: مدیریت سامانه" else "ثبت‌کننده: شرکت")
                    if (item.description.isNotBlank()) Text(item.description)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy && item.status == "published" && item.assignedDriverId == null, onClick = {
                            editing = item
                            draft = AdminLoadDraft(item.destination, item.cargoType, item.vehicleType, item.loadingType, if (item.freightAmount == 0L) "" else item.freightAmount.toString(), item.description)
                        }) { Text("ویرایش") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(enabled = !busy && item.status == "published" && item.assignedDriverId == null, onClick = { deleting = item }) { Text("حذف") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(enabled = !busy && item.status != "cancelled" && item.status != "settled", onClick = {
                            statusItem = item
                            selectedStatus = item.status
                        }) { Text("تغییر وضعیت / لغو") }
                    }
                }
            }
        }
        Text(message, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }

    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("ویرایش بار") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(draft.destination, { draft = draft.copy(destination = it.take(100)) }, label = { Text("مقصد") }, singleLine = true)
                    OutlinedTextField(draft.cargoType, { draft = draft.copy(cargoType = it.take(160)) }, label = { Text("نوع بار") }, singleLine = true)
                    OutlinedTextField(draft.vehicleType, { draft = draft.copy(vehicleType = it.take(100)) }, label = { Text("نوع خودرو") }, singleLine = true)
                    OutlinedTextField(draft.loadingType, { draft = draft.copy(loadingType = it.take(40)) }, label = { Text("نوع بارگیری") }, singleLine = true)
                    OutlinedTextField(draft.freightAmount, { draft = draft.copy(freightAmount = it.take(18)) }, label = { Text("کرایه تومان؛ خالی یعنی توافقی") }, singleLine = true)
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(3000)) }, label = { Text("توضیحات") }, minLines = 2)
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) { repository.update(item, draft) }
                        editing = null
                        val result = withContext(Dispatchers.IO) { repository.list() }
                        items = result.second
                        if (result.first != null) message += " " + result.first
                        busy = false
                    }
                }) { Text("ذخیره تغییرات") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("انصراف") } }
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف بار") },
            text = { Text("بار مقصد «" + item.destination + "» به‌طور کامل حذف شود؟ فقط بار منتشرشده و بدون راننده قابل حذف است. برای حفظ سابقه، بارهای دارای راننده فقط قابل لغو هستند.") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) { repository.delete(item) }
                        deleting = null
                        val result = withContext(Dispatchers.IO) { repository.list() }
                        items = result.second
                        if (result.first != null) message += " " + result.first
                        busy = false
                    }
                }) { Text("حذف قطعی بار") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("انصراف") } }
        )
    }

    statusItem?.let { item ->
        val choices = if (item.assignedDriverId == null) listOf("published", "cancelled") else listOf("driver_selected", "in_transit", "delivered", "settled", "cancelled")
        AlertDialog(
            onDismissRequest = { statusItem = null },
            title = { Text("تغییر وضعیت بار") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("مقصد: " + item.destination)
                    androidx.compose.foundation.layout.Box {
                        OutlinedTextField(
                            value = adminLoadStatusLabel(selectedStatus),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("وضعیت جدید") },
                            trailingIcon = { TextButton(onClick = { statusMenu = true }) { Text("انتخاب") } }
                        )
                        DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                            choices.forEach { option ->
                                DropdownMenuItem(text = { Text(adminLoadStatusLabel(option)) }, onClick = {
                                    selectedStatus = option
                                    statusMenu = false
                                })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy && selectedStatus != item.status, onClick = {
                    busy = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) { repository.changeStatus(item, selectedStatus) }
                        statusItem = null
                        val result = withContext(Dispatchers.IO) { repository.list() }
                        items = result.second
                        if (result.first != null) message += " " + result.first
                        busy = false
                    }
                }) { Text("ثبت وضعیت") }
            },
            dismissButton = { TextButton(onClick = { statusItem = null }) { Text("انصراف") } }
        )
    }
}
