package ir.elambar.mobl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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

private enum class AccountRole(val title: String) {
    DRIVER("راننده"), COMPANY("شرکت باربری"), SUPER_ADMIN("مدیر ارشد")
}

private data class LoginOutcome(
    val success: Boolean,
    val message: String,
    val displayName: String = "",
    val accessToken: String = "",
    val accountStatus: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ElaamBarMoblApp() }
            }
        }
    }
}

@Composable
private fun ElaamBarMoblApp() {
    var role by remember { mutableStateOf(AccountRole.DRIVER) }
    var loginId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("برای ورود، نوع حساب را انتخاب کنید.") }
    var loggedIn by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var accountStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text("اعلام بار مبل", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("سامانه ارتباط شرکت‌های باربری و رانندگان", textAlign = TextAlign.Center)

            if (!loggedIn) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ورود به حساب", style = MaterialTheme.typography.titleLarge)
                        Text("نوع حساب")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AccountRole.entries.forEach { item ->
                                if (role == item) {
                                    Button(onClick = { role = item }, modifier = Modifier.weight(1f)) { Text(item.title) }
                                } else {
                                    OutlinedButton(onClick = { role = item }, modifier = Modifier.weight(1f)) { Text(item.title) }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = loginId, onValueChange = { loginId = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (role == AccountRole.SUPER_ADMIN) "ایمیل مدیر ارشد" else "شماره موبایل یا نام کاربری") },
                            keyboardOptions = KeyboardOptions(keyboardType = if (role == AccountRole.SUPER_ADMIN) KeyboardType.Email else KeyboardType.Text)
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("رمز عبور") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        Button(
                            onClick = {
                                if (loginId.isBlank() || password.isBlank()) {
                                    notice = "شماره موبایل/نام کاربری و رمز عبور را وارد کنید."
                                } else {
                                    busy = true
                                    notice = "در حال بررسی اطلاعات ورود..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            if (role == AccountRole.SUPER_ADMIN) authenticateSuperAdmin(loginId.trim(), password)
                                            else authenticateUser(loginId.trim(), password, role)
                                        }
                                        busy = false
                                        notice = result.message
                                        if (result.success) {
                                            loggedIn = true
                                            displayName = result.displayName
                                            accessToken = result.accessToken
                                            accountStatus = result.accountStatus
                                        }
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busy) CircularProgressIndicator() else Text("ورود")
                        }
                        Text(
                            "ورود فعلاً فقط با رمز عبور است؛ پیامک و کد یک‌بارمصرف در این نسخه استفاده نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("خوش آمدید، $displayName", style = MaterialTheme.typography.titleLarge)
                        Text("پنل " + role.title)
                        if (accountStatus == "pending") {
                            Text("حساب شما در انتظار تأیید مدیر ارشد است.")
                        } else {
                            val sections = when (role) {
                                AccountRole.DRIVER -> listOf("سالن اعلام بار", "بارهای من", "پروفایل و مدارک", "گفتگوها")
                                AccountRole.COMPANY -> listOf("ثبت و مدیریت اعلام بار", "درخواست‌های رانندگان", "پروفایل شرکت", "گفتگوها")
                                AccountRole.SUPER_ADMIN -> listOf("مدیریت رانندگان و شرکت‌ها", "مدیریت بارها", "مدیریت مدیران و دسترسی‌ها", "گفتگوها و گزارش‌ها")
                            }
                            sections.forEach { title ->
                                OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(title) }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                loggedIn = false
                                accessToken = ""
                                displayName = ""
                                accountStatus = ""
                                loginId = ""
                                password = ""
                                notice = "از حساب خارج شدید."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("خروج از حساب") }
                    }
                }
            }
            Text(notice, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
            Text("نسخه پایه در حال ساخت و آزمایش است.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun authenticateUser(username: String, password: String, role: AccountRole): LoginOutcome {
    var auth: HttpURLConnection? = null
    var profile: HttpURLConnection? = null
    return try {
        auth = (URL("$SUPABASE_URL/functions/v1/username-auth-v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val accountType = if (role == AccountRole.DRIVER) "driver" else "company"
        val request = JSONObject().put("action", "login").put("username", username.trim().lowercase()).put("password", password).put("account_type", accountType)
        auth.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        val code = auth.responseCode
        val raw = (if (code in 200..299) auth.inputStream else auth.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return LoginOutcome(false, "نام کاربری یا رمز عبور صحیح نیست.")
        val response = JSONObject(raw)
        val user = response.optJSONObject("user") ?: return LoginOutcome(false, "اطلاعات حساب از سرور دریافت نشد.")
        val session = response.optJSONObject("session") ?: return LoginOutcome(false, "نشست ورود از سرور دریافت نشد.")
        val userId = user.optString("id")
        val token = session.optString("access_token")
        val type = user.optJSONObject("app_metadata")?.optString("account_type").orEmpty()
        val expected = if (role == AccountRole.DRIVER) "driver" else "company"
        if (type != expected) return LoginOutcome(false, "این حساب با نوع حساب انتخاب‌شده مطابقت ندارد.")
        if (userId.isBlank() || token.isBlank()) return LoginOutcome(false, "اطلاعات نشست ناقص است.")

        val table = if (role == AccountRole.DRIVER) "drivers" else "companies"
        val fields = if (role == AccountRole.DRIVER) "status,first_name,full_name" else "status,first_name,last_name,company_name,manager_name"
        profile = (URL("$SUPABASE_URL/rest/v1/$table?select=$fields&id=eq.$userId&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profile.responseCode
        val profileRaw = (if (profileCode in 200..299) profile.inputStream else profile.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (profileCode !in 200..299) return LoginOutcome(false, "ورود انجام شد، اما بررسی وضعیت حساب ممکن نشد.")
        val rows = JSONArray(profileRaw)
        if (rows.length() == 0) return LoginOutcome(false, "پرونده کاربری در سامانه پیدا نشد.")
        val row = rows.getJSONObject(0)
        val status = row.optString("status")
        if (status == "blocked") return LoginOutcome(false, "دسترسی این حساب مسدود شده است.")
        if (status == "rejected") return LoginOutcome(false, "درخواست حساب شما رد شده است.")
        if (status !in listOf("pending", "approved")) return LoginOutcome(false, "وضعیت حساب معتبر نیست.")
        val name = if (role == AccountRole.COMPANY) {
            row.optString("company_name").ifBlank { row.optString("manager_name").ifBlank { "شرکت باربری" } }
        } else {
            row.optString("full_name").ifBlank { row.optString("first_name").ifBlank { "راننده" } }
        }
        LoginOutcome(true, if (status == "pending") "ورود انجام شد؛ حساب در انتظار تأیید مدیر ارشد است." else "ورود با موفقیت انجام شد.", name, token, status)
    } catch (_: Exception) {
        LoginOutcome(false, "ارتباط با سامانه برقرار نشد؛ اینترنت را بررسی کنید.")
    } finally {
        auth?.disconnect(); profile?.disconnect()
    }
}

private fun authenticateSuperAdmin(email: String, password: String): LoginOutcome {
    var auth: HttpURLConnection? = null
    var profile: HttpURLConnection? = null
    return try {
        auth = (URL("$SUPABASE_URL/auth/v1/token?grant_type=password").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("email", email).put("password", password)
        auth.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = auth.responseCode
        val raw = (if (code in 200..299) auth.inputStream else auth.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return LoginOutcome(false, "ایمیل یا رمز عبور مدیر ارشد صحیح نیست.")
        val response = JSONObject(raw)
        val token = response.optString("access_token")
        val userId = response.optJSONObject("user")?.optString("id").orEmpty()
        if (token.isBlank() || userId.isBlank()) return LoginOutcome(false, "نشست ورود مدیر کامل نیست.")
        profile = (URL("$SUPABASE_URL/rest/v1/admin_users?select=role,is_blocked&user_id=eq.$userId&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profile.responseCode
        val profileRaw = (if (profileCode in 200..299) profile.inputStream else profile.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (profileCode !in 200..299) return LoginOutcome(false, "بررسی سطح دسترسی مدیر ممکن نشد.")
        val rows = JSONArray(profileRaw)
        if (rows.length() == 0) return LoginOutcome(false, "این حساب در فهرست مدیران ثبت نشده است.")
        val admin = rows.getJSONObject(0)
        if (admin.optBoolean("is_blocked", false)) return LoginOutcome(false, "دسترسی این حساب مدیر مسدود است.")
        if (admin.optString("role") != "super_admin") return LoginOutcome(false, "این حساب دسترسی مدیر ارشد اصلی را ندارد.")
        LoginOutcome(true, "ورود مدیر ارشد با موفقیت انجام شد.", "مدیر ارشد", token, "approved")
    } catch (_: Exception) {
        LoginOutcome(false, "ارتباط با سامانه برقرار نشد؛ اینترنت را بررسی کنید.")
    } finally {
        auth?.disconnect(); profile?.disconnect()
    }
}
