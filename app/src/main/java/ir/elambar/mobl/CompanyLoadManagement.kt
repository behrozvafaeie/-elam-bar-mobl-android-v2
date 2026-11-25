package ir.elambar.mobl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

private const val LOADS_API = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val LOADS_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class CompanyLoadItem(
    val id: String,
    val destination: String,
    val cargoType: String,
    val vehicleType: String,
    val loadingType: String,
    val freightAmount: Long,
    val description: String,
    val status: String,
    val assignedDriverId: String?,
    val loadingDate: String?
)

private data class CompanyLoadDraft(
    val destination: String,
    val cargoType: String,
    val vehicleType: String,
    val loadingType: String,
    val freightAmount: String,
    val description: String,
    val loadingDate: String = ""
)

private fun companyJwtUserId(token: String): String {
    return runCatching {
        val part = token.split(".").getOrNull(1).orEmpty()
        if (part.isBlank()) "" else {
            val decoded = android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        }
    }.getOrDefault("")
}

private fun normalizeCompanyDigits(value: String): String = buildString {
    value.forEach { ch ->
        append(when (ch) {
            in '۰'..'۹' -> ('0'.code + (ch - '۰')).toChar()
            in '٠'..'٩' -> ('0'.code + (ch - '٠')).toChar()
            else -> ch
        })
    }
}

private class CompanyLoadRepository(private val token: String) {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LOADS_API + "/rest/v1/" + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", LOADS_KEY)
                setRequestProperty("Authorization", "Bearer " + token)
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

    fun list(): Pair<String?, List<CompanyLoadItem>> {
        if (token.isBlank()) return "نشست معتبر نیست؛ دوباره وارد شوید." to emptyList()
        val uid = companyJwtUserId(token)
        if (uid.isBlank()) return "شناسه حساب از نشست دریافت نشد." to emptyList()
        return runCatching {
            val allRows = mutableListOf<JSONObject>()
            var offset = 0
            while (true) {
                val query = "loads?select=id,destination,cargo_type,vehicle_type,loading_type,freight_amount,description,status,assigned_driver_id,loading_date&publisher_user_id=eq." + enc(uid) + "&order=created_at.desc&limit=200&offset=" + offset
                val (code, body) = request("GET", query)
                if (code !in 200..299) return "دریافت بارها ناموفق بود (کد " + code + ")." to emptyList()
                val page = JSONArray(body)
                for (i in 0 until page.length()) allRows.add(page.getJSONObject(i))
                if (page.length() < 200) break
                offset += 200
            }
            null to allRows.map { row ->
                CompanyLoadItem(
                    id = row.optString("id"),
                    destination = row.optString("destination"),
                    cargoType = row.optString("cargo_type"),
                    vehicleType = row.optString("vehicle_type"),
                    loadingType = row.optString("loading_type"),
                    freightAmount = row.optLong("freight_amount"),
                    description = row.optString("description"),
                    status = row.optString("status"),
                    assignedDriverId = row.optString("assigned_driver_id").takeIf { it.isNotBlank() && it != "null" },
                    loadingDate = row.optString("loading_date").takeUnless { it.isBlank() || it == "null" }
                )
            }
        }.getOrElse { "ساختار پاسخ بارها قابل خواندن نیست." to emptyList() }
    }

