package com.example.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CallSession
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageStatus
import com.example.data.model.NexChatIds
import com.example.data.model.StatusPost
import com.example.data.model.UserPrivateProfile
import com.example.data.model.UserProfile
import com.example.data.model.UserSession
import com.example.data.remote.CloudinaryMediaService
import com.example.data.repository.NexChatRepository
import com.example.data.webrtc.WebRtcCallManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface ProfileLoadState {
    data object Loading : ProfileLoadState
    data object NeedsOnboarding : ProfileLoadState
    data class Ready(val profile: UserProfile, val privateProfile: UserPrivateProfile?) : ProfileLoadState
    data class Error(val message: String) : ProfileLoadState
}

/**
 * Lifecycle-aware ViewModel scoped to a single authenticated `currentUserId`.
 *
 * ANTI-BLINK & CONSISTENCY GUARANTEES:
 * 1. Never clears `conversations` or `messages` while snapshots update.
 * 2. Attaches at most ONE snapshot listener for the conversation list and ONE for the active conversation.
 * 3. Reconciles optimistic `SENDING` / `FAILED` messages deterministically by `message.effectiveId()`.
 * 4. Cancels all listeners and clears all caches immediately on account switch or sign-out.
 */
class NexChatViewModel(
    private val repository: NexChatRepository,
    val callManager: WebRtcCallManager,
    val mediaService: CloudinaryMediaService,
    val currentUserId: String,
    val currentUserEmail: String = "",
    val initialDisplayName: String = "",
    val initialPhotoUrl: String = ""
) : ViewModel() {

    private val sessionId: String = "sess_${currentUserId.take(6)}_${UUID.randomUUID().toString().take(8)}"

    private val _profileState = MutableStateFlow<ProfileLoadState>(ProfileLoadState.Loading)
    val profileState: StateFlow<ProfileLoadState> = _profileState.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    // Server messages for the currently open conversation
    private val _serverMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    // Older paginated messages keyed by conversationId
    private val _olderMessagesByConv = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    // Optimistic (SENDING) and failed (FAILED) messages keyed by conversationId
    private val _pendingMessagesByConv = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    // Combined flicker-free message stream for the active conversation
    val activeMessages: StateFlow<List<ChatMessage>> = combine(
        _activeConversationId,
        _serverMessages,
        _olderMessagesByConv,
        _pendingMessagesByConv
    ) { convId, serverList, olderMap, pendingMap ->
        if (convId == null) {
            emptyList()
        } else {
            val older = olderMap[convId].orEmpty()
            val pending = pendingMap[convId].orEmpty()
            val serverIds = serverList.map { it.effectiveId() }.toSet()

            // Deduplicate: once a message ID arrives in serverList, drop any SENDING optimistic copy
            val remainingPending = pending.filter { msg ->
                msg.status == MessageStatus.FAILED.name || !serverIds.contains(msg.effectiveId())
            }
            val mergedMap = LinkedHashMap<String, ChatMessage>()
            for (m in older) mergedMap[m.effectiveId()] = m
            for (m in serverList) mergedMap[m.effectiveId()] = m
            for (m in remainingPending) mergedMap[m.effectiveId()] = m

            mergedMap.values.sortedWith(
                compareBy<ChatMessage> { it.clientTimestamp }.thenBy { it.effectiveId() }
            )
        }
    }.distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val _statuses = MutableStateFlow<List<StatusPost>>(emptyList())
    val statuses: StateFlow<List<StatusPost>> = _statuses.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallSession>>(emptyList())
    val callHistory: StateFlow<List<CallSession>> = _callHistory.asStateFlow()

    private val _sessions = MutableStateFlow<List<UserSession>>(emptyList())
    val sessions: StateFlow<List<UserSession>> = _sessions.asStateFlow()

    private val _loginAlertBanner = MutableStateFlow<String?>(null)
    val loginAlertBanner: StateFlow<String?> = _loginAlertBanner.asStateFlow()

    private val _directoryUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    val directoryUsers: StateFlow<List<UserProfile>> = _directoryUsers.asStateFlow()

    private val _peerProfiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val peerProfiles: StateFlow<Map<String, UserProfile>> = _peerProfiles.asStateFlow()

    private val _uiBannerError = MutableStateFlow<String?>(null)
    val uiBannerError: StateFlow<String?> = _uiBannerError.asStateFlow()

    private val _uiSuccessToast = MutableStateFlow<String?>(null)
    val uiSuccessToast: StateFlow<String?> = _uiSuccessToast.asStateFlow()

    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatMessage?>(null)
    val replyTarget: StateFlow<ChatMessage?> = _replyTarget.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private var conversationsJob: Job? = null
    private var activeMessagesJob: Job? = null
    private var observedMessageConvId: String? = null
    private var statusesJob: Job? = null
    private var callsJob: Job? = null
    private var sessionsJob: Job? = null
    private val peerProfileJobs = mutableMapOf<String, Job>()

    init {
        loadOrObserveCurrentUserProfile()
        viewModelScope.launch {
            callManager.callErrorBanner.collect { callErr ->
                if (!callErr.isNullOrBlank()) {
                    _uiBannerError.value = callErr
                    callManager.clearCallError()
                }
            }
        }
    }

    fun clearBannerError() {
        _uiBannerError.value = null
    }

    fun showPermissionError(message: String) {
        _uiBannerError.value = message
    }

    fun clearSuccessToast() {
        _uiSuccessToast.value = null
    }

    fun dismissLoginAlert() {
        _loginAlertBanner.value = null
    }

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    fun setReplyTarget(message: ChatMessage?) {
        _replyTarget.value = message
    }

    private fun loadOrObserveCurrentUserProfile() {
        viewModelScope.launch {
            _profileState.value = ProfileLoadState.Loading
            val profileRes = repository.getUserProfile(currentUserId)
            val profile = profileRes.getOrNull()
            val privateProfile = repository.getPrivateProfile(currentUserId).getOrNull()

            if (profile == null || profile.username.isBlank() || privateProfile == null || privateProfile.phoneNumber.isBlank()) {
                _profileState.value = ProfileLoadState.NeedsOnboarding
            } else {
                _profileState.value = ProfileLoadState.Ready(profile, privateProfile)
                onProfileReady(profile)
            }
        }
    }

    private fun onProfileReady(profile: UserProfile) {
        viewModelScope.launch {
            repository.setUserOnlineStatus(currentUserId, true)
            val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            repository.registerDeviceSession(
                uid = currentUserId,
                sessionId = sessionId,
                deviceName = deviceModel,
                platform = "android"
            )
            refreshUserDirectory("")
        }
        startRealtimeObservers()
    }

    fun completeProfileOnboarding(
        username: String,
        displayName: String,
        phoneNumber: String,
        about: String,
        photoUrl: String = initialPhotoUrl
    ) {
        viewModelScope.launch {
            val res = repository.claimUsernameAndSaveProfile(
                uid = currentUserId,
                rawUsername = username,
                displayName = displayName,
                phoneNumber = phoneNumber,
                email = currentUserEmail,
                photoUrl = photoUrl,
                about = about
            )
            res.fold(
                onSuccess = { savedProfile ->
                    val priv = repository.getPrivateProfile(currentUserId).getOrNull()
                    _profileState.value = ProfileLoadState.Ready(savedProfile, priv)
                    _uiSuccessToast.value = "Profile saved (@${savedProfile.username})"
                    onProfileReady(savedProfile)
                },
                onFailure = { err ->
                    _uiBannerError.value = err.localizedMessage ?: "Failed to save profile"
                }
            )
        }
    }

    private fun startRealtimeObservers() {
        if (conversationsJob == null) {
            conversationsJob = viewModelScope.launch {
                repository.observeConversations(currentUserId)
                    .catch { e ->
                        val fsErr = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                        if (fsErr == null && e is CancellationException) throw e
                        _uiBannerError.value = (fsErr ?: e).localizedMessage ?: "Failed to sync conversations"
                    }
                    .collect { convList ->
                        _conversations.value = convList
                        // Ensure peer profiles are observed for online/lastSeen indicator
                        convList.mapNotNull { it.peerUidFor(currentUserId) }.forEach { peerUid ->
                            observePeerProfileIfNeeded(peerUid)
                        }
                    }
            }
        }

        if (statusesJob == null) {
            statusesJob = viewModelScope.launch {
                repository.observeStatuses(currentUserId)
                    .catch { e ->
                        val fsErr = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                        if (fsErr == null && e is CancellationException) throw e
                    }
                    .collect { statusList ->
                        _statuses.value = statusList
                    }
            }
        }

        if (callsJob == null) {
            callsJob = viewModelScope.launch {
                callManager.observeCallHistory(currentUserId)
                    .catch { e ->
                        val fsErr = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                        if (fsErr == null && e is CancellationException) throw e
                    }
                    .collect { calls ->
                        _callHistory.value = calls
                        // Check if there is an incoming RINGING call for currentUserId
                        val ringingIncoming = calls.firstOrNull {
                            it.receiverId == currentUserId && it.status == "RINGING"
                        }
                        if (ringingIncoming != null) {
                            callManager.onIncomingCallDetected(ringingIncoming, viewModelScope)
                        }
                    }
            }
        }

        if (sessionsJob == null) {
            sessionsJob = viewModelScope.launch {
                repository.observeUserSessions(currentUserId)
                    .catch { e ->
                        val fsErr = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                        if (fsErr == null && e is CancellationException) throw e
                    }
                    .collect { sessList ->
                        val prevIds = _sessions.value.map { it.sessionId }.toSet()
                        _sessions.value = sessList
                        if (prevIds.isNotEmpty()) {
                            val newSession = sessList.firstOrNull {
                                it.sessionId != sessionId && !prevIds.contains(it.sessionId)
                            }
                            if (newSession != null) {
                                _loginAlertBanner.value =
                                    "Security Alert: New login detected on ${newSession.deviceName} (${newSession.platform.uppercase()})"
                            }
                        }
                    }
            }
        }
    }

    private fun observePeerProfileIfNeeded(peerUid: String) {
        if (peerUid.isBlank() || peerProfileJobs.containsKey(peerUid)) return
        peerProfileJobs[peerUid] = viewModelScope.launch {
            repository.observeUserProfile(peerUid)
                .catch { }
                .collect { profile ->
                    if (profile != null) {
                        _peerProfiles.update { it + (peerUid to profile) }
                    }
                }
        }
    }

    /**
     * Opens a conversation without clearing cached messages if re-selecting the same chat,
     * and attaches a single snapshot listener for that conversationId.
     */
    fun selectConversation(conversationId: String?) {
        if (_activeConversationId.value == conversationId && observedMessageConvId == conversationId) {
            return
        }
        val previousId = _activeConversationId.value
        if (previousId != null && previousId != conversationId) {
            viewModelScope.launch {
                repository.updateTypingStatus(previousId, currentUserId, false)
            }
        }

        _activeConversationId.value = conversationId
        _replyTarget.value = null

        if (conversationId == null) {
            activeMessagesJob?.cancel()
            activeMessagesJob = null
            observedMessageConvId = null
            _serverMessages.value = emptyList()
            return
        }

        if (observedMessageConvId != conversationId) {
            activeMessagesJob?.cancel()
            observedMessageConvId = conversationId
            _serverMessages.value = emptyList()

            activeMessagesJob = viewModelScope.launch {
                repository.observeMessages(conversationId, currentUserId)
                    .catch { e ->
                        val fsErr = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                        if (fsErr == null && e is CancellationException) throw e
                        _uiBannerError.value = (fsErr ?: e).localizedMessage ?: "Error loading messages"
                    }
                    .collect { messages ->
                        _serverMessages.value = messages
                        // Clear any pending optimistic copies that are now confirmed in Firestore
                        val confirmedIds = messages.map { it.effectiveId() }.toSet()
                        _pendingMessagesByConv.update { currentMap ->
                            val list = currentMap[conversationId].orEmpty()
                            val filtered = list.filter { !confirmedIds.contains(it.effectiveId()) }
                            if (filtered.size == list.size) currentMap else currentMap + (conversationId to filtered)
                        }
                        // Acknowledge READ for messages in the currently open conversation
                        if (_activeConversationId.value == conversationId) {
                            repository.markMessagesRead(conversationId, currentUserId, messages)
                        } else {
                            repository.markMessagesDelivered(conversationId, currentUserId, messages)
                        }
                    }
            }
        }
    }

    fun loadOlderMessages() {
        val convId = _activeConversationId.value ?: return
        val earliest = activeMessages.value.minOfOrNull { it.clientTimestamp } ?: return
        viewModelScope.launch {
            val res = repository.loadOlderMessages(convId, currentUserId, earliest)
            res.onSuccess { older ->
                if (older.isNotEmpty()) {
                    _olderMessagesByConv.update { map ->
                        val existing = map[convId].orEmpty()
                        val combined = (older + existing).distinctBy { it.effectiveId() }
                        map + (convId to combined)
                    }
                }
            }
        }
    }

    fun sendTextMessage(text: String) {
        val clean = text.trim()
        val convId = _activeConversationId.value ?: return
        if (clean.isBlank()) return
        val conv = _conversations.value.firstOrNull { it.effectiveId() == convId } ?: return
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        val reply = _replyTarget.value
        _replyTarget.value = null

        val nowMillis = System.currentTimeMillis()
        val msgId = NexChatIds.messageId(nowMillis)

        val optimisticMsg = ChatMessage(
            docId = msgId,
            id = msgId,
            conversationId = convId,
            senderId = currentUserId,
            senderName = currentProfile.displayName,
            memberUids = conv.memberUids,
            type = "TEXT",
            text = clean,
            replyToMessageId = reply?.effectiveId().orEmpty(),
            replyToSenderName = reply?.senderName.orEmpty(),
            replyToText = reply?.text.orEmpty(),
            status = MessageStatus.SENDING.name,
            deliveredTo = listOf(currentUserId),
            readBy = listOf(currentUserId),
            clientTimestamp = nowMillis
        )

        enqueueOptimisticMessage(convId, optimisticMsg)
        dispatchMessageToServer(optimisticMsg)
    }

    fun uploadAndSendMediaMessage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        type: String, // IMAGE, VIDEO, FILE, VOICE
        caption: String = "",
        durationSecHint: Int = 0
    ) {
        val convId = _activeConversationId.value ?: return
        val conv = _conversations.value.firstOrNull { it.effectiveId() == convId } ?: return
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        val reply = _replyTarget.value
        _replyTarget.value = null

        val nowMillis = System.currentTimeMillis()
        val msgId = NexChatIds.messageId(nowMillis)
        val displayLabel = caption.ifBlank {
            when (type) {
                "VOICE" -> "Voice message (${durationSecHint}s)"
                "IMAGE" -> "Photo"
                "VIDEO" -> "Video"
                else -> fileName.ifBlank { "Document" }
            }
        }

        val optimisticMsg = ChatMessage(
            docId = msgId,
            id = msgId,
            conversationId = convId,
            senderId = currentUserId,
            senderName = currentProfile.displayName,
            memberUids = conv.memberUids,
            type = type,
            text = displayLabel,
            mediaMimeType = mimeType,
            mediaFileName = fileName,
            mediaSizeBytes = bytes.size.toLong(),
            mediaDurationSec = durationSecHint,
            replyToMessageId = reply?.effectiveId().orEmpty(),
            replyToSenderName = reply?.senderName.orEmpty(),
            replyToText = reply?.text.orEmpty(),
            status = MessageStatus.SENDING.name,
            deliveredTo = listOf(currentUserId),
            readBy = listOf(currentUserId),
            clientTimestamp = nowMillis
        )

        enqueueOptimisticMessage(convId, optimisticMsg)

        viewModelScope.launch {
            _isUploadingMedia.value = true
            val uploadRes = mediaService.uploadBytes(
                bytes = bytes,
                fileName = fileName,
                mimeType = mimeType,
                folder = "nexchat_pro/conversations/$convId",
                durationSecHint = durationSecHint
            )
            _isUploadingMedia.value = false

            uploadRes.fold(
                onSuccess = { uploaded ->
                    val readyMsg = optimisticMsg.copy(
                        mediaUrl = uploaded.secureUrl,
                        mediaSizeBytes = uploaded.bytes,
                        mediaDurationSec = uploaded.durationSec
                    )
                    updateOptimisticMessage(convId, readyMsg)
                    dispatchMessageToServer(readyMsg)
                },
                onFailure = { err ->
                    val failedMsg = optimisticMsg.copy(
                        status = MessageStatus.FAILED.name,
                        localError = err.localizedMessage ?: "Media upload failed"
                    )
                    updateOptimisticMessage(convId, failedMsg)
                    _uiBannerError.value = err.localizedMessage ?: "Media upload unavailable"
                }
            )
        }
    }

    fun uploadAndSendVoiceFile(file: File, durationSec: Int) {
        if (!file.exists() || file.length() <= 0L) {
            _uiBannerError.value = "Voice recording was too short or empty."
            return
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            _uiBannerError.value = "Failed to read recorded voice note."
            return
        } finally {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
        uploadAndSendMediaMessage(
            bytes = bytes,
            fileName = file.name,
            mimeType = "audio/mp4",
            type = "VOICE",
            caption = "Voice message (${durationSec}s)",
            durationSecHint = durationSec
        )
    }

    fun retryFailedMessage(message: ChatMessage) {
        val convId = message.conversationId
        if (message.type != "TEXT" && message.mediaUrl.isBlank()) {
            _uiBannerError.value = message.localError
                ?: "Upload Unavailable — Storage Not Configured: Set CLOUDINARY_SIGNING_ENDPOINT for cloud f06cifgw in Secrets."
            return
        }
        val retrying = message.copy(status = MessageStatus.SENDING.name, localError = null)
        updateOptimisticMessage(convId, retrying)
        dispatchMessageToServer(retrying)
    }

    fun removeFailedMessage(message: ChatMessage) {
        val convId = message.conversationId
        _pendingMessagesByConv.update { map ->
            val filtered = map[convId].orEmpty().filter { it.effectiveId() != message.effectiveId() }
            map + (convId to filtered)
        }
    }

    private fun enqueueOptimisticMessage(convId: String, message: ChatMessage) {
        _pendingMessagesByConv.update { map ->
            val current = map[convId].orEmpty().filter { it.effectiveId() != message.effectiveId() }
            map + (convId to (current + message))
        }
    }

    private fun updateOptimisticMessage(convId: String, message: ChatMessage) {
        _pendingMessagesByConv.update { map ->
            val updated = map[convId].orEmpty().map {
                if (it.effectiveId() == message.effectiveId()) message else it
            }
            map + (convId to updated)
        }
    }

    private fun dispatchMessageToServer(message: ChatMessage) {
        viewModelScope.launch {
            val res = repository.sendMessage(
                messageId = message.effectiveId(),
                conversationId = message.conversationId,
                senderId = message.senderId,
                senderName = message.senderName,
                memberUids = message.memberUids,
                type = message.type,
                text = message.text,
                mediaUrl = message.mediaUrl,
                mediaMimeType = message.mediaMimeType,
                mediaFileName = message.mediaFileName,
                mediaSizeBytes = message.mediaSizeBytes,
                mediaDurationSec = message.mediaDurationSec,
                replyToMessageId = message.replyToMessageId,
                replyToSenderName = message.replyToSenderName,
                replyToText = message.replyToText,
                clientTimestamp = message.clientTimestamp
            )
            res.onFailure { err ->
                val failed = message.copy(
                    status = MessageStatus.FAILED.name,
                    localError = err.localizedMessage ?: "Send failed. Tap to retry."
                )
                updateOptimisticMessage(message.conversationId, failed)
            }
        }
    }

    fun deleteMessageForMe(message: ChatMessage) {
        viewModelScope.launch {
            repository.deleteMessageForMe(
                conversationId = message.conversationId,
                messageId = message.effectiveId(),
                currentUid = currentUserId
            )
        }
    }

    fun deleteMessageForEveryone(message: ChatMessage) {
        viewModelScope.launch {
            val res = repository.deleteMessageForEveryone(
                conversationId = message.conversationId,
                messageId = message.effectiveId(),
                senderUid = currentUserId
            )
            res.onFailure { err ->
                _uiBannerError.value = err.localizedMessage ?: "Could not delete message for everyone"
            }
        }
    }

    fun onTypingChanged(isTyping: Boolean) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            repository.updateTypingStatus(convId, currentUserId, isTyping)
        }
    }

    // ------------------------------------------------------------------------
    // DIRECT & GROUP CHAT CREATION / MANAGEMENT
    // ------------------------------------------------------------------------

    fun refreshUserDirectory(query: String = "") {
        viewModelScope.launch {
            val res = repository.searchUsers(query, currentUserId)
            res.onSuccess { list ->
                _directoryUsers.value = list
            }
        }
    }

    fun startOrOpenDirectChat(peer: UserProfile, onOpened: (String) -> Unit = {}) {
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        viewModelScope.launch {
            val res = repository.getOrCreateDirectConversation(currentProfile, peer)
            res.fold(
                onSuccess = { conv ->
                    selectConversation(conv.effectiveId())
                    onOpened(conv.effectiveId())
                },
                onFailure = { err ->
                    _uiBannerError.value = err.localizedMessage ?: "Could not open chat"
                }
            )
        }
    }

    fun createGroupChat(
        groupName: String,
        groupDescription: String,
        members: List<UserProfile>,
        onCreated: (String) -> Unit = {}
    ) {
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        viewModelScope.launch {
            val res = repository.createGroupConversation(
                creator = currentProfile,
                groupName = groupName,
                groupDescription = groupDescription,
                groupPhotoUrl = "",
                members = members
            )
            res.fold(
                onSuccess = { conv ->
                    _uiSuccessToast.value = "Group \"${conv.groupName}\" created"
                    selectConversation(conv.effectiveId())
                    onCreated(conv.effectiveId())
                },
                onFailure = { err ->
                    _uiBannerError.value = err.localizedMessage ?: "Failed to create group"
                }
            )
        }
    }

    fun updateGroupInfo(conversationId: String, groupName: String, groupDescription: String) {
        viewModelScope.launch {
            val res = repository.updateGroupProfile(conversationId, groupName, groupDescription, "")
            res.fold(
                onSuccess = { _uiSuccessToast.value = "Group info updated" },
                onFailure = { _uiBannerError.value = it.localizedMessage ?: "Only group admins can edit group info" }
            )
        }
    }

    fun addMemberToGroup(conversation: Conversation, newMember: UserProfile) {
        viewModelScope.launch {
            val nextMembers = (conversation.memberUids + newMember.uid).distinct()
            val nextDetails = conversation.participantDetails + (
                newMember.uid to mapOf(
                    "displayName" to newMember.displayName,
                    "username" to newMember.username,
                    "photoUrl" to newMember.photoUrl
                )
            )
            val res = repository.updateGroupMembers(
                conversationId = conversation.effectiveId(),
                updatedMemberUids = nextMembers,
                updatedAdminUids = conversation.adminUids,
                updatedDetails = nextDetails
            )
            res.onFailure {
                _uiBannerError.value = it.localizedMessage ?: "Only group admins can add members"
            }
        }
    }

    fun removeMemberFromGroup(conversation: Conversation, memberUid: String) {
        viewModelScope.launch {
            val nextMembers = conversation.memberUids.filter { it != memberUid }
            val nextAdmins = conversation.adminUids.filter { it != memberUid }
            val res = repository.updateGroupMembers(
                conversationId = conversation.effectiveId(),
                updatedMemberUids = nextMembers,
                updatedAdminUids = nextAdmins,
                updatedDetails = conversation.participantDetails
            )
            res.onFailure {
                _uiBannerError.value = it.localizedMessage ?: "Only group admins can remove members"
            }
        }
    }

    fun promoteMemberToAdmin(conversation: Conversation, memberUid: String) {
        viewModelScope.launch {
            val nextAdmins = (conversation.adminUids + memberUid).distinct()
            val res = repository.updateGroupMembers(
                conversationId = conversation.effectiveId(),
                updatedMemberUids = conversation.memberUids,
                updatedAdminUids = nextAdmins,
                updatedDetails = conversation.participantDetails
            )
            res.onFailure {
                _uiBannerError.value = it.localizedMessage ?: "Only group admins can promote admins"
            }
        }
    }

    fun leaveGroup(conversationId: String) {
        viewModelScope.launch {
            val res = repository.leaveGroup(conversationId, currentUserId)
            res.onSuccess {
                if (_activeConversationId.value == conversationId) {
                    selectConversation(null)
                }
                _uiSuccessToast.value = "You left the group"
            }
        }
    }

    fun toggleArchive(conversation: Conversation) {
        val isArchived = conversation.archivedBy.contains(currentUserId)
        viewModelScope.launch {
            repository.toggleArchiveConversation(conversation.effectiveId(), currentUserId, !isArchived)
        }
    }

    fun toggleMute(conversation: Conversation) {
        val isMuted = conversation.mutedBy.contains(currentUserId)
        viewModelScope.launch {
            repository.toggleMuteConversation(conversation.effectiveId(), currentUserId, !isMuted)
        }
    }

    fun setChatTheme(conversationId: String, themeId: String) {
        viewModelScope.launch {
            repository.setConversationTheme(conversationId, currentUserId, themeId)
        }
    }

    // ------------------------------------------------------------------------
    // STATUSES / STORIES
    // ------------------------------------------------------------------------

    fun postTextStatus(text: String, backgroundColorHex: String, privacy: String = "ALL") {
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            val res = repository.postStatus(
                author = currentProfile,
                type = "TEXT",
                text = text.trim(),
                backgroundColorHex = backgroundColorHex,
                mediaUrl = "",
                privacy = privacy
            )
            res.fold(
                onSuccess = { _uiSuccessToast.value = "Status posted (expires in 24h)" },
                onFailure = { _uiBannerError.value = it.localizedMessage ?: "Failed to post status" }
            )
        }
    }

    fun postMediaStatus(bytes: ByteArray, fileName: String, mimeType: String, caption: String) {
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        viewModelScope.launch {
            _isUploadingMedia.value = true
            val uploadRes = mediaService.uploadBytes(
                bytes = bytes,
                fileName = fileName,
                mimeType = mimeType,
                folder = "nexchat_pro/statuses/$currentUserId"
            )
            _isUploadingMedia.value = false
            uploadRes.fold(
                onSuccess = { uploaded ->
                    val type = if (mimeType.startsWith("video/")) "VIDEO" else "IMAGE"
                    repository.postStatus(
                        author = currentProfile,
                        type = type,
                        text = caption,
                        mediaUrl = uploaded.secureUrl
                    )
                    _uiSuccessToast.value = "Media status posted"
                },
                onFailure = { err ->
                    _uiBannerError.value = err.localizedMessage ?: "Media upload unavailable"
                }
            )
        }
    }

    fun markStatusViewed(statusId: String) {
        viewModelScope.launch {
            repository.markStatusViewed(statusId, currentUserId)
        }
    }

    fun deleteStatus(statusId: String) {
        viewModelScope.launch {
            repository.deleteStatus(statusId)
        }
    }

    // ------------------------------------------------------------------------
    // WEBRTC CALLING
    // ------------------------------------------------------------------------

    fun startPeerCall(conversation: Conversation, callType: String) {
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile ?: return
        val peerUid = conversation.peerUidFor(currentUserId)
        if (peerUid == null) {
            _uiBannerError.value = "Direct 1-to-1 peer required for WebRTC call."
            return
        }
        val peerDetails = conversation.participantDetails[peerUid]
        val peerProfile = _peerProfiles.value[peerUid] ?: UserProfile(
            id = peerUid,
            uid = peerUid,
            displayName = peerDetails?.get("displayName") ?: conversation.displayTitleFor(currentUserId),
            username = peerDetails?.get("username").orEmpty(),
            photoUrl = peerDetails?.get("photoUrl").orEmpty()
        )

        viewModelScope.launch {
            val res = callManager.startOutgoingCall(
                caller = currentProfile,
                receiver = peerProfile,
                conversationId = conversation.effectiveId(),
                callType = callType,
                scope = viewModelScope,
                onMissedTimeout = { missedSession ->
                    repository.sendMessage(
                        messageId = NexChatIds.messageId(),
                        conversationId = missedSession.conversationId,
                        senderId = currentUserId,
                        senderName = currentProfile.displayName,
                        memberUids = conversation.memberUids,
                        type = "CALL",
                        text = "📞 Missed ${missedSession.callType.lowercase()} call"
                    )
                }
            )
            res.onFailure { err ->
                _uiBannerError.value = err.localizedMessage ?: "Failed to initiate call"
            }
        }
    }

    fun acceptIncomingCall(callSession: CallSession) {
        viewModelScope.launch {
            val res = callManager.acceptIncomingCall(
                callSession = callSession,
                currentUid = currentUserId,
                scope = viewModelScope
            )
            res.onFailure { err ->
                _uiBannerError.value = err.localizedMessage ?: "Failed to accept call"
            }
        }
    }

    fun rejectIncomingCall(callSession: CallSession) {
        viewModelScope.launch {
            callManager.rejectIncomingCall(callSession.effectiveId())
        }
    }

    fun endOrCancelActiveCall() {
        val current = callManager.activeCall.value ?: return
        val currentProfile = (_profileState.value as? ProfileLoadState.Ready)?.profile
        viewModelScope.launch {
            val duration = callManager.endActiveCall().getOrDefault(0)
            if (currentProfile != null && current.callerId == currentUserId && duration > 0) {
                repository.sendMessage(
                    messageId = NexChatIds.messageId(),
                    conversationId = current.conversationId,
                    senderId = currentUserId,
                    senderName = currentProfile.displayName,
                    memberUids = current.participantUids,
                    type = "CALL",
                    text = "📞 ${current.callType.lowercase().replaceFirstChar { it.uppercase() }} call (${duration}s)"
                )
            }
        }
    }

    fun toggleCallMute() {
        viewModelScope.launch {
            callManager.toggleMute(currentUserId)
        }
    }

    fun toggleCallCamera() {
        viewModelScope.launch {
            callManager.toggleCamera(currentUserId)
        }
    }

    // ------------------------------------------------------------------------
    // SESSION ISOLATION & ACCOUNT CLEANUP
    // ------------------------------------------------------------------------

    fun prepareForSignOut(onReady: () -> Unit) {
        viewModelScope.launch {
            repository.setUserOnlineStatus(currentUserId, false)
            repository.removeDeviceSession(currentUserId, sessionId)
            resetAllListenersAndState()
            onReady()
        }
    }

    fun deleteAccountAndData(onDeleted: () -> Unit) {
        val uname = (_profileState.value as? ProfileLoadState.Ready)?.profile?.username.orEmpty()
        viewModelScope.launch {
            repository.deleteAccountData(currentUserId, uname)
            resetAllListenersAndState()
            onDeleted()
        }
    }

    private fun resetAllListenersAndState() {
        conversationsJob?.cancel()
        conversationsJob = null
        activeMessagesJob?.cancel()
        activeMessagesJob = null
        statusesJob?.cancel()
        statusesJob = null
        callsJob?.cancel()
        callsJob = null
        sessionsJob?.cancel()
        sessionsJob = null
        peerProfileJobs.values.forEach { it.cancel() }
        peerProfileJobs.clear()
        callManager.cleanupActiveCall()
        repository.clearSessionCaches()
        _conversations.value = emptyList()
        _serverMessages.value = emptyList()
        _olderMessagesByConv.value = emptyMap()
        _pendingMessagesByConv.value = emptyMap()
        _statuses.value = emptyList()
        _callHistory.value = emptyList()
        _sessions.value = emptyList()
        _activeConversationId.value = null
        observedMessageConvId = null
    }

    override fun onCleared() {
        super.onCleared()
        resetAllListenersAndState()
    }
}
