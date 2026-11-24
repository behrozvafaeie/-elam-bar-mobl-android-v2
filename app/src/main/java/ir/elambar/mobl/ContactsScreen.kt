package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CONTACTS_SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val CONTACTS_SUPABASE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

private data class DirectoryContact(
    val id: String,
    val name: String,
    val role: String,
    val subtitle: String,
    val status: String,
    val photoUrl: String,
    val details: List<Pair<String, String>>
)

private data class DirectoryResult(val contacts: List<DirectoryContact>, val message: String)

@Composable
internal fun ContactsScreen(
    accessToken: String,
    role: String,
    onBack: () -> Unit
) {
    var contacts by remember { mutableStateOf<List<DirectoryContact>>(emptyList()) }
    var selected by remember { mutableStateOf<DirectoryContact?>(null) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("برای دریافت فهرست مخاطبین، کمی صبر کنید.") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (loading || accessToken.isBlank()) return
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchRoleDirectory(accessToken, role) }
                contacts = result.contacts
                message = result.message
                selected = null
            } catch (_: Exception) {
                contacts = emptyList()
                message = "دریافت فهرست مخاطبین ناموفق بود؛ اتصال را بررسی و دوباره تلاش کنید."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(accessToken, role) { refresh() }

    val visibleContacts = contacts.filter {
        search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) ||
            it.subtitle.contains(search.trim(), ignoreCase = true) ||
            it.details.any { detail -> detail.second.contains(search.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
            }
            Text(if (selected == null) "مخاطبین" else "پروفایل مخاطب", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (selected == null) {
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی مخاطبین")
                }
            }
        }

        if (loading) {
            CircularProgressIndicator()
            Text("در حال دریافت فهرست مخاطبین...")
        } else if (selected == null) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("جست‌وجوی نام یا مشخصات") },
                singleLine = true
            )
            Text(
                when (role) {
                    "COMPANY" -> "رانندگان تأییدشده"
                    "DRIVER" -> "شرکت‌های تأییدشده"
                    else -> "فهرست شرکت‌ها و رانندگان"
                },
                style = MaterialTheme.typography.titleMedium
            )
            if (visibleContacts.isEmpty()) {
                Text(if (message.isNotBlank()) message else "مخاطبی با این جست‌وجو پیدا نشد.", textAlign = TextAlign.Center)
            }
            visibleContacts.forEach { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selected = contact },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (contact.photoUrl.isNotBlank()) {
                            AsyncImage(
                                model = contact.photoUrl,
                                contentDescription = "عکس پروفایل",
                                modifier = Modifier.size(52.dp).clip(CircleShape)
                            )
                        } else {
                            Card(shape = CircleShape, modifier = Modifier.size(52.dp)) {
                                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                    Text(if (contact.role == "راننده") "ر" else "ش", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else {
            val contact = selected!!
            Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (contact.photoUrl.isNotBlank()) {
                        AsyncImage(model = contact.photoUrl, contentDescription = "عکس پروفایل مخاطب", modifier = Modifier.size(112.dp).clip(CircleShape))
                    }
                    Text(contact.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(contact.role, color = MaterialTheme.colorScheme.primary)
                    Text(contact.status, style = MaterialTheme.typography.bodySmall)
                }
            }
            contact.details.forEach { (label, value) ->
                if (value.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Text(
                "اطلاعات محرمانه و مدارک هویتی در فهرست مخاطبین نمایش داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = { selected = null }, modifier = Modifier.fillMaxWidth()) { Text("بازگشت به فهرست مخاطبین") }
        }
        if (message.isNotBlank() && selected == null && !loading && contacts.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("بازگشت به پنل") }
    }
}

// The RPC endpoints enforce role access on the server; local search only filters already-authorized results.
private fun fetchRoleDirectory(token: String, role: String): DirectoryResult {
    if (token.isBlank()) return DirectoryResult(emptyList(), "نشست ورود معتبر نیست.")
    val result = mutableListOf<DirectoryContact>()
    val calls = when (role) {
        "COMPANY" -> listOf("list_driver_directory_v2")
        "DRIVER" -> listOf("list_company_directory")
        else -> listOf("list_company_directory", "list_driver_directory_v2")
    }
    var errorMessage = ""
    for (rpcName in calls) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$CONTACTS_SUPABASE_URL/rest/v1/rpc/$rpcName").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 18000
                doOutput = true
                setRequestProperty("apikey", CONTACTS_SUPABASE_KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                errorMessage = "دریافت بخشی از مخاطبین مجاز نبود (کد $code)."
                continue
            }
            val rows = JSONArray(raw)
            val photoUrls = createProfilePhotoSignedUrls(
                (0 until rows.length()).map { rows.getJSONObject(it).optString("profile_photo_path") },
                token
            )
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (rpcName == "list_driver_directory_v2") {
                    // Match the actual list_driver_directory_v2 RPC contract:
                    // id + full_name (not driver_id / first_name / last_name).
                    val name = row.optString("full_name").trim().ifBlank { "راننده" }
                    val destinations = row.optJSONArray("destinations") ?: JSONArray()
                    val destinationText = (0 until destinations.length()).map { destinations.optString(it) }.filter { it.isNotBlank() }.joinToString("، ")
                    val photo = photoUrls[profilePhotoStoragePath(row.optString("profile_photo_path"))].orEmpty()
                    result += DirectoryContact(
                        row.optString("id"), name, "راننده", row.optString("vehicle_type"),
                        "حساب تأییدشده", photo,
                        listOf(
                            "نوع خودرو" to row.optString("vehicle_type"),
                            "مقصد اصلی" to row.optString("primary_destination"),
                            "مقصدهای دیگر" to destinationText
                        )
                    )
                } else {
                    val name = row.optString("company_name").ifBlank { "شرکت باربری" }
                    val photo = createProfilePhotoSignedUrl(row.optString("profile_photo_path"), token)
                    result += DirectoryContact(
                        row.optString("company_id"), name, "شرکت باربری",
                        row.optString("activity_city"), "حساب تأییدشده", photo,
                        listOf(
                            "نام مدیر یا مسئول" to row.optString("manager_name"),
                            "شهر محل فعالیت" to row.optString("activity_city")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            errorMessage = "ارتباط با فهرست مخاطبین برقرار نشد."
        } finally {
            connection?.disconnect()
        }
    }
    val deduped = result.distinctBy { it.id }.sortedBy { it.name }
    val message = when {
        deduped.isNotEmpty() -> "تعداد مخاطبین: ${deduped.size}"
        errorMessage.isNotBlank() -> errorMessage
        else -> "در حال حاضر مخاطب تأییدشده‌ای برای نمایش وجود ندارد."
    }
    return DirectoryResult(deduped, message)
}


