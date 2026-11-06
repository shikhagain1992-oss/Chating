package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.CallSession
import com.example.data.model.StatusPost
import com.example.data.model.UserPrivateProfile
import com.example.data.model.UserProfile
import com.example.data.model.UserSession
import com.example.ui.theme.NexDangerRed
import com.example.ui.theme.NexEmeraldBright
import com.example.ui.theme.NexEmeraldPrimary
import com.example.ui.theme.NexWarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// 1. 24-HOUR STATUS / STORIES TAB
// ---------------------------------------------------------------------------

@Composable
fun StatusTabScreen(
    statuses: List<StatusPost>,
    currentUid: String,
    onPostTextStatus: (text: String, bgHex: String, privacy: String) -> Unit,
    onPostMediaStatus: (bytes: ByteArray, fileName: String, mimeType: String, caption: String) -> Unit,
    onViewStatus: (StatusPost) -> Unit,
    onDeleteStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var showCreateTextDialog by remember { mutableStateOf(false) }
    var activeViewingStatus by remember { mutableStateOf<StatusPost?>(null) }

    val mediaStatusLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "image/jpeg"
                var fileName = "status_${System.currentTimeMillis()}.jpg"
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(idx) ?: fileName
                    }
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    onPostMediaStatus(bytes, fileName, mime, "Photo/Video Status")
                }
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Share 24-Hour Status",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Post disappearing text, image, or video updates with viewer tracking.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { showCreateTextDialog = true },
                                modifier = Modifier.testTag("create_text_status_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Text Status", tint = NexEmeraldPrimary)
                            }
                            IconButton(
                                onClick = {
                                    mediaStatusLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                modifier = Modifier.testTag("create_media_status_button")
                            ) {
                                Icon(Icons.Default.Image, contentDescription = "Media Status", tint = NexEmeraldPrimary)
                            }
                        }
                    }
                }
            }

            if (statuses.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No active statuses from the last 24 hours.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(statuses, key = { it.effectiveId() }) { status ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onViewStatus(status)
                                activeViewingStatus = status
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarCircle(
                                name = status.authorName,
                                photoUrl = status.authorPhoto,
                                size = 48.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (status.authorId == currentUid) "${status.authorName} (My Status)" else status.authorName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = status.text.ifBlank { status.type },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                val timeStr = remember(status.createdAtMillis) {
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.createdAtMillis))
                                }
                                Text(
                                    text = "Posted at $timeStr • ${status.viewerUids.size} views",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (status.authorId == currentUid) {
                                IconButton(onClick = { onDeleteStatus(status.effectiveId()) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete status", tint = NexDangerRed)
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateTextDialog = true },
            containerColor = NexEmeraldPrimary,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Post Status")
        }
    }

    if (showCreateTextDialog) {
        var text by remember { mutableStateOf("") }
        var selectedBg by remember { mutableStateOf("#00A884") }
        val bgOptions = listOf("#00A884", "#4F46E5", "#D97706", "#E11D48", "#1E293B")

        AlertDialog(
            onDismissRequest = { showCreateTextDialog = false },
            title = { Text("Create 24h Text Status") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("What's on your mind?") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Background Color", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(bgOptions) { hex ->
                            val parsed = try {
                                Color(android.graphics.Color.parseColor(hex))
                            } catch (_: Exception) {
                                NexEmeraldPrimary
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parsed)
                                    .clickable { selectedBg = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedBg == hex) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onPostTextStatus(text, selectedBg, "ALL")
                            showCreateTextDialog = false
                        }
                    },
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                ) {
                    Text("Post Status")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    activeViewingStatus?.let { status ->
        val bgColor = try {
            Color(android.graphics.Color.parseColor(status.backgroundColorHex))
        } catch (_: Exception) {
            NexEmeraldPrimary
        }
        AlertDialog(
            onDismissRequest = { activeViewingStatus = null },
            title = { Text("${status.authorName}'s Status") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = bgColor,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            if (status.mediaUrl.isNotBlank()) {
                                AsyncImage(
                                    model = status.mediaUrl,
                                    contentDescription = status.text,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = status.text,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RemoveRedEye, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${status.viewerUids.size} viewers • Expires 24h after posting")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeViewingStatus = null }) {
                    Text("Close")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 2. CALL HISTORY TAB
// ---------------------------------------------------------------------------

@Composable
fun CallsTabScreen(
    calls: List<CallSession>,
    currentUid: String
) {
    if (calls.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No call history yet",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Open any 1-to-1 chat and tap the Audio or Video Call icon in the header.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(calls, key = { it.effectiveId() }) { call ->
                val isOutgoing = call.callerId == currentUid
                val peerName = if (isOutgoing) call.receiverName else call.callerName
                val peerPhoto = if (isOutgoing) call.receiverPhoto else call.callerPhoto
                val isMissedOrRejected = call.status in listOf("MISSED", "REJECTED", "BUSY")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarCircle(name = peerName, photoUrl = peerPhoto, size = 46.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isMissedOrRejected) NexDangerRed else MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when {
                                        isMissedOrRejected -> Icons.AutoMirrored.Filled.CallMissed
                                        isOutgoing -> Icons.AutoMirrored.Filled.CallMade
                                        else -> Icons.AutoMirrored.Filled.CallReceived
                                    },
                                    contentDescription = null,
                                    tint = if (isMissedOrRejected) NexDangerRed else NexEmeraldPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val timeStr = remember(call.createdAt) {
                                    call.createdAt?.toDate()?.let {
                                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(it)
                                    } ?: "Recent"
                                }
                                Text(
                                    text = "${call.callType} • ${call.status} (${call.durationSec}s) • $timeStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = if (call.callType == "VIDEO") Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = call.callType,
                            tint = NexEmeraldPrimary
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. PROFILE, SESSIONS, SECURITY & SETTINGS TAB
// ---------------------------------------------------------------------------

@Composable
fun SettingsProfileTabScreen(
    profile: UserProfile,
    privateProfile: UserPrivateProfile?,
    sessions: List<UserSession>,
    isDarkMode: Boolean,
    isCloudinaryConfigured: Boolean,
    onToggleDarkMode: () -> Unit,
    onUpdateProfile: (username: String, displayName: String, phone: String, about: String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Profile Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarCircle(name = profile.displayName, photoUrl = profile.photoUrl, size = 64.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "@${profile.username}",
                            style = MaterialTheme.typography.titleMedium,
                            color = NexEmeraldPrimary
                        )
                        Text(
                            profile.about,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit profile")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "Phone: ${privateProfile?.phoneNumber ?: "Not set"} (${if (privateProfile?.phoneVerified == true) "Verified" else "Unverified Profile Contact"})",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!privateProfile?.email.isNullOrBlank()) {
                    Text(
                        text = "Email: ${privateProfile?.email}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Theme & Infrastructure Status Card
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = NexEmeraldPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Dark Theme", style = MaterialTheme.typography.titleMedium)
                    }
                    Switch(checked = isDarkMode, onCheckedChange = { onToggleDarkMode() })
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isCloudinaryConfigured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isCloudinaryConfigured) NexEmeraldPrimary else NexWarningAmber
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isCloudinaryConfigured) {
                                "Media Storage: Cloudinary Signed Upload (f06cifgw)"
                            } else {
                                "Media Storage: Signing Service Not Configured (f06cifgw)"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = if (isCloudinaryConfigured) {
                                "Cloudinary (f06cifgw) signed upload active via backend signing service."
                            } else {
                                "Set CLOUDINARY_SIGNING_ENDPOINT in AI Studio Secrets (API Secret stays on backend)."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = NexEmeraldPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "WebRTC Calling: Public STUN Servers Active",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Configured with public Google STUN servers (stun.l.google.com:19302). Direct P2P connectivity; shows clear connection error if STUN is blocked.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Active Device Sessions Card
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, contentDescription = null, tint = NexEmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Active Device Sessions (${sessions.size})", style = MaterialTheme.typography.titleMedium)
                }
                sessions.forEach { sess ->
                    Text(
                        text = "• ${sess.deviceName} (${sess.platform.uppercase()})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sign Out & Account Deletion
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSignOut,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sign_out_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .weight(1f)
                    .testTag("delete_account_button")
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = NexDangerRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Account", color = NexDangerRed)
            }
        }
    }

    if (showEditDialog) {
        var uname by remember { mutableStateOf(profile.username) }
        var dname by remember { mutableStateOf(profile.displayName) }
        var phone by remember { mutableStateOf(privateProfile?.phoneNumber ?: "+1") }
        var about by remember { mutableStateOf(profile.about) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = uname, onValueChange = { uname = it }, label = { Text("Username") })
                    OutlinedTextField(value = dname, onValueChange = { dname = it }, label = { Text("Display Name") })
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") })
                    OutlinedTextField(value = about, onValueChange = { about = it }, label = { Text("About") })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateProfile(uname, dname, phone, about)
                        showEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account Permanently?") },
            text = {
                Text("This will remove your @${profile.username} claim, public profile, and private profile from Firestore and sign you out.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexDangerRed)
                ) {
                    Text("Delete Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 4. FULL-SCREEN WEBRTC AUDIO & VIDEO CALL OVERLAY
// ---------------------------------------------------------------------------

@Composable
fun WebRtcCallOverlay(
    callSession: CallSession,
    currentUid: String,
    callDurationSec: Int,
    iceConnectionState: com.example.data.webrtc.WebRtcIceConnectionState,
    isSpeakerOn: Boolean,
    isFrontCamera: Boolean,
    onAcceptCall: () -> Unit,
    onRejectCall: () -> Unit,
    onEndOrCancelCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCameraFacing: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onPermissionDenied: (String) -> Unit
) {
    val context = LocalContext.current
    val isCaller = callSession.callerId == currentUid
    val peerName = if (isCaller) callSession.receiverName else callSession.callerName
    val peerPhoto = if (isCaller) callSession.receiverPhoto else callSession.callerPhoto
    val isMuted = if (isCaller) callSession.callerMuted else callSession.receiverMuted
    val isCamOn = if (isCaller) callSession.callerCameraEnabled else callSession.receiverCameraEnabled
    val isPeerCamOn = if (isCaller) callSession.receiverCameraEnabled else callSession.callerCameraEnabled

    val acceptCallPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasMic = result[Manifest.permission.RECORD_AUDIO] == true
        val hasCam = result[Manifest.permission.CAMERA] == true
        if (callSession.callType == "VIDEO" && (!hasMic || !hasCam)) {
            onPermissionDenied("Camera and Microphone permissions are required to accept a video call.")
        } else if (!hasMic) {
            onPermissionDenied("Microphone permission is required to accept an audio call.")
        } else {
            onAcceptCall()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF071E19), Color(0xFF0B141A))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
            .testTag("webrtc_call_overlay")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = NexEmeraldPrimary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "WebRTC ${callSession.callType} • STUN • ICE: ${iceConnectionState.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexEmeraldBright,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                AvatarCircle(name = peerName, photoUrl = peerPhoto, size = 96.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                val statusLabel = when {
                    callSession.status == "RINGING" && isCaller -> "Calling... (30s timeout active)"
                    callSession.status == "RINGING" && !isCaller -> "Incoming ${callSession.callType} Call..."
                    callSession.status == "ACCEPTED" -> "Negotiating STUN P2P ICE Candidates (${iceConnectionState.name})..."
                    callSession.status == "CONNECTED" -> {
                        val mins = callDurationSec / 60
                        val secs = callDurationSec % 60
                        String.format(Locale.US, "Connected • %02d:%02d", mins, secs)
                    }
                    else -> callSession.status
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            // Video Stage (when VIDEO call)
            if (callSession.callType == "VIDEO") {
                Surface(
                    color = Color(0xFF111B21),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (isPeerCamOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = NexEmeraldPrimary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isPeerCamOn) {
                                    "Remote Video Stream ($peerName)"
                                } else {
                                    "$peerName turned camera off"
                                },
                                color = Color.White
                            )
                            Text(
                                text = "Local Camera: ${if (isCamOn) (if (isFrontCamera) "Front" else "Rear") else "Off"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Call Control Buttons
            if (callSession.status == "RINGING" && !isCaller) {
                // Incoming Call Accept / Reject Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingActionButton(
                        onClick = onRejectCall,
                        containerColor = NexDangerRed,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("reject_call_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Reject Call")
                    }

                    FloatingActionButton(
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasCam = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (callSession.callType == "VIDEO") {
                                if (hasMic && hasCam) {
                                    onAcceptCall()
                                } else {
                                    acceptCallPermLauncher.launch(
                                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                    )
                                }
                            } else {
                                if (hasMic) {
                                    onAcceptCall()
                                } else {
                                    acceptCallPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            }
                        },
                        containerColor = NexEmeraldBright,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("accept_call_button")
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept Call")
                    }
                }
            } else {
                // Active / Outgoing Call Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) NexDangerRed else Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute/Unmute Microphone",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) NexEmeraldPrimary else Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Toggle Speakerphone",
                            tint = Color.White
                        )
                    }

                    if (callSession.callType == "VIDEO") {
                        IconButton(
                            onClick = onToggleCamera,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(if (!isCamOn) NexDangerRed else Color.White.copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = if (isCamOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle Camera",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = onSwitchCameraFacing,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Switch Front/Rear Camera",
                                tint = Color.White
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = onEndOrCancelCall,
                        containerColor = NexDangerRed,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(62.dp)
                            .testTag("end_call_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Call")
                    }
                }
            }
        }
    }
}
