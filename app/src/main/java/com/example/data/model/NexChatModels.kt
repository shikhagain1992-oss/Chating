package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.security.MessageDigest
import java.util.UUID

data class NexChatAuthUser(
    val uid: String,
    val email: String,
    val displayName: String = "",
    val photoUrl: String = "",
    val provider: String = "email"
)

/**
 * Deterministic ID generators and monotonic status rules shared between Android and Web.
 */
object NexChatIds {
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun emailAccountUid(rawEmail: String): String {
        val clean = rawEmail.trim().lowercase()
        return "usr_${sha256Hex(clean).take(24)}"
    }

    fun hashEmailPassword(rawEmail: String, password: String): String {
        val clean = rawEmail.trim().lowercase()
        return sha256Hex("$clean:nexchat_auth_v1:$password")
    }

    fun isEmailAccountUid(uid: String): Boolean {
        return uid.matches(Regex("^usr_[a-f0-9]{24}$"))
    }

    fun directConversationId(uidA: String, uidB: String): String {
        val first = if (uidA <= uidB) uidA else uidB
        val second = if (uidA <= uidB) uidB else uidA
        return "dm_${first}_${second}"
    }

    fun groupConversationId(nowMillis: Long = System.currentTimeMillis()): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "grp_${nowMillis}_${suffix}"
    }

    fun messageId(clientTimestamp: Long = System.currentTimeMillis()): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "msg_${clientTimestamp}_${suffix}"
    }

    fun callId(nowMillis: Long = System.currentTimeMillis()): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "call_${nowMillis}_${suffix}"
    }

    fun statusId(nowMillis: Long = System.currentTimeMillis()): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "sts_${nowMillis}_${suffix}"
    }

    fun normalizeUsername(raw: String): String {
        return raw.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
    }
}

/**
 * Authoritative monotonic message delivery status model.
 * SENDING and FAILED are client-side optimistic states; SENT, DELIVERED, READ are persisted in Firestore.
 */
