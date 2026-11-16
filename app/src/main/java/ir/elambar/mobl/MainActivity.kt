package ir.elambar.mobl

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.net.URLEncoder

private const val SUPABASE_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var palette by remember { mutableIntStateOf(getSharedPreferences("elambar_ui", MODE_PRIVATE).getInt("palette", 0).coerceIn(0, 4)) }
            val schemes = listOf(
                lightColorScheme(),
                lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B), secondary = androidx.compose.ui.graphics.Color(0xFFB06B32), primaryContainer = androidx.compose.ui.graphics.Color(0xFFD8EFE7)),
                lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF244B91), secondary = androidx.compose.ui.graphics.Color(0xFF9A5E9E), primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE6FA)),
                darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8ECDBA), secondary = androidx.compose.ui.graphics.Color(0xFFFFC18B))
            )
            val activeScheme = if (palette == 4) {
                if (androidx.compose.foundation.isSystemInDarkTheme()) schemes[3] else schemes[0]
            } else schemes[palette]
            MaterialTheme(colorScheme = activeScheme) {
                ElaanBarApp(palette) {
                    palette = (palette + 1) % 5
                    getSharedPreferences("elambar_ui", MODE_PRIVATE).edit().putInt("palette", palette).apply()
                }
            }
        }
    }
}

private enum class UserRole(val label: String) {
    DRIVER("راننده"),
    COMPANY("شرکت باربری"),
    ADMIN("مدیر ارشد")
}

