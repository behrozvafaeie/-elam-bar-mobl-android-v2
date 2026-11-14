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

private data class AdminLoginResult(val success: Boolean, val message: String)

@Composable
private fun ElaanBarApp() {
    var role by remember { mutableStateOf(UserRole.DRIVER) }
    var register by remember { mutableStateOf(false) }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("برای ادامه، نوع حساب را انتخاب کنید.") }
    var loading by remember { mutableStateOf(false) }
    var adminLoggedIn by remember { mutableStateOf(false) }
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
                                onClick = { selectedPanel = title },
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
                        Text(
                            "صفحه این بخش در برنامه اضافه شده، اما اتصال اطلاعات و عملیات واقعی آن هنوز تکمیل نشده است. برای جلوگیری از نمایش اطلاعات نادرست، فعلاً داده‌ای در این قسمت نشان داده نمی‌شود.",
                            textAlign = TextAlign.Center
                        )
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
                            value = mobile,
                            onValueChange = { mobile = it.filter { c -> c.isDigit() }.take(11) },
                            label = { Text("شماره موبایل (مثلاً 0912...)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
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
                                            if (result.success) adminLoggedIn = true
                                        }
                                    }
                                }
                            } else {
                                message = when {
                                    mobile.length != 11 || !mobile.startsWith("09") -> "شماره موبایل معتبر وارد کنید."
                                    password.length < 8 -> "رمز عبور باید حداقل ۸ کاراکتر باشد."
                                    register && (firstName.isBlank() || lastName.isBlank()) -> "نام و نام خانوادگی را کامل کنید."
                                    register && role == UserRole.COMPANY && companyName.isBlank() -> "نام شرکت را وارد کنید."
                                    else -> "ورود و ثبت‌نام راننده و شرکت هنوز به احراز هویت متصل نشده است."
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
                        Text("نسخه آزمایشی؛ ورود راننده و شرکت هنوز فعال نشده است.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
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
        AdminLoginResult(true, "ورود مدیر ارشد با موفقیت انجام شد.")
    } catch (_: Exception) {
        AdminLoginResult(false, "ارتباط با سرور برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.")
    } finally {
        authConnection?.disconnect()
        profileConnection?.disconnect()
    }
}
