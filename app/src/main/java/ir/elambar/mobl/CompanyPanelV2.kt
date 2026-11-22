package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CompanyPanelBlue = Color(0xFF245DA8)

@Composable
internal fun CompanyPanelV2(
    accessToken: String,
    onBackToOldPanel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("home") }
    var destinationText by remember { mutableStateOf("") }
    var cargoType by remember { mutableStateOf("مبل") }
    var vehicleType by remember { mutableStateOf("وانت مبلی") }
    var loadingType by remember { mutableStateOf("روباری") }
    var priceMode by remember { mutableStateOf("fixed") }
    var freightAmount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf("") }
    var driverDestination by remember { mutableStateOf("") }
    var driverVehicle by remember { mutableStateOf("وانت مبلی") }
    var driverSearchLoading by remember { mutableStateOf(false) }
    var driverSearchMessage by remember { mutableStateOf("برای جست‌وجو، مقصد را وارد کنید.") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = CompanyPanelBlue)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("پنل جدید شرکت", color = Color.White, fontSize = 24.sp)
                Text("ثبت و مدیریت بارهای شرکت در محیطی ساده و خوانا", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text("مبدأ ثابت همه بارها: تهران", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
        }

        OutlinedButton(onClick = onBackToOldPanel, modifier = Modifier.fillMaxWidth()) {
            Text("بازگشت به پنل فعلی")
        }

        when (page) {
            "home" -> {
                CompanyPanelActionCard(
                    title = "ثبت بار جدید",
                    description = "ثبت یک یا چند مقصد در یک مرحله؛ بدون محدودیت تعداد بار.",
                    buttonText = "ورود به فرم ثبت بار",
                    onClick = { actionMessage = ""; page = "publish" }
                )
                CompanyPanelActionCard(
                    title = "سوابق و مدیریت بارها",
                    description = "مشاهده بارهای همین شرکت، بروزرسانی، ویرایش و لغو بارهای مجاز.",
                    buttonText = "مشاهده سوابق شرکت",
                    onClick = { page = "history" }
                )
                CompanyPanelActionCard(
                    title = "جست‌وجوی راننده",
                    description = "جست‌وجوی رانندگان تأییدشده بر اساس مقصد و نوع خودرو.",
                    buttonText = "جست‌وجوی راننده مناسب",
                    onClick = { page = "drivers" }
                )
            }

            "publish" -> {
                Text("فرم اعلام بار", style = MaterialTheme.typography.headlineSmall, color = CompanyPanelBlue)
                OutlinedTextField(
                    value = destinationText,
                    onValueChange = { destinationText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("مقصد یا مقصدها") },
                    supportingText = { Text("چند مقصد را با ویرگول جدا کنید؛ برای هر مقصد یک بار ثبت می‌شود.") },
                    minLines = 2,
                    maxLines = 4
                )
                Text("نوع بار", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("مبل", "سرویس خواب", "نهارخوری", "اثاث منزل", "لوازم اداری", "سایر").chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { option ->
                                FilterChip(selected = cargoType == option, onClick = { cargoType = option }, label = { Text(option) })
                            }
                        }
                    }
                }
                Text("نوع خودرو", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { option ->
                        FilterChip(selected = vehicleType == option, onClick = { vehicleType = option }, label = { Text(option) })
                    }
                }
                Text("نوع بارگیری", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("دربست", "روباری", "نیم بار").forEach { option ->
                        FilterChip(selected = loadingType == option, onClick = { loadingType = option }, label = { Text(option) })
                    }
                }
                Text("کرایه", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = priceMode == "fixed", onClick = { priceMode = "fixed" }, label = { Text("مبلغ مشخص") })
                    FilterChip(selected = priceMode == "negotiable", onClick = { priceMode = "negotiable"; freightAmount = "" }, label = { Text("توافقی") })
                }
                if (priceMode == "fixed") {
                    OutlinedTextField(
                        value = freightAmount,
                        onValueChange = { freightAmount = it.filter(Char::isDigit).take(18) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("مبلغ کرایه به تومان") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(3000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("توضیحات (اختیاری)") },
                    minLines = 2,
                    maxLines = 4
                )
                Button(
                    enabled = !publishing,
                    onClick = {
                        val enteredDestinations = destinationText
                            .split(',', '،', ';', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val destinations = enteredDestinations.distinctBy { it.lowercase() }
                        val amount = freightAmount.trim().toLongOrNull()
                        if (destinations.isEmpty() || destinations.any { it.length < 2 || it.length > 100 }) {
                            actionMessage = "همه مقصدها باید معتبر و بین ۲ تا ۱۰۰ نویسه باشند؛ مقصد نامعتبر را اصلاح کنید."
                        } else if (priceMode == "fixed" && (amount == null || amount <= 0)) {
                            actionMessage = "مبلغ کرایه را به‌درستی وارد کنید یا گزینه توافقی را انتخاب کنید."
                        } else {
                            val drafts = destinations.map { destination ->
                                LoadDraft(
                                    destination = destination,
                                    cargoType = cargoType,
                                    vehicleType = vehicleType,
                                    loadingType = loadingType,
                                    priceMode = priceMode,
                                    freightAmount = if (priceMode == "fixed") amount.toString() else "0",
                                    description = description.trim()
                                )
                            }
                            publishing = true
                            actionMessage = "در حال ثبت ${destinations.size} بار..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { publishLoadBatch(accessToken, drafts) }
                                publishing = false
                                actionMessage = result
                                if (result.startsWith("ثبت شد")) {
                                    destinationText = ""
                                    freightAmount = ""
                                    description = ""
                                    page = "history"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CompanyPanelBlue)
                ) {
                    Text(if (publishing) "در حال ثبت..." else "ثبت و انتشار بارها", fontSize = 17.sp)
                }
                if (actionMessage.isNotBlank()) Text(actionMessage, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton(onClick = { page = "home" }, modifier = Modifier.fillMaxWidth()) {
                    Text("بازگشت به صفحه پنل جدید")
                }
            }

            "history" -> {
                Text("سوابق بارهای همین شرکت", style = MaterialTheme.typography.headlineSmall, color = CompanyPanelBlue)
                Text("سوابق شرکت‌های دیگر در این بخش نمایش داده نمی‌شود. بار لغوشده نیز از سابقه حذف نمی‌شود.", style = MaterialTheme.typography.bodySmall)
                CompanyLoadManagement(accessToken = accessToken)
                OutlinedButton(onClick = { page = "home" }, modifier = Modifier.fillMaxWidth()) {
                    Text("بازگشت به صفحه پنل جدید")
                }
            }

            "drivers" -> {
                Text("جست‌وجوی راننده مناسب", style = MaterialTheme.typography.headlineSmall, color = CompanyPanelBlue)
                OutlinedTextField(
                    value = driverDestination,
                    onValueChange = { driverDestination = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("مقصد موردنظر") },
                    singleLine = true
                )
                Text("نوع خودرو", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { option ->
                        FilterChip(selected = driverVehicle == option, onClick = { driverVehicle = option }, label = { Text(option) })
                    }
                }
                Button(
                    enabled = !driverSearchLoading && driverDestination.trim().length >= 2,
                    onClick = {
                        driverSearchLoading = true
                        scope.launch {
                            driverSearchMessage = withContext(Dispatchers.IO) {
                                fetchCompanyDrivers(accessToken, driverVehicle, driverDestination.trim())
                            }
                            driverSearchLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CompanyPanelBlue)
                ) {
                    Text(if (driverSearchLoading) "در حال جست‌وجو..." else "جست‌وجوی رانندگان تأییدشده")
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Text(driverSearchMessage, modifier = Modifier.fillMaxWidth().padding(14.dp), textAlign = TextAlign.Start)
                }
                OutlinedButton(onClick = { page = "home" }, modifier = Modifier.fillMaxWidth()) {
                    Text("بازگشت به صفحه پنل جدید")
                }
            }
        }
    }
}

@Composable
private fun CompanyPanelActionCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = CompanyPanelBlue)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CompanyPanelBlue)
            ) { Text(buttonText, fontSize = 16.sp) }
        }
    }
}
