package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageStatus
import com.example.data.model.UserProfile
import com.example.ui.media.VoiceRecorderHelper
import com.example.ui.theme.ChatThemes
import com.example.ui.theme.NexDangerRed
import com.example.ui.theme.NexEmeraldPrimary
import com.example.ui.theme.NexReadBlue
import com.example.ui.theme.getChatWallpaperTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatDetailPane(
    conversation: Conversation,
    messages: List<ChatMessage>,
    currentUid: String,
    peerProfile: UserProfile?,
    directoryUsers: List<UserProfile>,
    replyTarget: ChatMessage?,
    isUploadingMedia: Boolean,
    isDarkMode: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onUploadAndSendMedia: (bytes: ByteArray, fileName: String, mimeType: String, type: String) -> Unit,
    onUploadAndSendVoice: (file: File, durationSec: Int) -> Unit,
    onRetryMessage: (ChatMessage) -> Unit,
    onRemoveFailedMessage: (ChatMessage) -> Unit,
    onSetReplyTarget: (ChatMessage?) -> Unit,
    onDeleteForMe: (ChatMessage) -> Unit,
    onDeleteForEveryone: (ChatMessage) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onUpdateGroupInfo: (name: String, desc: String) -> Unit,
    onAddGroupMember: (UserProfile) -> Unit,
    onRemoveGroupMember: (String) -> Unit,
    onPromoteGroupAdmin: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onPermissionDeniedMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val voiceHelper = remember { VoiceRecorderHelper(context) }

    DisposableEffect(conversation.effectiveId()) {
        onDispose {
            voiceHelper.cancelRecording()
            voiceHelper.stopPlayback()
        }
    }

    var inputText by remember(conversation.effectiveId()) { mutableStateOf("") }
    var searchInChatVisible by remember(conversation.effectiveId()) { mutableStateOf(false) }
    var searchInChatQuery by remember(conversation.effectiveId()) { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showGroupInfoDialog by remember { mutableStateOf(false) }
    var selectedMessageForActions by remember { mutableStateOf<ChatMessage?>(null) }

    // Voice Recording State
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playingMessageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingSeconds = 0
            while (isRecordingVoice) {
                delay(1000L)
                recordingSeconds += 1
            }
        }
    }

    // Typing indicator debounce
    LaunchedEffect(inputText, conversation.effectiveId()) {
        if (inputText.isNotBlank()) {
            onTypingChanged(true)
            delay(3500L)
            onTypingChanged(false)
        } else {
            onTypingChanged(false)
        }
    }

    // Auto-scroll to bottom only when a new message arrives and user is already near the bottom
    val lastMsgId = messages.lastOrNull()?.effectiveId()
    LaunchedEffect(lastMsgId) {
        if (messages.isNotEmpty()) {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (total == 0 || total - lastVisible <= 4 || messages.lastOrNull()?.senderId == currentUid) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Runtime Permission Launchers
    val audioCallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartAudioCall()
        } else {
            onPermissionDeniedMessage("Microphone permission is required for NexChat audio calls.")
        }
    }

    val videoCallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
        val camGranted = result[Manifest.permission.CAMERA] == true
        // NEVER silently downgrade a video call to audio
        if (micGranted && camGranted) {
            onStartVideoCall()
        } else {
            onPermissionDeniedMessage("Both Camera and Microphone permissions are required for NexChat video calls.")
        }
    }

    val voiceNotePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val res = voiceHelper.startRecording()
            if (res.isSuccess) {
                isRecordingVoice = true
            } else {
                onPermissionDeniedMessage("Could not start microphone recorder.")
            }
        } else {
            onPermissionDeniedMessage("Microphone permission is required to record voice messages.")
        }
    }

    // Zero-Permission Photo/Video Picker (Google Play Policy Compliant)
    val visualMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            readUriPayload(context, uri)?.let { (bytes, name, mime) ->
                val type = if (mime.startsWith("video/")) "VIDEO" else "IMAGE"
                onUploadAndSendMedia(bytes, name, mime, type)
            }
        }
    }

    // Document / File Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            readUriPayload(context, uri)?.let { (bytes, name, mime) ->
                onUploadAndSendMedia(bytes, name, mime, "FILE")
            }
        }
    }

    val themeSpec = getChatWallpaperTheme(conversation.themeFor(currentUid))
    val canvasColor = if (isDarkMode) themeSpec.darkCanvas else themeSpec.lightCanvas
    val outgoingBubbleColor = if (isDarkMode) themeSpec.darkOutgoing else themeSpec.lightOutgoing
    val incomingBubbleColor = if (isDarkMode) Color(0xFF202C33) else Color.White

    val displayedMessages = remember(messages, searchInChatQuery) {
        if (searchInChatQuery.isBlank()) {
            messages
        } else {
            messages.filter { it.text.contains(searchInChatQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(canvasColor)
    ) {
        // Chat Header Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackButton) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to conversations"
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (conversation.isGroup) showGroupInfoDialog = true
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarCircle(
                        name = conversation.displayTitleFor(currentUid),
                        photoUrl = conversation.displayPhotoFor(currentUid),
                        isGroup = conversation.isGroup,
                        size = 42.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = conversation.displayTitleFor(currentUid),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val typingUsers = conversation.isTypingFor(currentUid)
                        val subtitleText = when {
                            typingUsers.isNotEmpty() -> "${typingUsers.joinToString(", ")} typing..."
                            conversation.isGroup -> "${conversation.memberUids.size} members • Tap for group info"
                            peerProfile?.online == true -> "Online"
                            peerProfile?.lastSeen != null -> {
                                val formatted = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                    .format(peerProfile.lastSeen.toDate())
                                "Last seen $formatted"
                            }
                            else -> conversation.displaySubtitleFor(currentUid)
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (typingUsers.isNotEmpty() || peerProfile?.online == true) {
                                NexEmeraldPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!conversation.isGroup) {
                    IconButton(
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                onStartAudioCall()
                            } else {
                                audioCallPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.testTag("start_audio_call_button")
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = NexEmeraldPrimary)
                    }

                    IconButton(
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasCam = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic && hasCam) {
                                onStartVideoCall()
                            } else {
                                videoCallPermissionLauncher.launch(
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                )
                            }
                        },
                        modifier = Modifier.testTag("start_video_call_button")
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = NexEmeraldPrimary)
                    }
                } else {
                    IconButton(onClick = { showGroupInfoDialog = true }) {
                        Icon(Icons.Default.Group, contentDescription = "Group Info")
                    }
                }

                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More chat options")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Search in Chat") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                searchInChatVisible = !searchInChatVisible
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Load Older Messages") },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                onLoadOlderMessages()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Chat Theme") },
                            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                showThemeDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Chat") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                showExportDialog = true
                            }
                        )
                        if (conversation.isGroup) {
                            DropdownMenuItem(
                                text = { Text("Group Members & Admin") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showGroupInfoDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }

        // In-Chat Search Bar
        AnimatedVisibility(visible = searchInChatVisible) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchInChatQuery,
                        onValueChange = { searchInChatQuery = it },
                        placeholder = { Text("Filter messages in this conversation...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        searchInChatQuery = ""
                        searchInChatVisible = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close in-chat search")
                    }
                }
            }
        }

        if (isUploadingMedia) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = NexEmeraldPrimary
            )
        }

        // Message List (Stable Keys, Zero Flicker)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("message_list")
        ) {
            items(
                items = displayedMessages,
                key = { it.effectiveId() }
            ) { message ->
                MessageBubbleItem(
                    message = message,
                    isMe = message.senderId == currentUid,
                    isGroup = conversation.isGroup,
                    outgoingBubbleColor = outgoingBubbleColor,
                    incomingBubbleColor = incomingBubbleColor,
                    isPlayingAudio = playingMessageUrl == message.mediaUrl && message.mediaUrl.isNotBlank(),
                    onToggleVoicePlay = { url ->
                        if (playingMessageUrl == url) {
                            voiceHelper.stopPlayback()
                            playingMessageUrl = null
                        } else {
                            playingMessageUrl = url
                            voiceHelper.playAudioUrl(
                                url = url,
                                onCompletion = { playingMessageUrl = null },
                                onError = { err ->
                                    playingMessageUrl = null
                                    onPermissionDeniedMessage(err)
                                }
                            )
                        }
                    },
                    onLongPressOrClick = {
                        selectedMessageForActions = message
                    },
                    onRetry = { onRetryMessage(message) },
                    onDismissFailed = { onRemoveFailedMessage(message) }
                )
            }
        }

        // Quoted Reply Banner
        AnimatedVisibility(visible = replyTarget != null) {
            replyTarget?.let { target ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = NexEmeraldPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${target.senderName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = NexEmeraldPrimary
                            )
                            Text(
                                text = target.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onSetReplyTarget(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }
        }

        // Bottom Composer Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRecordingVoice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(NexDangerRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Recording Voice Message... ${recordingSeconds}s",
                            style = MaterialTheme.typography.titleMedium,
                            color = NexDangerRed
                        )
                    }
                    Row {
                        TextButton(
                            onClick = {
                                isRecordingVoice = false
                                voiceHelper.cancelRecording()
                            }
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                isRecordingVoice = false
                                val (file, elapsed) = voiceHelper.stopRecording()
                                if (file != null) {
                                    onUploadAndSendVoice(file, elapsed)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            visualMediaLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.testTag("attach_media_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Share Image or Video",
                            tint = NexEmeraldPrimary
                        )
                    }

                    IconButton(
                        onClick = {
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.testTag("attach_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Share Document File",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input_field")
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    if (inputText.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val toSend = inputText
                                    inputText = ""
                                    onSendText(toSend)
                                }
                                .testTag("send_message_button"),
                            color = NexEmeraldPrimary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send Message",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        val res = voiceHelper.startRecording()
                                        if (res.isSuccess) {
                                            isRecordingVoice = true
                                        } else {
                                            onPermissionDeniedMessage("Unable to access microphone.")
                                        }
                                    } else {
                                        voiceNotePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                .testTag("record_voice_button"),
                            color = NexEmeraldPrimary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Record Voice Message",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Message Action Dialog (Reply, Copy, Delete for Me, Delete for Everyone)
    selectedMessageForActions?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectedMessageForActions = null },
            title = { Text("Message Actions") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            onSetReplyTarget(msg)
                            selectedMessageForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reply to Message", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("NexChat Message", msg.text))
                            selectedMessageForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Message Text", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            onDeleteForMe(msg)
                            selectedMessageForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete for Me", modifier = Modifier.weight(1f))
                    }
                    if (msg.senderId == currentUid && !msg.deletedForEveryone) {
                        TextButton(
                            onClick = {
                                onDeleteForEveryone(msg)
                                selectedMessageForActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = NexDangerRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Delete for Everyone",
                                color = NexDangerRed,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForActions = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Chat Theme Picker Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Chat Wallpaper Theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatThemes.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectTheme(theme.id)
                                    showThemeDialog = false
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(theme.accent)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = theme.label,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Export Chat Transcript Dialog
    if (showExportDialog) {
        val transcript = remember(messages) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            messages.joinToString("\n") { m ->
                "[${fmt.format(Date(m.clientTimestamp))}] ${m.senderName}: ${m.text}"
            }
        }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Chat Transcript") },
            text = {
                Column {
                    Text(
                        text = "${messages.size} messages ready to export.",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        Text(
                            text = transcript.ifBlank { "No messages in conversation." },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("NexChat Transcript", transcript))
                        showExportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                ) {
                    Text("Copy Transcript")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Group Management & Admin Sheet
    if (showGroupInfoDialog && conversation.isGroup) {
        GroupManagementDialog(
            conversation = conversation,
            currentUid = currentUid,
            directoryUsers = directoryUsers,
            onUpdateGroupInfo = onUpdateGroupInfo,
            onAddMember = onAddGroupMember,
            onRemoveMember = onRemoveGroupMember,
            onPromoteAdmin = onPromoteGroupAdmin,
            onLeaveGroup = {
                showGroupInfoDialog = false
                onLeaveGroup()
            },
            onDismiss = { showGroupInfoDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isMe: Boolean,
    isGroup: Boolean,
    outgoingBubbleColor: Color,
    incomingBubbleColor: Color,
    isPlayingAudio: Boolean,
    onToggleVoicePlay: (String) -> Unit,
    onLongPressOrClick: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailed: () -> Unit
) {
    if (message.type == "SYSTEM") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        return
    }

    val resolvedStatus = message.resolvedStatus()
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            color = if (isMe) outgoingBubbleColor else incomingBubbleColor,
            shape = bubbleShape,
            tonalElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = 96.dp, max = 320.dp)
                .combinedClickable(
                    onClick = onLongPressOrClick,
                    onLongClick = onLongPressOrClick
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isGroup && !isMe) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelLarge,
                        color = NexEmeraldPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // Quoted Reply Header
                if (message.replyToMessageId.isNotBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = message.replyToSenderName,
                                style = MaterialTheme.typography.labelSmall,
                                color = NexEmeraldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = message.replyToText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Media Attachments
                if (!message.deletedForEveryone && message.mediaUrl.isNotBlank()) {
                    when (message.type) {
                        "IMAGE" -> {
                            AsyncImage(
                                model = message.mediaUrl,
                                contentDescription = message.text,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        "VOICE" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                IconButton(
                                    onClick = { onToggleVoicePlay(message.mediaUrl) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(NexEmeraldPrimary)
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingAudio) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = "Play voice message",
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (isPlayingAudio) "Playing voice note..." else "Voice Note (${message.mediaDurationSec}s)",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                        "FILE", "VIDEO" -> {
                            Surface(
                                color = Color.Black.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, tint = NexEmeraldPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = message.mediaFileName.ifBlank { message.type },
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${message.mediaSizeBytes / 1024} KB",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Message Body Text
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Retry / Failed State Banner
                if (resolvedStatus == MessageStatus.FAILED) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = NexDangerRed.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = NexDangerRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.localError ?: "Delivery failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexDangerRed
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(onClick = onRetry) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry")
                                }
                                TextButton(onClick = onDismissFailed) {
                                    Text("Remove", color = NexDangerRed)
                                }
                            }
                        }
                    }
                }

                // Timestamp + Delivery/Read Ticks
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val timeStr = remember(message.clientTimestamp) {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.clientTimestamp))
                    }
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (resolvedStatus) {
                            MessageStatus.SENDING -> {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "Sending",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.SENT -> {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "Sent",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            MessageStatus.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Delivered",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            MessageStatus.READ -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Read",
                                    tint = NexReadBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            MessageStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Failed",
                                    tint = NexDangerRed,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupManagementDialog(
    conversation: Conversation,
    currentUid: String,
    directoryUsers: List<UserProfile>,
    onUpdateGroupInfo: (name: String, desc: String) -> Unit,
    onAddMember: (UserProfile) -> Unit,
    onRemoveMember: (String) -> Unit,
    onPromoteAdmin: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    val isAdmin = conversation.adminUids.contains(currentUid)
    var groupName by remember(conversation.groupName) { mutableStateOf(conversation.groupName) }
    var groupDesc by remember(conversation.groupDescription) { mutableStateOf(conversation.groupDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group Info & Members") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    enabled = isAdmin,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = groupDesc,
                    onValueChange = { groupDesc = it },
                    label = { Text("Description") },
                    enabled = isAdmin,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isAdmin) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onUpdateGroupInfo(groupName, groupDesc) },
                        colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Group Profile")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Members (${conversation.memberUids.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(conversation.memberUids, key = { it }) { memberUid ->
                        val details = conversation.participantDetails[memberUid]
                        val memberName = details?.get("displayName") ?: memberUid.take(8)
                        val memberIsAdmin = conversation.adminUids.contains(memberUid)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (memberUid == currentUid) "$memberName (You)" else memberName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (memberIsAdmin) {
                                    Text(
                                        text = "Group Admin",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NexEmeraldPrimary
                                    )
                                }
                            }
                            if (isAdmin && memberUid != currentUid) {
                                Row {
                                    if (!memberIsAdmin) {
                                        TextButton(onClick = { onPromoteAdmin(memberUid) }) {
                                            Text("Make Admin")
                                        }
                                    }
                                    TextButton(onClick = { onRemoveMember(memberUid) }) {
                                        Text("Remove", color = NexDangerRed)
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAdmin) {
                    val nonMembers = directoryUsers.filter { !conversation.memberUids.contains(it.uid) }
                    if (nonMembers.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Add Contacts to Group", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(modifier = Modifier.height(100.dp)) {
                            items(nonMembers, key = { it.uid }) { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onAddMember(candidate) }
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${candidate.displayName} (@${candidate.username})")
                                    Text("+ Add", color = NexEmeraldPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onLeaveGroup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Leave Group", color = NexDangerRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun readUriPayload(context: Context, uri: Uri): Triple<ByteArray, String, String>? {
    return try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName = "attachment_${System.currentTimeMillis()}"
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(nameIdx) ?: displayName
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        Triple(bytes, displayName, mime)
    } catch (_: Exception) {
        null
    }
}
