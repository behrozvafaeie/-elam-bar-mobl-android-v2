package ir.elambar.mobl

import android.Manifest
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunicationScreen(accessToken: String, isSuperAdmin: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userId = remember(accessToken) {
        runCatching {
            val part = accessToken.split(".").getOrNull(1) ?: ""
            val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            org.json.JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
        }.getOrDefault("")
    }
    val repository = remember(accessToken) { CommunicationRepository(accessToken) }
    var conversations by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var selected by remember { mutableStateOf<ChatConversation?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("گفتگوهای مجاز شما در حال دریافت است...") }
    var busy by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingMessageId by remember { mutableStateOf("") }

    fun refreshConversations() {
        if (userId.isBlank()) { status = "شناسه حساب از نشست دریافت نشد."; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.conversationsFor(userId, isSuperAdmin) }
            conversations = result.second
            status = result.first ?: if (result.second.isEmpty()) "گفتگوی فعالی برای حساب شما ثبت نشده است. گفتگو پس از تخصیص بار ایجاد می‌شود." else ""
            busy = false
        }
    }
    fun refreshMessages(conversation: ChatConversation) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.messages(conversation.id) }
            messages = result.second
            status = result.first.orEmpty()
            busy = false
        }
    }
    fun startRecording() {
        if (selected == null || isRecording) return
        try {
            val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
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
            status = "در حال ضبط ویس؛ برای پایان، دکمه توقف را بزنید."
        } catch (_: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
            status = "شروع ضبط صدا ناموفق بود. مجوز میکروفون و فضای ذخیره‌سازی را بررسی کنید."
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "برای ضبط ویس باید اجازه میکروفون را فعال کنید."
    }
    fun stopAndSendRecording() {
        val file = recordingFile
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) { status = "ضبط ویس کامل نشد؛ دوباره تلاش کنید." }
        recorder = null; recordingFile = null; isRecording = false
        val conversation = selected
        if (file == null || conversation == null || !file.exists() || file.length() == 0L) { file?.delete(); return }
        busy = true
        status = "در حال ارسال پیام صوتی..."
        scope.launch {
            status = withContext(Dispatchers.IO) { repository.sendVoiceFile(conversation.id, userId, file) }
            file.delete()
            refreshMessages(conversation)
        }
    }
    LaunchedEffect(accessToken) { refreshConversations() }
    LaunchedEffect(selected?.id, accessToken) {
        val conversation = selected ?: return@LaunchedEffect
        while (true) {
            delay(5000)
            val result = withContext(Dispatchers.IO) { repository.messages(conversation.id) }
            if (result.first == null) messages = result.second else status = result.first.orEmpty()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            runCatching { activePlayer?.release() }; recordingFile?.delete()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("پیام‌ها و گفتگوها", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("بستن") }
                }
                if (selected == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isSuperAdmin) "همه گفتگوهای بار" else "گفتگوهای بارهای تخصیص‌یافته", style = MaterialTheme.typography.titleSmall)
                        TextButton(enabled = !busy, onClick = { refreshConversations() }) { Text("بروزرسانی") }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        conversations.forEach { conversation ->
                            Card(onClick = { selected = conversation; messages = emptyList(); refreshMessages(conversation) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("گفتگوی بار ${conversation.loadId}", style = MaterialTheme.typography.titleMedium)
                                    Text("شناسه گفتگو: ${conversation.id.take(8)}…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selected = null; messages = emptyList(); refreshConversations() }) { Text("بازگشت به گفتگوها") }
                        Spacer(Modifier.weight(1f))
                        Text("بار ${selected!!.loadId}", style = MaterialTheme.typography.titleSmall)
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        messages.forEach { item ->
                            val mine = item.senderUserId == userId
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                                    Text(if (mine) "شما" else "طرف گفتگو", style = MaterialTheme.typography.labelSmall)
                                    if (item.type == "voice" && item.attachmentPath.isNotBlank()) {
                                        TextButton(enabled = playingMessageId != item.id, onClick = {
                                            playingMessageId = item.id
                                            scope.launch {
                                                val bytes = withContext(Dispatchers.IO) { repository.downloadVoice(item.attachmentPath) }
                                                if (bytes == null) { status = "دریافت فایل صوتی مجاز نشد."; playingMessageId = "" }
                                                else try {
                                                    val audio = File(context.cacheDir, "play-${UUID.randomUUID()}.m4a")
                                                    audio.writeBytes(bytes); activePlayer?.release()
                                                    val player = MediaPlayer(); activePlayer = player
                                                    player.setDataSource(audio.absolutePath)
                                                    player.setOnCompletionListener { it.release(); audio.delete(); activePlayer = null; playingMessageId = "" }
                                                    player.prepare(); player.start()
                                                } catch (_: Exception) {
                                                    activePlayer?.release(); activePlayer = null; playingMessageId = ""; status = "پخش پیام صوتی ناموفق بود."
                                                }
                                            }
                                        }) { Text(if (playingMessageId == item.id) "در حال پخش..." else "▶ پخش پیام صوتی") }
                                    } else Text(item.body)
                                }
                            }
                        }
                    }
                    OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp), label = { Text("متن پیام") }, minLines = 6, maxLines = 12)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy && draft.trim().isNotEmpty() && !isRecording, modifier = Modifier.weight(1f), onClick = {
                            val text = draft; val conversation = selected ?: return@Button
                            busy = true
                            scope.launch {
                                status = withContext(Dispatchers.IO) { repository.sendText(conversation.id, userId, text) }
                                draft = ""; refreshMessages(conversation)
                            }
                        }) { Text("ارسال پیام") }
                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            if (isRecording) stopAndSendRecording()
                            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (isRecording) "توقف و ارسال ویس" else "ضبط ویس") }
                    }
                    Text("پیام و ویس فقط برای اعضای مجاز همان گفتگوی بار قابل دسترسی است.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
