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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ElaanBarApp() } }
    }
}

private enum class UserRole(val label: String) {
    DRIVER("راننده"), COMPANY("شرکت باربری")
}

@Composable
private fun ElaanBarApp() {
    var role by remember { mutableStateOf(UserRole.DRIVER) }
    var register by remember { mutableStateOf(false) }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("برای ادامه، نوع حساب را انتخاب کنید.") }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text("اعلام بار مبل", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
                Text("سامانه ارتباط شرکت‌های باربری و رانندگان", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = role == UserRole.DRIVER, onClick = { role = UserRole.DRIVER }, label = { Text("ورود راننده") })
                    FilterChip(selected = role == UserRole.COMPANY, onClick = { role = UserRole.COMPANY }, label = { Text("ورود شرکت باربری") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { register = false; message = "ورود به حساب" }) { Text("ورود") }
                    TextButton(onClick = { register = true; message = "ثبت‌نام؛ حساب پس از بررسی مدیر ارشد فعال می‌شود." }) { Text("ثبت‌نام") }
                }
                if (register) {
                    OutlinedTextField(firstName, { firstName = it }, label = { Text("نام") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("نام خانوادگی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (role == UserRole.COMPANY) {
                        OutlinedTextField(companyName, { companyName = it }, label = { Text("نام شرکت باربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    mobile, { mobile = it.filter { c -> c.isDigit() }.take(11) },
                    label = { Text("شماره موبایل (مثلاً 0912...)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("رمز عبور") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        message = when {
                            mobile.length != 11 || !mobile.startsWith("09") -> "شماره موبایل معتبر وارد کنید."
                            password.length < 8 -> "رمز عبور باید حداقل ۸ کاراکتر باشد."
                            register && (firstName.isBlank() || lastName.isBlank()) -> "نام و نام خانوادگی را کامل کنید."
                            register && role == UserRole.COMPANY && companyName.isBlank() -> "نام شرکت را وارد کنید."
                            else -> "صفحه نمایشی آماده است؛ اتصال ورود و ثبت‌نام به احراز هویت امن Supabase در مرحله بعد فعال می‌شود."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (register) "ارسال درخواست ثبت‌نام" else "ورود به حساب") }
                Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                HorizontalDivider()
                Text("نسخه اولیه آزمایشی — هنوز برای ورود واقعی و ثبت بار عملیاتی آماده نیست.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
