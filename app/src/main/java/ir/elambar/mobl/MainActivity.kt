package ir.elambar.mobl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ElaanBarApp() } }
    }
}

private enum class UserRole(val label: String) {
    DRIVER("راننده"),
    COMPANY("شرکت باربری"),
    ADMIN("مدیر ارشد")
}

private data class AdminLoginResult(val success: Boolean, val message: String, val accessToken: String = "")
private data class UserLoginResult(val success: Boolean, val message: String, val displayName: String = "", val status: String = "")

@Composable
private fun ElaanBarApp() {
    var role by remember { mutableStateOf(UserRole.DRIVER) }
    var register by remember { mutableStateOf(false) }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var userLoggedIn by remember { mutableStateOf(false) }
    var userDisplayName by remember { mutableStateOf("") }
    var userStatus by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("برای ادامه، نوع حساب را انتخاب کنید.") }
    var loading by remember { mutableStateOf(false) }
    var adminLoggedIn by remember { mutableStateOf(false) }
    var adminAccessToken by remember { mutableStateOf("") }
    var adminPanelData by remember { mutableStateOf("") }
    var adminPanelLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(88.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.LocalShipping,
                            contentDescription = "نشان اعلام بار مبل",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }
                Text("اعلام بار مبل", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
                Text("سامانه ارتباط شرکت‌های باربری و رانندگان", textAlign = TextAlign.Center)
                if (adminLoggedIn) {
                    var selectedPanel by remember { mutableStateOf<String?>(null) }
                    val panels = listOf(
                        "مدیریت رانندگان" to "بررسی فهرست رانندگان، مدارک و وضعیت تأیید",
                        "مدیریت شرکت‌ها" to "بررسی شرکت‌ها و فعال‌سازی حساب‌ها",
                        "مدیریت بارها" to "مشاهده و مدیریت بارهای ثبت‌شده",
                        "مدیریت مدیران" to "مدیریت مدیران و سطح دسترسی",
                        "گفتگوها" to "اتاق گفتگوی عمومی و پیام‌های خصوصی",
                        "گزارش‌ها" to "گزارش فعالیت‌ها و آمار سامانه",
                        "تنظیمات و امنیت" to "تنظیمات سامانه و کنترل‌های امنیتی"
                    )
                    HorizontalDivider()
                    Text("پنل مدیر ارشد", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Text("خوش آمدید؛ ورود شما با نقش مدیر ارشد اصلی تأیید شده است.", textAlign = TextAlign.Center)
                    if (selectedPanel == null) {
                        panels.forEach { (title, subtitle) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    selectedPanel = title
                                    adminPanelData = ""
                                    if (title in listOf("مدیریت رانندگان", "مدیریت شرکت‌ها", "مدیریت بارها")) {
                                        adminPanelLoading = true
                                        scope.launch {
                                            adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, title) }
                                            adminPanelLoading = false
                                        }
                                    }
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(title, style = MaterialTheme.typography.titleMedium)
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        TextButton(onClick = { selectedPanel = null }, modifier = Modifier.align(Alignment.Start)) {
                            Text("بازگشت به پنل اصلی")
                        }
                        Text(selectedPanel ?: "", fontSize = 21.sp, color = MaterialTheme.colorScheme.primary)
                        if (selectedPanel == "مدیریت رانندگان" || selectedPanel == "مدیریت شرکت‌ها" || selectedPanel == "مدیریت بارها") {
                            if (adminPanelLoading) {
                                CircularProgressIndicator()
                                Text("در حال دریافت اطلاعات از سامانه...")
                            } else {
                                Text(adminPanelData.ifBlank { "اطلاعاتی برای نمایش دریافت نشد." }, textAlign = TextAlign.Start)
                                OutlinedButton(onClick = {
                                    adminPanelLoading = true
                                    scope.launch {
                                        adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, selectedPanel ?: "") }
                                        adminPanelLoading = false
                                    }
                                }) { Text("بروزرسانی") }
                            }
                        } else {
                            Text(
                                "این بخش در نسخه بعدی تکمیل می‌شود. فعلاً عملیات مدیریتی تغییردهنده اطلاعات فعال نشده است.",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            adminLoggedIn = false
                            email = ""
                            password = ""
                            message = "از حساب مدیر ارشد خارج شدید."
                            role = UserRole.ADMIN
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("خروج از حساب") }
                } else if (userLoggedIn) {
                    HorizontalDivider()
                    Text(if (role == UserRole.DRIVER) "پنل راننده" else "پنل شرکت باربری", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Text("خوش آمدید، $userDisplayName", textAlign = TextAlign.Center)
                    when (userStatus) {
                        "pending" -> Text("درخواست شما ثبت شده و در انتظار تأیید مدیر ارشد است. تا زمان تأیید، دسترسی عملیاتی فعال نیست.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                        "approved" -> Text("حساب شما تأیید شده است. اتصال بخش‌های عملیاتی این پنل در مرحله بعد تکمیل می‌شود.", textAlign = TextAlign.Center)
                        else -> Text("وضعیت حساب: $userStatus", textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = { userLoggedIn = false; username = ""; password = ""; message = "از حساب خارج شدید." }, modifier = Modifier.fillMaxWidth()) { Text("خروج از حساب") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = role == UserRole.DRIVER, onClick = { role = UserRole.DRIVER; register = false; message = "ورود راننده" }, label = { Text("راننده") })
                        FilterChip(selected = role == UserRole.COMPANY, onClick = { role = UserRole.COMPANY; register = false; message = "ورود شرکت باربری" }, label = { Text("شرکت") })
                    }
                    FilterChip(
                        selected = role == UserRole.ADMIN,
                        onClick = { role = UserRole.ADMIN; register = false; message = "ورود مدیر ارشد با ایمیل و رمز عبور" },
                        label = { Text("ورود مدیر ارشد") }
                    )

                    if (role != UserRole.ADMIN) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { register = false; message = "ورود به حساب" }) { Text("ورود") }
                            TextButton(onClick = { register = true; message = "ثبت‌نام؛ حساب پس از بررسی مدیر ارشد فعال می‌شود." }) { Text("ثبت‌نام") }
                        }
                    } else {
                        Text("فقط حساب مدیر ارشد ثبت‌شده در سامانه اجازه ورود به پنل مدیریت را دارد.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                    }

                    if (register && role != UserRole.ADMIN) {
                        OutlinedTextField(firstName, { firstName = it }, label = { Text("نام") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(lastName, { lastName = it }, label = { Text("نام خانوادگی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (role == UserRole.COMPANY) {
                            OutlinedTextField(companyName, { companyName = it }, label = { Text("نام شرکت باربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (role == UserRole.ADMIN) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.trim() },
                            label = { Text("ایمیل مدیر ارشد") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.trim().lowercase().take(32) },
                            label = { Text("نام کاربری") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("رمز عبور") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = !loading,
                        onClick = {
                            if (role == UserRole.ADMIN) {
                                when {
                                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> message = "ایمیل معتبر وارد کنید."
                                    password.isBlank() -> message = "رمز عبور را وارد کنید."
                                    else -> {
                                        loading = true
                                        message = "در حال بررسی حساب مدیر ارشد..."
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                authenticateAdmin(email.trim(), password)
                                            }
                                            loading = false
                                            message = result.message
                                            if (result.success) { adminAccessToken = result.accessToken; adminLoggedIn = true }
                                        }
                                    }
                                }
                            } else if (register) {
                                message = "ثبت‌نام جدید هنوز به فرم کامل اطلاعات راننده و شرکت متصل نشده است. فعلاً فقط حساب‌های ثبت‌شده امکان ورود دارند."
                            } else {
                                when {
                                    !Regex("^[a-z0-9_.-]{3,32}$").matches(username) -> message = "نام کاربری باید ۳ تا ۳۲ نویسه و فقط شامل حروف انگلیسی، عدد، نقطه، خط تیره یا زیرخط باشد."
                                    password.isBlank() -> message = "رمز عبور را وارد کنید."
                                    else -> {
                                        loading = true
                                        message = "در حال بررسی نام کاربری و رمز عبور..."
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { authenticateUser(username, password, role) }
                                            loading = false
                                            message = result.message
                                            if (result.success) {
                                                userLoggedIn = true
                                                userDisplayName = result.displayName
                                                userStatus = result.status
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loading) "لطفاً صبر کنید..." else if (role == UserRole.ADMIN) "ورود مدیر ارشد" else if (register) "ارسال درخواست ثبت‌نام" else "ورود به حساب")
                    }
                    Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                    if (role != UserRole.ADMIN) {
                        HorizontalDivider()
                        Text("ورود واقعی مدیر ارشد از بخش «ورود مدیر ارشد» انجام می‌شود.", fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text("ورود راننده و شرکت با نام کاربری و رمز عبور انجام می‌شود؛ تأیید پیامکی در مرحله بعد اضافه خواهد شد.", fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}


private fun fetchAdminPanelData(accessToken: String, panel: String): String {
    if (accessToken.isBlank()) return "نشست مدیر ارشد معتبر نیست؛ خارج شوید و دوباره وارد شوید."
    val (table, select, title) = when (panel) {
        "مدیریت رانندگان" -> Triple("drivers", "first_name,last_name,mobile,vehicle_type,primary_destination,status", "رانندگان")
        "مدیریت شرکت‌ها" -> Triple("companies", "company_name,manager_name,mobile,status", "شرکت‌ها")
        "مدیریت بارها" -> Triple("loads", "origin,destination,vehicle_type,loading_type,status,created_at", "بارها")
        else -> return "این بخش هنوز آماده نیست."
    }
    var connection: HttpURLConnection? = null
    return try {
        val url = "$SUPABASE_URL/rest/v1/$table?select=$select&order=created_at.desc&limit=100"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت اطلاعات $title ناموفق بود (کد $code). سطح دسترسی یا اتصال سامانه باید بررسی شود."
        val rows = JSONArray(body)
        if (rows.length() == 0) return "هنوز موردی در فهرست $title ثبت نشده است."
        buildString {
            append("تعداد موارد نمایش‌داده‌شده: ").append(rows.length()).append("\n\n")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                append(i + 1).append(". ")
                when (panel) {
                    "مدیریت رانندگان" -> {
                        append(item.optString("first_name")).append(" ").append(item.optString("last_name"))
                        append(" | ").append(item.optString("vehicle_type"))
                        append(" | ").append(item.optString("primary_destination"))
                        append(" | ").append(item.optString("status"))
                    }
                    "مدیریت شرکت‌ها" -> {
                        append(item.optString("company_name"))
                        append(" | مسئول: ").append(item.optString("manager_name"))
                        append(" | ").append(item.optString("mobile"))
                        append(" | ").append(item.optString("status"))
                    }
                    else -> {
                        append(item.optString("origin")).append(" ← ").append(item.optString("destination"))
                        append(" | ").append(item.optString("vehicle_type"))
                        append(" | ").append(item.optString("loading_type"))
                        append(" | ").append(item.optString("status"))
                    }
                }
                append("\n")
            }
            append("\\nفقط مشاهده اطلاعات فعال است؛ تغییر وضعیت و حذف تا تکمیل کنترل‌های امنیتی غیرفعال است.")
        }
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد. اینترنت را بررسی کنید."
    } finally {
        connection?.disconnect()
    }
}

private fun authenticateAdmin(email: String, password: String): AdminLoginResult {
    var authConnection: HttpURLConnection? = null
    var profileConnection: HttpURLConnection? = null
    return try {
        authConnection = (URL("$SUPABASE_URL/auth/v1/token?grant_type=password").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        val loginBody = JSONObject().put("email", email).put("password", password).toString()
        authConnection.outputStream.use { it.write(loginBody.toByteArray(Charsets.UTF_8)) }
        val authCode = authConnection.responseCode
        val authStream = if (authCode in 200..299) authConnection.inputStream else authConnection.errorStream
        val authText = authStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (authCode !in 200..299) {
            return AdminLoginResult(false, "ایمیل یا رمز عبور صحیح نیست، یا ورود ایمیلی در سامانه نیاز به بررسی دارد.")
        }

        val authJson = JSONObject(authText)
        val accessToken = authJson.optString("access_token")
        val userId = authJson.optJSONObject("user")?.optString("id").orEmpty()
        if (accessToken.isBlank() || userId.isBlank()) {
            return AdminLoginResult(false, "پاسخ ورود از سرور کامل نبود. دوباره تلاش کنید.")
        }

        val profileUrl = "$SUPABASE_URL/rest/v1/admin_users?select=role,is_blocked&user_id=eq.$userId&limit=1"
        profileConnection = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profileConnection.responseCode
        val profileStream = if (profileCode in 200..299) profileConnection.inputStream else profileConnection.errorStream
        val profileText = profileStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (profileCode !in 200..299) {
            return AdminLoginResult(false, "ورود انجام شد اما بررسی سطح دسترسی ممکن نشد. تنظیمات امنیتی باید بررسی شود.")
        }

        val rows = JSONArray(profileText)
        if (rows.length() == 0) {
            return AdminLoginResult(false, "این حساب در فهرست مدیران ثبت نشده است.")
        }
        val admin = rows.getJSONObject(0)
        if (admin.optBoolean("is_blocked", false)) {
            return AdminLoginResult(false, "دسترسی این حساب مدیر مسدود شده است.")
        }
        if (admin.optString("role") != "super_admin") {
            return AdminLoginResult(false, "این حساب دسترسی مدیر ارشد اصلی را ندارد.")
        }
        AdminLoginResult(true, "ورود مدیر ارشد با موفقیت انجام شد.", accessToken)
    } catch (_: Exception) {
        AdminLoginResult(false, "ارتباط با سرور برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.")
    } finally {
        authConnection?.disconnect()
        profileConnection?.disconnect()
    }
}

private fun authenticateUser(username: String, password: String, role: UserRole): UserLoginResult {
    var authConnection: HttpURLConnection? = null
    var profileConnection: HttpURLConnection? = null
    return try {
        authConnection = (URL("$SUPABASE_URL/functions/v1/username-auth-v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().put("action", "login").put("username", username.trim().lowercase()).put("password", password).toString()
        authConnection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val authCode = authConnection.responseCode
        val authStream = if (authCode in 200..299) authConnection.inputStream else authConnection.errorStream
        val authText = authStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (authCode !in 200..299) {
            return UserLoginResult(false, "نام کاربری یا رمز عبور صحیح نیست.")
        }
        val authJson = JSONObject(authText)
        val user = authJson.optJSONObject("user") ?: return UserLoginResult(false, "اطلاعات حساب از سرور دریافت نشد.")
        val session = authJson.optJSONObject("session") ?: return UserLoginResult(false, "نشست ورود از سرور دریافت نشد.")
        val userId = user.optString("id")
        val accessToken = session.optString("access_token")
        val accountType = user.optJSONObject("app_metadata")?.optString("account_type").orEmpty()
        val expectedType = if (role == UserRole.DRIVER) "driver" else "company"
        if (accountType != expectedType) return UserLoginResult(false, "این نام کاربری برای نوع حساب انتخاب‌شده ثبت نشده است.")
        if (userId.isBlank() || accessToken.isBlank()) return UserLoginResult(false, "اطلاعات نشست ورود ناقص است.")

        val table = if (role == UserRole.DRIVER) "drivers" else "companies"
        val select = if (role == UserRole.DRIVER) "status,first_name,last_name,full_name" else "status,first_name,last_name,company_name,manager_name"
        val profileUrl = "$SUPABASE_URL/rest/v1/$table?select=$select&id=eq.$userId&limit=1"
        profileConnection = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profileConnection.responseCode
        val profileStream = if (profileCode in 200..299) profileConnection.inputStream else profileConnection.errorStream
        val profileText = profileStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (profileCode !in 200..299) return UserLoginResult(false, "ورود انجام شد، اما دریافت وضعیت حساب ممکن نشد. لطفاً با پشتیبانی تماس بگیرید.")
        val rows = JSONArray(profileText)
        if (rows.length() == 0) return UserLoginResult(false, "حساب ورود پیدا شد، اما پرونده راننده یا شرکت در سامانه ثبت نشده است.")
        val profile = rows.getJSONObject(0)
        val status = profile.optString("status")
        val first = profile.optString("first_name")
        val last = profile.optString("last_name")
        val displayName = when {
            role == UserRole.COMPANY && profile.optString("company_name").isNotBlank() -> profile.optString("company_name")
            (first + last).isNotBlank() -> (first + " " + last).trim()
            role == UserRole.DRIVER -> profile.optString("full_name", "راننده")
            else -> profile.optString("manager_name", "شرکت باربری")
        }
        when (status) {
            "blocked" -> UserLoginResult(false, "دسترسی این حساب مسدود شده است.")
            "rejected" -> UserLoginResult(false, "درخواست حساب شما رد شده است. برای پیگیری با مدیریت تماس بگیرید.")
            "pending", "approved" -> UserLoginResult(true, if (status == "pending") "ورود انجام شد؛ حساب در انتظار تأیید مدیر ارشد است." else "ورود با موفقیت انجام شد.", displayName, status)
            else -> UserLoginResult(false, "وضعیت حساب در سامانه معتبر نیست.")
        }
    } catch (_: Exception) {
        UserLoginResult(false, "ارتباط با سامانه برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.")
    } finally {
        authConnection?.disconnect()
        profileConnection?.disconnect()
    }
}
