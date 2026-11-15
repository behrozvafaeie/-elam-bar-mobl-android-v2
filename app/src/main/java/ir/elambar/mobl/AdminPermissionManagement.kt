package ir.elambar.mobl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private data class PermissionAdmin(val id: String, val email: String, val role: String)
private data class SectionPermission(
    val key: String,
    val title: String,
    val view: Boolean = false,
    val create: Boolean = false,
    val edit: Boolean = false,
    val delete: Boolean = false
)

@Composable
internal fun AdminPermissionManagement(accessToken: String) {
    val scope = rememberCoroutineScope()
    val sections = remember {
        listOf(
            SectionPermission("drivers", "مدیریت رانندگان"),
            SectionPermission("companies", "مدیریت شرکت‌ها"),
            SectionPermission("loads", "مدیریت بارها"),
            SectionPermission("driver_documents", "مدیریت مدارک"),
            SectionPermission("driver_locations", "موقعیت رانندگان"),
            SectionPermission("load_favorites", "علاقه‌مندی‌ها"),
            SectionPermission("complaints", "شکایات"),
            SectionPermission("ratings", "امتیازها"),
            SectionPermission("chat", "گفتگوها و اتاق عمومی")
        )
    }
    var admins by remember { mutableStateOf<List<PermissionAdmin>>(emptyList()) }
    var selectedAdmin by remember { mutableStateOf<PermissionAdmin?>(null) }
    var permissions by remember { mutableStateOf(sections) }
    var menuExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("در حال دریافت فهرست مدیران...") }
    var busy by remember { mutableStateOf(false) }

    fun loadPermissions(admin: PermissionAdmin) {
        selectedAdmin = admin
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadSectionPermissions(accessToken, admin.id, sections) }
            permissions = result.second
            status = result.first
            busy = false
        }
    }

    LaunchedEffect(accessToken) {
        val result = withContext(Dispatchers.IO) { loadPermissionAdmins(accessToken) }
        admins = result.second
        status = result.first
        if (selectedAdmin == null) admins.firstOrNull()?.let { loadPermissions(it) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("مدیریت دسترسی مدیران", style = MaterialTheme.typography.titleMedium)
            Text("مدیر ارشد اصلی می‌تواند مجوز مشاهده، ثبت، ویرایش و حذف هر بخش را جداگانه برای هر مدیر تعیین کند. مدیران دیگر به گفتگوهای خصوصی شرکت‌ها دسترسی خودکار ندارند.", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = admins.isNotEmpty()) {
                    Text(selectedAdmin?.email ?: "انتخاب مدیر برای تنظیم مجوزها")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    admins.forEach { admin ->
                        DropdownMenuItem(
                            text = { Text(admin.email.ifBlank { admin.id }) },
                            onClick = { menuExpanded = false; loadPermissions(admin) }
                        )
                    }
                }
            }
            if (selectedAdmin != null) {
                Text("مدیر انتخاب‌شده: ${selectedAdmin!!.email} • ${selectedAdmin!!.role}", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    permissions.forEach { item ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    PermissionToggle("مشاهده", item.view, { v -> permissions = permissions.map { if (it.key == item.key) it.copy(view = v) else it } })
                                    PermissionToggle("ثبت", item.create, { v -> permissions = permissions.map { if (it.key == item.key) it.copy(create = v) else it } })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    PermissionToggle("ویرایش", item.edit, { v -> permissions = permissions.map { if (it.key == item.key) it.copy(edit = v) else it } })
                                    PermissionToggle("حذف", item.delete, { v -> permissions = permissions.map { if (it.key == item.key) it.copy(delete = v) else it } })
                                }
                                Button(enabled = !busy, onClick = {
                                    val target = selectedAdmin ?: return@Button
                                    busy = true
                                    scope.launch {
                                        status = withContext(Dispatchers.IO) { saveSectionPermission(accessToken, target.id, item) }
                                        busy = false
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره مجوزهای این بخش") }
                            }
                        }
                    }
                }
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.widthIn(min = 120.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun loadPermissionAdmins(token: String): Pair<String, List<PermissionAdmin>> {
    val (code, body) = adminPermissionRequest(token, "POST", "rpc/admin_list_users", JSONObject())
    if (code !in 200..299) return "فهرست مدیران دریافت نشد؛ فقط مدیر ارشد اصلی مجاز است." to emptyList()
    return runCatching {
        val rows = JSONArray(body)
        val list = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            PermissionAdmin(row.optString("user_id"), row.optString("email"), row.optString("role"))
        }.filter { it.role != "super_admin" }
        (if (list.isEmpty()) "مدیر دیگری برای تنظیم مجوز وجود ندارد." else "") to list
    }.getOrElse { "پاسخ فهرست مدیران قابل خواندن نیست." to emptyList() }
}

private fun loadSectionPermissions(token: String, adminId: String, defaults: List<SectionPermission>): Pair<String, List<SectionPermission>> {
    val path = "admin_section_permissions?select=section_key,can_view,can_create,can_edit,can_delete&admin_user_id=eq.${URLEncoder.encode(adminId, "UTF-8")}"
    val (code, body) = adminPermissionRequest(token, "GET", path)
    if (code !in 200..299) return "مجوزهای مدیر دریافت نشد." to defaults
    return runCatching {
        val rows = JSONArray(body)
        val saved = (0 until rows.length()).associate { i ->
            val r = rows.getJSONObject(i)
            r.optString("section_key") to SectionPermission(
                r.optString("section_key"), "",
                r.optBoolean("can_view"), r.optBoolean("can_create"), r.optBoolean("can_edit"), r.optBoolean("can_delete")
            )
        }
        "" to defaults.map { base ->
            val value = saved[base.key]
            if (value == null) base else base.copy(view = value.view, create = value.create, edit = value.edit, delete = value.delete)
        }
    }.getOrElse { "ساختار مجوزهای مدیر قابل خواندن نیست." to defaults }
}

private fun saveSectionPermission(token: String, adminId: String, item: SectionPermission): String {
    val payload = JSONObject()
        .put("p_target_user_id", adminId)
        .put("p_section_key", item.key)
        .put("p_can_view", item.view)
        .put("p_can_create", item.create)
        .put("p_can_edit", item.edit)
        .put("p_can_delete", item.delete)
    val (code, _) = adminPermissionRequest(token, "POST", "rpc/admin_set_section_permission", payload)
    return if (code in 200..299) "مجوزهای «${item.title}» ذخیره شد و در سرور اعمال شد."
    else "ذخیره مجوز رد شد؛ نشست مدیر ارشد یا اطلاعات مدیر را بررسی کنید."
}

private fun adminPermissionRequest(token: String, method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("https://sckcxtceybwtiecyxqsy.supabase.co/rest/v1/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (e: Exception) { -1 to (e.message ?: "network_error") }
    finally { connection?.disconnect() }
}
