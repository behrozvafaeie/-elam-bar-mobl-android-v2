package ir.elambar.mobl

import android.os.Bundle
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import coil.compose.AsyncImage
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Context
import android.content.Intent
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
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.lifecycle.lifecycleScope
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

class MainActivity : FragmentActivity() {
    private var updateInfo by mutableStateOf<JSONObject?>(null)
    private var updateCheckMessage by mutableStateOf("")
    private var updateCheckInProgress by mutableStateOf(false)
    private var updateDownloadId: Long
        get() = getSharedPreferences("elambar_update", MODE_PRIVATE).getLong("download_id", -1L)
        set(value) { getSharedPreferences("elambar_update", MODE_PRIVATE).edit().putLong("download_id", value).apply() }

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId == updateDownloadId) installDownloadedUpdate(completedId)
            }
        }
    }
    private var updateReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloadFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateDownloadReceiver, downloadFilter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(updateDownloadReceiver, downloadFilter)
        }
        updateReceiverRegistered = true
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
                ElaanBarApp(
                    palette, updateCheckMessage, updateCheckInProgress,
                    onCheckForUpdates = { checkForAppUpdates() },
                    onBiometricAuth = { callback -> authenticateBiometric(callback) }
                ) {
                    palette = (palette + 1) % 5
                    getSharedPreferences("elambar_ui", MODE_PRIVATE).edit().putInt("palette", palette).apply()
                }
                val availableUpdate = updateInfo
                if (availableUpdate != null) {
                    AlertDialog(
                        onDismissRequest = { updateInfo = null },
                        title = { Text("به‌روزرسانی برنامه آماده است") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("نسخه ${availableUpdate.optString("versionName", "جدید")} برای نصب آماده است.")
                                val notes = availableUpdate.optString("releaseNotes")
                                if (notes.isNotBlank()) Text(notes)
                                Text("برای دریافت و نصب، فقط دکمه زیر را بزنید. تأیید نهایی نصب با اندروید است.")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val id = enqueueAppUpdate(availableUpdate)
                                if (id > 0L) {
                                    updateDownloadId = id
                                    updateInfo = null
                                    android.widget.Toast.makeText(this, "دریافت نسخه جدید آغاز شد؛ پس از دانلود، نصب با تأیید اندروید انجام می‌شود.", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    android.widget.Toast.makeText(this, "شروع دریافت به‌روزرسانی ممکن نشد؛ دوباره تلاش کنید.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }) { Text("دریافت و نصب") }
                        },
                        dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("بعداً") } }
                    )
                }
            }
        }
        checkForAppUpdates()
    }

    private fun authenticateBiometric(onResult: (Boolean) -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود امن به اعلام بار مبل")
            .setSubtitle("برای بازیابی نشست قبلی، اثر انگشت خود را تأیید کنید.")
            .setNegativeButtonText("لغو")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun checkForAppUpdates() {
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        updateCheckMessage = "در حال بررسی نسخه جدید..."
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { fetchAppUpdateManifest() }
            updateCheckInProgress = false
            val latestCode = latest?.optInt("versionCode", 0) ?: 0
            val latestApkUrl = latest?.optString("apkUrl").orEmpty()
            val archiveHash = latest?.optString("archiveSha256").orEmpty()
            val apkHash = latest?.optString("apkSha256").orEmpty()
            val validManifest = latestCode > 0 &&
                latestApkUrl.startsWith("https://") &&
                archiveHash.matches(Regex("^[a-fA-F0-9]{64}$")) &&
                apkHash.matches(Regex("^[a-fA-F0-9]{64}$"))
            if (!validManifest) {
                updateCheckMessage = "اطلاعات نسخه از سرور دریافت نشد؛ بعداً دوباره تلاش کنید."
            } else if (latestCode > BuildConfig.VERSION_CODE) {
                updateCheckMessage = "نسخه جدید ${latest?.optString("versionName", "")} در دسترس است."
                updateInfo = latest
            } else {
                updateCheckMessage = "برنامه شما به‌روز است."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingId = updateDownloadId
        if (pendingId > 0L) {
            val permissionPending = getSharedPreferences("elambar_update", MODE_PRIVATE).getBoolean("install_permission_pending", false)
            if (!permissionPending || Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                installDownloadedUpdate(pendingId)
            }
        }
    }

    override fun onDestroy() {
        if (updateReceiverRegistered) {
            runCatching { unregisterReceiver(updateDownloadReceiver) }
            updateReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun enqueueAppUpdate(info: JSONObject): Long {
        val downloadUrl = info.optString("apkUrl")
        val archiveHash = info.optString("archiveSha256").lowercase()
        val apkHash = info.optString("apkSha256").lowercase()
        if (!downloadUrl.startsWith("https://") ||
            !archiveHash.matches(Regex("^[a-f0-9]{64}$")) ||
            !apkHash.matches(Regex("^[a-f0-9]{64}$"))
        ) return -1L
        return runCatching {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("به‌روزرسانی اعلام بار مبل")
                .setDescription("در حال دریافت نسخه جدید برنامه")
                .setMimeType("application/zip")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "elambar-update-${info.optInt("versionCode")}.zip")
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            getSharedPreferences("elambar_update", MODE_PRIVATE).edit()
                .putString("expected_archive_sha256", archiveHash)
                .putString("expected_apk_sha256", apkHash)
                .apply()
            id
        }.getOrDefault(-1L)
    }

    private fun installDownloadedUpdate(downloadId: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = manager.query(query) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return
            val status = it.getInt(statusIndex)
            if (status == DownloadManager.STATUS_FAILED) {
                updateDownloadId = -1L
                getSharedPreferences("elambar_update", MODE_PRIVATE).edit()
                    .remove("expected_archive_sha256").remove("expected_apk_sha256")
                    .putBoolean("install_permission_pending", false).apply()
                android.widget.Toast.makeText(this, "دریافت به‌روزرسانی ناموفق بود؛ اینترنت را بررسی کنید.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
        }

        val prefs = getSharedPreferences("elambar_update", MODE_PRIVATE)
        val expectedArchiveHash = prefs.getString("expected_archive_sha256", "").orEmpty()
        val expectedApkHash = prefs.getString("expected_apk_sha256", "").orEmpty()
        fun sha256Of(read: (java.security.MessageDigest) -> Unit): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            read(digest)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        fun rejectUpdate(message: String) {
            manager.remove(downloadId)
            updateDownloadId = -1L
            prefs.edit().remove("expected_archive_sha256").remove("expected_apk_sha256")
                .putBoolean("install_permission_pending", false).apply()
            java.io.File(cacheDir, "updates/app-update.apk").delete()
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }

        val actualArchiveHash = runCatching {
            sha256Of { digest ->
                manager.openDownloadedFile(downloadId).use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
        }.getOrDefault("")
        if (expectedArchiveHash.isBlank() || actualArchiveHash != expectedArchiveHash) {
            rejectUpdate("اعتبار فایل فشرده به‌روزرسانی تأیید نشد؛ فایل حذف شد.")
            return
        }

        val extractedApk = java.io.File(cacheDir, "updates/app-update.apk")
        val extracted = runCatching {
            extractedApk.parentFile?.mkdirs()
            manager.openDownloadedFile(downloadId).use { descriptor ->
                java.util.zip.ZipInputStream(java.io.FileInputStream(descriptor.fileDescriptor)).use { zip ->
                    var entry = zip.nextEntry
                    var found = false
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            java.io.FileOutputStream(extractedApk).use { output -> zip.copyTo(output, 8192) }
                            found = true
                            break
                        }
                        entry = zip.nextEntry
                    }
                    found
                }
            }
        }.getOrDefault(false)
        if (!extracted || !extractedApk.exists() || extractedApk.length() < 1024L) {
            rejectUpdate("فایل نصب داخل بسته به‌روزرسانی پیدا نشد.")
            return
        }

        val actualApkHash = runCatching {
            sha256Of { digest ->
                java.io.FileInputStream(extractedApk).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }.getOrDefault("")
        if (expectedApkHash.isBlank() || actualApkHash != expectedApkHash) {
            rejectUpdate("اعتبار فایل نصب تأیید نشد؛ بسته به‌روزرسانی حذف شد.")
            return
        }

        val packageInfo = runCatching {
            packageManager.getPackageArchiveInfo(extractedApk.absolutePath, 0)
        }.getOrNull()
        if (packageInfo == null || packageInfo.packageName != packageName || packageInfo.versionCode <= BuildConfig.VERSION_CODE) {
            rejectUpdate("مشخصات فایل نصب با برنامه فعلی سازگار نیست؛ به‌روزرسانی لغو شد.")
            return
        }

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            prefs.edit().putBoolean("install_permission_pending", true).apply()
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(settingsIntent)
            return
        }
        prefs.edit().putBoolean("install_permission_pending", false).apply()
        val apkUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", extractedApk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
        updateDownloadId = -1L
        prefs.edit().remove("expected_archive_sha256").remove("expected_apk_sha256").apply()
    }
}

private fun fetchAppUpdateManifest(): JSONObject? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("https://sckcxtceybwtiecyxqsy.supabase.co/functions/v1/app-update").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) null
        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private enum class UserRole(val label: String) {
    DRIVER("راننده"),
    COMPANY("شرکت باربری"),
    ADMIN("مدیر ارشد")
}

private data class AdminLoginResult(val success: Boolean, val message: String, val accessToken: String = "", val role: String = "", val refreshToken: String = "")
private data class UserLoginResult(val success: Boolean, val message: String, val displayName: String = "", val status: String = "", val accessToken: String = "", val refreshToken: String = "", val photoUrl: String = "", val username: String = "")
internal data class LoadDraft(val destination: String, val cargoType: String, val vehicleType: String, val loadingType: String, val priceMode: String, val freightAmount: String, val description: String)

@Composable
private fun LoadSectionDropdown(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "$title ▲" else "$title ▼")
        }
        if (expanded) content()
    }
}

