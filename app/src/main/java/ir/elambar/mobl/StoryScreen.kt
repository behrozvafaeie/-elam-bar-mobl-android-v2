package ir.elambar.mobl

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryScreen(
    accessToken: String,
    role: String,
    isSuperAdmin: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(accessToken) { StoryRepository(accessToken) }
    var userId by remember { mutableStateOf("") }
    var stories by remember { mutableStateOf<List<UserStory>>(emptyList()) }
    var selectedStory by remember { mutableStateOf<UserStory?>(null) }
    var comments by remember { mutableStateOf<List<UserStoryComment>>(emptyList()) }
    var caption by remember { mutableStateOf("") }
    var commentDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("در حال دریافت استوری‌ها...") }
    var busy by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<UserStory?>(null) }

    fun refresh() {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val uid = repository.currentUserId()
                uid to repository.listStories()
            }
            userId = result.first.orEmpty()
            status = result.second.first ?: "استوری‌ها به‌روز شدند."
            stories = result.second.second
            busy = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && userId.isNotBlank()) {
            scope.launch {
                busy = true
                status = withContext(Dispatchers.IO) { repository.publishStory(context, uri, userId, role, caption) }
                caption = ""
                refresh()
                busy = false
            }
        }
    }

    LaunchedEffect(accessToken) { refresh() }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("استوری‌ها", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("برگشت") }
        }
        Text("همه کاربران تأییدشده می‌توانند استوری بگذارند. استوری پس از ۲۴ ساعت از نمایش عمومی خارج می‌شود و در آرشیو صاحب آن می‌ماند.")
        OutlinedTextField(
            value = caption, onValueChange = { caption = it.take(500) },
            modifier = Modifier.fillMaxWidth(), label = { Text("متن استوری (اختیاری)") },
            maxLines = 2
        )
        Button(onClick = { imagePicker.launch("image/*") }, enabled = !busy && userId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("انتخاب عکس و انتشار استوری")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(stories, key = { it.id }) { story ->
                val expired = runCatching { Instant.parse(story.expiresAt).isBefore(Instant.now()) }.getOrDefault(false)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (story.role.lowercase()) { "driver" -> "راننده"; "company" -> "شرکت باربری"; else -> "مدیر" },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (expired) AssistChip(onClick = {}, label = { Text("آرشیو شخصی") })
                            else AssistChip(onClick = {}, label = { Text("فعال") })
                            if (isSuperAdmin) TextButton(onClick = { showDeleteConfirm = story }) { Text("حذف") }
                        }
                        if (story.signedUrl.isNotBlank()) {
                            AsyncImage(model = story.signedUrl, contentDescription = "تصویر استوری", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
                        } else {
                            Text("تصویر استوری در دسترس نیست.")
                        }
                        if (story.caption.isNotBlank()) Text(story.caption)
                        TextButton(onClick = {
                            selectedStory = story
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { repository.comments(story.id) }
                                comments = result.second
                                if (result.first != null) status = result.first!!
                            }
                        }) { Text("نظرها و گفتگو") }
                    }
                }
            }
        }
    }
        }
    }

    selectedStory?.let { story ->
        AlertDialog(
            onDismissRequest = { selectedStory = null; comments = emptyList() },
            title = { Text("نظرهای استوری") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (comments.isEmpty()) Text("هنوز نظری ثبت نشده است.")
                    comments.forEach { item ->
                        Text(item.body, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                    }
                    OutlinedTextField(
                        value = commentDraft, onValueChange = { commentDraft = it.take(1000) },
                        label = { Text("نظر شما") }, modifier = Modifier.fillMaxWidth(), maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = commentDraft.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        status = withContext(Dispatchers.IO) { repository.addComment(story.id, userId, commentDraft) }
                        commentDraft = ""
                        val result = withContext(Dispatchers.IO) { repository.comments(story.id) }
                        comments = result.second
                        busy = false
                    }
                }) { Text("ارسال نظر") }
            },
            dismissButton = { TextButton(onClick = { selectedStory = null; comments = emptyList() }) { Text("بستن") } }
        )
    }

    showDeleteConfirm?.let { story ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("حذف استوری") },
            text = { Text("این استوری و نظرهای آن حذف شود؟ این کار فقط برای مدیر ارشد مجاز است.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        status = withContext(Dispatchers.IO) { repository.deleteStory(story) }
                        showDeleteConfirm = null
                        refresh()
                        busy = false
                    }
                }) { Text("حذف استوری") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("انصراف") } }
        )
    }
}
