package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.NexChatAuthUser
import com.example.data.remote.CloudinaryMediaService
import com.example.data.repository.NexChatRepository
import com.example.data.webrtc.WebRtcCallManager
import com.example.ui.notification.NexChatNotificationHelper
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallsTabScreen
import com.example.ui.screens.ChatDetailPane
import com.example.ui.screens.ConversationListPane
import com.example.ui.screens.NexChatAuthSessionManager
import com.example.ui.screens.ProfileOnboardingScreen
import com.example.ui.screens.SettingsProfileTabScreen
import com.example.ui.screens.StatusTabScreen
import com.example.ui.screens.WebRtcCallOverlay
import com.example.ui.screens.performFullSignOut
import com.example.ui.theme.NexChatProTheme
import com.example.ui.theme.NexEmeraldDark
import com.example.ui.theme.NexEmeraldPrimary
import com.example.ui.theme.NexWarningAmber
import com.example.ui.viewmodel.NexChatViewModel
import com.example.ui.viewmodel.ProfileLoadState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

enum class MainNavDestination(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    GROUPS("Groups", Icons.Default.Group),
    STATUS("Status", Icons.Default.DonutLarge),
    CALLS("Calls", Icons.Default.Call),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NexChatNotificationHelper.ensureChannels(this)
        NexChatAuthSessionManager.restoreSession(this)

        val initialConversationId = intent?.getStringExtra(NexChatNotificationHelper.EXTRA_CONVERSATION_ID)

        setContent {
            NexChatRootApp(initialConversationId = initialConversationId)
        }
    }
}