@Composable
private fun LoadChoiceDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$label: $selected")
                Text("⌄", style = MaterialTheme.typography.titleMedium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun loadDraftOwnerKey(token: String, role: String): String {
    if (token.isBlank()) return ""
    return runCatching {
        val payload = token.split(".").getOrNull(1) ?: return ""
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val userId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (userId.isBlank()) "" else "load_drafts_${role.lowercase()}_${userId}"
    }.getOrDefault("")
}

private fun encodeLoadDraftState(
    drafts: List<LoadDraft>,
    destination: String,
    cargoType: String,
    vehicleType: String,
    loadingType: String,
    priceMode: String,
    freightAmount: String,
    description: String,
    quantity: String
): String {
    val rows = JSONArray()
    drafts.forEach { d ->
        rows.put(JSONObject()
            .put("destination", d.destination)
            .put("cargoType", d.cargoType)
            .put("vehicleType", d.vehicleType)
            .put("loadingType", d.loadingType)
            .put("priceMode", d.priceMode)
            .put("freightAmount", d.freightAmount)
            .put("description", d.description))
    }
    return JSONObject()
        .put("drafts", rows)
        .put("destination", destination)
        .put("cargoType", cargoType)
        .put("vehicleType", vehicleType)
        .put("loadingType", loadingType)
        .put("priceMode", priceMode)
        .put("freightAmount", freightAmount)
        .put("description", description)
        .put("quantity", quantity)
        .toString()
}

private fun decodeLoadDraftState(raw: String): Pair<List<LoadDraft>, JSONObject?> {
    return runCatching {
        val root = JSONObject(raw)
        val rows = root.optJSONArray("drafts") ?: JSONArray()
        val drafts = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val destination = row.optString("destination").trim()
            val cargoType = row.optString("cargoType").trim()
            if (destination.isBlank() || cargoType.isBlank()) return@mapNotNull null
            LoadDraft(
                destination,
                cargoType,
                row.optString("vehicleType", "وانت"),
                row.optString("loadingType", "روباری"),
                row.optString("priceMode", "fixed"),
                row.optString("freightAmount", "0"),
                row.optString("description")
            )
        }
        drafts to root
    }.getOrElse { emptyList<LoadDraft>() to null }
}
private data class AdminRecord(val id: String, val title: String, val detail: String, val status: String, val mediaUrl: String = "")
private data class MatchedLoad(val id: String, val origin: String, val destination: String, val cargoType: String, val vehicleType: String, val loadingType: String, val freightAmount: String, val description: String, val companyName: String = "", val companyMobile: String = "")
private data class AcceptedLoadContact(val id: String, val destination: String, val companyName: String, val managerName: String, val mobile: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElaanBarApp(
    palette: Int,
    updateCheckMessage: String,
    updateCheckInProgress: Boolean,
    onCheckForUpdates: () -> Unit,
    onBiometricAuth: ((Boolean) -> Unit) -> Unit,
    onNextPalette: () -> Unit
) {
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
    var editableProfileLoading by remember { mutableStateOf(false) }
    var editableProfileSaving by remember { mutableStateOf(false) }
    var editableProfileMessage by remember { mutableStateOf("") }
    var editProfileFirstName by remember { mutableStateOf("") }
    var editProfileLastName by remember { mutableStateOf("") }
    var editProfileMobile by remember { mutableStateOf("") }
    var editProfileCompanyName by remember { mutableStateOf("") }
    var editProfileActivityCity by remember { mutableStateOf("تهران") }
    var editProfileOfficeAddress by remember { mutableStateOf("") }
    var editProfileVehicleType by remember { mutableStateOf("وانت مبلی") }
    var editProfilePlate by remember { mutableStateOf("") }
    var editProfilePrimaryDestination by remember { mutableStateOf("") }
    var editProfileDestinations by remember { mutableStateOf("") }
    var editProfileNewPassword by remember { mutableStateOf("") }
    var editProfileConfirmPassword by remember { mutableStateOf("") }
    var showPasswordRecovery by remember { mutableStateOf(false) }
    var recoveryCodeSent by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf("") }
    var recoveryNewPassword by remember { mutableStateOf("") }
    var recoveryConfirmPassword by remember { mutableStateOf("") }
    var recoveryBusy by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf("") }
    var editProfileBirthDate by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showCommunication by remember { mutableStateOf(false) }
    var showChatRooms by remember { mutableStateOf(false) }
    var showStories by remember { mutableStateOf(false) }
    var selectedDriverDocType by remember { mutableStateOf("کارت هوشمند") }
    var driverDocUploading by remember { mutableStateOf(false) }
    var driverDocMessage by remember { mutableStateOf("") }
    var matchedLoadsText by remember { mutableStateOf("برای دریافت بارهای متناسب، دکمه بروزرسانی را بزنید.") }
    var matchedLoads by remember { mutableStateOf<List<MatchedLoad>>(emptyList()) }
    var matchedLoadsLoading by remember { mutableStateOf(false) }
    var publicHallLoads by remember { mutableStateOf<List<MatchedLoad>>(emptyList()) }
    var publicHallMessage by remember { mutableStateOf("برای مشاهده بارهای عمومی، دکمه بروزرسانی را بزنید.") }
    var publicHallLoading by remember { mutableStateOf(false) }
    var acceptedLoadContacts by remember { mutableStateOf<List<AcceptedLoadContact>>(emptyList()) }
    var acceptedLoadContactsLoading by remember { mutableStateOf(false) }
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
    var loadQuantity by remember { mutableStateOf("1") }
        var loadDestinationExpanded by remember { mutableStateOf(false) }
    var loadCargoExpanded by remember { mutableStateOf(false) }
    var loadVehicleExpanded by remember { mutableStateOf(false) }
    var loadLoadingExpanded by remember { mutableStateOf(false) }
    var loadPriceExpanded by remember { mutableStateOf(false) }
    var loadQuantityExpanded by remember { mutableStateOf(false) }
    var loadDescriptionExpanded by remember { mutableStateOf(false) }
    var loadActionMessage by remember { mutableStateOf("") }
    var loadPublishing by remember { mutableStateOf(false) }
    var companyLoadPanelExpanded by remember { mutableStateOf(true) }
    var companyApprovalRefreshing by remember { mutableStateOf(false) }
    var companyHallExpanded by remember { mutableStateOf(false) }
    var companyPanelV2Selected by remember { mutableStateOf(true) }
    var showPublicHall by remember { mutableStateOf(false) }
    var adminLoadPanelExpanded by remember { mutableStateOf(false) }
    val loadDrafts = remember { androidx.compose.runtime.mutableStateListOf<LoadDraft>() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var companyName by remember { mutableStateOf("") }
    var activityCity by remember { mutableStateOf("تهران") }
    var officeAddress by remember { mutableStateOf("") }
    var nationalId by remember { mutableStateOf("") }
    var smartCardNumber by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("وانت") }
    var licensePlate by remember { mutableStateOf("") }
    var primaryDestination by remember { mutableStateOf("") }
    var destinationsText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("برای ادامه، نوع حساب را انتخاب کنید.") }
    var loading by remember { mutableStateOf(false) }
    var adminLoggedIn by remember { mutableStateOf(false) }
    var adminRole by remember { mutableStateOf("") }
    var adminAccessToken by remember { mutableStateOf("") }
    var loadedDraftOwner by remember { mutableStateOf("") }
    val activeDraftOwner = remember(userAccessToken, adminAccessToken, userLoggedIn, adminLoggedIn, role, adminRole) {
        when {
            adminLoggedIn -> loadDraftOwnerKey(adminAccessToken, "admin")
            userLoggedIn && role == UserRole.COMPANY -> loadDraftOwnerKey(userAccessToken, "company")
            else -> ""
        }
    }

    // Refresh the public hall when an approved driver opens it and keep it current
    // while visible, so newly published loads appear without a manual refresh.
    LaunchedEffect(showPublicHall, userLoggedIn, role, userStatus, userAccessToken, adminLoggedIn, adminAccessToken) {
        if (showPublicHall && (adminLoggedIn || (userLoggedIn && role == UserRole.DRIVER && userStatus == "approved"))) {
            while (true) {
                publicHallLoading = true
                val hallToken = if (adminLoggedIn) adminAccessToken else userAccessToken
                val result = withContext(Dispatchers.IO) { fetchPublicHallLoads(hallToken) }
                publicHallMessage = result.first
                publicHallLoads = result.second
                publicHallLoading = false
                delay(30_000)
            }
        }
    }
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
    var adminMenuVisibleToOtherAdmins by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var biometricEnabled by remember { mutableStateOf(context.getSharedPreferences("elambar_security", Context.MODE_PRIVATE).getBoolean("biometric_enabled", false)) }
    var biometricGatePassed by remember { mutableStateOf(!biometricEnabled) }
    var biometricMessage by remember { mutableStateOf("") }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var pendingAdminDocumentUrl by remember { mutableStateOf("") }
    val saveAdminDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val sourceUrl = pendingAdminDocumentUrl
        if (destination != null && sourceUrl.isNotBlank()) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    copySignedDocumentToUri(context, sourceUrl, destination)
                }
                android.widget.Toast.makeText(
                    context,
                    if (saved) "فایل مدرک ذخیره شد." else "ذخیره فایل انجام نشد؛ دوباره تلاش کنید.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        pendingAdminDocumentUrl = ""
    }

    LaunchedEffect(Unit) {
        if (biometricEnabled) {
            onBiometricAuth { success ->
                biometricGatePassed = success
                biometricMessage = if (success) "اثر انگشت تأیید شد." else "اثر انگشت تأیید نشد؛ می‌توانید با روش ورود قبلی وارد شوید."
            }
        }
    }

    LaunchedEffect(activeDraftOwner) {
        loadedDraftOwner = ""
        if (activeDraftOwner.isBlank()) {
            loadDrafts.clear()
            loadDestination = ""
            loadDescription = ""
        } else {
            val raw = EncryptedDraftStore.load(context, activeDraftOwner)
            if (!raw.isNullOrBlank()) {
                val (savedDrafts, form) = decodeLoadDraftState(raw)
                loadDrafts.clear()
                loadDrafts.addAll(savedDrafts)
                if (form != null) {
                    loadDestination = form.optString("destination")
                    loadCargoType = form.optString("cargoType", "مبل")
                    loadVehicleType = form.optString("vehicleType", "وانت")
                    loadLoadingType = form.optString("loadingType", "روباری")
                    loadPriceMode = form.optString("priceMode", "fixed")
                    loadFreightAmount = form.optString("freightAmount")
                    loadDescription = form.optString("description")
                    loadQuantity = form.optString("quantity", "1")
                }
            } else {
                loadDrafts.clear()
                loadDestination = ""
                loadCargoType = "مبل"
                loadVehicleType = "وانت"
                loadLoadingType = "روباری"
                loadPriceMode = "fixed"
                loadFreightAmount = ""
                loadDescription = ""
                loadQuantity = "1"
            }
            loadedDraftOwner = activeDraftOwner
        }
    }
    LaunchedEffect(
        activeDraftOwner, loadedDraftOwner, loadDestination, loadCargoType, loadVehicleType,
        loadLoadingType, loadPriceMode, loadFreightAmount, loadDescription, loadQuantity, loadDrafts.toList()
    ) {
        if (activeDraftOwner.isNotBlank() && loadedDraftOwner == activeDraftOwner) {
            delay(350)
            val snapshot = encodeLoadDraftState(
                loadDrafts.toList(), loadDestination, loadCargoType, loadVehicleType,
                loadLoadingType, loadPriceMode, loadFreightAmount, loadDescription, loadQuantity
            )
            EncryptedDraftStore.save(context, activeDraftOwner, snapshot)
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && userAccessToken.isNotBlank() && (role == UserRole.DRIVER || role == UserRole.COMPANY)) {
            driverDocUploading = true
            driverDocMessage = "در حال بارگذاری مدرک..."
            scope.launch {
                driverDocMessage = withContext(Dispatchers.IO) { uploadAccountDocument(context, uri, userAccessToken, selectedDriverDocType, role) }
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
    BackHandler(
        enabled = showStories || showChatRooms || showCommunication || showProfile || showContacts || showSettings || showPublicHall || selectedPanel != null || companyLoadPanelExpanded || companyHallExpanded || drawerState.isOpen
    ) {
        when {
            showStories -> showStories = false
            showChatRooms -> showChatRooms = false
            showCommunication -> showCommunication = false
            showProfile -> showProfile = false
            showContacts -> showContacts = false
            showSettings -> showSettings = false
            showPublicHall -> showPublicHall = false
            selectedPanel != null -> selectedPanel = null
            companyLoadPanelExpanded -> companyLoadPanelExpanded = false
            companyHallExpanded -> companyHallExpanded = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
        }
    }
    LaunchedEffect(showProfile, userLoggedIn, role, userAccessToken) {
        if (showProfile && userLoggedIn && userAccessToken.isNotBlank()) {
            editableProfileLoading = true
            editableProfileMessage = ""
            val profile = withContext(Dispatchers.IO) { fetchEditableUserProfile(userAccessToken, role) }
            if (profile != null) {
                editProfileFirstName = profile.optString("first_name")
                editProfileBirthDate = formatBirthDateForDisplay(profile.optString("birth_date"))
                editProfileLastName = profile.optString("last_name")
                editProfileMobile = profile.optString("mobile")
                editProfileCompanyName = profile.optString("company_name")
                editProfileActivityCity = profile.optString("activity_city", "تهران").ifBlank { "تهران" }
                editProfileOfficeAddress = profile.optString("office_address")
                editProfileVehicleType = profile.optString("vehicle_type", "وانت مبلی").ifBlank { "وانت مبلی" }
                editProfilePlate = profile.optString("license_plate")
                editProfilePrimaryDestination = profile.optString("primary_destination")
                val destinations = profile.optJSONArray("destinations")
                editProfileDestinations = if (destinations != null) (0 until destinations.length()).map { destinations.optString(it) }.joinToString("، ") else ""
            } else {
                editableProfileMessage = "دریافت مشخصات ممکن نشد؛ اتصال اینترنت را بررسی و دوباره تلاش کنید."
            }
            editableProfileLoading = false
        }
    }
    LaunchedEffect(userLoggedIn, role, userStatus, userAccessToken) {
        if (userLoggedIn && role == UserRole.DRIVER && userStatus == "approved" && userAccessToken.isNotBlank()) {
            publicHallLoading = true
            val result = withContext(Dispatchers.IO) { fetchPublicHallLoads(userAccessToken) }
            publicHallMessage = result.first
            publicHallLoads = result.second
            publicHallLoading = false
        } else {
            publicHallLoads = emptyList()
            publicHallMessage = if (userLoggedIn && role == UserRole.DRIVER && userStatus != "approved")
                "دسترسی سالن پس از تأیید مدیر فعال می‌شود."
            else "برای مشاهده سالن عمومی وارد حساب راننده شوید."
            if (role == UserRole.DRIVER && userStatus != "approved") showPublicHall = false
        }
    }
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
    var textScale by remember { mutableFloatStateOf(context.getSharedPreferences("elambar_ui", Context.MODE_PRIVATE).getFloat("text_scale", 1.15f).coerceIn(0.9f, 1.35f)) }
    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(density = currentDensity.density, fontScale = textScale)
    LaunchedEffect(biometricGatePassed) {
        if (!biometricGatePassed) return@LaunchedEffect
        val saved = DeviceSessionStore.load(context)
        if (saved != null) {
            val savedRole = when (saved.second) { "COMPANY" -> UserRole.COMPANY; "ADMIN" -> UserRole.ADMIN; else -> UserRole.DRIVER }
            val restored = withContext(Dispatchers.IO) { restoreDeviceSession(saved.first, savedRole) }
            if (restored.success && restored.refreshToken.isNotBlank()) {
                DeviceSessionStore.save(context, restored.refreshToken, savedRole.name)
                if (savedRole == UserRole.ADMIN) {
                    adminLoggedIn = true
                    adminRole = restored.status
                    adminAccessToken = restored.accessToken
                    adminProfilePhotoUrl = withContext(Dispatchers.IO) { fetchAdminProfilePhoto(restored.accessToken) }
                    adminMenuVisibleToOtherAdmins = withContext(Dispatchers.IO) { fetchAdminMenuVisibility(restored.accessToken) }
                } else {
                    role = savedRole; userLoggedIn = true; userDisplayName = restored.displayName; username = restored.username
                    userStatus = restored.status; userAccessToken = restored.accessToken; profilePhotoUrl = restored.photoUrl
                    if (savedRole == UserRole.DRIVER) {
                        acceptedLoadContactsLoading = true
                        acceptedLoadContacts = withContext(Dispatchers.IO) { fetchAcceptedLoadContacts(restored.accessToken) }
                        acceptedLoadContactsLoading = false
                    }
                }
                message = "نشست امن قبلی بازیابی شد."
            } else { DeviceSessionStore.clear(context); message = "نشست قبلی معتبر نیست یا حساب حذف/مسدود شده است." }
        }
    }

    // Refresh device sessions before the normal one-hour access-token expiry.
    LaunchedEffect(userLoggedIn, adminLoggedIn) {
        if (userLoggedIn || adminLoggedIn) {
            while (true) {
                delay(10 * 60 * 1000L)
                val saved = DeviceSessionStore.load(context)
                if (saved == null) {
                    userLoggedIn = false
                    userAccessToken = ""
                    message = "نشست امن پیدا نشد؛ دوباره وارد شوید."
                    break
                }
                val savedRole = when (saved.second) { "COMPANY" -> UserRole.COMPANY; "ADMIN" -> UserRole.ADMIN; else -> UserRole.DRIVER }
                val refreshed = withContext(Dispatchers.IO) { restoreDeviceSession(saved.first, savedRole) }
                if (refreshed.success && refreshed.accessToken.isNotBlank() && refreshed.refreshToken.isNotBlank()) {
                    DeviceSessionStore.save(context, refreshed.refreshToken, savedRole.name)
                    if (savedRole == UserRole.ADMIN) {
                        adminAccessToken = refreshed.accessToken
                        adminRole = refreshed.status
                    } else {
                        userAccessToken = refreshed.accessToken
                        userStatus = refreshed.status
                        userDisplayName = refreshed.displayName
                        username = refreshed.username
                        profilePhotoUrl = refreshed.photoUrl
                    }
                } else {
                    DeviceSessionStore.clear(context)
                    if (savedRole == UserRole.ADMIN) {
                        adminLoggedIn = false; adminAccessToken = ""; adminRole = ""
                    } else {
                        userLoggedIn = false; userAccessToken = ""
                    }
                    message = "نشست معتبر نیست یا حساب حذف/مسدود شده است."
                    break
                }
            }
        }
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
    if (showStories && (userLoggedIn || adminLoggedIn)) {
        StoryScreen(
            accessToken = if (adminLoggedIn) adminAccessToken else userAccessToken,
            role = if (adminLoggedIn) adminRole else role.name,
            isSuperAdmin = adminLoggedIn && adminRole == "super_admin"
        ) { showStories = false }
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
                            onClick = { showPublicHall = false; showProfile = true; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        NavigationDrawerItem(
                            label = { Text("مخاطبین") },
                            selected = showContacts,
                            onClick = {
                                showContacts = true
                                showProfile = false
                                showSettings = false
                                showPublicHall = false
                                showStories = false
                                showChatRooms = false
                                showCommunication = false
                                selectedPanel = null
                                companyLoadPanelExpanded = false
                                companyHallExpanded = false
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if ((userLoggedIn && role == UserRole.DRIVER && userStatus == "approved") || adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("سالن عمومی بار") },
                            selected = showPublicHall,
                            onClick = {
                                showPublicHall = true
                                showProfile = false
                                showSettings = false
                                showStories = false
                                showChatRooms = false
                                showCommunication = false
                                selectedPanel = null
                                companyLoadPanelExpanded = false
                                companyHallExpanded = false
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if ((userLoggedIn && userStatus == "approved") || (adminLoggedIn && adminRole == "super_admin")) {
                        NavigationDrawerItem(
                            label = { Text("پیام‌های بار") },
                            selected = showCommunication,
                            onClick = { showCommunication = true; showChatRooms = false; showStories = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if ((userLoggedIn && userStatus == "approved") || adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("اتاق عمومی و گفتگوها") },
                            selected = showChatRooms,
                            onClick = { showChatRooms = true; showCommunication = false; showStories = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (userLoggedIn || adminLoggedIn) {
                        NavigationDrawerItem(
                            label = { Text("استوری‌ها") },
                            selected = showStories,
                            onClick = { showStories = true; showChatRooms = false; showCommunication = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("بخش‌های مدیریتی", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (adminLoggedIn && (adminRole == "super_admin" || adminMenuVisibleToOtherAdmins)) {
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
                    } else if (userLoggedIn && role == UserRole.COMPANY) {
                        NavigationDrawerItem(
                            label = { Text("پنل اعلام بار") },
                            selected = companyLoadPanelExpanded,
                            onClick = { companyLoadPanelExpanded = true; companyHallExpanded = false; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        NavigationDrawerItem(
                            label = { Text("سالن اعلام بار") },
                            selected = companyHallExpanded,
                            onClick = { companyLoadPanelExpanded = false; companyHallExpanded = true; scope.launch { drawerState.close() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (adminLoggedIn && (adminRole == "super_admin" || adminMenuVisibleToOtherAdmins)) {
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
                    } else if (adminLoggedIn) {
                        Text("منوی مدیریتی برای مدیران دیگر توسط مدیر ارشد اصلی خاموش شده است.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(if (userLoggedIn) "بخش‌های حساب شما از این منو در دسترس خواهند بود." else "برای ورود به بخش‌های برنامه، ابتدا وارد حساب شوید.", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("تنظیمات برنامه") },
                        selected = showSettings,
                        onClick = {
                            showSettings = true
                            showPublicHall = false
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
                    if (userLoggedIn || adminLoggedIn) {
                        TextButton(
                            onClick = {
                                DeviceSessionStore.clear(context)
                                userLoggedIn = false
                                userAccessToken = ""
                                userStatus = ""
                                userDisplayName = ""
                                profilePhotoUrl = ""
                                adminLoggedIn = false
                                adminAccessToken = ""
                                adminRole = ""
                                adminProfilePhotoUrl = ""
                                showProfile = false
                                showPublicHall = false
                                showCommunication = false
                                showChatRooms = false
                                showStories = false
                                selectedPanel = null
                                adminRecords = emptyList()
                                adminPanelData = ""
                                adminActionMessage = ""
                                message = "با موفقیت از حساب خارج شدید."
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("خروج از حساب") }
                    }
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
                        if (showProfile || showContacts || showSettings || showPublicHall || selectedPanel != null || companyLoadPanelExpanded || companyHallExpanded) {
                            IconButton(onClick = {
                                when {
                                    showProfile -> showProfile = false
                                    showContacts -> showContacts = false
                                    showSettings -> showSettings = false
                                    showPublicHall -> showPublicHall = false
                                    selectedPanel != null -> selectedPanel = null
                                    companyLoadPanelExpanded -> companyLoadPanelExpanded = false
                                    companyHallExpanded -> companyHallExpanded = false
                                }
                            }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "برگشت")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "باز کردن منو")
                            }
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
                if (biometricEnabled && !biometricGatePassed && !userLoggedIn && !adminLoggedIn && biometricMessage.isNotBlank()) {
                    Text(biometricMessage, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
                }
                if (!(userLoggedIn && role == UserRole.COMPANY && !showProfile && !showPublicHall)) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.elambar_logo),
                            contentDescription = "نشان اعلام بار مبل",
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    }
                    Text("اعلام بار مبل", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
                    Text("سامانه ارتباط شرکت‌های باربری و رانندگان", textAlign = TextAlign.Center)
                }
                if (showPublicHall && (adminLoggedIn || (userLoggedIn && role == UserRole.DRIVER && userStatus == "approved"))) {
                    PublicLoadHallScreen(
                        loads = publicHallLoads,
                        message = publicHallMessage,
                        loading = publicHallLoading,
                        acceptingLoadId = acceptingLoadId,
                        canAccept = userLoggedIn && role == UserRole.DRIVER && userStatus == "approved",
                        onRefresh = {
                            publicHallLoading = true
                            scope.launch {
                                val hallToken = if (adminLoggedIn) adminAccessToken else userAccessToken
                                val result = withContext(Dispatchers.IO) { fetchPublicHallLoads(hallToken) }
                                publicHallMessage = result.first
                                publicHallLoads = result.second
                                publicHallLoading = false
                            }
                        },
                        onAccept = { loadId ->
                            acceptingLoadId = loadId
                            scope.launch {
                                val accepted = withContext(Dispatchers.IO) { acceptDriverLoad(userAccessToken, loadId) }
                                publicHallMessage = accepted
                                val refreshed = withContext(Dispatchers.IO) { fetchPublicHallLoads(userAccessToken) }
                                publicHallLoads = refreshed.second
                                if (refreshed.first.isNotBlank()) publicHallMessage += "\\n" + refreshed.first
                                acceptingLoadId = ""
                            }
                        }
                    )
                } else if (showContacts && (userLoggedIn || adminLoggedIn)) {
                    ContactsScreen(
                        accessToken = if (adminLoggedIn) adminAccessToken else userAccessToken,
                        role = if (adminLoggedIn) adminRole else role.name,
                        onBack = { showContacts = false }
                    )
                } else if (showSettings) {
                    HorizontalDivider()
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("تنظیمات برنامه", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text("نسخه نصب‌شده: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onCheckForUpdates, enabled = !updateCheckInProgress, modifier = Modifier.fillMaxWidth()) {
                                Text(if (updateCheckInProgress) "در حال بررسی..." else "بررسی به‌روزرسانی برنامه")
                            }
                            if (updateCheckMessage.isNotBlank()) Text(updateCheckMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Text("پوسته و رنگ‌بندی", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onNextPalette, modifier = Modifier.fillMaxWidth()) {
                                Text("پوسته فعلی: " + listOf("روشن", "سبز", "آبی", "تیره", "خودکار")[palette] + "  |  تغییر پوسته")
                            }
                            HorizontalDivider()
                            Text("قفل اثر انگشت", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (biometricEnabled) "ورود با اثر انگشت روشن" else "ورود با اثر انگشت خاموش")
                                Switch(
                                    checked = biometricEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            biometricMessage = "اثر انگشت را برای فعال‌سازی تأیید کنید."
                                            onBiometricAuth { success ->
                                                if (success) {
                                                    biometricEnabled = true
                                                    biometricGatePassed = true
                                                    context.getSharedPreferences("elambar_security", Context.MODE_PRIVATE).edit().putBoolean("biometric_enabled", true).apply()
                                                    biometricMessage = "قفل اثر انگشت فعال شد."
                                                } else {
                                                    biometricMessage = "فعال‌سازی انجام نشد؛ حسگر و اثر انگشت ثبت‌شده را بررسی کنید."
                                                }
                                            }
                                        } else {
                                            biometricEnabled = false
                                            biometricGatePassed = true
                                            context.getSharedPreferences("elambar_security", Context.MODE_PRIVATE).edit().putBoolean("biometric_enabled", false).apply()
                                            biometricMessage = "ورود با اثر انگشت غیرفعال شد."
                                        }
                                    }
                                )
                            }
                            if (biometricMessage.isNotBlank()) Text(biometricMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                } else if (showProfile && userLoggedIn) {
                    HorizontalDivider()
                    Text("پروفایل من", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(if (role == UserRole.DRIVER) "ویرایش مشخصات راننده" else "ویرایش مشخصات شرکت", style = MaterialTheme.typography.titleMedium)
                    Text("وضعیت حساب: " + when (userStatus) { "approved" -> "تأیید شده"; "pending" -> "در انتظار تأیید"; "rejected" -> "رد شده"; "blocked" -> "مسدود"; else -> userStatus }, color = MaterialTheme.colorScheme.secondary)
                    if (editableProfileLoading) {
                        CircularProgressIndicator()
                        Text("در حال دریافت مشخصات حساب...")
                    } else {
                        OutlinedTextField(value = editProfileFirstName, onValueChange = { editProfileFirstName = it.take(80) }, label = { Text("نام") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editProfileLastName, onValueChange = { editProfileLastName = it.take(100) }, label = { Text("نام خانوادگی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editProfileMobile, onValueChange = {}, readOnly = true, label = { Text("شماره موبایل (تغییر با تأیید پیامکی)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = username, onValueChange = {}, readOnly = true, label = { Text("نام کاربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("نوع کاربری: " + if (role == UserRole.DRIVER) "راننده" else "شرکت باربری", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = editProfileBirthDate, onValueChange = { editProfileBirthDate = it.filter { ch -> ch.isDigit() || ch == '/' }.take(10) }, label = { Text("تاریخ تولد (شمسی: ۱۳۷۸/۰۵/۲۱)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (role == UserRole.COMPANY) {
                            OutlinedTextField(value = editProfileCompanyName, onValueChange = { editProfileCompanyName = it.take(160) }, label = { Text("نام شرکت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = editProfileActivityCity, onValueChange = { editProfileActivityCity = it.take(100) }, label = { Text("شهر محل فعالیت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = editProfileOfficeAddress, onValueChange = { editProfileOfficeAddress = it.take(500) }, label = { Text("نشانی دفتر") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        } else {
                            OutlinedTextField(value = editProfileVehicleType, onValueChange = { editProfileVehicleType = it.take(80) }, label = { Text("نوع خودرو") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = editProfilePlate, onValueChange = { editProfilePlate = it.take(30) }, label = { Text("شماره پلاک") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = editProfilePrimaryDestination, onValueChange = { editProfilePrimaryDestination = it.take(100) }, label = { Text("مقصد اصلی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = editProfileDestinations, onValueChange = { editProfileDestinations = it.take(1500) }, label = { Text("مقصدهای دیگر (با ویرگول جدا کنید)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        }
                        HorizontalDivider()
                        HorizontalDivider()
                        Text("عکس پروفایل", style = MaterialTheme.typography.titleMedium)
                        if (profilePhotoUrl.isNotBlank()) AsyncImage(model = profilePhotoUrl, contentDescription = "عکس پروفایل", modifier = Modifier.size(96.dp).clip(CircleShape))
                        OutlinedButton(enabled = !profilePhotoUploading, onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (profilePhotoUploading) "در حال بارگذاری عکس..." else "انتخاب یا تغییر عکس پروفایل") }
                        HorizontalDivider()
                        Text("مدارک موردنیاز", style = MaterialTheme.typography.titleMedium)
                        val requiredDocs = if (role == UserRole.DRIVER) listOf("کارت ملی", "گواهینامه", "کارت هوشمند", "کارت خودرو") else listOf("شناسه ملی شرکت", "روزنامه رسمی", "مجوز فعالیت", "مدرک هویتی مدیرعامل")
                        requiredDocs.forEach { doc -> FilterChip(selected = selectedDriverDocType == doc, onClick = { selectedDriverDocType = doc }, label = { Text(doc) }) }
                        OutlinedButton(enabled = !driverDocUploading, onClick = { documentPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (driverDocUploading) "در حال ارسال مدرک..." else "انتخاب و ارسال مدرک انتخاب‌شده") }
                        if (driverDocMessage.isNotBlank()) Text(driverDocMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        HorizontalDivider()
                        Text("تغییر رمز عبور", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = editProfileNewPassword, onValueChange = { editProfileNewPassword = it.take(72) }, label = { Text("رمز جدید؛ حداقل ۸ نویسه") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editProfileConfirmPassword, onValueChange = { editProfileConfirmPassword = it.take(72) }, label = { Text("تکرار رمز جدید") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = !editableProfileSaving,
                            onClick = {
                                val birthDateIso = if (editProfileBirthDate.isBlank()) "" else parsePersianBirthDateToIso(editProfileBirthDate)
                                if (editProfileFirstName.isBlank() || editProfileLastName.isBlank() || editProfileMobile.isBlank()) {
                                    editableProfileMessage = "نام، نام خانوادگی و شماره موبایل را کامل کنید."
                                } else if (editProfileBirthDate.isNotBlank() && birthDateIso == null) {
                                    editableProfileMessage = "تاریخ تولد شمسی را به شکل سال/ماه/روز وارد کنید."
                                } else if (editProfileNewPassword.isNotBlank() && (editProfileNewPassword.length < 8 || editProfileNewPassword != editProfileConfirmPassword)) {
                                    editableProfileMessage = "رمز جدید باید حداقل ۸ نویسه باشد و دو رمز یکسان باشند."
                                } else {
                                    editableProfileSaving = true
                                    editableProfileMessage = "در حال ذخیره تغییرات..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            updateEditableUserProfile(
                                                userAccessToken, role, editProfileFirstName, editProfileLastName, editProfileMobile,
                                                editProfileCompanyName, editProfileActivityCity, editProfileOfficeAddress,
                                                editProfileVehicleType, editProfilePlate, editProfilePrimaryDestination,
                                                editProfileDestinations, editProfileNewPassword, birthDateIso.orEmpty()
                                            )
                                        }
                                        editableProfileMessage = result
                                        if (result.contains("موفقیت")) {
                                            userDisplayName = if (role == UserRole.COMPANY) editProfileCompanyName else (editProfileFirstName + " " + editProfileLastName).trim()
                                            editProfileNewPassword = ""
                                            editProfileConfirmPassword = ""
                                        }
                                        editableProfileSaving = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (editableProfileSaving) "در حال ذخیره..." else if (editProfileNewPassword.isNotBlank()) "ذخیره مشخصات و تغییر رمز" else "ذخیره تغییرات") }
                    }
                    OutlinedButton(
                        enabled = !recoveryBusy,
                        onClick = {
                            showPasswordRecovery = true
                            recoveryCodeSent = false
                            recoveryCode = ""
                            recoveryNewPassword = ""
                            recoveryConfirmPassword = ""
                            recoveryMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("بازیابی رمز عبور با پیامک") }
                    if (showPasswordRecovery) {
                        AlertDialog(
                            onDismissRequest = { if (!recoveryBusy) showPasswordRecovery = false },
                            title = { Text("بازیابی رمز عبور") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("کد بازیابی به شماره موبایل ثبت‌شده در پروفایل ارسال می‌شود.")
                                    if (recoveryCodeSent) {
                                        OutlinedTextField(
                                            value = recoveryCode,
                                            onValueChange = { recoveryCode = it.filter(Char::isDigit).take(6) },
                                            label = { Text("کد ۶ رقمی پیامک‌شده") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = recoveryNewPassword,
                                            onValueChange = { recoveryNewPassword = it.take(72) },
                                            label = { Text("رمز جدید؛ حداقل ۸ نویسه") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = recoveryConfirmPassword,
                                            onValueChange = { recoveryConfirmPassword = it.take(72) },
                                            label = { Text("تکرار رمز جدید") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true
                                        )
                                    }
                                    if (recoveryMessage.isNotBlank()) Text(recoveryMessage, color = MaterialTheme.colorScheme.secondary)
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !recoveryBusy && (!recoveryCodeSent || (recoveryCode.length == 6 && recoveryNewPassword.length >= 8 && recoveryNewPassword == recoveryConfirmPassword)),
                                    onClick = {
                                        recoveryBusy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                if (!recoveryCodeSent) requestPasswordRecoveryCode(userAccessToken)
                                                else confirmPasswordRecovery(userAccessToken, recoveryCode, recoveryNewPassword)
                                            }
                                            recoveryMessage = result
                                            recoveryBusy = false
                                            if (!recoveryCodeSent && result.contains("ارسال شد")) recoveryCodeSent = true
                                            else if (recoveryCodeSent && result.contains("موفقیت")) {
                                                editProfileNewPassword = ""
                                                editProfileConfirmPassword = ""
                                                showPasswordRecovery = false
                                            }
                                        }
                                    }
                                ) { Text(if (recoveryBusy) "لطفاً صبر کنید..." else if (recoveryCodeSent) "تأیید کد و تغییر رمز" else "ارسال کد بازیابی") }
                            },
                            dismissButton = {
                                TextButton(enabled = !recoveryBusy, onClick = { showPasswordRecovery = false }) { Text("بستن") }
                            }
                        )
                    }
                    if (editableProfileMessage.isNotBlank()) Text(editableProfileMessage, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
                    Text("وضعیت تأیید حساب، مدارک هویتی و سطح دسترسی فقط توسط مدیریت تغییر می‌کند.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = { showProfile = false }, modifier = Modifier.fillMaxWidth()) { Text("برگشت") }
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
                        if (adminRole == "super_admin") {
                            AdminDashboard(accessToken = adminAccessToken) { panel ->
                                selectedPanel = panel
                                adminPanelData = ""
                                adminActionMessage = ""
                                adminPanelLoading = true
                                scope.launch {
                                    adminPanelData = withContext(Dispatchers.IO) { fetchAdminPanelData(adminAccessToken, panel) }
                                    if (panel == "مدیریت رانندگان" || panel == "مدیریت شرکت‌ها" || panel == "مدیریت مدارک") {
                                        adminRecords = withContext(Dispatchers.IO) { fetchAdminRecords(adminAccessToken, panel) }
                                    }
                                    adminPanelLoading = false
                                }
                            }
                        }
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
                                    Text("تنظیمات سراسری سامانه", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("نمایش منوی مدیریتی برای مدیران غیرارشد", style = MaterialTheme.typography.bodyMedium)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (adminMenuVisibleToOtherAdmins) "روشن" else "خاموش")
                                        Switch(
                                            checked = adminMenuVisibleToOtherAdmins,
                                            enabled = !adminManageBusy,
                                            onCheckedChange = { visible ->
                                                adminManageBusy = true
                                                scope.launch {
                                                    val result = withContext(Dispatchers.IO) { setAdminMenuVisibility(adminAccessToken, visible) }
                                                    if (result.startsWith("ذخیره شد")) adminMenuVisibleToOtherAdmins = visible
                                                    adminActionMessage = result
                                                    adminManageBusy = false
                                                }
                                            }
                                        )
                                    }
                                    Text("تغییر این گزینه برای همه مدیران دیگر اعمال می‌شود و در گزارش مدیریتی ثبت خواهد شد.", style = MaterialTheme.typography.bodySmall)
                                }
                                if (selectedPanel == "مدیریت بارها") {
                                    AdminLoadManagement(accessToken = adminAccessToken)
                                    HorizontalDivider()
                                    OutlinedButton(onClick = { adminLoadPanelExpanded = !adminLoadPanelExpanded }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (adminLoadPanelExpanded) "بستن فرم اعلام بار مدیریت ▲" else "باز کردن فرم اعلام بار مدیریت ▼")
                                    }
                                    if (adminLoadPanelExpanded) {
                                    Text("اعلام بار به نام مدیریت سامانه", style = MaterialTheme.typography.titleMedium)
                                    Text("ثبت این بار با مجوز مدیر ارشد و بدون انتساب به شرکت انجام می‌شود.", style = MaterialTheme.typography.bodySmall)
                                    LoadSectionDropdown("مقصد از تهران", loadDestinationExpanded, { loadDestinationExpanded = !loadDestinationExpanded }) {
                                        OutlinedTextField(loadDestination, { loadDestination = it }, label = { Text("نام مقصد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                    LoadChoiceDropdown(
                                        label = "نوع بار",
                                        selected = loadCargoType,
                                        options = listOf("مبل", "سرویس خواب", "نهارخوری", "اثاث منزل", "لوازم اداری", "سایر"),
                                        onSelected = { loadCargoType = it }
                                    )
                                    LoadChoiceDropdown(
                                        label = "نوع ماشین",
                                        selected = loadVehicleType,
                                        options = listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار"),
                                        onSelected = { loadVehicleType = it }
                                    )
                                    LoadChoiceDropdown(
                                        label = "نوع اعلام بار",
                                        selected = loadLoadingType,
                                        options = listOf("روباری", "دربست", "نیم بار"),
                                        onSelected = { loadLoadingType = it }
                                    )
                                    LoadSectionDropdown("کرایه", loadPriceExpanded, { loadPriceExpanded = !loadPriceExpanded }) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = loadPriceMode == "fixed", onClick = { loadPriceMode = "fixed" }, label = { Text("مبلغ مشخص") })
                                            FilterChip(selected = loadPriceMode == "negotiable", onClick = { loadPriceMode = "negotiable" }, label = { Text("توافقی") })
                                        }
                                        if (loadPriceMode == "fixed") OutlinedTextField(loadFreightAmount, { loadFreightAmount = it.filter(Char::isDigit).take(18) }, label = { Text("مبلغ کرایه (تومان)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                    LoadSectionDropdown("تعداد بار", loadQuantityExpanded, { loadQuantityExpanded = !loadQuantityExpanded }) {
                                        OutlinedTextField(loadQuantity, { loadQuantity = it.filter(Char::isDigit).take(5) }, label = { Text("تعداد اقلام") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    }
                                    LoadSectionDropdown("توضیحات بار", loadDescriptionExpanded, { loadDescriptionExpanded = !loadDescriptionExpanded }) {
                                        OutlinedTextField(loadDescription, { loadDescription = it.take(3000) }, label = { Text("توضیحات (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                                    }
                                    OutlinedButton(onClick = {
                                        if (loadDestination.trim().length < 2 || loadCargoType.trim().length < 2 || (loadPriceMode == "fixed" && (loadFreightAmount.isBlank() || loadFreightAmount.toLongOrNull() == null || loadFreightAmount.toLong() <= 0))) {
                                            adminActionMessage = "مقصد، نوع بار و کرایه را کامل کنید."
                                        } else {
                                            loadDrafts.add(LoadDraft(loadDestination.trim(), loadCargoType.trim(), loadVehicleType, loadLoadingType, loadPriceMode, if (loadPriceMode == "fixed") loadFreightAmount else "0", (if (loadQuantity.isNotBlank()) "تعداد: ${loadQuantity.trim()}\n" else "") + loadDescription.trim()))
                                            loadDestination = ""
                                            loadDescription = ""
                                            loadQuantity = "1"
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
                                    }
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
                                                if (selectedPanel == "مدیریت مدارک" && adminLoggedIn && adminRole == "super_admin" && record.mediaUrl.isNotBlank()) {
                                                    val lowerUrl = record.mediaUrl.substringBefore("?").lowercase()
                                                    if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".webp")) {
                                                        AsyncImage(model = record.mediaUrl, contentDescription = "مدرک راننده", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
                                                    } else {
                                                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(record.mediaUrl) } }) { Text("بازکردن فایل مدرک") }
                                                    }
                                                }
                                                if (selectedPanel == "مدیریت مدارک") {
                                                    if (adminLoggedIn && adminRole == "super_admin" && record.mediaUrl.isNotBlank()) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                pendingAdminDocumentUrl = record.mediaUrl
                                                                val extension = record.mediaUrl.substringBefore("?").substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{2,5}")) } ?: "file"
                                                                val safeName = record.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "document" }
                                                                saveAdminDocumentLauncher.launch("$safeName.$extension")
                                                            },
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) { Text("دانلود مدرک") }
                                                    }
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
                    Text("نشست مدیر روی این دستگاه محفوظ می‌ماند؛ خروج فقط با حذف حساب توسط مدیر ارشد اصلی یا ابطال امنیتی همان حساب انجام می‌شود.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
                            if (mobile.isNotBlank()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("شماره موبایل")
                                    Text(mobile)
                                }
                                HorizontalDivider()
                            }
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
                    if (role == UserRole.COMPANY) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("سوابق بارهای من", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "فقط بارهایی که با این حساب شرکت ثبت شده‌اند در این بخش نمایش داده می‌شوند.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CompanyLoadManagement(accessToken = userAccessToken)
                            }
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
                    if (role != UserRole.COMPANY) {
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
                    }
                    if (role == UserRole.COMPANY && userStatus != "approved") {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("پنل اعلام بار شرکت", style = MaterialTheme.typography.titleMedium)
                                Text("برای دریافت آخرین وضعیت تأیید، دکمه زیر را بزنید. پس از تأیید مدیر ارشد، پنل ثبت و انتشار بار فعال می‌شود.", style = MaterialTheme.typography.bodySmall)
                                Button(
                                    enabled = !companyApprovalRefreshing,
                                    onClick = {
                                        companyApprovalRefreshing = true
                                        scope.launch {
                                            val saved = DeviceSessionStore.load(context)
                                            val refreshed = if (saved != null && saved.second == UserRole.COMPANY.name) {
                                                withContext(Dispatchers.IO) { restoreDeviceSession(saved.first, UserRole.COMPANY) }
                                            } else UserLoginResult(false, "نشست شرکت پیدا نشد؛ یک‌بار خارج و دوباره وارد شوید.")
                                            companyApprovalRefreshing = false
                                            if (refreshed.success && refreshed.refreshToken.isNotBlank()) {
                                                DeviceSessionStore.save(context, refreshed.refreshToken, UserRole.COMPANY.name)
                                                userAccessToken = refreshed.accessToken
                                                userStatus = refreshed.status
                                                userDisplayName = refreshed.displayName
                        username = refreshed.username
                                                profilePhotoUrl = refreshed.photoUrl
                                                if (refreshed.status == "approved") {
                                                    companyHallExpanded = false
                                                    companyLoadPanelExpanded = true
                                                    message = "شرکت تأیید شد؛ پنل اعلام بار فعال است."
                                                } else {
                                                    message = "وضعیت شرکت هنوز در انتظار تأیید است."
                                                }
                                            } else {
                                                message = refreshed.message
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (companyApprovalRefreshing) "در حال بررسی وضعیت..." else "بررسی تأیید و باز کردن پنل")
                                }
                            }
                        }
                    }
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
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { companyPanelV2Selected = false },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("پنل فعلی") }
                                    Button(
                                        onClick = { companyPanelV2Selected = true },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("پنل جدید") }
                                }
                                if (companyPanelV2Selected) {
                                    CompanyPanelV2(
                                        accessToken = userAccessToken,
                                        onBackToOldPanel = { companyPanelV2Selected = false }
                                    )
                                } else {

                                    Box(
                                        Modifier.fillMaxWidth()
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                                                ),
                                                shape = MaterialTheme.shapes.extraLarge
                                            )
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Image(
                                                    painter = painterResource(id = R.drawable.elambar_logo),
                                                    contentDescription = "نشان کامیون اعلام بار مبل",
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("ثبت بار جدید", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                    Text("فرم ثبت بار شرکت؛ پس از انتشار، بار به سالن اعلام بار منتقل می‌شود.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("مبدأ ثابت: تهران", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                                                    Text("بدون محدودیت تعداد", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Text("برای ثبت بارهای بعدی، پس از انتشار از سالن اعلام بار گزینه «ثبت بار جدید» را بزنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                    LoadSectionDropdown("مقصد", loadDestinationExpanded, { loadDestinationExpanded = !loadDestinationExpanded }) {
                                        OutlinedTextField(loadDestination, { loadDestination = it.take(1200) }, label = { Text("مقصد یا مقصدها (با ویرگول جدا کنید)") }, supportingText = { Text("مثال: اصفهان، شیراز، مشهد") }, singleLine = false, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth())
                                    }
                                    LoadChoiceDropdown(
                                        label = "نوع بار",
                                        selected = loadCargoType,
                                        options = listOf("مبل", "سرویس خواب", "نهارخوری", "اثاث منزل", "لوازم اداری", "سایر"),
                                        onSelected = { loadCargoType = it }
                                    )
                                    Text("نوع ماشین", style = MaterialTheme.typography.titleSmall)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { v ->
                                            FilterChip(selected = loadVehicleType == v, onClick = { loadVehicleType = v }, label = { Text(v) })
                                        }
                                    }
                                    LoadChoiceDropdown(
                                        label = "نوع اعلام بار",
                                        selected = loadLoadingType,
                                        options = listOf("روباری", "دربست", "نیم بار"),
                                        onSelected = { loadLoadingType = it }
                                    )
                                    LoadSectionDropdown("کرایه", loadPriceExpanded, { loadPriceExpanded = !loadPriceExpanded }) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = loadPriceMode == "fixed", onClick = { loadPriceMode = "fixed" }, label = { Text("مبلغ مشخص") })
                                            FilterChip(selected = loadPriceMode == "negotiable", onClick = { loadPriceMode = "negotiable" }, label = { Text("توافقی") })
                                        }
                                        if (loadPriceMode == "fixed") OutlinedTextField(loadFreightAmount, { loadFreightAmount = it.filter(Char::isDigit).take(18) }, label = { Text("مبلغ کرایه (تومان)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                    LoadSectionDropdown("توضیحات بار", loadDescriptionExpanded, { loadDescriptionExpanded = !loadDescriptionExpanded }) {
                                        OutlinedTextField(loadDescription, { loadDescription = it.take(3000) }, label = { Text("توضیحات (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                                    }
                                    Button(enabled = !loadPublishing, onClick = {
                                        val destinations = loadDestination
                                            .split(',', '،', ';', '\n')
                                            .map { it.trim() }
                                            .filter { it.length >= 2 }
                                            .distinctBy { it.lowercase() }
                                        if (destinations.isEmpty() || destinations.any { it.length > 100 } || loadCargoType.trim().length < 2 || (loadPriceMode == "fixed" && (loadFreightAmount.isBlank() || loadFreightAmount.toLongOrNull() == null || loadFreightAmount.toLong() <= 0))) {
                                            loadActionMessage = "حداقل یک مقصد معتبر، نوع بار و کرایه را کامل کنید."
                                        } else {
                                            loadPublishing = true
                                            loadActionMessage = "در حال ثبت و انتشار ${destinations.size} بار..."
                                            val currentLoads = destinations.map { destination ->
                                                LoadDraft(
                                                    destination, loadCargoType.trim(), loadVehicleType,
                                                    loadLoadingType, loadPriceMode,
                                                    if (loadPriceMode == "fixed") loadFreightAmount else "0",
                                                    loadDescription.trim()
                                                )
                                            }
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    publishLoadBatch(userAccessToken, currentLoads)
                                                }
                                                loadPublishing = false
                                                loadActionMessage = result
                                                if (result.startsWith("ثبت شد")) {
                                                    loadDrafts.clear()
                                                    loadDestination = ""
                                                    loadFreightAmount = ""
                                                    loadDescription = ""
                                                    companyLoadPanelExpanded = false
                                                    companyHallExpanded = true
                                                }
                                            }
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (loadPublishing) "در حال ثبت..." else "ثبت و انتشار بار")
                                    }
                                }
                                if (loadActionMessage.isNotBlank()) Text(loadActionMessage, color = MaterialTheme.colorScheme.secondary)
                                if (companyHallExpanded) {
                                Button(onClick = { companyHallExpanded = false; companyLoadPanelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("＋ ثبت بار جدید")
                                }
                                Text("سالن اعلام بار شرکت", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                Text("بارهای منتشرشده شرکت و ابزار مدیریت آن‌ها در این بخش قرار دارند.", style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                                Text("سالن انتشار اعلام بار | بارهای ثبت‌شده شرکت", style = MaterialTheme.typography.titleMedium)
                                CompanyLoadManagement(accessToken = userAccessToken)
                                HorizontalDivider()
                                Text("جست‌وجوی راننده مناسب", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(driverSearchDestination, { driverSearchDestination = it }, label = { Text("مقصد موردنظر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { v ->
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
                                }
                                else if (!companyLoadPanelExpanded) {
                                    Text("بخش‌های شرکت", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("فقط فرم ثبت بار را تکمیل کنید؛ پس از انتشار، بارها به سالن اعلام بار منتقل می‌شوند.", style = MaterialTheme.typography.bodyMedium)
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("پنل اعلام بار", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text("ثبت بار تکی یا چند بار پشت سر هم؛ پس از انتشار، بارها در سالن اعلام بار نمایش داده می‌شوند.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Button(onClick = { companyHallExpanded = false; companyLoadPanelExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("ورود به پنل اعلام بار") }
                                        }
                                    }
                                }
                            } else {
                                Text("سالن عمومی بار", style = MaterialTheme.typography.titleLarge)
                                Text("همه بارهای منتشرشده در این سالن نمایش داده می‌شوند؛ حتی اگر خودرو یا مقصد با مشخصات شما یکسان نباشد.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = !publicHallLoading, onClick = {
                                    publicHallLoading = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { fetchPublicHallLoads(userAccessToken) }
                                        publicHallMessage = result.first
                                        publicHallLoads = result.second
                                        publicHallLoading = false
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (publicHallLoading) "در حال دریافت سالن عمومی..." else "بروزرسانی سالن عمومی بار")
                                }
                                Text(publicHallMessage, style = MaterialTheme.typography.bodySmall)
                                publicHallLoads.forEach { load ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text("تهران ← ${load.destination}", style = MaterialTheme.typography.titleMedium)
                                            Text("${load.cargoType} | ${load.vehicleType} | ${load.loadingType}")
                                            Text(if (load.freightAmount == "0") "کرایه: توافقی / اعلام‌نشده" else "کرایه: ${load.freightAmount} تومان")
                                            if (load.companyName.isNotBlank()) Text("شرکت: ${load.companyName}")
                                            if (load.companyMobile.isNotBlank()) {
                                                Text("شماره تماس: ${load.companyMobile}")
                                                OutlinedButton(
                                                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${load.companyMobile}"))) },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text("تماس با شرکت") }
                                            } else {
                                                Text("پس از دریافت بار، امکان تماس با شرکت در بخش بارهای گرفته‌شده فعال می‌شود.", style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (load.description.isNotBlank()) Text(load.description, style = MaterialTheme.typography.bodySmall)
                                            Button(enabled = acceptingLoadId.isBlank(), onClick = {
                                                acceptingLoadId = load.id
                                                scope.launch {
                                                    val accepted = withContext(Dispatchers.IO) { acceptDriverLoad(userAccessToken, load.id) }
                                                    publicHallMessage = accepted
                                                    val refreshed = withContext(Dispatchers.IO) { fetchPublicHallLoads(userAccessToken) }
                                                    publicHallLoads = refreshed.second
                                                    if (refreshed.first.isNotBlank()) publicHallMessage += "\\n" + refreshed.first
                                                    acceptingLoadId = ""
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (acceptingLoadId == load.id) "در حال ثبت..." else "درخواست دریافت این بار")
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider()
                                Text("بارهای متناسب با خودرو و مقصدهای شما", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(enabled = !matchedLoadsLoading, onClick = {
                                    matchedLoadsLoading = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { fetchMatchedLoadsDetailed(userAccessToken) }
                                        matchedLoadsText = result.first
                                        matchedLoads = result.second
                                        acceptedLoadContactsLoading = true
                                        acceptedLoadContacts = withContext(Dispatchers.IO) { fetchAcceptedLoadContacts(userAccessToken) }
                                        acceptedLoadContactsLoading = false
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
                                                    acceptedLoadContactsLoading = true
                                                    acceptedLoadContacts = withContext(Dispatchers.IO) { fetchAcceptedLoadContacts(userAccessToken) }
                                                    acceptedLoadContactsLoading = false
                                                    acceptingLoadId = ""
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (acceptingLoadId == load.id) "در حال ثبت..." else "گرفتن این بار")
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider()
                                Text("بارهایی که شما گرفته‌اید", style = MaterialTheme.typography.titleMedium)
                                if (acceptedLoadContactsLoading) {
                                    CircularProgressIndicator()
                                } else if (acceptedLoadContacts.isEmpty()) {
                                    Text("هنوز باری به نام شما ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    acceptedLoadContacts.forEach { accepted ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("تهران ← ${accepted.destination}", style = MaterialTheme.typography.titleMedium)
                                                Text("شرکت: ${accepted.companyName}")
                                                if (accepted.managerName.isNotBlank()) Text("مسئول: ${accepted.managerName}")
                                                Button(
                                                    enabled = accepted.mobile.isNotBlank(),
                                                    onClick = {
                                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + accepted.mobile)))
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text(if (accepted.mobile.isBlank()) "اطلاعات تماس در دسترس نیست" else "تماس با شرکت") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> Text("وضعیت حساب: $userStatus", textAlign = TextAlign.Center)
                    }
                    Text("ورود شما روی این دستگاه محفوظ می‌ماند؛ خروج فقط با حذف حساب توسط مدیر ارشد اصلی انجام می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
                            label = { Text("نام کاربری یا شماره موبایل") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrect = false),
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
                            listOf("وانت مبلی", "کامیونت مبلی", "کامیون ۶ متری", "کامیون دربستی کار", "خاور مبلی پتو دار").forEach { v ->
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
                                                DeviceSessionStore.save(context, result.refreshToken, "ADMIN")
                                                adminProfilePhotoUrl = withContext(Dispatchers.IO) { fetchAdminProfilePhoto(result.accessToken) }
                                                adminMenuVisibleToOtherAdmins = withContext(Dispatchers.IO) { fetchAdminMenuVisibility(result.accessToken) }
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
                                             username = result.username.ifBlank { username }
                                            userStatus = result.status
                                            userAccessToken = result.accessToken
                                            profilePhotoUrl = result.photoUrl
                                            DeviceSessionStore.save(context, result.refreshToken, role.name)
                                            if (role == UserRole.DRIVER) {
                                                acceptedLoadContactsLoading = true
                                                acceptedLoadContacts = withContext(Dispatchers.IO) { fetchAcceptedLoadContacts(result.accessToken) }
                                                acceptedLoadContactsLoading = false
                                            }
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
                                             username = result.username.ifBlank { username }
                                            userStatus = result.status
                                            userAccessToken = result.accessToken
                                            profilePhotoUrl = result.photoUrl
                                            DeviceSessionStore.save(context, result.refreshToken, role.name)
                                            if (role == UserRole.DRIVER) {
                                                acceptedLoadContactsLoading = true
                                                acceptedLoadContacts = withContext(Dispatchers.IO) { fetchAcceptedLoadContacts(result.accessToken) }
                                                acceptedLoadContactsLoading = false
                                            }
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

@Composable
private fun PublicLoadHallScreen(
    loads: List<MatchedLoad>,
    message: String,
    loading: Boolean,
    acceptingLoadId: String,
    canAccept: Boolean,
    onRefresh: () -> Unit,
    onAccept: (String) -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("سالن عمومی اعلام بار", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("بارهای منتشرشده برای همه کاربران واردشده قابل مشاهده است. شماره تماس شرکت نیز در هر آگهی نمایش داده می‌شود.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "در حال بروزرسانی سالن..." else "بروزرسانی سالن عمومی")
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (loads.isEmpty() && !loading) Text("در حال حاضر بار منتشرشده‌ای برای نمایش وجود ندارد.")
        loads.forEach { load ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${load.origin} ← ${load.destination}", style = MaterialTheme.typography.titleMedium)
                    Text("${load.cargoType} | ${load.vehicleType} | ${load.loadingType}")
                    Text(if (load.freightAmount == "0") "کرایه: توافقی / اعلام‌نشده" else "کرایه: ${load.freightAmount} تومان")
                    if (load.companyName.isNotBlank()) Text("شرکت: ${load.companyName}")
                    if (load.companyMobile.isNotBlank()) {
                        Text("شماره تماس: ${load.companyMobile}", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${load.companyMobile}"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("تماس با شرکت") }
                    } else {
                        Text("پس از دریافت بار، امکان تماس با شرکت در بخش بارهای گرفته‌شده فعال می‌شود.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (load.description.isNotBlank()) Text(load.description, style = MaterialTheme.typography.bodySmall)
                    if (canAccept) {
                        Button(enabled = acceptingLoadId.isBlank(), onClick = { onAccept(load.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (acceptingLoadId == load.id) "در حال ثبت..." else "درخواست دریافت این بار")
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

private fun normalizePersianDigits(value: String): String = value
    .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
    .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
    .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
    .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')

private fun parsePersianBirthDateToIso(value: String): String? {
    return runCatching {
        val normalized = normalizePersianDigits(value.trim()).replace('-', '/')
        val parser = android.icu.text.SimpleDateFormat("yyyy/MM/dd", android.icu.util.ULocale("fa_IR@calendar=persian"))
        parser.isLenient = false
        parser.timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
        val date = parser.parse(normalized) ?: throw IllegalArgumentException("invalid date")
        val formatter = android.icu.text.SimpleDateFormat("yyyy-MM-dd", android.icu.util.ULocale.US)
        formatter.timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
        formatter.format(date)
    }.getOrNull()
}

private fun formatBirthDateForDisplay(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val parser = android.icu.text.SimpleDateFormat("yyyy-MM-dd", android.icu.util.ULocale.US)
        parser.timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
        val date = parser.parse(value) ?: throw IllegalArgumentException("invalid date")
        val formatter = android.icu.text.SimpleDateFormat("yyyy/MM/dd", android.icu.util.ULocale("fa_IR@calendar=persian"))
        formatter.timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
        formatter.format(date)
    }.getOrDefault("")
}

private fun uploadAccountDocument(context: Context, uri: Uri, token: String, documentType: String, role: UserRole): String {
    var upload: HttpURLConnection? = null
    var insert: HttpURLConnection? = null
    var cleanup: HttpURLConnection? = null
    return try {
        val payload = token.split(".").getOrNull(1) ?: return "نشست ورود نامعتبر است."
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ownerId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (ownerId.isBlank()) return "شناسه حساب دریافت نشد."
        val isDriver = role == UserRole.DRIVER
        if (!isDriver && role != UserRole.COMPANY) return "نوع حساب برای ارسال مدرک مجاز نیست."
        val bucket = if (isDriver) "driver-documents" else "company-documents"
        val table = if (isDriver) "driver_documents" else "company_documents"
        val ownerColumn = if (isDriver) "driver_id" else "company_id"
        val mime = context.contentResolver.getType(uri) ?: return "نوع فایل قابل تشخیص نیست."
        val allowed = listOf("application/pdf", "image/jpeg", "image/png", "image/webp")
        if (mime !in allowed) return "فقط PDF، JPG، PNG و WEBP مجاز است."
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "خواندن فایل انجام نشد."
        if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return "حجم مدرک باید کمتر از ۱۰ مگابایت باشد."
        val ext = when(mime) { "application/pdf" -> "pdf"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val safeType = documentType.replace(Regex("[^آ-یA-Za-z0-9_-]"), "_")
        val path = ownerId + "/" + safeType + "-" + UUID.randomUUID().toString() + "." + ext
        upload = (URL(SUPABASE_URL + "/storage/v1/object/" + bucket + "/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", mime)
        }
        upload.outputStream.use { it.write(bytes) }
        val upCode = upload.responseCode
        if (upCode !in 200..299) return "بارگذاری مدرک مجاز نشد (کد " + upCode + ")."
        insert = (URL(SUPABASE_URL + "/rest/v1/" + table).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "return=minimal")
        }
        val row = JSONObject().put(ownerColumn, ownerId).put("document_type", documentType)
            .put("storage_path", path).put("review_status", "pending")
        insert.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
        val insertCode = insert.responseCode
        if (insertCode !in 200..299) {
            cleanup = (URL(SUPABASE_URL + "/storage/v1/object/" + bucket + "/" + path).openConnection() as HttpURLConnection).apply {
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
        val fileLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (fileLength == 0L) return "فایل تصویر خالی است."
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val path = subject + "/profile." + ext
        upload = (URL(SUPABASE_URL + "/storage/v1/object/profile-photos/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", mime)
            setRequestProperty("x-upsert", "true")
            if (fileLength > 0L) setFixedLengthStreamingMode(fileLength) else setChunkedStreamingMode(64 * 1024)
        }
        var uploadedBytes = 0L
        val photoInput = context.contentResolver.openInputStream(uri) ?: return "خواندن تصویر انجام نشد."
        upload.outputStream.use { output ->
            photoInput.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    uploadedBytes += count
                }
            }
        }
        if (uploadedBytes == 0L) return "فایل تصویر خالی است."
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
            if (token.isBlank() || role !in listOf("DRIVER","COMPANY","ADMIN")) null else token to role
        } catch (_: Exception) { clear(context); null }
    }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}

private object EncryptedDraftStore {
    private const val PREFS = "elambar_load_drafts"
    private const val ALIAS = "elambar_load_draft_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, owner: String, value: String) {
        if (owner.isBlank()) return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(owner, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            clear(context, owner)
        }
    }

    fun load(context: Context, owner: String): String? {
        if (owner.isBlank()) return null
        return try {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(owner, null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context, owner)
            null
        }
    }

    private fun clear(context: Context, owner: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(owner).apply()
    }
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
    var auth: HttpURLConnection? = null
    var profile: HttpURLConnection? = null
    return try {
        auth = (URL(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Content-Type", "application/json")
        }
        auth.outputStream.use { it.write(JSONObject().put("refresh_token", refreshToken).toString().toByteArray(Charsets.UTF_8)) }
        val code = auth.responseCode
        val stream = if (code in 200..299) auth.inputStream else auth.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return UserLoginResult(false, "نشست منقضی شده است.")
        val json = JSONObject(raw)
        val user = json.optJSONObject("user") ?: return UserLoginResult(false, "حساب پیدا نشد.")
        val access = json.optString("access_token")
        val fresh = json.optString("refresh_token")
        val id = user.optString("id")
        if (access.isBlank() || fresh.isBlank() || id.isBlank()) return UserLoginResult(false, "نشست جدید ناقص است.")

        if (role == UserRole.ADMIN) {
            profile = (URL(SUPABASE_URL + "/rest/v1/admin_users?select=role,is_blocked,access_expires_at&user_id=eq." + id + "&limit=1").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Authorization", "Bearer " + access)
            }
            val pc = profile.responseCode
            val ps = if (pc in 200..299) profile.inputStream else profile.errorStream
            if (pc !in 200..299) return UserLoginResult(false, "بررسی دسترسی مدیر ممکن نشد.")
            val rows = JSONArray(ps?.bufferedReader()?.use { it.readText() }.orEmpty())
            if (rows.length() == 0) return UserLoginResult(false, "حساب مدیر حذف شده یا دسترسی ندارد.")
            val p = rows.getJSONObject(0)
            val adminRole = p.optString("role")
            val expires = p.optString("access_expires_at")
            val expired = expires.isNotBlank() && expires != "null" && runCatching { java.time.Instant.parse(expires).isBefore(java.time.Instant.now()) }.getOrDefault(true)
            if (p.optBoolean("is_blocked", false) || expired || adminRole !in setOf("super_admin", "senior_admin", "admin")) {
                return UserLoginResult(false, "دسترسی حساب مدیر مسدود، منقضی یا حذف شده است.")
            }
            return UserLoginResult(true, "نشست مدیر بازیابی شد.", "مدیر", adminRole, access, fresh, "")
        }

        val table = if (role == UserRole.DRIVER) "drivers" else "companies"
        val select = if (role == UserRole.DRIVER) "status,first_name,last_name,full_name,profile_photo_path" else "status,first_name,last_name,company_name,manager_name,profile_photo_path"
        profile = (URL(SUPABASE_URL + "/rest/v1/" + table + "?select=" + select + "&id=eq." + id + "&limit=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY); setRequestProperty("Authorization", "Bearer " + access)
        }
        val pc = profile.responseCode
        val ps = if (pc in 200..299) profile.inputStream else profile.errorStream
        if (pc !in 200..299) return UserLoginResult(false, "بررسی دسترسی حساب ممکن نشد.")
        val rows = JSONArray(ps?.bufferedReader()?.use { it.readText() }.orEmpty())
        if (rows.length() == 0) return UserLoginResult(false, "پرونده حساب پیدا نشد.")
        val p = rows.getJSONObject(0)
        val status = p.optString("status")
        if (status !in listOf("pending", "approved")) return UserLoginResult(false, "دسترسی حساب فعال نیست.")
        val name = if (role == UserRole.COMPANY) p.optString("company_name").ifBlank { p.optString("first_name") + " " + p.optString("last_name") } else (p.optString("first_name") + " " + p.optString("last_name")).trim()
        UserLoginResult(true, "نشست قبلی بازیابی شد.", name, status, access, fresh, profilePhotoPublicUrl(p.optString("profile_photo_path")), user.optJSONObject("user_metadata")?.optString("username").orEmpty())
    } catch (_: Exception) {
        UserLoginResult(false, "ارتباط با سامانه برقرار نشد.")
    } finally {
        auth?.disconnect()
        profile?.disconnect()
    }
}

private data class AdminAccount(
    val userId: String,
    val email: String,
    val role: String,
    val isBlocked: Boolean = false
)

internal fun adminRpc(accessToken: String, functionName: String, body: JSONObject): Pair<Int, String> {
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

private fun fetchAdminMenuVisibility(accessToken: String): Boolean {
    val (code, body) = adminRpc(accessToken, "admin_get_menu_visibility", JSONObject())
    return code in 200..299 && body.trim().equals("true", ignoreCase = true)
}

private fun setAdminMenuVisibility(accessToken: String, visible: Boolean): String {
    val (code, _) = adminRpc(accessToken, "admin_set_menu_visibility", JSONObject().put("p_visible", visible))
    return if (code in 200..299) "ذخیره شد؛ نمایش منوی مدیران دیگر به‌روزرسانی شد."
    else "ذخیره تنظیمات منو ناموفق بود؛ فقط مدیر ارشد اصلی اجازه تغییر دارد."
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
        AdminLoginResult(true, "ورود با سطح دسترسی $roleTitle انجام شد.", accessToken, adminRole, authJson.optString("refresh_token"))
    } catch (_: Exception) {
        AdminLoginResult(false, "ارتباط با سرور برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.")
    } finally {
        authConnection?.disconnect()
        profileConnection?.disconnect()
    }
}

private fun fetchEditableUserProfile(token: String, role: UserRole): JSONObject? {
    var connection: HttpURLConnection? = null
    return try {
        val userId = runCatching {
            val payload = token.split(".").getOrNull(1) ?: return null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        }.getOrDefault("")
        if (userId.isBlank()) return null
        val table = if (role == UserRole.DRIVER) "drivers" else "companies"
        val columns = if (role == UserRole.DRIVER)
            "first_name,last_name,mobile,birth_date,vehicle_type,license_plate,primary_destination,destinations"
        else "first_name,last_name,mobile,birth_date,company_name,activity_city,office_address"
        val url = "$SUPABASE_URL/rest/v1/$table?select=$columns&id=eq.$userId&limit=1"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) null else JSONArray(body).optJSONObject(0)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun updateEditableUserProfile(
    token: String, role: UserRole, firstName: String, lastName: String, mobile: String,
    companyName: String, activityCity: String, officeAddress: String,
    vehicleType: String, plate: String, primaryDestination: String, destinationsText: String,
    newPassword: String, birthDate: String
): String {
    var connection: HttpURLConnection? = null
    return try {
        val destinations = JSONArray()
        destinationsText.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(29).forEach { destinations.put(it) }
        val body = JSONObject()
            .put("action", "update_profile")
            .put("first_name", firstName.trim())
            .put("last_name", lastName.trim())
            .put("mobile", mobile.trim())
            .put("new_password", newPassword)
            .put("birth_date", birthDate)
            .put("company_name", companyName.trim())
            .put("activity_city", activityCity.trim())
            .put("office_address", officeAddress.trim())
            .put("vehicle_type", vehicleType.trim())
            .put("license_plate", plate.trim())
            .put("primary_destination", primaryDestination.trim())
            .put("destinations", destinations)
        connection = (URL("$SUPABASE_URL/functions/v1/username-auth-v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 200..299) JSONObject(response).optString("message", "تغییرات با موفقیت ذخیره شد.")
        else JSONObject(response).optString("error", "ذخیره تغییرات انجام نشد.")
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد؛ دوباره تلاش کنید."
    } finally {
        connection?.disconnect()
    }
}

private fun requestPasswordRecoveryCode(token: String): String =
    callPasswordRecovery("recovery_send", token, "", "")

private fun confirmPasswordRecovery(token: String, code: String, newPassword: String): String =
    callPasswordRecovery("recovery_confirm", token, code, newPassword)

private fun callPasswordRecovery(action: String, token: String, code: String, newPassword: String): String {
    var connection: HttpURLConnection? = null
    return try {
        val body = JSONObject()
            .put("action", action)
            .put("code", code)
            .put("new_password", newPassword)
        connection = (URL("$SUPABASE_URL/functions/v1/username-auth-v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val normalizedCode = code.map { ch ->
            when (ch) {
                in '۰'..'۹' -> ('0'.code + (ch - '۰')).toChar()
                in '٠'..'٩' -> ('0'.code + (ch - '٠')).toChar()
                else -> ch
            }
        }.joinToString("").filter { it in '0'..'9' }
        body.put("code", normalizedCode)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status in 200..299) JSONObject(response).optString("message", "عملیات با موفقیت انجام شد.")
        else JSONObject(response).optString("error", "عملیات بازیابی رمز انجام نشد.")
    } catch (_: Exception) {
        "ارتباط با سامانه برقرار نشد؛ دوباره تلاش کنید."
    } finally {
        connection?.disconnect()
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
        val body = JSONObject().put("action", "login").put("username", username.trim().lowercase()).put("account_type", if (role == UserRole.DRIVER) "driver" else "company").put("password", password).toString()
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
                refreshToken = refreshToken,
                username = user.optJSONObject("user_metadata")?.optString("username").orEmpty().ifBlank { username }
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


internal fun publishLoadBatch(accessToken: String, drafts: List<LoadDraft>, publisherType: String = "company"): String {
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



internal fun fetchCompanyDrivers(accessToken: String, vehicle: String, destination: String): String {
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

private fun normalizeMatchText(value: String): String =
    value.trim()
        .replace('ي', 'ی')
        .replace('ى', 'ی')
        .replace('ك', 'ک')
        .replace("\\u200c", " ")
        .replace(Regex("\\s+"), " ")
        .lowercase()

private fun normalizeVehicleType(value: String): String {
    val normalized = normalizeMatchText(value)
    return when (normalized) {
        "وانت" -> "وانت مبلی"
        else -> normalized
    }
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
            if (value.isNotBlank()) destinations.add(normalizeMatchText(value))
        }
        val primary = driver.optString("primary_destination").trim()
        if (primary.isNotBlank()) destinations.add(normalizeMatchText(primary))
        if (vehicle.isBlank() || destinations.isEmpty()) return "ابتدا نوع خودرو و مقصدهای خود را در پروفایل کامل کنید." to emptyList()
        val loadsUrl = "$SUPABASE_URL/rest/v1/loads?select=id,origin,destination,cargo_type,vehicle_type,freight_amount,description,loading_type&status=eq.published&assigned_driver_id=is.null&order=created_at.desc&limit=200"
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
            normalizeVehicleType(item.optString("vehicle_type")).equals(normalizeVehicleType(vehicle), ignoreCase = true) &&
                destinations.contains(normalizeMatchText(item.optString("destination")) )
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

private fun fetchPublicHallLoads(accessToken: String): Pair<String, List<MatchedLoad>> {
    if (accessToken.isBlank()) return "برای مشاهده سالن عمومی وارد حساب کاربری شوید." to emptyList()
    var connection: HttpURLConnection? = null
    return try {
        val url = "$SUPABASE_URL/rest/v1/rpc/get_public_load_hall"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(JSONObject().toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت سالن عمومی ناموفق بود (کد $code)." to emptyList()
        val rows = JSONArray(raw)
        val loads = (0 until rows.length()).map { rows.getJSONObject(it) }.map { item ->
            MatchedLoad(
                id = item.optString("id"),
                origin = item.optString("origin", "تهران"),
                destination = item.optString("destination"),
                cargoType = item.optString("cargo_type"),
                vehicleType = item.optString("vehicle_type"),
                loadingType = item.optString("loading_type"),
                freightAmount = item.optString("freight_amount", "0"),
                description = item.optString("description").takeUnless { it == "null" }.orEmpty(),
                companyName = item.optString("company_name").takeUnless { it == "null" }.orEmpty(),
                companyMobile = item.optString("company_mobile").takeUnless { it == "null" }.orEmpty()
            )
        }.filter { it.id.isNotBlank() }
        (if (loads.isEmpty()) "در حال حاضر بار منتشرشده‌ای در سالن عمومی وجود ندارد." else "${loads.size} بار منتشرشده در سالن عمومی موجود است.") to loads
    } catch (_: Exception) {
        "ارتباط با سالن عمومی برقرار نشد؛ اینترنت را بررسی کنید." to emptyList()
    } finally {
        connection?.disconnect()
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
                destinations.contains(normalizeMatchText(item.optString("destination")) )
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


private fun fetchAcceptedLoadContacts(accessToken: String): List<AcceptedLoadContact> {
    if (accessToken.isBlank()) return emptyList()
    var connection: HttpURLConnection? = null
    return try {
        val payload = accessToken.split(".").getOrNull(1) ?: return emptyList()
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val userId = JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        if (userId.isBlank()) return emptyList()
        val query = URLEncoder.encode(userId, "UTF-8")
        connection = (URL("$SUPABASE_URL/rest/v1/loads?select=id,destination&assigned_driver_id=eq.$query&status=eq.driver_selected&order=driver_selected_at.desc&limit=100").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (code !in 200..299) return emptyList()
        val rows = JSONArray(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        val result = mutableListOf<AcceptedLoadContact>()
        for (i in 0 until rows.length()) {
            val load = rows.getJSONObject(i)
            val id = load.optString("id")
            if (id.isBlank()) continue
            var rpc: HttpURLConnection? = null
            try {
                rpc = (URL("$SUPABASE_URL/rest/v1/rpc/get_assigned_load_company_contact").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
                    setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                rpc.outputStream.use { it.write(JSONObject().put("p_load_id", id).toString().toByteArray(Charsets.UTF_8)) }
                if (rpc.responseCode !in 200..299) continue
                val contacts = JSONArray(rpc.inputStream.bufferedReader().use { it.readText() })
                if (contacts.length() == 0) continue
                val contact = contacts.getJSONObject(0)
                result.add(AcceptedLoadContact(
                    id, load.optString("destination"), contact.optString("company_name"),
                    contact.optString("manager_name"), contact.optString("mobile")
                ))
            } catch (_: Exception) {
                // Fail closed: never show a contact when the assignment check fails.
            } finally {
                rpc?.disconnect()
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    } finally {
        connection?.disconnect()
    }
}

private fun fetchAdminRecords(accessToken: String, panel: String): List<AdminRecord> {
    if (accessToken.isBlank()) return emptyList()
    if (panel == "مدیریت مدارک") {
        fun fetchRows(table: String, select: String): JSONArray? {
            var connection: HttpURLConnection? = null
            return try {
                val url = "$SUPABASE_URL/rest/v1/$table?select=$select&order=created_at.desc&limit=100"
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 18000
                    setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) null
                else JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) { null }
            finally { connection?.disconnect() }
        }
        val driverRows = fetchRows("driver_documents", "id,driver_id,document_type,storage_path,review_status")
        val companyRows = fetchRows("company_documents", "id,company_id,document_type,storage_path,review_status")
        val result = mutableListOf<AdminRecord>()
        driverRows?.let { rows ->
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                result += AdminRecord(
                    "driver|" + item.optString("id"),
                    item.optString("document_type"),
                    "راننده: " + item.optString("driver_id"),
                    item.optString("review_status"),
                    createSignedDriverDocumentUrl(accessToken, item.optString("storage_path"), "driver-documents")
                )
            }
        }
        companyRows?.let { rows ->
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                result += AdminRecord(
                    "company|" + item.optString("id"),
                    item.optString("document_type"),
                    "شرکت: " + item.optString("company_id"),
                    item.optString("review_status"),
                    createSignedDriverDocumentUrl(accessToken, item.optString("storage_path"), "company-documents")
                )
            }
        }
        return result
    }
    val table = when (panel) {
        "مدیریت رانندگان" -> "drivers"
        "مدیریت شرکت‌ها" -> "companies"
        else -> return emptyList()
    }
    var connection: HttpURLConnection? = null
    return try {
        val select = if (table == "drivers") "id,first_name,last_name,full_name,mobile,vehicle_type,primary_destination,status"
            else "id,first_name,last_name,company_name,manager_name,mobile,status"
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
            val title = if (table == "drivers")
                (item.optString("first_name") + " " + item.optString("last_name")).trim().ifBlank { item.optString("full_name") }
            else item.optString("company_name")
            val detail = if (table == "drivers")
                item.optString("mobile") + " | " + item.optString("vehicle_type") + " | " + item.optString("primary_destination")
            else item.optString("manager_name") + " | " + item.optString("mobile")
            AdminRecord(item.optString("id"), title, detail, item.optString("status"))
        }
    } catch (_: Exception) { emptyList() }
    finally { connection?.disconnect() }
}

private fun createSignedDriverDocumentUrl(accessToken: String, path: String, bucket: String = "driver-documents"): String {
    if (accessToken.isBlank() || path.isBlank()) return ""
    var connection: HttpURLConnection? = null
    return try {
        val encodedPath = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        connection = (URL(SUPABASE_URL + "/storage/v1/object/sign/" + bucket + "/" + encodedPath).openConnection() as HttpURLConnection).apply {
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

private fun copySignedDocumentToUri(context: Context, signedUrl: String, destination: Uri): Boolean {
    if (!signedUrl.startsWith(SUPABASE_URL + "/storage/v1/object/sign/")) return false
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) return false
        val output = context.contentResolver.openOutputStream(destination, "w") ?: return false
        output.use { target -> connection.inputStream.use { input -> input.copyTo(target) } }
        true
    } catch (_: Exception) {
        runCatching { context.contentResolver.delete(destination, null, null) }
        false
    } finally {
        connection?.disconnect()
    }
}

private fun updateDriverDocumentStatus(accessToken: String, id: String, status: String): String {
    if (accessToken.isBlank() || id.isBlank() || status !in listOf("approved", "rejected")) return "درخواست تغییر وضعیت مدرک معتبر نیست."
    val parts = id.split("|", limit = 2)
    val table = if (parts.size == 2 && parts[0] == "company") "company_documents" else "driver_documents"
    val recordId = if (parts.size == 2) parts[1] else id
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(SUPABASE_URL + "/rest/v1/" + table + "?id=eq." + recordId).openConnection() as HttpURLConnection).apply {
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