    fun update(item: CompanyLoadItem, draft: CompanyLoadDraft): String {
        if (token.isBlank()) return "نشست معتبر نیست."
        if (item.status != "published" || item.assignedDriverId != null) return "این بار پس از انتخاب راننده قابل ویرایش نیست."
        if (draft.destination.trim().length !in 2..100 || draft.cargoType.trim().length !in 2..160) return "مقصد یا نوع بار باید کامل و در محدوده مجاز باشد."
        val allowedVehicles = setOf(
            "وانت مبلی", "کامیونت مبلی", "کامیونت روباری کار", "کامیون ۶ متری",
            "وانت", "کامیون دربستی کار", "خاور مبلی پتو دار", "خاور مبلی پتو‌دار",
            "کامیون بزرگ", "سایر خودروهای مبلی"
        )
        if (draft.vehicleType !in allowedVehicles) return "نوع خودرو را از فهرست معتبر سامانه انتخاب کنید."
        if (draft.loadingType !in setOf("روباری", "دربست", "نیم بار")) return "نوع بارگیری معتبر نیست."
        val normalizedAmount = normalizeCompanyDigits(draft.freightAmount.trim())
        val amount = if (normalizedAmount.isBlank()) 0L else normalizedAmount.toLongOrNull() ?: return "مبلغ کرایه معتبر نیست."
        if (amount < 0) return "مبلغ کرایه معتبر نیست."
        val body = JSONObject()
            .put("destination", draft.destination.trim())
            .put("cargo_type", draft.cargoType.trim())
            .put("vehicle_type", draft.vehicleType.trim())
            .put("loading_type", draft.loadingType.trim())
            .put("freight_amount", amount)
            .put("description", draft.description.trim().take(3000))
            .put("loading_date", draft.loadingDate.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        val uid = companyJwtUserId(token)
        val path = "loads?id=eq." + enc(item.id) + "&publisher_user_id=eq." + enc(uid) + "&status=eq.published&assigned_driver_id=is.null"
        val (code, response) = request("PATCH", path, body)
        if (code !in 200..299) return "ویرایش بار از طرف سرور رد شد (کد " + code + ")."
        return if (runCatching { JSONArray(response).length() }.getOrDefault(0) > 0) "بار با موفقیت ویرایش شد."
        else "باری برای ویرایش پیدا نشد؛ مالکیت یا وضعیت بار را بررسی کنید."
    }

    fun cancel(item: CompanyLoadItem): String {
        if (token.isBlank()) return "نشست معتبر نیست."
        if (item.status != "published" || item.assignedDriverId != null) return "این بار پس از انتخاب راننده قابل لغو نیست."
        val uid = companyJwtUserId(token)
        val path = "loads?id=eq." + enc(item.id) + "&publisher_user_id=eq." + enc(uid) + "&status=eq.published&assigned_driver_id=is.null"
        val body = JSONObject().put("status", "cancelled")
        val (code, response) = request("PATCH", path, body)
        if (code !in 200..299) return "لغو بار از طرف سرور رد شد (کد " + code + ")."
        return if (runCatching { JSONArray(response).length() }.getOrDefault(0) > 0) "بار لغو شد و سابقه آن حفظ شد."
        else "باری برای لغو پیدا نشد؛ ممکن است بار قبلاً تغییر وضعیت داده باشد."
    }
}

@Composable
internal fun CompanyLoadManagement(accessToken: String) {
    val scope = rememberCoroutineScope()
    val repository = remember(accessToken) { CompanyLoadRepository(accessToken) }
    var items by remember { mutableStateOf<List<CompanyLoadItem>>(emptyList()) }
    var status by remember { mutableStateOf("در حال دریافت بارهای شرکت...") }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CompanyLoadItem?>(null) }
    var cancelling by remember { mutableStateOf<CompanyLoadItem?>(null) }
    var draft by remember { mutableStateOf(CompanyLoadDraft("", "", "", "روباری", "", "")) }
    var vehicleMenu by remember { mutableStateOf(false) }
    var loadingMenu by remember { mutableStateOf(false) }
    var historyDateFilter by remember { mutableStateOf("") }
    var cargoMenu by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.list() }
            items = result.second
            status = result.first ?: if (result.second.isEmpty()) "هنوز باری ثبت نشده است." else ""
            loading = false
        }
    }
    LaunchedEffect(accessToken) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("بارهای ثبت‌شده شرکت", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !loading, onClick = { refresh() }) { Text("بروزرسانی") }
        }
        if (loading) CircularProgressIndicator()
        JalaliDatePickerField(selectedIsoDate = historyDateFilter, onDateSelected = { historyDateFilter = it }, label = "فیلتر تاریخ سوابق")
        items.filter { historyDateFilter.isBlank() || it.loadingDate == historyDateFilter }.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("تهران ← " + item.destination, style = MaterialTheme.typography.titleMedium)
                    Text(item.cargoType + " | " + item.vehicleType + " | " + item.loadingType)
                    Text("تاریخ بار: " + formatJalaliDate(item.loadingDate), style = MaterialTheme.typography.bodySmall)
                    Text(if (item.freightAmount == 0L) "کرایه: توافقی" else "کرایه: " + item.freightAmount + " تومان")
                    Text("وضعیت: " + item.status + if (item.assignedDriverId != null) " | راننده انتخاب شده" else "")
                    if (item.description.isNotBlank()) Text(item.description)
                    val canManage = item.status == "published" && item.assignedDriverId == null
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = canManage, onClick = {
                            editing = item
                            draft = CompanyLoadDraft(item.destination, item.cargoType, item.vehicleType, item.loadingType, if (item.freightAmount == 0L) "" else item.freightAmount.toString(), item.description, item.loadingDate.orEmpty())
                        }) { Text("ویرایش") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = canManage, onClick = { cancelling = item }) { Text("لغو بار") }
                    }
                    if (!canManage) Text("پس از انتخاب راننده یا تغییر وضعیت، ویرایش و حذف شرکت غیرفعال است.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary)
    }

    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("ویرایش بار") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(draft.destination, { draft = draft.copy(destination = it.take(100)) }, label = { Text("مقصد") }, singleLine = true)
                    Box {
                        OutlinedTextField(
                            value = draft.cargoType,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().clickable { cargoMenu = true },
                            label = { Text("نوع کالا") },
                            trailingIcon = { Text("⌄") },
                            singleLine = true
                        )
                        DropdownMenu(expanded = cargoMenu, onDismissRequest = { cargoMenu = false }) {
                            listOf("مبل", "سرویس خواب", "اثاث منزل", "کمد", "نهارخوری", "مبل استیل", "سایر مصنوعات چوبی").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    draft = draft.copy(cargoType = option)
                                    cargoMenu = false
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedTextField(
                            value = draft.vehicleType,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().clickable { vehicleMenu = true },
                            label = { Text("نوع خودرو") },
                            trailingIcon = { Text("⌄") },
                            singleLine = true
                        )
                        DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                            listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    draft = draft.copy(vehicleType = option)
                                    vehicleMenu = false
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedTextField(
                            value = draft.loadingType,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().clickable { loadingMenu = true },
                            label = { Text("نوع بارگیری") },
                            trailingIcon = { Text("⌄") },
                            singleLine = true
                        )
                        DropdownMenu(expanded = loadingMenu, onDismissRequest = { loadingMenu = false }) {
                            listOf("دربست", "روباری", "نیم بار").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    draft = draft.copy(loadingType = option)
                                    loadingMenu = false
                                })
                            }
                        }
                    }
                    JalaliDatePickerField(selectedIsoDate = draft.loadingDate, onDateSelected = { draft = draft.copy(loadingDate = it) }, label = "تاریخ بار")
                    OutlinedTextField(draft.freightAmount, { draft = draft.copy(freightAmount = it.filter(Char::isDigit).take(15)) }, label = { Text("کرایه به تومان؛ خالی یعنی توافقی") }, singleLine = true)
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(3000)) }, label = { Text("توضیحات") }, minLines = 2)
                }
            },
            confirmButton = {
                Button(enabled = !loading, onClick = {
                    loading = true
                    scope.launch {
                        status = withContext(Dispatchers.IO) { repository.update(item, draft) }
                        editing = null
                        val result = withContext(Dispatchers.IO) { repository.list() }
                        items = result.second
                        if (result.first != null) status += " " + result.first
                        loading = false
                    }
                }) { Text("ذخیره تغییرات") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("انصراف") } }
        )
    }
    cancelling?.let { item ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("لغو بار") },
            text = { Text("بار مقصد «" + item.destination + "» لغو شود؟ سابقه بار حفظ می‌شود. این عملیات فقط برای بار منتشرشده و بدون راننده مجاز است.") },
            confirmButton = {
                Button(enabled = !loading, onClick = {
                    loading = true
                    scope.launch {
                        status = withContext(Dispatchers.IO) { repository.cancel(item) }
                        cancelling = null
                        val result = withContext(Dispatchers.IO) { repository.list() }
                        items = result.second
                        if (result.first != null) status += " " + result.first
                        loading = false
                    }
                }) { Text("تأیید لغو بار") }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("انصراف") } }
        )
    }
}