@Composable
fun NexChatRootApp(
    auth: FirebaseAuth = Firebase.auth,
    initialConversationId: String? = null
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        NexChatAuthSessionManager.restoreSession(context)
    }

    val firebaseUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val emailSession by NexChatAuthSessionManager.emailSession.collectAsStateWithLifecycle()

    val activeUser: NexChatAuthUser? = remember(firebaseUser, emailSession) {
        val fb = firebaseUser
        when {
            fb != null -> NexChatAuthUser(
                uid = fb.uid,
                email = fb.email.orEmpty(),
                displayName = fb.displayName.orEmpty(),
                photoUrl = fb.photoUrl?.toString().orEmpty(),
                provider = "firebase"
            )
            emailSession != null -> emailSession
            else -> null
        }
    }

    if (activeUser == null) {
        NexChatProTheme(darkTheme = true) {
            AuthScreen(onAuthSuccess = {})
        }
    } else {
        // Keyed by activeUser.uid so switching accounts creates a clean ViewModel instance with zero stale state
        val viewModel: NexChatViewModel = viewModel(
            key = activeUser.uid,
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY]) {
                        "APPLICATION_KEY missing from CreationExtras"
                    }
                    val databaseId = app.getString(R.string.firestore_database_id)
                    val db = FirebaseFirestore.getInstance(databaseId)
                    NexChatViewModel(
                        repository = NexChatRepository(db),
                        callManager = WebRtcCallManager(db, app),
                        mediaService = CloudinaryMediaService(),
                        currentUserId = activeUser.uid,
                        currentUserEmail = activeUser.email,
                        initialDisplayName = activeUser.displayName,
                        initialPhotoUrl = activeUser.photoUrl
                    )
                }
            }
        )

        val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

        NexChatProTheme(darkTheme = isDarkMode) {
            AuthenticatedWorkspace(
                viewModel = viewModel,
                initialConversationId = initialConversationId
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedWorkspace(
    viewModel: NexChatViewModel,
    initialConversationId: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val loginAlertBanner by viewModel.loginAlertBanner.collectAsStateWithLifecycle()
    val directoryUsers by viewModel.directoryUsers.collectAsStateWithLifecycle()
    val peerProfiles by viewModel.peerProfiles.collectAsStateWithLifecycle()
    val uiBannerError by viewModel.uiBannerError.collectAsStateWithLifecycle()
    val uiSuccessToast by viewModel.uiSuccessToast.collectAsStateWithLifecycle()
    val isUploadingMedia by viewModel.isUploadingMedia.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

    val activeCall by viewModel.callManager.activeCall.collectAsStateWithLifecycle()
    val callDurationSec by viewModel.callManager.callDurationSec.collectAsStateWithLifecycle()
    val iceConnectionState by viewModel.callManager.iceConnectionState.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.callManager.isSpeakerOn.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.callManager.isFrontCamera.collectAsStateWithLifecycle()

    // Request POST_NOTIFICATIONS on Android 13+
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!initialConversationId.isNullOrBlank()) {
            viewModel.selectConversation(initialConversationId)
        }
    }

    // Trigger system notification for incoming calls
    LaunchedEffect(activeCall?.effectiveId(), activeCall?.status) {
        val call = activeCall
        if (call != null && call.receiverId == viewModel.currentUserId && call.status == "RINGING") {
            NexChatNotificationHelper.showIncomingCallNotification(
                context = context,
                callId = call.effectiveId(),
                callerName = call.callerName,
                callType = call.callType
            )
        }
    }

    when (val state = profileState) {
        is ProfileLoadState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NexEmeraldPrimary)
            }
        }
        is ProfileLoadState.NeedsOnboarding -> {
            val databaseId = context.getString(R.string.firestore_database_id)
            val repo = remember(databaseId) { NexChatRepository(FirebaseFirestore.getInstance(databaseId)) }
            ProfileOnboardingScreen(
                repository = repo,
                currentUid = viewModel.currentUserId,
                initialDisplayName = viewModel.initialDisplayName,
                initialEmail = viewModel.currentUserEmail,
                errorBanner = uiBannerError,
                onCompleteProfile = { uname, dname, phone, about ->
                    viewModel.completeProfileOnboarding(uname, dname, phone, about)
                },
                onSignOut = {
                    viewModel.prepareForSignOut {
                        performFullSignOut(context, scope) {}
                    }
                }
            )
        }
        is ProfileLoadState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }
        is ProfileLoadState.Ready -> {
            var currentTab by rememberSaveable { mutableIntStateOf(0) }
            val activeConversation = remember(conversations, activeConversationId) {
                conversations.firstOrNull { it.effectiveId() == activeConversationId }
            }
            val totalUnreadCount = remember(conversations, viewModel.currentUserId) {
                conversations.sumOf { it.unreadCountFor(viewModel.currentUserId) }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isExpandedScreen = maxWidth >= 720.dp

                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            if (isExpandedScreen || activeConversation == null) {
                                TopAppBar(
                                    title = {
                                        Column {
                                            Text(
                                                text = "NexChat Pro",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "@${state.profile.username} • Online",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.85f)
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = { viewModel.toggleDarkMode() }) {
                                            Icon(
                                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                                contentDescription = "Toggle Dark/Light Theme",
                                                tint = Color.White
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = NexEmeraldDark,
                                        titleContentColor = Color.White
                                    )
                                )
                            }
                        },
                        bottomBar = {
                            if (!isExpandedScreen && activeConversation == null) {
                                NavigationBar(
                                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                ) {
                                    MainNavDestination.entries.forEachIndexed { index, dest ->
                                        NavigationBarItem(
                                            selected = currentTab == index,
                                            onClick = { currentTab = index },
                                            icon = {
                                                if (dest == MainNavDestination.CHATS && totalUnreadCount > 0) {
                                                    BadgedBox(
                                                        badge = {
                                                            Badge(containerColor = NexEmeraldPrimary) {
                                                                Text(totalUnreadCount.toString())
                                                            }
                                                        }
                                                    ) {
                                                        Icon(dest.icon, contentDescription = dest.label)
                                                    }
                                                } else {
                                                    Icon(dest.icon, contentDescription = dest.label)
                                                }
                                            },
                                            label = { Text(dest.label) },
                                            modifier = Modifier.testTag("nav_tab_${dest.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            // Login Security Alert Banner
                            AnimatedVisibility(visible = loginAlertBanner != null) {
                                Surface(
                                    color = NexWarningAmber.copy(alpha = 0.2f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Security, contentDescription = null, tint = NexWarningAmber)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = loginAlertBanner.orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.dismissLoginAlert() }) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss alert")
                                        }
                                    }
                                }
                            }

                            // Error / Toast Banner
                            AnimatedVisibility(visible = uiBannerError != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = uiBannerError.orEmpty(),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.clearBannerError() }) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss error")
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = uiSuccessToast != null) {
                                Surface(
                                    color = NexEmeraldPrimary.copy(alpha = 0.18f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = uiSuccessToast.orEmpty(),
                                            color = NexEmeraldPrimary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.clearSuccessToast() }) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss message")
                                        }
                                    }
                                }
                            }

                            if (isExpandedScreen) {
                                // Tablet / Foldable / Desktop Split-Pane Canonical Layout
                                Row(modifier = Modifier.fillMaxSize()) {
                                    NavigationRail {
                                        MainNavDestination.entries.forEachIndexed { index, dest ->
                                            NavigationRailItem(
                                                selected = currentTab == index,
                                                onClick = { currentTab = index },
                                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                                label = { Text(dest.label) }
                                            )
                                        }
                                    }
                                    VerticalDivider()

                                    if (currentTab == 0 || currentTab == 1) {
                                        ConversationListPane(
                                            conversations = conversations,
                                            activeConversationId = activeConversationId,
                                            currentUid = viewModel.currentUserId,
                                            peerProfiles = peerProfiles,
                                            directoryUsers = directoryUsers,
                                            onlyGroupsMode = currentTab == 1,
                                            onSelectConversation = { viewModel.selectConversation(it) },
                                            onStartDirectChat = { viewModel.startOrOpenDirectChat(it) },
                                            onCreateGroupChat = { name, desc, members ->
                                                viewModel.createGroupChat(name, desc, members)
                                            },
                                            onSearchDirectory = { viewModel.refreshUserDirectory(it) },
                                            onToggleArchive = { viewModel.toggleArchive(it) },
                                            onToggleMute = { viewModel.toggleMute(it) },
                                            modifier = Modifier
                                                .width(350.dp)
                                                .fillMaxHeight()
                                        )
                                        VerticalDivider()
                                        if (activeConversation != null) {
                                            val peerUid = activeConversation.peerUidFor(viewModel.currentUserId)
                                            ChatDetailPane(
                                                conversation = activeConversation,
                                                messages = activeMessages,
                                                currentUid = viewModel.currentUserId,
                                                peerProfile = peerUid?.let { peerProfiles[it] },
                                                directoryUsers = directoryUsers,
                                                replyTarget = replyTarget,
                                                isUploadingMedia = isUploadingMedia,
                                                isDarkMode = isDarkMode,
                                                showBackButton = false,
                                                onBack = { viewModel.selectConversation(null) },
                                                onSendText = { viewModel.sendTextMessage(it) },
                                                onUploadAndSendMedia = { bytes, name, mime, type ->
                                                    viewModel.uploadAndSendMediaMessage(bytes, name, mime, type)
                                                },
                                                onUploadAndSendVoice = { file, dur ->
                                                    viewModel.uploadAndSendVoiceFile(file, dur)
                                                },
                                                onRetryMessage = { viewModel.retryFailedMessage(it) },
                                                onRemoveFailedMessage = { viewModel.removeFailedMessage(it) },
                                                onSetReplyTarget = { viewModel.setReplyTarget(it) },
                                                onDeleteForMe = { viewModel.deleteMessageForMe(it) },
                                                onDeleteForEveryone = { viewModel.deleteMessageForEveryone(it) },
                                                onTypingChanged = { viewModel.onTypingChanged(it) },
                                                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                                onStartAudioCall = { viewModel.startPeerCall(activeConversation, "AUDIO") },
                                                onStartVideoCall = { viewModel.startPeerCall(activeConversation, "VIDEO") },
                                                onSelectTheme = { viewModel.setChatTheme(activeConversation.effectiveId(), it) },
                                                onUpdateGroupInfo = { n, d ->
                                                    viewModel.updateGroupInfo(activeConversation.effectiveId(), n, d)
                                                },
                                                onAddGroupMember = { viewModel.addMemberToGroup(activeConversation, it) },
                                                onRemoveGroupMember = { viewModel.removeMemberFromGroup(activeConversation, it) },
                                                onPromoteGroupAdmin = { viewModel.promoteMemberToAdmin(activeConversation, it) },
                                                onLeaveGroup = { viewModel.leaveGroup(activeConversation.effectiveId()) },
                                                onPermissionDeniedMessage = { msg ->
                                                    viewModel.showPermissionError(msg)
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Select a conversation on the left to start messaging",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    } else {
                                        Box(modifier = Modifier.weight(1f)) {
                                            SecondaryTabContent(
                                                tabIndex = currentTab,
                                                viewModel = viewModel,
                                                profile = state.profile,
                                                privateProfile = state.privateProfile,
                                                statuses = statuses,
                                                callHistory = callHistory,
                                                sessions = sessions,
                                                isDarkMode = isDarkMode,
                                                onSignOut = {
                                                    viewModel.prepareForSignOut {
                                                        performFullSignOut(context, scope) {}
                                                    }
                                                },
                                                onDeleteAccount = {
                                                    viewModel.deleteAccountAndData {
                                                        performFullSignOut(context, scope) {}
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Mobile Portrait Layout
                                if (activeConversation != null) {
                                    val peerUid = activeConversation.peerUidFor(viewModel.currentUserId)
                                    ChatDetailPane(
                                        conversation = activeConversation,
                                        messages = activeMessages,
                                        currentUid = viewModel.currentUserId,
                                        peerProfile = peerUid?.let { peerProfiles[it] },
                                        directoryUsers = directoryUsers,
                                        replyTarget = replyTarget,
                                        isUploadingMedia = isUploadingMedia,
                                        isDarkMode = isDarkMode,
                                        showBackButton = true,
                                        onBack = { viewModel.selectConversation(null) },
                                        onSendText = { viewModel.sendTextMessage(it) },
                                        onUploadAndSendMedia = { bytes, name, mime, type ->
                                            viewModel.uploadAndSendMediaMessage(bytes, name, mime, type)
                                        },
                                        onUploadAndSendVoice = { file, dur ->
                                            viewModel.uploadAndSendVoiceFile(file, dur)
                                        },
                                        onRetryMessage = { viewModel.retryFailedMessage(it) },
                                        onRemoveFailedMessage = { viewModel.removeFailedMessage(it) },
                                        onSetReplyTarget = { viewModel.setReplyTarget(it) },
                                        onDeleteForMe = { viewModel.deleteMessageForMe(it) },
                                        onDeleteForEveryone = { viewModel.deleteMessageForEveryone(it) },
                                        onTypingChanged = { viewModel.onTypingChanged(it) },
                                        onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                        onStartAudioCall = { viewModel.startPeerCall(activeConversation, "AUDIO") },
                                        onStartVideoCall = { viewModel.startPeerCall(activeConversation, "VIDEO") },
                                        onSelectTheme = { viewModel.setChatTheme(activeConversation.effectiveId(), it) },
                                        onUpdateGroupInfo = { n, d ->
                                            viewModel.updateGroupInfo(activeConversation.effectiveId(), n, d)
                                        },
                                        onAddGroupMember = { viewModel.addMemberToGroup(activeConversation, it) },
                                        onRemoveGroupMember = { viewModel.removeMemberFromGroup(activeConversation, it) },
                                        onPromoteGroupAdmin = { viewModel.promoteMemberToAdmin(activeConversation, it) },
                                        onLeaveGroup = { viewModel.leaveGroup(activeConversation.effectiveId()) },
                                        onPermissionDeniedMessage = { msg ->
                                            viewModel.showPermissionError(msg)
                                        }
                                    )
                                } else {
                                    when (currentTab) {
                                        0, 1 -> {
                                            ConversationListPane(
                                                conversations = conversations,
                                                activeConversationId = activeConversationId,
                                                currentUid = viewModel.currentUserId,
                                                peerProfiles = peerProfiles,
                                                directoryUsers = directoryUsers,
                                                onlyGroupsMode = currentTab == 1,
                                                onSelectConversation = { viewModel.selectConversation(it) },
                                                onStartDirectChat = { viewModel.startOrOpenDirectChat(it) },
                                                onCreateGroupChat = { name, desc, members ->
                                                    viewModel.createGroupChat(name, desc, members)
                                                },
                                                onSearchDirectory = { viewModel.refreshUserDirectory(it) },
                                                onToggleArchive = { viewModel.toggleArchive(it) },
                                                onToggleMute = { viewModel.toggleMute(it) }
                                            )
                                        }
                                        else -> {
                                            SecondaryTabContent(
                                                tabIndex = currentTab,
                                                viewModel = viewModel,
                                                profile = state.profile,
                                                privateProfile = state.privateProfile,
                                                statuses = statuses,
                                                callHistory = callHistory,
                                                sessions = sessions,
                                                isDarkMode = isDarkMode,
                                                onSignOut = {
                                                    viewModel.prepareForSignOut {
                                                        performFullSignOut(context, scope) {}
                                                    }
                                                },
                                                onDeleteAccount = {
                                                    viewModel.deleteAccountAndData {
                                                        performFullSignOut(context, scope) {}
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Full-Screen WebRTC Call Overlay (Incoming, Outgoing, Connected)
                activeCall?.let { callSession ->
                    WebRtcCallOverlay(
                        callSession = callSession,
                        currentUid = viewModel.currentUserId,
                        callDurationSec = callDurationSec,
                        iceConnectionState = iceConnectionState,
                        isSpeakerOn = isSpeakerOn,
                        isFrontCamera = isFrontCamera,
                        onAcceptCall = { viewModel.acceptIncomingCall(callSession) },
                        onRejectCall = { viewModel.rejectIncomingCall(callSession) },
                        onEndOrCancelCall = { viewModel.endOrCancelActiveCall() },
                        onToggleMute = { viewModel.toggleCallMute() },
                        onToggleCamera = { viewModel.toggleCallCamera() },
                        onSwitchCameraFacing = { viewModel.callManager.switchCameraFacing() },
                        onToggleSpeaker = { viewModel.callManager.toggleSpeakerphone() },
                        onPermissionDenied = { msg -> viewModel.showPermissionError(msg) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryTabContent(
    tabIndex: Int,
    viewModel: NexChatViewModel,
    profile: com.example.data.model.UserProfile,
    privateProfile: com.example.data.model.UserPrivateProfile?,
    statuses: List<com.example.data.model.StatusPost>,
    callHistory: List<com.example.data.model.CallSession>,
    sessions: List<com.example.data.model.UserSession>,
    isDarkMode: Boolean,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    when (tabIndex) {
        2 -> {
            StatusTabScreen(
                statuses = statuses,
                currentUid = viewModel.currentUserId,
                onPostTextStatus = { text, bg, privacy ->
                    viewModel.postTextStatus(text, bg, privacy)
                },
                onPostMediaStatus = { bytes, name, mime, caption ->
                    viewModel.postMediaStatus(bytes, name, mime, caption)
                },
                onViewStatus = { viewModel.markStatusViewed(it.effectiveId()) },
                onDeleteStatus = { viewModel.deleteStatus(it) }
            )
        }
        3 -> {
            CallsTabScreen(
                calls = callHistory,
                currentUid = viewModel.currentUserId
            )
        }
        4 -> {
            SettingsProfileTabScreen(
                profile = profile,
                privateProfile = privateProfile,
                sessions = sessions,
                isDarkMode = isDarkMode,
                isCloudinaryConfigured = viewModel.mediaService.isConfigured(),
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                onUpdateProfile = { uname, dname, phone, about ->
                    viewModel.completeProfileOnboarding(uname, dname, phone, about, profile.photoUrl)
                },
                onSignOut = onSignOut,
                onDeleteAccount = onDeleteAccount
            )
        }
    }
}