private data class AdminLoginResult(val success: Boolean, val message: String, val accessToken: String = "", val role: String = "")
private data class UserLoginResult(val success: Boolean, val message: String, val displayName: String = "", val status: String = "", val accessToken: String = "", val refreshToken: String = "", val photoUrl: String = "")
private data class LoadDraft(val destination: String, val cargoType: String, val vehicleType: String, val loadingType: String, val priceMode: String, val freightAmount: String, val description: String)
private data class AdminRecord(val id: String, val title: String, val detail: String, val status: String, val mediaUrl: String = "")
private data class MatchedLoad(val id: String, val origin: String, val destination: String, val cargoType: String, val vehicleType: String, val loadingType: String, val freightAmount: String, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElaanBarApp(palette: Int, onNextPalette: () -> Unit) {
    var role by remember { mutableStateOf(UserRole.DRIVER) }
    var register by remember { mutableStateOf(false) }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var userLoggedIn by remember { mutableStateOf(false) }
    var userDisplayName by remember { mutableStateOf("") }
    var userStatus by remember { mutableStateOf("") }
    var userAccessToken by remember { mutableStateOf("") }
    var profilePhotoUrl by remember { mutableStateOf("") }
    var profilePhotoUploading by remember { mutableStateOf(false) }
    var profilePhotoMessage by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }
    var showCommunication by remember { mutableStateOf(false) }
    var showChatRooms by remember { mutableStateOf(false) }
    var selectedDriverDocType by remember { mutableStateOf("کارت هوشمند") }
    var driverDocUploading by remember { mutableStateOf(false) }
    var driverDocMessage by remember { mutableStateOf("") }
    var matchedLoadsText by remember { mutableStateOf("برای دریافت بارهای متناسب، دکمه بروزرسانی را بزنید.") }
    var matchedLoads by remember { mutableStateOf<List<MatchedLoad>>(emptyList()) }
    var matchedLoadsLoading by remember { mutableStateOf(false) }
    var acceptingLoadId by remember { mutableStateOf("") }
    var companyLoadsText by remember { mutableStateOf("برای مشاهده بارهای ثبت‌شده، بروزرسانی را بزنید.") }
    var companyLoadsLoading by remember { mutableStateOf(false) }
    var driverSearchDestination by remember { mutableStateOf("") }
    var driverSearchVehicle by remember { mutableStateOf("وانت مبلی") }
    var driverSearchResults by remember { mutableStateOf("برای پیدا کردن راننده، مقصد را وارد کنید.") }
    var driverSearchLoading by remember { mutableStateOf(false) }
    var loadDestination by remember { mutableStateOf("") }
    var loadCargoType by remember { mutableStateOf("مبل") }
    var loadVehicleType by remember { mutableStateOf("وانت مبلی") }
    var loadLoadingType by remember { mutableStateOf("روباری") }
    var loadFreightAmount by remember { mutableStateOf("") }
    var loadPriceMode by remember { mutableStateOf("fixed") }
    var loadDescription by remember { mutableStateOf("") }
    var loadActionMessage by remember { mutableStateOf("") }
    var loadPublishing by remember { mutableStateOf(false) }
    val loadDrafts = remember { androidx.compose.runtime.mutableStateListOf<LoadDraft>() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var companyName by remember { mutableStateOf("") }
    var activityCity by remember { mutableStateOf("تهران") }
    var officeAddress by remember { mutableStateOf("") }
    var nationalId by remember { mutableStateOf("") }
    var smartCardNumber by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("وانت مبلی") }
    var licensePlate by remember { mutableStateOf("") }
    var primaryDestination by remember { mutableStateOf("") }
    var destinationsText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("برای ادامه، نوع حساب را انتخاب کنید.") }
    var loading by remember { mutableStateOf(false) }
    var adminLoggedIn by remember { mutableStateOf(false) }
    var adminRole by remember { mutableStateOf("") }
    var adminAccessToken by remember { mutableStateOf("") }
    var adminProfilePhotoUrl by remember { mutableStateOf("") }
    var adminPanelData by remember { mutableStateOf("") }
    var adminRecords by remember { mutableStateOf<List<AdminRecord>>(emptyList()) }
    var adminActionMessage by remember { mutableStateOf("") }
    var adminStatusReason by remember { mutableStateOf("") }
    var adminPanelLoading by remember { mutableStateOf(false) }
    var adminSearchQuery by remember { mutableStateOf("") }
    var adminSearchResults by remember { mutableStateOf<List<AdminAccount>>(emptyList()) }
    var managedAdmins by remember { mutableStateOf<List<AdminAccount>>(emptyList()) }
    var adminNewRole by remember { mutableStateOf("admin") }
    var adminManageBusy by remember { mutableStateOf(false) }
    var forceLogoutTarget by remember { mutableStateOf<AdminRecord?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && userAccessToken.isNotBlank() && role == UserRole.DRIVER) {
            driverDocUploading = true
            driverDocMessage = "در حال بارگذاری مدرک..."
            scope.launch {
                driverDocMessage = withContext(Dispatchers.IO) { uploadDriverDocument(context, uri, userAccessToken, selectedDriverDocType) }
                driverDocUploading = false
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val photoToken = if (adminLoggedIn) adminAccessToken else userAccessToken
        val photoRole = if (adminLoggedIn) UserRole.ADMIN else role
        if (uri != null && photoToken.isNotBlank()) {
            profilePhotoUploading = true
            profilePhotoMessage = "در حال بارگذاری تصویر پروفایل..."
            scope.launch {
                val uploaded = withContext(Dispatchers.IO) { uploadProfilePhoto(context, uri, photoToken, photoRole) }
                profilePhotoUploading = false
                if (uploaded.startsWith("https://")) {
                    if (adminLoggedIn) adminProfilePhotoUrl = uploaded else profilePhotoUrl = uploaded
                    profilePhotoMessage = "تصویر پروفایل ذخیره شد."
                } else profilePhotoMessage = uploaded
            }
        }
    }
    var soundEnabled by remember { mutableStateOf(context.getSharedPreferences("elambar_ui", Context.MODE_PRIVATE).getBoolean("sound", true)) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedPanel by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var clockNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var serverTimeOffsetMillis by remember { mutableLongStateOf(0L) }
    var clockUsesServerTime by remember { mutableStateOf(false) }
    val adminPanels = listOf(
        "مدیریت رانندگان", "مدیریت شرکت‌ها", "مدیریت بارها", "مدیریت مدیران",
        "مدیریت مدارک", "گفتگوها", "شکایات", "گزارش‌ها", "تنظیمات کل برنامه"
    )
    LaunchedEffect(Unit) {
        while (true) {
            val serverMillis = withContext(Dispatchers.IO) { fetchSupabaseServerTimeMillis() }
            if (serverMillis != null) {
                serverTimeOffsetMillis = serverMillis - System.currentTimeMillis()
                clockUsesServerTime = true
            } else {
                clockUsesServerTime = false
            }
            clockNowMillis = System.currentTimeMillis() + serverTimeOffsetMillis
            repeat(60) {
                delay(1000)
                clockNowMillis = System.currentTimeMillis() + serverTimeOffsetMillis
            }
        }
    }
    val tehranClockText = remember(clockNowMillis) { formatPersianTehranDateTime(clockNowMillis) }
    var textScale by remember { mutableFloatStateOf(context.getSharedPreferences("elambar_ui", Context.MODE_PRIVATE).getFloat("text_scale", 1f).coerceIn(0.9f, 1.35f)) }
    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(density = currentDensity.density, fontScale = textScale)
    LaunchedEffect(Unit) {
        val saved = DeviceSessionStore.load(context)
        if (saved != null) {
            val savedRole = if (saved.second == "COMPANY") UserRole.COMPANY else UserRole.DRIVER
            val restored = withContext(Dispatchers.IO) { restoreDeviceSession(saved.first, savedRole) }
            if (restored.success && restored.refreshToken.isNotBlank()) {
                role = savedRole; userLoggedIn = true; userDisplayName = restored.displayName
                userStatus = restored.status; userAccessToken = restored.accessToken; profilePhotoUrl = restored.photoUrl
                DeviceSessionStore.save(context, restored.refreshToken, savedRole.name)
                message = "نشست امن قبلی بازیابی شد."
            } else { DeviceSessionStore.clear(context); message = "نشست قبلی منقضی شده است؛ ورود مجدد نیازمند احراز شماره است." }
        }
    }

    // Refresh device sessions before the normal one-hour access-token expiry.
    LaunchedEffect(userLoggedIn) {
        if (userLoggedIn) {
            while (true) {
                delay(40 * 60 * 1000L)
                val saved = DeviceSessionStore.load(context)
                if (saved == null) {
                    userLoggedIn = false
                    userAccessToken = ""
                    message = "نشست امن پیدا نشد؛ دوباره وارد شوید."
                    break
                }
                val savedRole = if (saved.second == "COMPANY") UserRole.COMPANY else UserRole.DRIVER
                val refreshed = withContext(Dispatchers.IO) { restoreDeviceSession(saved.first, savedRole) }
                if (refreshed.success && refreshed.accessToken.isNotBlank() && refreshed.refreshToken.isNotBlank()) {
                    userAccessToken = refreshed.accessToken
                    userStatus = refreshed.status
                    userDisplayName = refreshed.displayName
                    profilePhotoUrl = refreshed.photoUrl
                    DeviceSessionStore.save(context, refreshed.refreshToken, savedRole.name)
                } else {
                    DeviceSessionStore.clear(context)
                    userLoggedIn = false
                    userAccessToken = ""
                    message = "نشست منقضی شده یا حساب غیرفعال شده است؛ دوباره وارد شوید."
                    break
                }
            }
        }
    }


    if (forceLogoutTarget != null) {
        val target = forceLogoutTarget!!
        AlertDialog(
            onDismissRequest = { forceLogoutTarget = null },
            title = { Text("خروج اجباری از همه دستگاه‌ها") },
            text = { Text("آیا می‌خواهید نشست‌های فعال «" + target.title + "» در همه دستگاه‌ها باطل شود؟") },
            confirmButton = {
                TextButton(enabled = !adminManageBusy, onClick = {
                    adminManageBusy = true
                    scope.launch {
                        adminActionMessage = withContext(Dispatchers.IO) { forceLogoutMember(adminAccessToken, target.id) }
                        adminManageBusy = false
                        forceLogoutTarget = null
                    }
                }) { Text("تأیید خروج اجباری") }
            },
            dismissButton = { TextButton(onClick = { forceLogoutTarget = null }) { Text("انصراف") } }
        )
    }

    if (showCommunication && ((userLoggedIn && userStatus == "approved") || (adminLoggedIn && adminRole == "super_admin"))) {
        CommunicationScreen(
            accessToken = if (adminLoggedIn) adminAccessToken else userAccessToken,
            isSuperAdmin = adminLoggedIn && adminRole == "super_admin"
        ) { showCommunication = false }
    }
    if (showChatRooms && ((userLoggedIn && userStatus == "approved") || adminLoggedIn)) {
        ChatRoomScreen(
            accessToken = if (adminLoggedIn) adminAccessToken else userAccessToken,
            role = if (adminLoggedIn) adminRole else role.name
        ) { showChatRooms = false }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("اعلام بار مبل", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(if (adminLoggedIn) "پنل " + when (adminRole) { "super_admin" -> "مدیر ارشد اصلی"; "senior_admin" -> "ادمین ارشد"; else -> "ادمین عادی" } else if (userLoggedIn) "پنل کاربر" else "سامانه اعلام بار", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    if (userLoggedIn || adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("پروفایل من") },
                            selected = showProfile,
                            onClick = { showProfile = true; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if ((userLoggedIn && userStatus == "approved") || (adminLoggedIn && adminRole == "super_admin")) {
                        NavigationDrawerItem(
                            label = { Text("پیام‌های بار") },
                            selected = showCommunication,
                            onClick = { showCommunication = true; showChatRooms = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if ((userLoggedIn && userStatus == "approved") || adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("اتاق عمومی و گفتگوها") },
                            selected = showChatRooms,
                            onClick = { showChatRooms = true; showCommunication = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("بخش‌های مدیریتی", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("اعلام بار") },
                            selected = selectedPanel == "مدیریت بارها",
                            onClick = {
                                selectedPanel = "مدیریت بارها"
                                adminPanelData = ""
                                adminActionMessage = ""
                                scope.launch {
                                    drawerState.close()
                                    adminPanelLoading = true
                                    adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, "مدیریت بارها") }
                                    adminPanelLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (userLoggedIn && role == UserRole.COMPANY && userStatus == "approved") {
                        NavigationDrawerItem(
                            label = { Text("اعلام بار") },
                            selected = false,
                            onClick = { scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (adminLoggedIn) {
                        adminPanels.filter { it != "مدیریت بارها" && (adminRole == "super_admin" || (it != "مدیریت مدیران" && it != "تنظیمات کل برنامه")) }.forEach { title ->
                            NavigationDrawerItem(
                                label = { Text(title) },
                                selected = selectedPanel == title,
                                onClick = {
                                    selectedPanel = title
                                    adminPanelData = ""
                                    adminRecords = emptyList()
                                    adminActionMessage = ""
                                    scope.launch {
                                        drawerState.close()
                                        adminPanelLoading = true
                                        adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, title) }
                                        if (title == "مدیریت رانندگان" || title == "مدیریت شرکت‌ها" || title == "مدیریت مدارک") {
                                            adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, title) }
                                        }
                                        if (title == "مدیریت مدیران") {
                                            managedAdmins = withContext(Dispatchers.IO) { fetchAdminUsers(adminAccessToken) }
                                            adminSearchResults = emptyList()
                                            adminSearchQuery = ""
                                        }
                                        adminPanelLoading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Text(if (userLoggedIn) "بخش‌های حساب شما از این منو در دسترس خواهند بود." else "برای ورود به بخش‌های برنامه، ابتدا وارد حساب شوید.", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("تنظیمات برنامه") },
                        selected = showSettings,
                        onClick = {
                            showSettings = true
                            showProfile = false
                            selectedPanel = null
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                    Text("زمان سامانه", style = MaterialTheme.typography.labelMedium)
                    Text(tehranClockText, style = MaterialTheme.typography.bodyMedium)
                    Text(if (clockUsesServerTime) "همگام با زمان سرور" else "زمان دستگاه؛ سرور در دسترس نیست", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch { drawerState.close() } }, modifier = Modifier.fillMaxWidth()) { Text("بستن منو") }
                }
            }
        }
    ) {
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("اعلام بار مبل", style = MaterialTheme.typography.titleMedium)
                            Text(tehranClockText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "باز کردن منو")
                        }
                    },
                    actions = {
                        Text(if (clockUsesServerTime) "سرور" else "دستگاه", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(88.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = "نشان اعلام بار مبل",
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
                Text("اعلام بار مبل", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
                Text("سامانه ارتباط شرکت‌های باربری و رانندگان", textAlign = TextAlign.Center)
                if (showSettings) {
                    HorizontalDivider()
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("تنظیمات برنامه", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text("پوسته و رنگ‌بندی", style = MaterialTheme.typography.titleMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("روشن", "سبز", "آبی", "تیره", "خودکار").forEachIndexed { index, label ->
                                    FilterChip(
                                        selected = palette == index,
                                        onClick = {
                                            val next = index
                                            getSharedPreferences("elambar_ui", MODE_PRIVATE).edit().putInt("palette", next).apply()
                                            onNextPalette()
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            HorizontalDivider()
                            Text("اعلان و صدا", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (soundEnabled) "صدای اعلان روشن" else "صدای اعلان خاموش")
                                Switch(
                                    checked = soundEnabled,
                                    onCheckedChange = { enabled ->
                                        soundEnabled = enabled
                                        context.getSharedPreferences("elambar_ui", Context.MODE_PRIVATE).edit().putBoolean("sound", enabled).apply()
                                    }
                                )
                            }
                            HorizontalDivider()
                            Text("اندازه نوشته‌ها", style = MaterialTheme.typography.titleMedium)
                            listOf("کوچک" to 0.9f, "متوسط" to 1f, "بزرگ" to 1.15f, "خیلی بزرگ" to 1.3f).forEach { (label, scale) ->
                                FilterChip(
                                    selected = kotlin.math.abs(textScale - scale) < 0.02f,
                                    onClick = {
                                        textScale = scale
                                        context.getSharedPreferences("elambar_ui", Context.MODE_PRIVATE).edit().putFloat("text_scale", scale).apply()
                                    },
                                    label = { Text(label) }
                                )
                            }
                            HorizontalDivider()
                            Text("زبان: فارسی")
                            Text("تقویم: شمسی")
                            Text("منطقه زمانی: تهران")
                            OutlinedButton(onClick = { showSettings = false }, modifier = Modifier.fillMaxWidth()) {
                                Text("برگشت")
                            }
                        }
                    }
                } else if (showProfile && adminLoggedIn) {
                    HorizontalDivider()
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (adminProfilePhotoUrl.isNotBlank()) {
                                AsyncImage(model = adminProfilePhotoUrl, contentDescription = "عکس پروفایل مدیر", modifier = Modifier.size(112.dp).clip(CircleShape))
                            } else {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text("مدیر", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                }
                            }
                            Text("پروفایل مدیر", style = MaterialTheme.typography.headlineSmall)
                            Text(when (adminRole) { "super_admin" -> "مدیر ارشد اصلی"; "senior_admin" -> "ادمین ارشد"; else -> "ادمین عادی" }, style = MaterialTheme.typography.titleMedium)
                            if (email.isNotBlank()) Text(email, style = MaterialTheme.typography.bodyMedium)
                            Button(enabled = !profilePhotoUploading, onClick = { photoPicker.launch("image/*") }) {
                                Text(if (profilePhotoUploading) "در حال بارگذاری تصویر..." else if (adminProfilePhotoUrl.isBlank()) "انتخاب عکس از گالری" else "تغییر عکس پروفایل")
                            }
                            if (profilePhotoMessage.isNotBlank()) Text(profilePhotoMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Text("عکس پروفایل برای نمایش حساب مدیر است؛ تصویر یا مدرک محرمانه بارگذاری نکنید.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                    OutlinedButton(onClick = { showProfile = false }, modifier = Modifier.fillMaxWidth()) { Text("بازگشت به پنل مدیریت") }
                } else if (adminLoggedIn) {
                    HorizontalDivider()
                    Text("پنل مدیریت", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Text("خوش آمدید؛ سطح دسترسی: " + when (adminRole) { "super_admin" -> "مدیر ارشد اصلی"; "senior_admin" -> "ادمین ارشد"; else -> "ادمین عادی" }, textAlign = TextAlign.Center)
                    if (selectedPanel == null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedPanel = "مدیریت بارها"
                                adminPanelLoading = true
                                scope.launch {
                                    adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, "مدیریت بارها") }
                                    adminPanelLoading = false
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("اعلام بار و مدیریت بارها", style = MaterialTheme.typography.titleLarge)
                                Text("ثبت بار جدید و مدیریت بارهای منتشرشده", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        TextButton(onClick = { selectedPanel = null }, modifier = Modifier.align(Alignment.Start)) {
                            Text("بازگشت به پنل اصلی")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selectedPanel ?: "", fontSize = 21.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(enabled = !adminPanelLoading, onClick = {
                                val panel = selectedPanel ?: return@TextButton
                                adminPanelLoading = true
                                scope.launch {
                                    adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, panel) }
                                    if (panel == "مدیریت رانندگان" || panel == "مدیریت شرکت‌ها" || panel == "مدیریت مدارک") {
                                        adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, panel) }
                                    }
                                    if (panel == "مدیریت مدیران") {
                                        managedAdmins = withContext(Dispatchers.IO) { fetchAdminUsers(adminAccessToken) }
                                    }
                                    adminPanelLoading = false
                                }
                            }) { Text("بروزرسانی") }
                        }
                        if (selectedPanel != null) {
                            if (adminPanelLoading) {
                                CircularProgressIndicator()
                                Text("در حال دریافت اطلاعات از سامانه...")
                            } else {
                                Text(adminPanelData.ifBlank { "اطلاعاتی برای نمایش دریافت نشد." }, textAlign = TextAlign.Start)
                                if (selectedPanel == "مدیریت مدیران" && adminRole == "super_admin") {
                                    Text("افزودن مدیر جدید", style = MaterialTheme.typography.titleMedium)
                                    Text("ابتدا کاربر باید با ایمیل خود در برنامه ثبت‌نام کرده باشد. سپس می‌توانید سطح دسترسی او را تعیین کنید.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(
                                        value = adminSearchQuery,
                                        onValueChange = { adminSearchQuery = it.trim().take(254) },
                                        label = { Text("ایمیل کاربر برای جستجو") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text("سطح دسترسی حساب جدید")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = adminNewRole == "admin", onClick = { adminNewRole = "admin" }, label = { Text("ادمین عادی") })
                                        FilterChip(selected = adminNewRole == "senior_admin", onClick = { adminNewRole = "senior_admin" }, label = { Text("ادمین ارشد") })
                                    }
                                    Button(
                                        enabled = !adminManageBusy && adminSearchQuery.trim().length >= 3,
                                        onClick = {
                                            adminManageBusy = true
                                            adminActionMessage = "در حال جستجوی حساب..."
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { searchAdminCandidates(adminAccessToken, adminSearchQuery.trim()) }
                                                adminSearchResults = result.second
                                                adminActionMessage = result.first
                                                adminManageBusy = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("جستجوی کاربر") }
                                    adminSearchResults.forEach { candidate ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(candidate.email, style = MaterialTheme.typography.titleSmall)
                                                Text("این حساب هنوز مدیر نیست.")
                                                Button(
                                                    enabled = !adminManageBusy,
                                                    onClick = {
                                                        adminManageBusy = true
                                                        scope.launch {
                                                            adminActionMessage = withContext(Dispatchers.IO) { addAdminUser(adminAccessToken, candidate.userId, adminNewRole) }
                                                            if (adminActionMessage.startsWith("انجام شد")) {
                                                                managedAdmins = withContext(Dispatchers.IO) { fetchAdminUsers(adminAccessToken) }
                                                                adminSearchResults = emptyList()
                                                            }
                                                            adminManageBusy = false
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text(if (adminNewRole == "admin") "افزودن به‌عنوان ادمین عادی" else "افزودن به‌عنوان ادمین ارشد") }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                    Text("فهرست مدیران سامانه", style = MaterialTheme.typography.titleMedium)
                                    if (managedAdmins.isEmpty()) {
                                        Text("فهرست مدیران دریافت نشد یا هنوز مدیری ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    managedAdmins.forEach { account ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(account.email.ifBlank { account.userId }, style = MaterialTheme.typography.titleSmall)
                                                Text("سطح فعلی: " + when (account.role) {
                                                    "super_admin" -> "مدیر ارشد اصلی"
                                                    "senior_admin" -> "ادمین ارشد"
                                                    else -> "ادمین عادی"
                                                })
                                                if (account.role == "super_admin") {
                                                    Text("حساب اصلی قابل تغییر نیست.", color = MaterialTheme.colorScheme.primary)
                                                } else {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(
                                                            enabled = !adminManageBusy && account.role != "admin",
                                                            onClick = {
                                                                adminManageBusy = true
                                                                scope.launch {
                                                                    adminActionMessage = withContext(Dispatchers.IO) { setAdminUserRole(adminAccessToken, account.userId, "admin") }
                                                                    managedAdmins = withContext(Dispatchers.IO) { fetchAdminUsers(adminAccessToken) }
                                                                    adminManageBusy = false
                                                                }
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("ادمین عادی") }
                                                        OutlinedButton(
                                                            enabled = !adminManageBusy && account.role != "senior_admin",
                                                            onClick = {
                                                                adminManageBusy = true
                                                                scope.launch {
                                                                    adminActionMessage = withContext(Dispatchers.IO) { setAdminUserRole(adminAccessToken, account.userId, "senior_admin") }
                                                                    managedAdmins = withContext(Dispatchers.IO) { fetchAdminUsers(adminAccessToken) }
                                                                    adminManageBusy = false
                                                                }
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("ادمین ارشد") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (adminActionMessage.isNotBlank()) Text(adminActionMessage, color = MaterialTheme.colorScheme.secondary)
                                    if (adminRole == "super_admin") {
                                        AdminPermissionManagement(accessToken = adminAccessToken)
                                    }
                                    HorizontalDivider()
                                }
                                if (selectedPanel == "تنظیمات کل برنامه" && adminRole == "super_admin") {
                                    Text("این بخش مربوط به تنظیمات سراسری سامانه است و تغییرات آن باید برای همه شرکت‌ها، رانندگان و مدیران اعمال شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (selectedPanel == "مدیریت بارها") {
                                    Text("اعلام بار به نام مدیریت سامانه", style = MaterialTheme.typography.titleMedium)
                                    Text("ثبت این بار با مجوز مدیر ارشد و بدون انتساب به شرکت انجام می‌شود.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(loadDestination, { loadDestination = it }, label = { Text("مقصد از تهران") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("نوع بار")
                                    listOf("مبل", "اثاث منزل", "سرویس خواب", "سایر موارد").forEach { cargo ->
                                        FilterChip(selected = loadCargoType == cargo, onClick = { loadCargoType = cargo }, label = { Text(cargo) })
                                    }
                                    Text("نوع خودرو")
                                    listOf("وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون دربستی کار","خاور مبلی پتو دار").forEach { v ->
                                        FilterChip(selected = loadVehicleType == v, onClick = { loadVehicleType = v }, label = { Text(v) })
                                    }
                                    Text("نوع بارگیری")
                                    listOf("دربست","روباری","نیم بار").forEach { v ->
                                        FilterChip(selected = loadLoadingType == v, onClick = { loadLoadingType = v }, label = { Text(v) })
                                    }
                                    Text("نحوه تعیین کرایه")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = loadPriceMode == "fixed", onClick = { loadPriceMode = "fixed" }, label = { Text("مبلغ مشخص") })
                                        FilterChip(selected = loadPriceMode == "negotiable", onClick = { loadPriceMode = "negotiable" }, label = { Text("توافقی") })
                                    }
                                    if (loadPriceMode == "fixed") OutlinedTextField(loadFreightAmount, { loadFreightAmount = it.filter(Char::isDigit).take(18) }, label = { Text("مبلغ کرایه (تومان)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(loadDescription, { loadDescription = it.take(3000) }, label = { Text("توضیحات (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = {
                                        if (loadDestination.trim().length < 2 || loadCargoType.trim().length < 2 || (loadPriceMode == "fixed" && (loadFreightAmount.isBlank() || loadFreightAmount.toLongOrNull() == null || loadFreightAmount.toLong() <= 0))) {
                                            adminActionMessage = "مقصد، نوع بار و کرایه را کامل کنید."
                                        } else {
                                            loadDrafts.add(LoadDraft(loadDestination.trim(), loadCargoType.trim(), loadVehicleType, loadLoadingType, loadPriceMode, if (loadPriceMode == "fixed") loadFreightAmount else "0", loadDescription.trim()))
                                            loadDestination = ""
                                            loadDescription = ""
                                            adminActionMessage = "بار به فهرست انتشار مدیریت اضافه شد."
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("افزودن بار به فهرست") }
                                    if (loadDrafts.isNotEmpty()) {
                                        Text("بارهای آماده انتشار: ${loadDrafts.size}", style = MaterialTheme.typography.titleSmall)
                                        loadDrafts.forEachIndexed { index, draft ->
                                            Card(Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text("${index + 1}. تهران ← ${draft.destination}")
                                                    Text("${draft.cargoType} | ${draft.vehicleType} | ${draft.loadingType}")
                                                    Text(if (draft.priceMode == "negotiable") "کرایه: توافقی" else "کرایه: ${draft.freightAmount} تومان")
                                                    TextButton(onClick = { loadDrafts.removeAt(index) }) { Text("حذف از فهرست") }
                                                }
                                            }
                                        }
                                        Button(enabled = !loadPublishing, onClick = {
                                            loadPublishing = true
                                            adminActionMessage = "در حال ثبت بارهای مدیریت..."
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { publishLoadBatch(adminAccessToken, loadDrafts.toList(), "admin_system") }
                                                loadPublishing = false
                                                adminActionMessage = result
                                                if (result.startsWith("ثبت شد")) {
                                                    loadDrafts.clear()
                                                    adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, "مدیریت بارها") }
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth()) { Text(if (loadPublishing) "در حال ثبت..." else "انتشار بارهای مدیریت سامانه") }
                                    }
                                    if (adminActionMessage.isNotBlank()) Text(adminActionMessage, color = MaterialTheme.colorScheme.secondary)
                                    HorizontalDivider()
                                }
                                if (selectedPanel == "مدیریت رانندگان" || selectedPanel == "مدیریت شرکت‌ها" || selectedPanel == "مدیریت مدارک") {
                                    if (selectedPanel == "مدیریت رانندگان" || selectedPanel == "مدیریت شرکت‌ها") {
                                        OutlinedTextField(value = adminStatusReason, onValueChange = { adminStatusReason = it.take(500) }, label = { Text("دلیل رد یا مسدودسازی (برای مسدودسازی الزامی است)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                    }
                                    adminRecords.forEach { record ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(record.title, style = MaterialTheme.typography.titleSmall)
                                                Text(record.detail, style = MaterialTheme.typography.bodySmall)
                                                Text("وضعیت: ${record.status}")
                                                if (adminRole == "super_admin" && selectedPanel != "مدیریت مدارک") {
                                                    OutlinedButton(
                                                        enabled = !adminManageBusy && record.id.isNotBlank(),
                                                        onClick = { forceLogoutTarget = record },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) { Text("خروج اجباری از همه دستگاه‌ها") }
                                                }
                                                if (selectedPanel == "مدیریت مدارک" && record.mediaUrl.isNotBlank()) {
                                                    val lowerUrl = record.mediaUrl.substringBefore("?").lowercase()
                                                    if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".webp")) {
                                                        AsyncImage(model = record.mediaUrl, contentDescription = "مدرک راننده", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
                                                    } else {
                                                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(record.mediaUrl) } }) { Text("بازکردن فایل مدرک") }
                                                    }
                                                }
                                                if (selectedPanel == "مدیریت مدارک") {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        if (record.status != "approved") {
                                                            Button(onClick = {
                                                                adminPanelLoading = true
                                                                scope.launch {
                                                                    adminActionMessage = withContext(Dispatchers.IO) { updateDriverDocumentStatus(adminAccessToken, record.id, "approved") }
                                                                    adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, "مدیریت مدارک") }
                                                                    adminPanelLoading = false
                                                                }
                                                            }) { Text("تأیید مدرک") }
                                                        }
                                                        if (record.status != "rejected") {
                                                            OutlinedButton(onClick = {
                                                                adminPanelLoading = true
                                                                scope.launch {
                                                                    adminActionMessage = withContext(Dispatchers.IO) { updateDriverDocumentStatus(adminAccessToken, record.id, "rejected") }
                                                                    adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, "مدیریت مدارک") }
                                                                    adminPanelLoading = false
                                                                }
                                                            }) { Text("رد مدرک") }
                                                        }
                                                    }
                                                } else {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (record.status != "approved") {
                                                        Button(onClick = {
                                                            adminPanelLoading = true
                                                            scope.launch {
                                                                adminActionMessage = withContext(Dispatchers.IO) { updateMemberStatus(adminAccessToken, selectedPanel ?: "", record.id, "approved", adminStatusReason) }
                                                                adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, selectedPanel ?: "") }
                                                                adminPanelLoading = false
                                                            }
                                                        }) { Text("تأیید حساب") }
                                                    if (record.status != "rejected" && record.status != "blocked") {
                                                        OutlinedButton(onClick = {
                                                            adminPanelLoading = true
                                                            scope.launch {
                                                                adminActionMessage = withContext(Dispatchers.IO) { updateMemberStatus(adminAccessToken, selectedPanel ?: "", record.id, "rejected", adminStatusReason) }
                                                                adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, selectedPanel ?: "") }
                                                                adminPanelLoading = false
                                                            }
                                                        }) { Text("رد درخواست") }
                                                    }
                                                    }
                                                    if (record.status != "blocked") {
                                                        OutlinedButton(onClick = {
                                                            adminPanelLoading = true
                                                            scope.launch {
                                                                adminActionMessage = withContext(Dispatchers.IO) { updateMemberStatus(adminAccessToken, selectedPanel ?: "", record.id, "blocked", adminStatusReason) }
                                                                adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, selectedPanel ?: "") }
                                                                adminPanelLoading = false
                                                            }
                                                        }) { Text("مسدودسازی") }
                                                    }
                                                    if (record.status == "blocked") {
                                                        Button(onClick = {
                                                            adminPanelLoading = true
                                                            scope.launch {
                                                                adminActionMessage = withContext(Dispatchers.IO) { updateMemberStatus(adminAccessToken, selectedPanel ?: "", record.id, "approved", adminStatusReason) }
                                                                adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, selectedPanel ?: "") }
                                                                adminPanelLoading = false
                                                            }
                                                        }) { Text("رفع مسدودی") }
                                                    }
                                                }
                                                }
                                            }
                                        }
                                    }
                                    if (adminRecords.isEmpty()) Text(if (selectedPanel == "مدیریت مدارک") "مدرکی ثبت نشده یا مجوز مشاهده ندارید." else "فهرست حساب‌ها خالی است یا دسترسی مشاهده ندارید.")
                                    if (adminActionMessage.isNotBlank()) Text(adminActionMessage, color = MaterialTheme.colorScheme.secondary)
                                }
                                OutlinedButton(onClick = {
                                    adminPanelLoading = true
                                    scope.launch {
                                        val panel = selectedPanel ?: ""
                                        adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, panel) }
                                        if (panel == "مدیریت رانندگان" || panel == "مدیریت شرکت‌ها" || panel == "مدیریت مدارک") {
                                            adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, panel) }
                                        }
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
                            adminProfilePhotoUrl = ""
                            adminAccessToken = ""
                            showProfile = false
                            email = ""
                            password = ""
                            message = "از حساب مدیر ارشد خارج شدید."
                            role = UserRole.ADMIN
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("خروج از حساب") }
                } else if (userLoggedIn && showProfile) {
                    HorizontalDivider()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (profilePhotoUrl.isNotBlank()) {
                                AsyncImage(
                                    model = profilePhotoUrl,
                                    contentDescription = "عکس پروفایل",
                                    modifier = Modifier.size(116.dp).clip(CircleShape)
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(116.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("عکس شما", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            Text(userDisplayName.ifBlank { "کاربر اعلام بار مبل" }, style = MaterialTheme.typography.headlineSmall)
                            Text(if (role == UserRole.DRIVER) "پروفایل راننده" else "پروفایل شرکت باربری", style = MaterialTheme.typography.titleMedium)
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp
                            ) {
                                Text(
                                    when (userStatus) {
                                        "approved" -> "✓ حساب تأیید شده"
                                        "pending" -> "در انتظار تأیید مدیر"
                                        else -> "وضعیت حساب: $userStatus"
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("تصویر پروفایل", style = MaterialTheme.typography.titleMedium)
                            Text("عکس دلخواه را از گالری انتخاب کنید. این تصویر برای نمایش پروفایل قابل مشاهده است؛ عکس یا مدرک محرمانه بارگذاری نکنید.", style = MaterialTheme.typography.bodyMedium)
                            Button(
                                enabled = !profilePhotoUploading,
                                onClick = { photoPicker.launch("image/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (profilePhotoUploading) "در حال بارگذاری تصویر..." else if (profilePhotoUrl.isBlank()) "انتخاب عکس از گالری" else "تغییر عکس پروفایل")
                            }
                            if (profilePhotoMessage.isNotBlank()) {
                                Text(profilePhotoMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("اطلاعات حساب", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("نوع حساب")
                                Text(if (role == UserRole.DRIVER) "راننده" else "شرکت باربری")
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("وضعیت")
                                Text(if (userStatus == "approved") "تأیید شده" else "در انتظار بررسی")
                            }
                            if (role == UserRole.DRIVER) {
                                HorizontalDivider()
                                Text("نوع خودرو: $vehicleType")
                                if (primaryDestination.isNotBlank()) Text("مقصد اصلی: $primaryDestination")
                            } else if (companyName.isNotBlank()) {
                                HorizontalDivider()
                                Text("نام شرکت: $companyName")
                            }
                            Text("نشست ورود با ذخیره‌سازی امن روی دستگاه مدیریت می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        onClick = { showProfile = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("بازگشت به پنل اصلی") }
                } else if (userLoggedIn) {
                    HorizontalDivider()
                    Text(if (role == UserRole.DRIVER) "پنل راننده" else "پنل شرکت باربری", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Text("خوش آمدید، $userDisplayName", textAlign = TextAlign.Center)
                    if (profilePhotoUrl.isNotBlank()) {
                        AsyncImage(model = profilePhotoUrl, contentDescription = "عکس پروفایل", modifier = Modifier.size(104.dp).clip(CircleShape))
                    } else {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(104.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("عکس پروفایل") }
                        }
                    }
                    OutlinedButton(enabled = !profilePhotoUploading, onClick = { photoPicker.launch("image/*") }) {
                        Text(if (profilePhotoUploading) "در حال بارگذاری..." else "انتخاب عکس پروفایل")
                    }
                    if (profilePhotoMessage.isNotBlank()) Text(profilePhotoMessage, style = MaterialTheme.typography.bodySmall)
                    if (role == UserRole.DRIVER) {
                        HorizontalDivider()
                        Text("مدارک راننده", style = MaterialTheme.typography.titleMedium)
                        listOf("کارت هوشمند", "کارت ملی", "گواهینامه", "کارت خودرو").forEach { doc ->
                            FilterChip(selected = selectedDriverDocType == doc, onClick = { selectedDriverDocType = doc }, label = { Text(doc) })
                        }
                        OutlinedButton(enabled = !driverDocUploading, onClick = { documentPicker.launch("*/*") }) {
                            Text(if (driverDocUploading) "در حال بارگذاری..." else "انتخاب و ارسال مدرک")
                        }
                        if (driverDocMessage.isNotBlank()) Text(driverDocMessage, style = MaterialTheme.typography.bodySmall)
                    }
                    when (userStatus) {
                        "pending" -> Text("درخواست شما ثبت شده و در انتظار تأیید مدیر ارشد است. تا زمان تأیید، دسترسی عملیاتی فعال نیست.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                        "approved" -> {
                            Text("حساب شما تأیید شده است.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                            if (role == UserRole.COMPANY) {
                                Text("اعلام بار | ثبت گروهی از مبدأ تهران", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(loadDestination, { loadDestination = it }, label = { Text("مقصد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text("نوع بار")
                                    listOf("مبل", "اثاث منزل", "سرویس خواب", "سایر موارد").forEach { cargo ->
                                        FilterChip(selected = loadCargoType == cargo, onClick = { loadCargoType = cargo }, label = { Text(cargo) })
                                    }
                                Text("نوع خودرو")
                                listOf("وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون دربستی کار","خاور مبلی پتو دار").forEach { v ->
                                    FilterChip(selected = loadVehicleType == v, onClick = { loadVehicleType = v }, label = { Text(v) })
                                }
                                Text("نوع بارگیری")
                                listOf("دربست","روباری","نیم بار").forEach { v ->
                                    FilterChip(selected = loadLoadingType == v, onClick = { loadLoadingType = v }, label = { Text(v) })
                                }
                                Text("نحوه تعیین کرایه")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = loadPriceMode == "fixed", onClick = { loadPriceMode = "fixed" }, label = { Text("مبلغ مشخص") })
                                        FilterChip(selected = loadPriceMode == "negotiable", onClick = { loadPriceMode = "negotiable" }, label = { Text("توافقی") })
                                    }
                                    if (loadPriceMode == "fixed") OutlinedTextField(loadFreightAmount, { loadFreightAmount = it.filter(Char::isDigit).take(18) }, label = { Text("مبلغ کرایه (تومان)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(loadDescription, { loadDescription = it.take(3000) }, label = { Text("توضیحات (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = {
                                    if (loadDestination.trim().length < 2 || loadCargoType.trim().length < 2 || (loadPriceMode == "fixed" && (loadFreightAmount.isBlank() || loadFreightAmount.toLongOrNull() == null || loadFreightAmount.toLong() <= 0))) {
                                        loadActionMessage = "مقصد، نوع بار و کرایه را کامل کنید."
                                    } else {
                                        loadDrafts.add(LoadDraft(loadDestination.trim(), loadCargoType.trim(), loadVehicleType, loadLoadingType, loadPriceMode, if (loadPriceMode == "fixed") loadFreightAmount else "0", loadDescription.trim()))
                                        loadDestination = ""
                                        loadDescription = ""
                                        loadActionMessage = "بار به فهرست گروهی اضافه شد."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("افزودن بار به فهرست") }
                                if (loadDrafts.isNotEmpty()) {
                                    Text("بارهای آماده انتشار: ${loadDrafts.size}", style = MaterialTheme.typography.titleSmall)
                                    loadDrafts.forEachIndexed { index, draft ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("${index + 1}. تهران ← ${draft.destination}")
                                                Text("${draft.cargoType} | ${draft.vehicleType} | ${draft.loadingType}")
                                                Text(if (draft.priceMode == "negotiable") "کرایه: توافقی" else "کرایه: ${draft.freightAmount} تومان")
                                                TextButton(onClick = { loadDrafts.removeAt(index) }) { Text("حذف از فهرست") }
                                            }
                                        }
                                    }
                                    Button(enabled = !loadPublishing, onClick = {
                                        loadPublishing = true
                                        loadActionMessage = "در حال ثبت بارها..."
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { publishLoadBatch(userAccessToken, loadDrafts.toList()) }
                                            loadPublishing = false
                                            loadActionMessage = result
                                            if (result.startsWith("ثبت شد")) loadDrafts.clear()
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text(if (loadPublishing) "در حال ثبت..." else "انتشار همه بارهای فهرست") }
                                }
                                if (loadActionMessage.isNotBlank()) Text(loadActionMessage, color = MaterialTheme.colorScheme.secondary)
                                HorizontalDivider()
                                Text("بارهای ثبت‌شده شرکت", style = MaterialTheme.typography.titleMedium)
                                CompanyLoadManagement(accessToken = userAccessToken)
                                HorizontalDivider()
                                Text("جست‌وجوی راننده مناسب", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(driverSearchDestination, { driverSearchDestination = it }, label = { Text("مقصد موردنظر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                listOf("وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون دربستی کار","خاور مبلی پتو دار").forEach { v ->
                                    FilterChip(selected = driverSearchVehicle == v, onClick = { driverSearchVehicle = v }, label = { Text(v) })
                                }
                                OutlinedButton(enabled = !driverSearchLoading && driverSearchDestination.trim().length >= 2, onClick = {
                                    driverSearchLoading = true
                                    scope.launch {
                                        driverSearchResults = withContext(Dispatchers.IO) { fetchCompanyDrivers(userAccessToken, driverSearchVehicle, driverSearchDestination.trim()) }
                                        driverSearchLoading = false
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (driverSearchLoading) "در حال جست‌وجو..." else "جست‌وجوی رانندگان تأییدشده")
                                }
                                Text(driverSearchResults, textAlign = TextAlign.Start)
                            } else {
                                Text("بارهای متناسب با نوع خودرو و مقصدهای ثبت‌شده شما", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(enabled = !matchedLoadsLoading, onClick = {
                                    matchedLoadsLoading = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { fetchMatchedLoadsDetailed(userAccessToken) }
                                        matchedLoadsText = result.first
                                        matchedLoads = result.second
                                        matchedLoadsLoading = false
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (matchedLoadsLoading) "در حال بروزرسانی..." else "بروزرسانی بارهای قابل دریافت") }
                                Text(matchedLoadsText, textAlign = TextAlign.Start)
                                matchedLoads.forEach { load ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text("تهران ← ${load.destination}", style = MaterialTheme.typography.titleMedium)
                                            Text("${load.cargoType} | ${load.vehicleType} | ${load.loadingType}")
                                            Text(if (load.freightAmount == "0") "کرایه: توافقی / اعلام‌نشده" else "کرایه: ${load.freightAmount} تومان")
                                            if (load.description.isNotBlank()) Text(load.description, style = MaterialTheme.typography.bodySmall)
                                            Button(enabled = acceptingLoadId.isBlank(), onClick = {
                                                acceptingLoadId = load.id
                                                scope.launch {
                                                    val accepted = withContext(Dispatchers.IO) { acceptDriverLoad(userAccessToken, load.id) }
                                                    val refreshed = withContext(Dispatchers.IO) { fetchMatchedLoadsDetailed(userAccessToken) }
                                                    matchedLoadsText = accepted + "\n" + refreshed.first
                                                    matchedLoads = refreshed.second
                                                    acceptingLoadId = ""
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (acceptingLoadId == load.id) "در حال ثبت..." else "گرفتن این بار")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> Text("وضعیت حساب: $userStatus", textAlign = TextAlign.Center)
                    }
                    Text("ورود شما روی این دستگاه محفوظ می‌ماند؛ خروج فقط با دستور مدیر ارشد اصلی یا غیرفعال‌شدن حساب انجام می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = role == UserRole.DRIVER, onClick = { role = UserRole.DRIVER; register = true; message = "ثبت‌نام راننده" }, label = { Text("راننده") })
                        FilterChip(selected = role == UserRole.COMPANY, onClick = { role = UserRole.COMPANY; register = true; message = "ثبت‌نام شرکت" }, label = { Text("شرکت") })
                    }
                    FilterChip(
                        selected = role == UserRole.ADMIN,
                        onClick = { role = UserRole.ADMIN; register = false; message = "ورود مدیران با ایمیل و رمز عبور" },
                        label = { Text("ورود مدیر") }
                    )

                    if (role != UserRole.ADMIN) {
                        Text(
                            if (register) "نام کاربری را با حروف و اعداد انگلیسی بسازید؛ برای ورودهای بعدی همین نام کاربری و رمز را استفاده می‌کنید."
                            else "با همان نام کاربری و رمزی وارد شوید که هنگام ثبت‌نام تعیین کرده‌اید.",
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text("فقط حساب‌های مدیریتی فعال در سامانه اجازه ورود به پنل مدیریت را دارند.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                    }

                    if (role != UserRole.ADMIN) {
                        TextButton(onClick = {
                            register = !register
                            message = if (register) "فرم ثبت‌نام را تکمیل کنید." else "نام کاربری و رمز عبور را وارد کنید."
                        }) {
                            Text(if (register) "قبلاً حساب دارید؟ ورود" else "حساب ندارید؟ ثبت‌نام")
                        }
                    }

                    if (!register && role != UserRole.ADMIN) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.trim() },
                            label = { Text("نام کاربری ثبت‌شده") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrect = false),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("رمز عبور") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "مخفی کردن رمز عبور" else "نمایش رمز عبور"
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (register && role != UserRole.ADMIN) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.trim().lowercase().take(32) },
                            label = { Text("نام کاربری (حداقل ۳ نویسه)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrect = false),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("رمز عبور (حداقل ۸ نویسه)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "مخفی کردن رمز عبور" else "نمایش رمز عبور"
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(firstName, { firstName = it }, label = { Text("نام") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(lastName, { lastName = it }, label = { Text("نام خانوادگی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mobile, { mobile = it }, label = { Text("شماره موبایل (09...)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                        if (role == UserRole.COMPANY) {
                            OutlinedTextField(companyName, { companyName = it }, label = { Text("نام شرکت باربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(activityCity, { activityCity = it }, label = { Text("شهر محل فعالیت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(officeAddress, { officeAddress = it }, label = { Text("نشانی دفتر (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(nationalId, { nationalId = it.filter { c -> c.isDigit() }.take(10) }, label = { Text("کد ملی ۱۰ رقمی") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(smartCardNumber, { smartCardNumber = it }, label = { Text("شماره کارت هوشمند") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("نوع خودرو")
                            listOf("وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون دربستی کار","خاور مبلی پتو دار").forEach { v ->
                                FilterChip(selected = vehicleType == v, onClick = { vehicleType = v }, label = { Text(v) })
                            }
                            OutlinedTextField(licensePlate, { licensePlate = it }, label = { Text("شماره پلاک") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(primaryDestination, { primaryDestination = it }, label = { Text("مقصد اصلی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(destinationsText, { destinationsText = it }, label = { Text("مقصدهای دیگر (با ویرگول جدا کنید)") }, modifier = Modifier.fillMaxWidth())
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
                    }
                    if (role == UserRole.ADMIN) {
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("رمز عبور مدیر ارشد") },
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "مخفی کردن رمز عبور" else "نمایش رمز عبور")
                            } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }

                    Button(
                        enabled = !loading,
                        onClick = {
                            if (role == UserRole.ADMIN) {
                                when {
                                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> message = "ایمیل معتبر وارد کنید."
                                    password.isBlank() -> message = "رمز عبور را وارد کنید."
                                    else -> {
                                        loading = true
                                        message = "در حال بررسی سطح دسترسی مدیر..."
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                authenticateAdmin(email.trim(), password)
                                            }
                                            loading = false
                                            message = result.message
                                            if (result.success) {
                                                adminAccessToken = result.accessToken
                                                adminRole = result.role
                                                adminLoggedIn = true
                                                adminProfilePhotoUrl = withContext(Dispatchers.IO) { fetchAdminProfilePhoto(result.accessToken) }
                                            }
                                        }
                                    }
                                }
                            } else if (register) {
                                val commonInvalid = username.length < 3 || password.length < 8 ||
                                    firstName.isBlank() || lastName.isBlank() ||
                                    !mobile.matches(Regex("^09\\d{9}$"))
                                val typeInvalid = if (role == UserRole.COMPANY) companyName.isBlank() || activityCity.isBlank()
                                    else nationalId.length != 10 || smartCardNumber.isBlank() || licensePlate.isBlank() || primaryDestination.isBlank()
                                if (commonInvalid || typeInvalid) {
                                    message = "نام کاربری، رمز عبور و مشخصات را کامل و صحیح وارد کنید. شماره موبایل باید ۱۱ رقم و با ۰۹ شروع شود."
                                } else {
                                    loading = true
                                    message = "در حال ساخت حساب و بررسی ورود..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            val registration = registerUserAccount(
                                                username, password, role, firstName, lastName, mobile,
                                                companyName, activityCity, officeAddress, nationalId,
                                                smartCardNumber, vehicleType, licensePlate,
                                                primaryDestination, destinationsText
                                            )
                                            if (registration == "OK") {
                                                authenticateUser(username, password, role)
                                            } else {
                                                UserLoginResult(false, registration)
                                            }
                                        }
                                        loading = false
                                        message = result.message
                                        if (result.success && result.accessToken.isNotBlank() && result.refreshToken.isNotBlank()) {
                                            userLoggedIn = true
                                            userDisplayName = result.displayName
                                            userStatus = result.status
                                            userAccessToken = result.accessToken
                                            profilePhotoUrl = result.photoUrl
                                            DeviceSessionStore.save(context, result.refreshToken, role.name)
                                            if (soundEnabled) playAppTone()
                                        }
                                    }
                                }
                            } else {
                                if (username.isBlank() || password.isBlank()) {
                                    message = "نام کاربری و رمز عبور را وارد کنید."
                                } else {
                                    loading = true
                                    message = "در حال بررسی اطلاعات ورود..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            authenticateUser(username, password, role)
                                        }
                                        loading = false
                                        message = result.message
                                        if (result.success && result.accessToken.isNotBlank() && result.refreshToken.isNotBlank()) {
                                            userLoggedIn = true
                                            userDisplayName = result.displayName
                                            userStatus = result.status
                                            userAccessToken = result.accessToken
                                            profilePhotoUrl = result.photoUrl
                                            DeviceSessionStore.save(context, result.refreshToken, role.name)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loading) "لطفاً صبر کنید..." else if (role == UserRole.ADMIN) "ورود مدیر ارشد" else if (register) "ساخت حساب" else "ورود به حساب")
                    }
                    Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                    if (role != UserRole.ADMIN) {
                        HorizontalDivider()
                        Text("ورود واقعی مدیر ارشد از بخش «ورود مدیر ارشد» انجام می‌شود.", fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text("نشست ورود روی همین گوشی ذخیره می‌شود؛ ورود روی گوشی جدید پس از فعال‌سازی پیامک انجام خواهد شد.", fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}



}


private fun fetchSupabaseServerTimeMillis(): Long? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(SUPABASE_URL + "/rest/v1/").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
        }
        connection.connect()
        val dateHeader = connection.getHeaderField("Date") ?: return null
        java.time.ZonedDateTime.parse(dateHeader, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun formatPersianTehranDateTime(epochMillis: Long): String {
    val formatter = android.icu.text.SimpleDateFormat(
        "yyyy/MM/dd  HH:mm:ss",
        android.icu.util.ULocale("fa_IR@calendar=persian")
    )
    formatter.timeZone = android.icu.util.TimeZone.getTimeZone("Asia/Tehran")
    return formatter.format(java.util.Date(epochMillis))
}

private fun uploadDriverDocument(context: Context, uri: Uri, token: String, documentType: String): String {
    var upload: HttpURLConnection? = null
    var insert: HttpURLConnection? = null
    var cleanup: HttpURLConnection? = null
    return try {
        val payload = token.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است."
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val driverId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (driverId.isBlank()) return "شناسه راننده دریافت نشد."
        val mime = context.contentResolver.getType(uri) ?: return "نوع فایل قابل تشخیص نیست."
        val allowed = listOf("application/pdf", "image/jpeg", "image/png", "image/webp")
        if (mime !in allowed) return "فقط PDF، JPG، PNG و WEBP مجاز است."
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن فایل انجام نشد."
        if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return "حجم مدرک باید کمتر از ۱۰ مگابایت باشد."
        val ext = when(mime) { "application/pdf" -> "pdf"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val safeType = documentType.replace(Regex("[^آ-یA-Za-z0-9_-]"), "_")
        val path = driverId + "/" + safeType + "-" + UUID.randomUUID().toString() + "." + ext
        upload = (URL(SUPABASE_URL + "/storage/v1/object/driver-documents/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", mime)
        }
        upload.outputStream.use { it.write(bytes) }
        val upCode = upload.responseCode
        if (upCode !in 200..299) return "بارگذاری مدرک مجاز نشد (کد " + upCode + ")."
        insert = (URL(SUPABASE_URL + "/rest/v1/driver_documents").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "return=minimal")
        }
        val row = JSONObject().put("driver_id", driverId).put("document_type", documentType)
            .put("storage_path", path).put("review_status", "pending")
        insert.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
        val insertCode = insert.responseCode
        if (insertCode !in 200..299) {
            cleanup = (URL(SUPABASE_URL + "/storage/v1/object/driver-documents/" + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"; connectTimeout = 10000; readTimeout = 10000
                setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer " + token)
            }
            runCatching { cleanup.responseCode }
            return "فایل بارگذاری شد اما ثبت پرونده مدرک انجام نشد (کد " + insertCode + ")."
        }
        "مدرک با موفقیت ارسال شد و در انتظار بررسی مدیر ارشد است."
    } catch (_: Exception) { "ارتباط با فضای مدارک برقرار نشد." }
    finally { upload?.disconnect(); insert?.disconnect(); cleanup?.disconnect() }
}

private fun profilePhotoPublicUrl(path: String): String {
    if (path.isBlank() || path == "null") return ""
    if (path.startsWith("http")) return path
    return SUPABASE_URL + "/storage/v1/object/public/profile-photos/" + path
}

private fun fetchAdminProfilePhoto(token: String): String {
    var connection: HttpURLConnection? = null
    return try {
        val part = token.split(".").getOrNull(1) ?: return ""
        val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val userId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (userId.isBlank()) return ""
        val query = URLEncoder.encode(userId, "UTF-8")
        connection = (URL(SUPABASE_URL + "/rest/v1/admin_users?select=profile_photo_path&user_id=eq." + query + "&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
        }
        val code = connection.responseCode
        if (code !in 200..299) return ""
        val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        if (rows.length() == 0) "" else profilePhotoPublicUrl(rows.getJSONObject(0).optString("profile_photo_path"))
    } catch (_: Exception) {
        ""
    } finally {
        connection?.disconnect()
    }
}

private fun uploadProfilePhoto(context: Context, uri: Uri, token: String, role: UserRole): String {
    var upload: HttpURLConnection? = null
    var update: HttpURLConnection? = null
    return try {
        val subject = try {
            val part = token.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است."
            val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        } catch (_: Exception) { "" }
        if (subject.isBlank()) return "شناسه حساب از نشست دریافت نشد."
        val mime = context.contentResolver.getType(uri) ?: return "نوع تصویر قابل تشخیص نیست."
        if (mime !in listOf("image/jpeg", "image/png", "image/webp")) return "فقط تصویر JPG، PNG یا WEBP مجاز است."
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن تصویر انجام نشد."
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return "حجم تصویر باید کمتر از ۲ مگابایت باشد."
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val path = subject + "/profile." + ext
        upload = (URL(SUPABASE_URL + "/storage/v1/object/profile-photos/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", mime)
            setRequestProperty("x-upsert", "true")
        }
        upload.outputStream.use { it.write(bytes) }
        val uploadCode = upload.responseCode
        if (uploadCode !in 200..299) return "بارگذاری تصویر مجاز نشد (کد " + uploadCode + ")."
        update = (URL(SUPABASE_URL + "/rest/v1/rpc/set_my_profile_photo_path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
        }
        update.outputStream.use { it.write(JSONObject().put("p_path", path).toString().toByteArray(Charsets.UTF_8)) }
        val updateCode = update.responseCode
        if (updateCode !in 200..299) return "تصویر بارگذاری شد اما ذخیره نشانی پروفایل ناموفق بود (کد " + updateCode + ")."
        profilePhotoPublicUrl(path) + "?v=" + System.currentTimeMillis()
    } catch (_: Exception) { "ارتباط با فضای تصاویر برقرار نشد." }
    finally { upload?.disconnect(); update?.disconnect() }
}

private fun playAppTone() {
    try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65).startTone(ToneGenerator.TONE_PROP_ACK, 160) } catch (_: Exception) { }
}

private object DeviceSessionStore {
    private const val PREFS = "elambar_secure_session"
    private const val ALIAS = "elambar_session_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun save(context: Context, token: String, role: String) {
        if (token.isBlank()) return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("token", Base64.encodeToString(bytes, Base64.NO_WRAP)).putString("role", role).apply()
        } catch (_: Exception) { clear(context) }
    }
    fun load(context: Context): Pair<String,String>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val packed = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0,12)))
            val token = String(cipher.doFinal(packed.copyOfRange(12,packed.size)), Charsets.UTF_8)
            val role = prefs.getString("role", null) ?: return null
            if (token.isBlank() || role !in listOf("DRIVER","COMPANY")) null else token to role
        } catch (_: Exception) { clear(context); null }
    }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}

private fun registerDeviceAccount(role: UserRole, first: String, last: String, mobile: String,
    companyName: String, city: String, address: String, nationalId: String, smart: String,
    vehicle: String, plate: String, destination: String, destinationsText: String): UserLoginResult {
    var auth: HttpURLConnection? = null
    var profile: HttpURLConnection? = null
    return try {
        auth = (URL(SUPABASE_URL + "/functions/v1/device-register").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Content-Type","application/json")
        }
        val destinations = JSONArray()
        destinationsText.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(29).forEach { destinations.put(it) }
        val body = JSONObject().put("action","register").put("account_type",if(role==UserRole.DRIVER)"driver" else "company")
            .put("first_name",first.trim()).put("last_name",last.trim()).put("mobile",mobile.trim())
            .put("company_name",companyName.trim()).put("activity_city",city.trim()).put("office_address",address.trim())
            .put("national_id",nationalId.trim()).put("smart_card_number",smart.trim()).put("vehicle_type",vehicle)
            .put("license_plate",plate.trim()).put("primary_destination",destination.trim()).put("destinations",destinations)
        auth.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = auth.responseCode
        val stream = if(code in 200..299) auth.inputStream else auth.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if(code !in 200..299) return UserLoginResult(false, runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty().ifBlank { "ثبت‌نام انجام نشد؛ اطلاعات را بررسی کنید." })
        val response = JSONObject(raw)
        val session = response.optJSONObject("session") ?: return UserLoginResult(false,"نشست ورود دریافت نشد.")
        val user = response.optJSONObject("user") ?: return UserLoginResult(false,"اطلاعات حساب دریافت نشد.")
        val access = session.optString("access_token"); val refresh = session.optString("refresh_token"); val id = user.optString("id")
        if(access.isBlank() || refresh.isBlank() || id.isBlank()) return UserLoginResult(false,"نشست امن ناقص است.")
        val table = if(role==UserRole.DRIVER) "drivers" else "companies"
        val select = if(role==UserRole.DRIVER) "status,first_name,last_name,full_name,profile_photo_path" else "status,first_name,last_name,company_name,manager_name,profile_photo_path"
        profile = (URL(SUPABASE_URL + "/rest/v1/" + table + "?select=" + select + "&id=eq." + id + "&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod="GET"; connectTimeout=15000; readTimeout=15000
            setRequestProperty("apikey",SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Authorization","Bearer " + access)
        }
        val pCode=profile.responseCode; val pStream=if(pCode in 200..299) profile.inputStream else profile.errorStream
        val rows=JSONArray(pStream?.bufferedReader()?.use{it.readText()}.orEmpty())
        if(pCode !in 200..299 || rows.length()==0) return UserLoginResult(false,"پرونده ثبت شد اما وضعیت آن قابل دریافت نیست؛ با پشتیبانی تماس بگیرید.")
        val p=rows.getJSONObject(0); val status=p.optString("status")
        if(status !in listOf("pending","approved")) return UserLoginResult(false,"وضعیت حساب اجازه ورود نمی‌دهد.")
        val name=if(role==UserRole.COMPANY) p.optString("company_name").ifBlank { first + " " + last } else first + " " + last
        UserLoginResult(true,if(status=="pending")"ثبت‌نام انجام شد؛ حساب در انتظار تأیید مدیر ارشد است." else "ثبت‌نام و ورود موفق بود.",name,status,access,refresh,profilePhotoPublicUrl(p.optString("profile_photo_path")))
    } catch (_:Exception) { UserLoginResult(false,"ارتباط با سامانه برقرار نشد؛ دوباره تلاش کنید.") }
    finally { auth?.disconnect(); profile?.disconnect() }
}

private fun restoreDeviceSession(refreshToken: String, role: UserRole): UserLoginResult {
    var auth: HttpURLConnection?=null; var profile: HttpURLConnection?=null
    return try {
        auth=(URL(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=15000; readTimeout=20000; doOutput=true
            setRequestProperty("apikey",SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Content-Type","application/json")
        }
        auth.outputStream.use { it.write(JSONObject().put("refresh_token",refreshToken).toString().toByteArray(Charsets.UTF_8)) }
        val code=auth.responseCode; val stream=if(code in 200..299) auth.inputStream else auth.errorStream
        val raw=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(code !in 200..299) return UserLoginResult(false,"نشست منقضی شده است.")
        val json=JSONObject(raw); val user=json.optJSONObject("user") ?: return UserLoginResult(false,"حساب پیدا نشد.")
        val access=json.optString("access_token"); val fresh=json.optString("refresh_token"); val id=user.optString("id")
        if(access.isBlank()||fresh.isBlank()||id.isBlank()) return UserLoginResult(false,"نشست جدید ناقص است.")
        val table=if(role==UserRole.DRIVER)"drivers" else "companies"
        val select=if(role==UserRole.DRIVER)"status,first_name,last_name,full_name,profile_photo_path" else "status,first_name,last_name,company_name,manager_name,profile_photo_path"
        profile=(URL(SUPABASE_URL + "/rest/v1/" + table + "?select=" + select + "&id=eq." + id + "&limit=1").openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=15000;readTimeout=15000
            setRequestProperty("apikey",SUPABASE_PUBLISHABLE_KEY);setRequestProperty("Authorization","Bearer " + access)
        }
        val pc=profile.responseCode; val ps=if(pc in 200..299)profile.inputStream else profile.errorStream
        if(pc !in 200..299)return UserLoginResult(false,"بررسی دسترسی حساب ممکن نشد.")
        val rows=JSONArray(ps?.bufferedReader()?.use{it.readText()}.orEmpty())
        if(rows.length()==0)return UserLoginResult(false,"پرونده حساب پیدا نشد.")
        val p=rows.getJSONObject(0);val status=p.optString("status")
        if(status !in listOf("pending","approved"))return UserLoginResult(false,"دسترسی حساب فعال نیست.")
        val name=if(role==UserRole.COMPANY)p.optString("company_name").ifBlank{p.optString("first_name")+" "+p.optString("last_name")} else (p.optString("first_name")+" "+p.optString("last_name")).trim()
        UserLoginResult(true,"نشست قبلی بازیابی شد.",name,status,access,fresh,profilePhotoPublicUrl(p.optString("profile_photo_path")))
    } catch (_:Exception) { UserLoginResult(false,"ارتباط با سامانه برقرار نشد.") }
    finally { auth?.disconnect(); profile?.disconnect() }
}

private data class AdminAccount(
    val userId: String,
    val email: String,
    val role: String,
    val isBlocked: Boolean = false
)

private fun adminRpc(accessToken: String, functionName: String, body: JSONObject): Pair<Int, String> {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("$SUPABASE_URL/rest/v1/rpc/$functionName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (e: Exception) {
        -1 to (e.message ?: "network_error")
    } finally {
        connection?.disconnect()
    }
}

private fun fetchAdminUsers(accessToken: String): List<AdminAccount> {
    val (code, body) = adminRpc(accessToken, "admin_list_users", JSONObject())
    if (code !in 200..299) return emptyList()
    return runCatching {
        val rows = JSONArray(body)
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            AdminAccount(row.optString("user_id"), row.optString("email"), row.optString("role"), row.optBoolean("is_blocked"))
        }
    }.getOrDefault(emptyList())
}

private fun searchAdminCandidates(accessToken: String, query: String): Pair<String, List<AdminAccount>> {
    if (query.length !in 3..254) return "حداقل سه نویسه از ایمیل را وارد کنید." to emptyList()
    val (code, body) = adminRpc(accessToken, "admin_search_users", JSONObject().put("p_query", query))
    if (code !in 200..299) return "جستجو ناموفق بود؛ دسترسی مدیر ارشد و اتصال را بررسی کنید." to emptyList()
    val rows = JSONArray(body)
    val result = (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        AdminAccount(row.optString("user_id"), row.optString("email"), "")
    }
    return (if (result.isEmpty()) "کاربر ثبت‌نام‌شده‌ای با این ایمیل پیدا نشد یا قبلاً مدیر شده است." else "حساب‌های قابل افزودن پیدا شدند.") to result
}

private fun addAdminUser(accessToken: String, userId: String, role: String): String {
    val (code, body) = adminRpc(accessToken, "admin_add_user", JSONObject().put("p_target_user_id", userId).put("p_new_role", role))
    return if (code in 200..299) "انجام شد؛ مدیر جدید با سطح دسترسی انتخاب‌شده اضافه شد."
    else "افزودن مدیر انجام نشد؛ ممکن است حساب قبلاً مدیر شده باشد یا مجوز کافی وجود نداشته باشد."
}

private fun setAdminUserRole(accessToken: String, userId: String, role: String): String {
    val (code, body) = adminRpc(accessToken, "admin_set_role", JSONObject().put("p_target_user_id", userId).put("p_new_role", role))
    return if (code in 200..299) "انجام شد؛ سطح دسترسی مدیر تغییر کرد."
    else "تغییر سطح دسترسی انجام نشد؛ مجوزها را بررسی کنید."
}

private fun forceLogoutMember(accessToken: String, userId: String): String {
    val (code, body) = adminRpc(accessToken, "admin_force_logout_member", JSONObject().put("p_target_user_id", userId))
    return if (code in 200..299) {
        val count = body.trim().trim(34.toChar()).toIntOrNull()
        "دستور خروج اجباری ثبت شد؛ نشست‌های فعال باطل شدند" + (count?.let { " (تعداد نشست: $it)." } ?: ".")
    } else "خروج اجباری انجام نشد؛ فقط مدیر ارشد اصلی مجاز است یا حساب هدف معتبر نیست."
}

private fun fetchAdminPanelData(accessToken: String, panel: String): String {
    if (accessToken.isBlank()) return "نشست مدیر ارشد معتبر نیست؛ خارج شوید و دوباره وارد شوید."
    val (table, select, title) = when (panel) {
        "مدیریت رانندگان" -> Triple("drivers", "first_name,last_name,mobile,vehicle_type,primary_destination,status", "رانندگان")
        "مدیریت شرکت‌ها" -> Triple("companies", "company_name,manager_name,mobile,status", "شرکت‌ها")
        "مدیریت بارها" -> Triple("loads", "origin,destination,vehicle_type,loading_type,status,created_at", "بارها")
        "مدیریت مدیران" -> Triple("admin_users", "user_id,role,is_blocked,access_expires_at,created_at", "مدیران")
        "مدیریت مدارک" -> Triple("driver_documents", "driver_id,document_type,storage_path,review_status,created_at", "مدارک رانندگان")
        "گفتگوها" -> Triple("conversations", "id,load_id,company_id,driver_id,created_at,last_message_at", "گفتگوها")
        "شکایات" -> Triple("load_complaints", "load_id,reporter_user_id,reported_user_id,subject,status,admin_note,created_at", "شکایات")
        "گزارش‌ها" -> Triple("admin_audit_logs", "actor_user_id,target_user_id,action,details,created_at", "گزارش فعالیت‌ها")
        "تنظیمات کل برنامه" -> Triple("app_settings", "setting_key,setting_value,updated_at,updated_by", "تنظیمات")
        else -> return "این بخش هنوز آماده نیست."
    }
    var connection: HttpURLConnection? = null
    return try {
        val url = "$SUPABASE_URL/rest/v1/$table?select=$select&limit=100"
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
                    "مدیریت بارها" -> {
                        append(item.optString("origin")).append(" ← ").append(item.optString("destination"))
                        append(" | ").append(item.optString("vehicle_type")).append(" | ").append(item.optString("loading_type"))
                        append(" | ").append(item.optString("status"))
                    }
                    "مدیریت مدیران" -> {
                        append(item.optString("role")).append(" | شناسه: ").append(item.optString("user_id"))
                        append(" | مسدود: ").append(item.optString("is_blocked"))
                        append(" | پایان دسترسی: ").append(item.optString("access_expires_at"))
                    }
                    "مدیریت مدارک" -> {
                        append(item.optString("document_type")).append(" | راننده: ").append(item.optString("driver_id"))
                        append(" | وضعیت بررسی: ").append(item.optString("review_status"))
                    }
                    "گفتگوها" -> {
                        append("شناسه گفتگو: ").append(item.optString("id"))
                        append(" | شرکت: ").append(item.optString("company_id")).append(" | راننده: ").append(item.optString("driver_id"))
                    }
                    "شکایات" -> {
                        append(item.optString("subject")).append(" | وضعیت: ").append(item.optString("status"))
                        append(" | بار: ").append(item.optString("load_id"))
                        if (item.optString("admin_note").isNotBlank()) append(" | یادداشت: ").append(item.optString("admin_note"))
                    }
                    "گزارش‌ها" -> {
                        append(item.optString("action")).append(" | مدیر: ").append(item.optString("actor_user_id"))
                        append(" | هدف: ").append(item.optString("target_user_id"))
                    }
                    else -> {
                        append(item.optString("setting_key")).append(" = ").append(item.optString("setting_value"))
                    }
                }
                append("\n")
            }
            append("\\nاین بخش به سامانه متصل است و فعلاً حالت مشاهده دارد؛ عملیات تغییردهنده پس از تکمیل کنترل دسترسی فعال می‌شود.")
        }
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد. اینترنت را بررسی کنید."
    } finally {
        connection?.disconnect()
    }
}

private fun registerUserAccount(
    username: String, password: String, role: UserRole, firstName: String, lastName: String,
    mobile: String, companyName: String, activityCity: String, officeAddress: String,
    nationalId: String, smartCardNumber: String, vehicleType: String, licensePlate: String,
    primaryDestination: String, destinationsText: String
): String {
    var connection: HttpURLConnection? = null
    return try {
        val url = "$SUPABASE_URL/functions/v1/username-auth-v2"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val accountType = if (role == UserRole.DRIVER) "driver" else "company"
        val destinations = JSONArray()
        destinationsText.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(29).forEach { destinations.put(it) }
        val body = JSONObject()
            .put("action", "register")
            .put("account_type", accountType)
            .put("username", username.trim().lowercase())
            .put("password", password)
            .put("first_name", firstName.trim())
            .put("last_name", lastName.trim())
            .put("mobile", mobile.trim())
            .put("company_name", companyName.trim())
            .put("activity_city", activityCity.trim())
            .put("office_address", officeAddress.trim())
            .put("national_id", nationalId.trim())
            .put("smart_card_number", smartCardNumber.trim())
            .put("vehicle_type", vehicleType)
            .put("license_plate", licensePlate.trim())
            .put("primary_destination", primaryDestination.trim())
            .put("destinations", destinations)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 200..299) "OK" else JSONObject(text).optString("error", "ثبت‌نام انجام نشد؛ اطلاعات را بررسی کنید.")
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
        val adminRole = admin.optString("role")
        if (adminRole !in setOf("super_admin", "senior_admin", "admin")) {
            return AdminLoginResult(false, "سطح دسترسی این حساب معتبر نیست.")
        }
        val roleTitle = when (adminRole) { "super_admin" -> "مدیر ارشد اصلی"; "senior_admin" -> "ادمین ارشد"; else -> "ادمین عادی" }
        AdminLoginResult(true, "ورود با سطح دسترسی $roleTitle انجام شد.", accessToken, adminRole)
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
        val refreshToken = session.optString("refresh_token")
        val accountType = user.optJSONObject("app_metadata")?.optString("account_type").orEmpty()
        val expectedType = if (role == UserRole.DRIVER) "driver" else "company"
        if (accountType != expectedType) return UserLoginResult(false, "این نام کاربری برای نوع حساب انتخاب‌شده ثبت نشده است.")
        if (userId.isBlank() || accessToken.isBlank() || refreshToken.isBlank()) return UserLoginResult(false, "اطلاعات نشست ورود ناقص است.")

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
            "pending", "approved" -> UserLoginResult(
                success = true,
                message = if (status == "pending") "ورود انجام شد؛ حساب در انتظار تأیید مدیر ارشد است." else "ورود با موفقیت انجام شد.",
                displayName = displayName,
                status = status,
                accessToken = accessToken,
                refreshToken = refreshToken
            )
            else -> UserLoginResult(false, "وضعیت حساب در سامانه معتبر نیست.")
        }
    } catch (_: Exception) {
        UserLoginResult(false, "ارتباط با سامانه برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.")
    } finally {
        authConnection?.disconnect()
        profileConnection?.disconnect()
    }
}


private fun publishLoadBatch(accessToken: String, drafts: List<LoadDraft>, publisherType: String = "company"): String {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست؛ خارج شوید و دوباره وارد شوید."
    if (drafts.isEmpty()) return "ابتدا حداقل یک بار به فهرست اضافه کنید."
    var connection: HttpURLConnection? = null
    return try {
        val items = JSONArray()
        drafts.forEach { d ->
            items.put(JSONObject()
                .put("destination", d.destination)
                .put("cargo_type", d.cargoType)
                .put("vehicle_type", d.vehicleType)
                .put("loading_type", d.loadingType)
                .put("price_mode", d.priceMode)
                .put("freight_amount", d.freightAmount)
                .put("description", d.description))
        }
        val body = JSONObject()
            .put("p_items", items)
            .put("p_publisher_type", publisherType)
            .put("p_company_id", JSONObject.NULL)
        connection = (URL("$SUPABASE_URL/rest/v1/rpc/publish_tehran_load_batch_v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 200..299) {
            val count = try { JSONArray(response).length() } catch (_: Exception) { drafts.size }
            "ثبت شد: $count بار از مبدأ تهران منتشر شد."
        } else {
            val detail = try { JSONObject(response).optString("message", JSONObject(response).optString("hint", "")) } catch (_: Exception) { "" }
            "ثبت بار انجام نشد (کد $code). " + detail.ifBlank { "وضعیت تأیید شرکت و مجوز ثبت بار را بررسی کنید." }
        }
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد. اینترنت را بررسی کنید."
    } finally {
        connection?.disconnect()
    }
}



private fun fetchCompanyDrivers(accessToken: String, vehicle: String, destination: String): String {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست."
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(SUPABASE_URL + "/rest/v1/rpc/search_approved_drivers").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().put("p_vehicle_type", vehicle).put("p_destination", destination.trim())
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "جست‌وجوی رانندگان ناموفق بود (کد $code). فقط شرکت تأییدشده می‌تواند جست‌وجو کند."
        val rows = JSONArray(raw)
        if (rows.length() == 0) return "راننده تأییدشده‌ای با این خودرو و مقصد پیدا نشد."
        buildString {
            append("رانندگان تأییدشده متناسب: ").append(rows.length()).append("\n\n")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                append(i + 1).append(". ").append(item.optString("first_name")).append(" ").append(item.optString("last_name"))
                append("\nخودرو: ").append(item.optString("vehicle_type"))
                append(" | مقصد اصلی: ").append(item.optString("primary_destination")).append("\n\n")
            }
            append("اطلاعات تماس و مدارک خصوصی نمایش داده نمی‌شود.")
        }
    } catch (_: Exception) { "ارتباط با سامانه برقرار نشد." }
    finally { connection?.disconnect() }
}


private fun fetchCompanyLoads(accessToken: String): String {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست."
    var connection: HttpURLConnection? = null
    return try {
        val payload = accessToken.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است."
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val userId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (userId.isBlank()) return "شناسه شرکت دریافت نشد."
        val url = SUPABASE_URL + "/rest/v1/loads?select=id,origin,destination,cargo_type,vehicle_type,loading_type,freight_amount,status,created_at&publisher_user_id=eq." + userId + "&order=created_at.desc&limit=100"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت بارهای شرکت ناموفق بود (کد " + code + ")."
        val rows = JSONArray(raw)
        if (rows.length() == 0) return "هنوز باری از این شرکت ثبت نشده است."
        buildString {
            append("تعداد بارهای ثبت‌شده: ").append(rows.length()).append("\n\n")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                append(i + 1).append(". ").append(item.optString("origin")).append(" ← ").append(item.optString("destination"))
                append("\n").append(item.optString("cargo_type")).append(" | ").append(item.optString("vehicle_type"))
                append(" | ").append(item.optString("loading_type")).append("\nکرایه: ").append(item.optString("freight_amount"))
                append(" | وضعیت: ").append(item.optString("status")).append("\n\n")
            }
        }
    } catch (_: Exception) { "ارتباط با سامانه برقرار نشد." }
    finally { connection?.disconnect() }
}

private fun fetchMatchedLoadsDetailed(accessToken: String): Pair<String, List<MatchedLoad>> {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست؛ دوباره وارد شوید." to emptyList()
    var profileConnection: HttpURLConnection? = null
    var loadsConnection: HttpURLConnection? = null
    return try {
        val payload = accessToken.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است." to emptyList()
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val userId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (userId.isBlank()) return "شناسه راننده از نشست دریافت نشد." to emptyList()
        val profileUrl = "$SUPABASE_URL/rest/v1/drivers?select=vehicle_type,primary_destination,destinations&id=eq.$userId&limit=1"
        profileConnection = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profileConnection.responseCode
        val profileStream = if (profileCode in 200..299) profileConnection.inputStream else profileConnection.errorStream
        val profileRows = JSONArray(profileStream?.bufferedReader()?.use { it.readText() }.orEmpty())
        if (profileCode !in 200..299 || profileRows.length() == 0) return "اطلاعات خودرو و مقصد راننده دریافت نشد." to emptyList()
        val driver = profileRows.getJSONObject(0)
        val vehicle = driver.optString("vehicle_type").trim()
        val destinations = mutableSetOf<String>()
        val destinationArray = driver.optJSONArray("destinations")
        if (destinationArray != null) for (i in 0 until destinationArray.length()) {
            val value = destinationArray.optString(i).trim()
            if (value.isNotBlank()) destinations.add(value.lowercase())
        }
        val primary = driver.optString("primary_destination").trim()
        if (primary.isNotBlank()) destinations.add(primary.lowercase())
        if (vehicle.isBlank() || destinations.isEmpty()) return "ابتدا نوع خودرو و مقصدهای خود را در پروفایل کامل کنید." to emptyList()
        val loadsUrl = "$SUPABASE_URL/rest/v1/loads?select=id,origin,destination,cargo_type,vehicle_type,freight_amount,description,loading_type&status=eq.published&order=created_at.desc&limit=200"
        loadsConnection = (URL(loadsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = loadsConnection.responseCode
        val stream = if (code in 200..299) loadsConnection.inputStream else loadsConnection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت بارها ناموفق بود (کد $code)." to emptyList()
        val rows = JSONArray(raw)
        val matching = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { item ->
            item.optString("vehicle_type").trim().equals(vehicle, ignoreCase = true) &&
                destinations.contains(item.optString("destination").trim().lowercase())
        }.map { item ->
            MatchedLoad(
                id = item.optString("id"),
                origin = item.optString("origin", "تهران"),
                destination = item.optString("destination"),
                cargoType = item.optString("cargo_type"),
                vehicleType = item.optString("vehicle_type"),
                loadingType = item.optString("loading_type"),
                freightAmount = item.optString("freight_amount", "0"),
                description = item.optString("description").takeUnless { it == "null" }.orEmpty()
            )
        }.filter { it.id.isNotBlank() }
        (if (matching.isEmpty()) "در حال حاضر بار منتشرشده متناسب با خودرو و مقصدهای شما وجود ندارد." else "بارهای متناسب پیدا شد: ${matching.size} مورد. برای گرفتن بار، دکمه هر کارت را بزنید.") to matching
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد. اینترنت را بررسی کنید." to emptyList()
    } finally {
        profileConnection?.disconnect()
        loadsConnection?.disconnect()
    }
}

private fun acceptDriverLoad(accessToken: String, loadId: String): String {
    if (accessToken.isBlank() || loadId.isBlank()) return "نشست یا شناسه بار معتبر نیست."
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("$SUPABASE_URL/rest/v1/rpc/driver_accept_load").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().put("p_load_id", loadId)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 200..299) "بار با موفقیت به نام شما ثبت شد."
        else {
            val detail = runCatching { JSONObject(response).optString("message") }.getOrDefault("")
            "گرفتن بار انجام نشد." + if (detail.isNotBlank()) " $detail" else " ممکن است راننده دیگری زودتر آن را گرفته باشد."
        }
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد؛ وضعیت بار را دوباره بررسی کنید."
    } finally {
        connection?.disconnect()
    }
}

private fun fetchMatchedLoads(accessToken: String): String {
    if (accessToken.isBlank()) return "نشست ورود معتبر نیست؛ دوباره وارد شوید."
    var profileConnection: HttpURLConnection? = null
    var loadsConnection: HttpURLConnection? = null
    return try {
        val subject = try {
            val part = accessToken.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است."
            val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        } catch (_: Exception) { "" }
        if (subject.isBlank()) return "شناسه راننده از نشست دریافت نشد."
        val profileUrl = "$SUPABASE_URL/rest/v1/drivers?select=vehicle_type,primary_destination,destinations&id=eq.$subject&limit=1"
        profileConnection = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val profileCode = profileConnection.responseCode
        val profileStream = if (profileCode in 200..299) profileConnection.inputStream else profileConnection.errorStream
        val profileRows = JSONArray(profileStream?.bufferedReader()?.use { it.readText() }.orEmpty())
        if (profileCode !in 200..299 || profileRows.length() == 0) return "اطلاعات نوع خودرو و مقصد راننده دریافت نشد."
        val driver = profileRows.getJSONObject(0)
        val vehicle = driver.optString("vehicle_type").trim()
        val destinations = mutableSetOf<String>()
        val destinationArray = driver.optJSONArray("destinations")
        if (destinationArray != null) {
            for (i in 0 until destinationArray.length()) {
                val value = destinationArray.optString(i).trim()
                if (value.isNotBlank()) destinations.add(value.lowercase())
            }
        }
        val primary = driver.optString("primary_destination").trim()
        if (primary.isNotBlank()) destinations.add(primary.lowercase())
        if (vehicle.isBlank() || destinations.isEmpty()) return "ابتدا نوع خودرو و مقصدهای خود را در پروفایل کامل کنید."
        val loadsUrl = "$SUPABASE_URL/rest/v1/loads?select=origin,destination,cargo_type,vehicle_type,freight_amount,description,loading_type,created_at&status=eq.published&order=created_at.desc&limit=200"
        loadsConnection = (URL(loadsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = loadsConnection.responseCode
        val stream = if (code in 200..299) loadsConnection.inputStream else loadsConnection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت بارها ناموفق بود (کد $code). مجوز و اتصال سامانه را بررسی کنید."
        val rows = JSONArray(response)
        val matching = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { item ->
            item.optString("vehicle_type").trim().equals(vehicle, ignoreCase = true) &&
                destinations.contains(item.optString("destination").trim().lowercase())
        }
        if (matching.isEmpty()) return "در حال حاضر بار منتشرشده متناسب با خودرو و مقصدهای شما وجود ندارد."
        buildString {
            append("تعداد بارهای متناسب با خودرو و مقصد شما: ").append(matching.size).append("\n\n")
            matching.forEachIndexed { index, item ->
                append(index + 1).append(". ").append(item.optString("origin", "تهران"))
                    .append(" ← ").append(item.optString("destination"))
                    .append("\nنوع بار: ").append(item.optString("cargo_type"))
                    .append(" | خودرو: ").append(item.optString("vehicle_type"))
                    .append("\nبارگیری: ").append(item.optString("loading_type"))
                    .append(" | کرایه: ").append(item.optString("freight_amount"))
                val description = item.optString("description")
                if (description.isNotBlank() && description != "null") append("\nتوضیحات: ").append(description)
                append("\n\n")
            }
        }
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد. اینترنت را بررسی کنید."
    } finally {
        profileConnection?.disconnect()
        loadsConnection?.disconnect()
    }
}


private fun fetchAdminRecords(accessToken: String, panel: String): List<AdminRecord> {
    if (accessToken.isBlank()) return emptyList()
    val table = when (panel) {
        "مدیریت رانندگان" -> "drivers"
        "مدیریت شرکت‌ها" -> "companies"
        "مدیریت مدارک" -> "driver_documents"
        else -> return emptyList()
    }
    var connection: HttpURLConnection? = null
    return try {
        val select = when (table) {
            "drivers" -> "id,first_name,last_name,full_name,mobile,vehicle_type,primary_destination,status"
            "companies" -> "id,first_name,last_name,company_name,manager_name,mobile,status"
            else -> "id,driver_id,document_type,storage_path,review_status"
        }
        val url = "$SUPABASE_URL/rest/v1/$table?select=$select&order=created_at.desc&limit=100"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) return emptyList()
        val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        (0 until rows.length()).map { i ->
            val item = rows.getJSONObject(i)
            val title = when (table) {
                "drivers" -> (item.optString("first_name") + " " + item.optString("last_name")).trim().ifBlank { item.optString("full_name") }
                "companies" -> item.optString("company_name")
                else -> item.optString("document_type")
            }
            val detail = when (table) {
                "drivers" -> item.optString("mobile") + " | " + item.optString("vehicle_type") + " | " + item.optString("primary_destination")
                "companies" -> item.optString("manager_name") + " | " + item.optString("mobile")
                else -> "راننده: " + item.optString("driver_id") + " | " + item.optString("storage_path")
            }
            val mediaUrl = if (table == "driver_documents") createSignedDriverDocumentUrl(accessToken, item.optString("storage_path")) else ""
            AdminRecord(item.optString("id"), title, detail, if (table == "driver_documents") item.optString("review_status") else item.optString("status"), mediaUrl)
        }
    } catch (_: Exception) {
        emptyList()
    } finally {
        connection?.disconnect()
    }
}


private fun createSignedDriverDocumentUrl(accessToken: String, path: String): String {
    if (accessToken.isBlank() || path.isBlank()) return ""
    var connection: HttpURLConnection? = null
    return try {
        val encodedPath = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        connection = (URL(SUPABASE_URL + "/storage/v1/object/sign/driver-documents/" + encodedPath).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(JSONObject().put("expiresIn", 900).toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return ""
        val signed = JSONObject(raw).optString("signedURL")
        if (signed.isBlank()) "" else if (signed.startsWith("http")) signed else SUPABASE_URL + signed
    } catch (_: Exception) { "" }
    finally { connection?.disconnect() }
}

private fun updateDriverDocumentStatus(accessToken: String, id: String, status: String): String {
    if (accessToken.isBlank() || id.isBlank() || status !in listOf("approved", "rejected")) return "درخواست تغییر وضعیت مدرک معتبر نیست."
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(SUPABASE_URL + "/rest/v1/driver_documents?id=eq." + id).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "return=minimal")
        }
        connection.outputStream.use { it.write(JSONObject().put("review_status", status).toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code in 200..299) "وضعیت مدرک تغییر کرد." else "تغییر وضعیت مدرک انجام نشد (کد " + code + ")."
    } catch (_: Exception) { "ارتباط با سامانه برقرار نشد." }
    finally { connection?.disconnect() }
}

private fun updateMemberStatus(accessToken: String, panel: String, id: String, status: String, reason: String): String {
    if (accessToken.isBlank() || id.isBlank()) return "نشست مدیر معتبر نیست."
    val table = when (panel) {
        "مدیریت رانندگان" -> "drivers"
        "مدیریت شرکت‌ها" -> "companies"
        else -> return "این عملیات برای این بخش تعریف نشده است."
    }
    if (status !in setOf("approved", "rejected", "blocked")) return "وضعیت درخواستی معتبر نیست."
    if (status == "blocked" && reason.trim().length < 3) return "برای مسدودسازی، دلیل حداقل سه نویسه‌ای وارد کنید."
    val payload = JSONObject().put("p_table", table).put("p_user_id", id).put("p_status", status)
        .put("p_reason", reason.trim().ifBlank { JSONObject.NULL })
    val (code, body) = adminRpc(accessToken, "admin_set_member_status", payload)
    if (code in 200..299) return when (status) {
        "approved" -> "حساب تأیید شد."
        "rejected" -> "درخواست حساب رد شد."
        else -> "حساب با ثبت دلیل مسدود شد."
    }
    val detail = runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("hint") } }.getOrDefault("")
    return "تغییر وضعیت انجام نشد (کد $code)." + if (detail.isNotBlank()) " $detail" else " مجوز مدیر و وضعیت حساب را بررسی کنید."
}
