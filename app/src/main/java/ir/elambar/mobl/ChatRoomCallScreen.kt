package ir.elambar.mobl

import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

@Composable
internal fun ChatRoomCallScreen(
    accessToken: String,
    call: ChatRoomCall,
    currentUserId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(accessToken) { ChatRoomRepository(accessToken) }
    val isCaller = call.callerId == currentUserId
    val remoteUserId = if (isCaller) call.receiverId else call.callerId
    val isVideo = call.callType == "video"
    val eglBase = remember(call.id) { EglBase.create() }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var engine by remember(call.id) { mutableStateOf<WebRtcCallEngine?>(null) }
    var permissionGranted by remember(call.id) { mutableStateOf(false) }
    var status by remember(call.id) { mutableStateOf(if (isCaller) "در حال آماده‌سازی تماس..." else "در حال اتصال به تماس...") }
    var microphoneEnabled by remember { mutableStateOf(true) }
    var speakerEnabled by remember { mutableStateOf(isVideo) }
    var remoteEnded by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
        if (!permissionGranted) status = "برای تماس، مجوز میکروفون و در تماس تصویری مجوز دوربین لازم است."
    }

    LaunchedEffect(call.id) {
        val required = if (isVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            permissionGranted = true
        } else {
            permissionLauncher.launch(required)
        }
    }

    LaunchedEffect(permissionGranted, localRenderer, remoteRenderer, call.id) {
        if (!permissionGranted || engine != null || (isVideo && (localRenderer == null || remoteRenderer == null))) return@LaunchedEffect
        try {
            val created = WebRtcCallEngine(
                context = context,
                videoEnabled = isVideo,
                eglBase = eglBase,
                localRenderer = localRenderer,
                remoteRenderer = remoteRenderer,
                onSignal = { type, payload ->
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            repository.sendCallSignal(call.id, currentUserId, remoteUserId, type, payload)
                        }
                        if (error.isNotBlank()) status = error
                    }
                },
                onState = { value -> scope.launch(Dispatchers.Main) { status = value } }
            )
            engine = created
            if (isCaller) created.createOffer()
        } catch (e: Exception) {
            status = "راه‌اندازی تماس ناموفق بود: ${e.message.orEmpty()}"
        }
    }

    LaunchedEffect(engine, call.id) {
        val activeEngine = engine ?: return@LaunchedEffect
        val seen = mutableSetOf<String>()
        while (!remoteEnded && !closing) {
            val signals = withContext(Dispatchers.IO) { repository.callSignals(call.id) }
            signals.forEach { signal ->
                if (signal.recipientId == currentUserId && signal.senderId == remoteUserId && seen.add(signal.id)) {
                    when (signal.type) {
                        "offer" -> if (!isCaller) activeEngine.setRemoteOffer(signal.payload.optString("sdp"))
                        "answer" -> if (isCaller) activeEngine.setRemoteAnswer(signal.payload.optString("sdp"))
                        "ice_candidate" -> activeEngine.addRemoteCandidate(signal.payload)
                        "hangup" -> {
                            remoteEnded = true
                            status = "طرف مقابل تماس را پایان داد."
                        }
                    }
                }
            }
            delay(900)
        }
    }

    LaunchedEffect(call.id, isCaller) {
        while (!remoteEnded && !closing) {
            val latest = withContext(Dispatchers.IO) { repository.getCall(call.id).second }
            if (latest != null) {
                when (latest.status) {
                    "accepted" -> if (isCaller) status = "تماس پذیرفته شد؛ در حال اتصال..."
                    "rejected" -> { status = "تماس رد شد."; remoteEnded = true }
                    "ended","missed","cancelled" -> { status = "تماس پایان یافت."; remoteEnded = true }
                }
            }
            delay(1800)
        }
    }

    fun endCall() {
        if (closing) return
        closing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.sendCallSignal(call.id, currentUserId, remoteUserId, "hangup", JSONObject())
                val latest = repository.getCall(call.id).second
                val newStatus = when {
                    latest?.status == "ringing" && isCaller -> "cancelled"
                    latest?.status == "ringing" && !isCaller -> "rejected"
                    latest?.status == "accepted" -> "ended"
                    else -> ""
                }
                if (newStatus.isNotBlank()) repository.updateCallStatus(call.id, newStatus)
            }
            engine?.close()
            onClose()
        }
    }

    DisposableEffect(call.id) {
        onDispose {
            runCatching { engine?.close() }
            runCatching { localRenderer?.release() }
            runCatching { remoteRenderer?.release() }
            runCatching { eglBase.release() }
        }
    }

    Dialog(onDismissRequest = { endCall() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isVideo) "تماس تصویری" else "تماس صوتی", style = MaterialTheme.typography.headlineSmall)
                if (isVideo) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))) {
                        AndroidView(
                            factory = { viewContext ->
                                SurfaceViewRenderer(viewContext).apply {
                                    init(eglBase.eglBaseContext, null)
                                    setEnableHardwareScaler(true)
                                    setMirror(false)
                                    remoteRenderer = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        AndroidView(
                            factory = { viewContext ->
                                SurfaceViewRenderer(viewContext).apply {
                                    init(eglBase.eglBaseContext, null)
                                    setEnableHardwareScaler(true)
                                    setMirror(true)
                                    localRenderer = this
                                }
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(width = 112.dp, height = 154.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("در حال برقراری ارتباط صوتی...", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = engine != null, modifier = Modifier.weight(1f), onClick = {
                        microphoneEnabled = !microphoneEnabled
                        engine?.setMicrophoneEnabled(microphoneEnabled)
                    }) { Text(if (microphoneEnabled) "قطع میکروفون" else "روشن‌کردن میکروفون") }
                    OutlinedButton(enabled = engine != null, modifier = Modifier.weight(1f), onClick = {
                        speakerEnabled = !speakerEnabled
                        engine?.setSpeakerEnabled(speakerEnabled)
                    }) { Text(if (speakerEnabled) "بلندگو روشن" else "بلندگو خاموش") }
                }
                Button(onClick = { endCall() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("پایان تماس")
                }
                Text("برای کیفیت پایدار در همه شبکه‌ها، سرویس TURN اختصاصی نیز لازم است.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