enum class MessageStatus(val rank: Int) {
    FAILED(-1),
    SENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3);

    companion object {
        fun fromString(value: String?): MessageStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SENT
        }

        /**
         * Ensures message status only moves forward monotonically (e.g. READ never regresses to DELIVERED or SENT).
         */
        fun canTransition(current: String?, next: MessageStatus): Boolean {
            if (next == FAILED) return true
            val currentEnum = fromString(current)
            return next.rank > currentEnum.rank
        }

        fun resolveAuthoritative(
            rawStatus: String,
            senderId: String,
            deliveredTo: List<String>,
            readBy: List<String>
        ): MessageStatus {
            val otherReaders = readBy.any { it != senderId }
            if (otherReaders || rawStatus == READ.name) return READ
            val otherDelivered = deliveredTo.any { it != senderId }
            if (otherDelivered || rawStatus == DELIVERED.name) return DELIVERED
            return fromString(rawStatus)
        }
    }
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val username: String = "",
    val usernameLower: String = "",
    val displayName: String = "",
    val displayNameLower: String = "",
    val photoUrl: String = "",
    val about: String = "Hey there! I am using NexChat Pro.",
    val online: Boolean = false,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserPrivateProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val phoneVerified: Boolean = false,
    val fcmTokens: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UsernameClaim(
    @DocumentId val id: String = "",
    val uid: String = "",
    val username: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserSession(
    @DocumentId val id: String = "",
    val sessionId: String = "",
    val uid: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastActiveAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val docId: String = "",
    val id: String = "",
    val isGroup: Boolean = false,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val groupName: String = "",
    val groupPhotoUrl: String = "",
    val groupDescription: String = "",
    val participantDetails: Map<String, Map<String, String>> = emptyMap(),
    val lastMessageId: String = "",
    val lastMessageText: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageSenderId: String = "",
    val lastMessageClientTimestamp: Long = 0L,
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    val unreadCounts: Map<String, Long> = emptyMap(),
    val typingTimestamps: Map<String, Long> = emptyMap(),
    val archivedBy: List<String> = emptyList(),
    val mutedBy: List<String> = emptyList(),
    val chatThemes: Map<String, String> = emptyMap(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveId(): String = id.ifBlank { docId }

    fun displayTitleFor(currentUid: String): String {
        if (isGroup) return groupName.ifBlank { "Group Chat" }
        val peerUid = memberUids.firstOrNull { it != currentUid } ?: currentUid
        val details = participantDetails[peerUid]
        return details?.get("displayName")?.takeIf { it.isNotBlank() }
            ?: details?.get("username")?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: "Chat"
    }

    fun displaySubtitleFor(currentUid: String): String {
        if (isGroup) return "${memberUids.size} members"
        val peerUid = memberUids.firstOrNull { it != currentUid } ?: currentUid
        val details = participantDetails[peerUid]
        val uname = details?.get("username").orEmpty()
        return if (uname.isNotBlank()) "@$uname" else ""
    }

    fun displayPhotoFor(currentUid: String): String {
        if (isGroup) return groupPhotoUrl
        val peerUid = memberUids.firstOrNull { it != currentUid } ?: currentUid
        return participantDetails[peerUid]?.get("photoUrl").orEmpty()
    }

    fun peerUidFor(currentUid: String): String? {
        if (isGroup) return null
        return memberUids.firstOrNull { it != currentUid }
    }

    fun unreadCountFor(currentUid: String): Int {
        return (unreadCounts[currentUid] ?: 0L).toInt()
    }

    fun isTypingFor(currentUid: String, nowMillis: Long = System.currentTimeMillis()): List<String> {
        return typingTimestamps.entries
            .filter { (uid, ts) -> uid != currentUid && (nowMillis - ts) in 0..6000 }
            .map { (uid, _) ->
                participantDetails[uid]?.get("displayName")?.takeIf { it.isNotBlank() } ?: "Someone"
            }
    }

    fun themeFor(currentUid: String): String {
        return chatThemes[currentUid] ?: "emerald"
    }
}

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val docId: String = "",
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val type: String = "TEXT", // TEXT, IMAGE, VIDEO, FILE, VOICE, CALL, SYSTEM
    val text: String = "",
    val mediaUrl: String = "",
    val mediaMimeType: String = "",
    val mediaFileName: String = "",
    val mediaSizeBytes: Long = 0L,
    val mediaDurationSec: Int = 0,
    val replyToMessageId: String = "",
    val replyToSenderName: String = "",
    val replyToText: String = "",
    val status: String = "SENT", // SENDING, SENT, DELIVERED, READ, FAILED
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val deletedFor: List<String> = emptyList(),
    val deletedForEveryone: Boolean = false,
    val clientTimestamp: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    val localError: String? = null
) {
    fun effectiveId(): String = id.ifBlank { docId }

    fun resolvedStatus(): MessageStatus {
        if (status == MessageStatus.SENDING.name) return MessageStatus.SENDING
        if (status == MessageStatus.FAILED.name) return MessageStatus.FAILED
        return MessageStatus.resolveAuthoritative(status, senderId, deliveredTo, readBy)
    }
}

@IgnoreExtraProperties
data class CallSession(
    @DocumentId val docId: String = "",
    val id: String = "",
    val conversationId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerPhoto: String = "",
    val receiverId: String = "",
    val receiverName: String = "",
    val receiverPhoto: String = "",
    val participantUids: List<String> = emptyList(),
    val callType: String = "AUDIO", // AUDIO, VIDEO
    val status: String = "RINGING", // RINGING, ACCEPTED, CONNECTED, REJECTED, CANCELLED, BUSY, MISSED, ENDED, FAILED
    val offer: Map<String, String>? = null,
    val answer: Map<String, String>? = null,
    val callerMuted: Boolean = false,
    val receiverMuted: Boolean = false,
    val callerCameraEnabled: Boolean = false,
    val receiverCameraEnabled: Boolean = false,
    val connectedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val durationSec: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveId(): String = id.ifBlank { docId }
}

@IgnoreExtraProperties
data class IceCandidateModel(
    @DocumentId val docId: String = "",
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StatusPost(
    @DocumentId val docId: String = "",
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorPhoto: String = "",
    val type: String = "TEXT", // TEXT, IMAGE, VIDEO
    val text: String = "",
    val backgroundColorHex: String = "#00A884",
    val mediaUrl: String = "",
    val privacy: String = "ALL", // ALL, ONLY_SHARE_WITH, EXCEPT
    val allowedUids: List<String> = emptyList(),
    val excludedUids: List<String> = emptyList(),
    val viewerUids: List<String> = emptyList(),
    val createdAtMillis: Long = 0L,
    val expiresAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun effectiveId(): String = id.ifBlank { docId }
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis >= expiresAtMillis
}
