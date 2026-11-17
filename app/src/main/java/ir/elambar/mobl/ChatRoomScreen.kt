package ir.elambar.mobl

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRoomScreen(accessToken: String, role: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(accessToken) { ChatRoomRepository(accessToken) }
    val userId = remember(accessToken) {
        runCatching {
            val part = accessToken.split(".").getOrNull(1).orEmpty()
            val decoded = android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        }.getOrDefault("")
    }
    val isAdmin = role in setOf("super_admin", "senior_admin", "admin")
    val isSuperAdmin = role == "super_admin"
    var rooms by remember { mutableStateOf<List<ChatRoomItem>>(emptyList()) }
    var selectedRoom by remember { mutableStateOf<ChatRoomItem?>(null) }
    var messages by remember { mutableStateOf<List<ChatRoomMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("در حال دریافت اتاق‌های مجاز...") }
    var busy by remember { mutableStateOf(false) }
    var showCompanySettings by remember { mutableStateOf(false) }
    var showContactSearch by remember { mutableStateOf(false) }
    var contactQuery by remember { mutableStateOf("") }
    var contactResults by remember { mutableStateOf<List<ChatContactItem>>(emptyList()) }
    var companySettings by remember { mutableStateOf<List<ChatCompanyItem>>(emptyList()) }
    var showDriverSettings by remember { mutableStateOf(false) }
    var driverAccess by remember { mutableStateOf<List<ChatDriverAccess>>(emptyList()) }
    var downloadedImages by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf("") }
    var roomMembers by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeCall by remember { mutableStateOf<ChatRoomCall?>(null) }
    var incomingCall by remember { mutableStateOf<ChatRoomCall?>(null) }

    fun refreshRooms() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.listRooms() }
            rooms = result.second
            if (selectedRoom == null || result.second.none { it.id == selectedRoom?.id }) {
                selectedRoom = result.second.firstOrNull { it.type == "public" } ?: result.second.firstOrNull()
            }
            status = result.first ?: if (result.second.isEmpty()) "اتاق قابل دسترسی پیدا نشد. اگر راننده هستید، فقط با اجازه مدیر ارشد امکان مشاهده اتاق عمومی را دارید." else ""
            busy = false
        }
    }

    fun refreshMessages(room: ChatRoomItem) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.messages(room.id) }
            if (result.first == null) messages = result.second else status = result.first.orEmpty()
        }
    }

    fun startRecording() {
        if (selectedRoom == null || isRecording) return
        try {
            val file = File(context.cacheDir, "room-voice-${UUID.randomUUID()}.m4a")
            val active = MediaRecorder()
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioEncodingBitRate(64000)
            active.setAudioSamplingRate(44100)
            active.setOutputFile(file.absolutePath)
            active.prepare()
            active.start()
            recorder = active
            recordingFile = file
            isRecording = true
            status = "در حال ضبط ویس؛ برای ارسال، توقف را بزنید."
        } catch (_: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
            status = "ضبط صدا شروع نشد؛ مجوز میکروفون را بررسی کنید."
        }
    }

    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "برای ضبط ویس باید مجوز میکروفون را بدهید."
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val room = selectedRoom
        if (uri != null && room != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = when (mime.lowercase()) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                    repository.sendAttachment(room.id, userId, "image", bytes, mime, ext)
                }
                status = result
                refreshMessages(room)
                busy = false
            }
        }
    }

    fun stopAndSendVoice() {
        val file = recordingFile
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) { status = "ضبط ویس کامل نشد." }
        recorder = null
        recordingFile = null
        isRecording = false
        val room = selectedRoom
        if (file == null || room == null || !file.exists() || file.length() == 0L) { file?.delete(); return }
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) { repository.sendAttachment(room.id, userId, "voice", file.readBytes(), "audio/mp4", "m4a") }
            file.delete()
            refreshMessages(room)
            busy = false
        }
    }

    LaunchedEffect(accessToken) { refreshRooms() }
    // Refresh the room list while the inbox is open so newly created private rooms appear.
    LaunchedEffect(selectedRoom?.id, accessToken) {
        if (selectedRoom != null) return@LaunchedEffect
        while (true) {
            delay(10000)
            val result = withContext(Dispatchers.IO) { repository.listRooms() }
            rooms = result.second
            status = result.first ?: if (result.second.isEmpty()) "اتاق قابل دسترسی پیدا نشد." else ""
        }
    }
    LaunchedEffect(selectedRoom?.id) {
        val room = selectedRoom ?: return@LaunchedEffect
        messages = emptyList()
        roomMembers = if (room.type == "private") withContext(Dispatchers.IO) { repository.roomMemberIds(room.id) } else emptyList()
        refreshMessages(room)
        while (true) {
            delay(5000)
            val result = withContext(Dispatchers.IO) { repository.messages(room.id) }
            if (result.first == null) messages = result.second else status = result.first.orEmpty()
            if (room.type == "private") roomMembers = withContext(Dispatchers.IO) { repository.roomMemberIds(room.id) }
        }
    }
    LaunchedEffect(accessToken, userId) {
        while (true) {
            if (userId.isNotBlank() && activeCall == null && incomingCall == null) {
                incomingCall = withContext(Dispatchers.IO) { repository.incomingCall(userId) }
            }
            delay(2500)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { activePlayer?.release() }
            recordingFile?.delete()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("اتاق عمومی و گفتگوها", style = MaterialTheme.typography.titleLarge)
                        Text(if (isSuperAdmin) "مدیریت کامل اتاق" else if (isAdmin) "دسترسی مدیریتی بر اساس مجوز" else if (role == "DRIVER") "دسترسی راننده فقط طبق اجازه مدیر" else "گفتگوی شرکت‌ها", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onClose) { Text("برگشت") }
                }

                if (selectedRoom == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("اتاق‌های قابل دسترسی")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy, onClick = { refreshRooms() }) { Text("بروزرسانی") }
                            TextButton(onClick = { showContactSearch = !showContactSearch; contactResults = emptyList(); contactQuery = "" }) { Text(if (showContactSearch) "بستن جستجو" else "گفتگوی خصوصی جدید") }
                        }
                    }
                    if (showContactSearch) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("جستجوی شرکت یا مدیر", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = contactQuery,
                                    onValueChange = { contactQuery = it.take(80) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("نام شرکت، نام مخاطب یا شماره همراه") },
                                    singleLine = true
                                )
                                Button(enabled = !busy && contactQuery.trim().length >= 2, modifier = Modifier.fillMaxWidth(), onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { repository.searchContacts(contactQuery) }
                                        contactResults = result.second
                                        status = result.first ?: if (result.second.isEmpty()) "مخاطبی با این مشخصات پیدا نشد." else "مخاطبان پیدا شدند."
                                        busy = false
                                    }
                                }) { Text("جستجو") }
                                contactResults.forEach { contact ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(contact.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(when (contact.accountType) { "company" -> "شرکت باربری"; "driver" -> "راننده"; else -> "مدیر سامانه" }, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { repository.createPrivateRoom(contact.id, "گفتگوی خصوصی با ${contact.name}") }
                                                if (result.second != null) {
                                                    val room = ChatRoomItem(result.second!!, "private", "گفتگوی خصوصی با ${contact.name}", false)
                                                    rooms = (rooms.filterNot { it.id == room.id } + room)
                                                    selectedRoom = room
                                                    showContactSearch = false
                                                    status = "گفتگوی خصوصی ایجاد شد."
                                                } else status = result.first.orEmpty()
                                                busy = false
                                            }
                                        }) { Text("شروع گفتگو") }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rooms.forEach { room ->
                            Card(onClick = { selectedRoom = room; showCompanySettings = false }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(room.title, style = MaterialTheme.typography.titleMedium)
                                    Text(if (room.type == "public") "اتاق عمومی" else "گفتگوی خصوصی")
                                }
                            }
                        }
                    }
                } else {
                    val room = selectedRoom!!
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedRoom = null; messages = emptyList(); refreshRooms() }) { Text("فهرست اتاق‌ها") }
                        Spacer(Modifier.weight(1f))
                        Text(room.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    if (room.type == "private") {
                        val targetId = roomMembers.firstOrNull { it != userId } ?: roomMembers.firstOrNull()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = targetId != null && activeCall == null && !busy, modifier = Modifier.weight(1f), onClick = {
                                val target = targetId ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { repository.createCall(room.id, target, "audio") }
                                    activeCall = result.second
                                    status = result.first.orEmpty()
                                    busy = false
                                }
                            }) { Text("تماس صوتی") }
                            OutlinedButton(enabled = targetId != null && activeCall == null && !busy, modifier = Modifier.weight(1f), onClick = {
                                val target = targetId ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { repository.createCall(room.id, target, "video") }
                                    activeCall = result.second
                                    status = result.first.orEmpty()
                                    busy = false
                                }
                            }) { Text("تماس تصویری") }
                        }
                    }
                    if (isSuperAdmin && room.type == "public") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    status = withContext(Dispatchers.IO) { repository.setRoomLocked(room.id, !room.locked) }
                                    val result = withContext(Dispatchers.IO) { repository.listRooms() }
                                    rooms = result.second
                                    selectedRoom = result.second.firstOrNull { it.id == room.id }
                                    busy = false
                                }
                            }) { Text(if (room.locked) "بازکردن اتاق" else "قفل‌کردن ارسال") }
                            OutlinedButton(onClick = {
                                showCompanySettings = !showCompanySettings
                                showDriverSettings = false
                                if (showCompanySettings) {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { repository.companiesForModeration(room.id) }
                                        companySettings = result.second
                                        status = result.first.orEmpty()
                                        busy = false
                                    }
                                }
                            }) { Text("مجوز شرکت‌ها") }
                            OutlinedButton(onClick = {
                                showDriverSettings = !showDriverSettings
                                showCompanySettings = false
                                if (showDriverSettings) {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { repository.driversForModeration(room.id) }
                                        driverAccess = result.second
                                        status = result.first.orEmpty()
                                        busy = false
                                    }
                                }
                            }) { Text("مجوز رانندگان") }
                        }
                    }

                    if (showCompanySettings && isSuperAdmin && room.type == "public") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("کنترل دسترسی شرکت‌ها", style = MaterialTheme.typography.titleSmall)
                                companySettings.forEach { item ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("بی‌صدا")
                                            Switch(checked = item.muted, onCheckedChange = { value -> companySettings = companySettings.map { if (it.id == item.id) it.copy(muted = value) else it } })
                                            Text("فقط‌خواندنی")
                                            Switch(checked = item.readOnly, onCheckedChange = { value -> companySettings = companySettings.map { if (it.id == item.id) it.copy(readOnly = value) else it } })
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("ارسال تصویر")
                                            Switch(checked = item.canSendImages, onCheckedChange = { value -> companySettings = companySettings.map { if (it.id == item.id) it.copy(canSendImages = value) else it } })
                                            Text("ارسال ویس")
                                            Switch(checked = item.canSendVoice, onCheckedChange = { value -> companySettings = companySettings.map { if (it.id == item.id) it.copy(canSendVoice = value) else it } })
                                        }
                                        TextButton(enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                status = withContext(Dispatchers.IO) { repository.saveCompanySetting(room.id, item) }
                                                busy = false
                                            }
                                        }) { Text("ذخیره مجوز این شرکت") }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }

                    if (showDriverSettings && isSuperAdmin && room.type == "public") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("دسترسی فقط‌خواندنی رانندگان", style = MaterialTheme.typography.titleSmall)
                                Text("راننده فقط پیام‌ها را می‌بیند و امکان ارسال متن، تصویر یا ویس در اتاق عمومی ندارد.", style = MaterialTheme.typography.bodySmall)
                                driverAccess.forEach { item ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = item.canRead, onCheckedChange = { value ->
                                            driverAccess = driverAccess.map { if (it.id == item.id) it.copy(canRead = value) else it }
                                        })
                                        TextButton(enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                status = withContext(Dispatchers.IO) { repository.saveDriverReadPermission(room.id, item.id, item.canRead) }
                                                val result = withContext(Dispatchers.IO) { repository.driversForModeration(room.id) }
                                                driverAccess = result.second
                                                if (result.first != null) status += " " + result.first
                                                busy = false
                                            }
                                        }) { Text("ذخیره") }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        messages.forEach { item ->
                            val mine = item.senderId == userId
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(9.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (mine) "شما" else "عضو گفتگو", style = MaterialTheme.typography.labelSmall)
                                        if (item.pinned) Text("  • سنجاق‌شده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    when (item.type) {
                                        "voice" -> TextButton(enabled = playingId != item.id, onClick = {
                                            playingId = item.id
                                            scope.launch {
                                                val bytes = withContext(Dispatchers.IO) { repository.downloadAttachment(item.attachmentPath) }
                                                if (bytes == null) { status = "دریافت ویس مجاز نشد."; playingId = "" }
                                                else try {
                                                    val file = File(context.cacheDir, "room-play-${UUID.randomUUID()}.m4a")
                                                    file.writeBytes(bytes)
                                                    activePlayer?.release()
                                                    val player = MediaPlayer()
                                                    activePlayer = player
                                                    player.setDataSource(file.absolutePath)
                                                    player.setOnCompletionListener { it.release(); file.delete(); activePlayer = null; playingId = "" }
                                                    player.prepare()
                                                    player.start()
                                                } catch (_: Exception) { activePlayer?.release(); activePlayer = null; playingId = ""; status = "پخش ویس ناموفق بود." }
                                            }
                                        }) { Text(if (playingId == item.id) "در حال پخش..." else "▶ پخش پیام صوتی") }
                                        "image" -> {
                                            val imageFile = downloadedImages[item.id]
                                            if (imageFile == null) {
                                                TextButton(enabled = !busy, onClick = {
                                                    busy = true
                                                    scope.launch {
                                                        val bytes = withContext(Dispatchers.IO) { repository.downloadAttachment(item.attachmentPath) }
                                                        if (bytes == null) status = "دریافت تصویر مجاز نشد."
                                                        else {
                                                            val file = File(context.cacheDir, "room-image-${UUID.randomUUID()}.img")
                                                            file.writeBytes(bytes)
                                                            downloadedImages = downloadedImages + (item.id to file)
                                                        }
                                                        busy = false
                                                    }
                                                }) { Text("نمایش تصویر پیوست") }
                                            } else AsyncImage(model = imageFile, contentDescription = "تصویر پیام", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                                        }
                                        else -> Text(item.body)
                                    }
                                    if (isSuperAdmin) {
                                        Row {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    status = withContext(Dispatchers.IO) { repository.setPinned(item.id, !item.pinned) }
                                                    refreshMessages(room)
                                                }
                                            }) { Text(if (item.pinned) "برداشتن سنجاق" else "سنجاق") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    status = withContext(Dispatchers.IO) { repository.deleteMessage(item.id, userId) }
                                                    refreshMessages(room)
                                                }
                                            }) { Text("حذف پیام") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val canCompose = room.type != "public" || role != "DRIVER"
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(4000) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canCompose && !room.locked,
                        label = { Text("متن پیام") },
                        minLines = 1,
                        maxLines = 3
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = canCompose && !busy && draft.isNotBlank() && !room.locked, modifier = Modifier.weight(1f), onClick = {
                            val text = draft
                            busy = true
                            scope.launch {
                                status = withContext(Dispatchers.IO) { repository.sendText(room.id, userId, text) }
                                draft = ""
                                refreshMessages(room)
                                busy = false
                            }
                        }) { Text("ارسال") }
                        OutlinedButton(enabled = canCompose && !busy && !room.locked, onClick = { imagePicker.launch("image/*") }) { Text("تصویر") }
                        OutlinedButton(enabled = canCompose && !busy && !room.locked, onClick = {
                            if (isRecording) stopAndSendVoice()
                            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                            else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (isRecording) "توقف و ارسال" else "ویس") }
                    }
                    if (role == "DRIVER" && room.type == "public") {
                        Text("دسترسی راننده در اتاق عمومی فقط خواندن است.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }

                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    incomingCall?.let { call ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (call.callType == "video") "تماس تصویری ورودی" else "تماس صوتی ورودی") },
            text = { Text("یک تماس خصوصی از طرف یکی از اعضای گفتگو دریافت کرده‌اید.") },
            confirmButton = {
                TextButton(enabled = activeCall == null, onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { repository.updateCallStatus(call.id, "accepted") }
                        if (result.isBlank()) activeCall = call.copy(status = "accepted") else status = result
                        incomingCall = null
                    }
                }) { Text("پاسخ") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.updateCallStatus(call.id, "rejected") }
                        incomingCall = null
                    }
                }) { Text("رد تماس") }
            }
        )
    }

    activeCall?.let { call ->
        ChatRoomCallScreen(accessToken = accessToken, call = call, currentUserId = userId) {
            activeCall = null
            selectedRoom?.let { refreshMessages(it) }
        }
    }
}
