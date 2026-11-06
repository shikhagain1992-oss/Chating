package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.IceCandidateModel
import com.example.data.model.MessageStatus
import com.example.data.model.NexChatIds
import com.example.data.remote.CloudinaryMediaService
import com.example.data.remote.MediaStorageNotConfiguredException
import com.example.data.webrtc.WebRtcCallManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class NexChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }

    @Test
    fun observeConversations_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = NexChatRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeConversations("some_uid").first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated user")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    @Test
    fun claimUsernameAndSaveProfile_enforcesAtomicUniqueness_andIsolatesPrivatePii() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        val aliceUid = signInTestUser("alice_$suffix@nexchat.test")
        val repo = NexChatRepository(firestore)

        val uname = "alice_$suffix"
        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.claimUsernameAndSaveProfile(
                uid = aliceUid,
                rawUsername = uname,
                displayName = "Alice Vance",
                phoneNumber = "+15550101234",
                email = "alice_$suffix@nexchat.test"
            )
        }
        assertTrue("Alice profile creation should succeed: ${profileRes.exceptionOrNull()}", profileRes.isSuccess)

        val privateProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getPrivateProfile(aliceUid).getOrThrow()
        }
        assertNotNull(privateProfile)
        assertEquals("+15550101234", privateProfile?.phoneNumber)
        assertFalse("Phone must remain unverified unless OTP verified", privateProfile?.phoneVerified ?: true)

        // Switch to Bob and verify Bob cannot claim Alice's username or read Alice's private PII
        val bobUid = signInTestUser("bob_$suffix@nexchat.test")
        val hijackRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.claimUsernameAndSaveProfile(
                uid = bobUid,
                rawUsername = uname,
                displayName = "Bob Impersonator",
                phoneNumber = "+15550109999",
                email = "bob_$suffix@nexchat.test"
            )
        }
        assertTrue("Bob must not be able to claim Alice's username", hijackRes.isFailure)

        val crossReadPii = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getPrivateProfile(aliceUid)
        }
        assertTrue("Bob must not be able to read Alice's private PII", crossReadPii.isFailure)
        val fsErr = crossReadPii.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun directMessaging_idempotentSend_monotonicReceipts_andCrossUserIsolation() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        val aliceUid = signInTestUser("alice_dm_$suffix@nexchat.test")
        val repo = NexChatRepository(firestore)

        val aliceProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.claimUsernameAndSaveProfile(
                uid = aliceUid,
                rawUsername = "alicedm_$suffix",
                displayName = "Alice DM",
                phoneNumber = "+15550001111",
                email = "alice_dm_$suffix@nexchat.test"
            ).getOrThrow()
        }

        val bobUid = signInTestUser("bob_dm_$suffix@nexchat.test")
        val bobProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.claimUsernameAndSaveProfile(
                uid = bobUid,
                rawUsername = "bobdm_$suffix",
                displayName = "Bob DM",
                phoneNumber = "+15550002222",
                email = "bob_dm_$suffix@nexchat.test"
            ).getOrThrow()
        }

        // Verify deterministic 1-to-1 conversation ID parity regardless of order
        assertEquals(
            NexChatIds.directConversationId(aliceUid, bobUid),
            NexChatIds.directConversationId(bobUid, aliceUid)
        )

        // Sign in as Alice and create direct conversation + send deterministic message
        signInTestUser("alice_dm_$suffix@nexchat.test")
        val conv = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrCreateDirectConversation(aliceProfile, bobProfile).getOrThrow()
        }
        val msgId = NexChatIds.messageId()
        val sendRes1 = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.sendMessage(
                messageId = msgId,
                conversationId = conv.effectiveId(),
                senderId = aliceUid,
                senderName = aliceProfile.displayName,
                memberUids = conv.memberUids,
                type = "TEXT",
                text = "Instant flicker-free message"
            )
        }
        assertTrue(sendRes1.isSuccess)

        // Sending the same deterministic messageId a second time (retry) does not create duplicate documents
        val sendRes2 = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.sendMessage(
                messageId = msgId,
                conversationId = conv.effectiveId(),
                senderId = aliceUid,
                senderName = aliceProfile.displayName,
                memberUids = conv.memberUids,
                type = "TEXT",
                text = "Instant flicker-free message"
            )
        }
        assertTrue(sendRes2.isSuccess)

        // Switch to Bob, observe message in real time, and mark as READ
        signInTestUser("bob_dm_$suffix@nexchat.test")
        val bobMessages = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(conv.effectiveId(), bobUid).first { list -> list.any { it.effectiveId() == msgId } }
        }
        assertEquals(1, bobMessages.count { it.effectiveId() == msgId })

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.markMessagesRead(conv.effectiveId(), bobUid, bobMessages)
        }

        val updatedMessages = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(conv.effectiveId(), bobUid).first { list ->
                list.firstOrNull { it.effectiveId() == msgId }?.resolvedStatus() == MessageStatus.READ
            }
        }
        assertEquals(MessageStatus.READ, updatedMessages.first { it.effectiveId() == msgId }.resolvedStatus())
        assertFalse("READ status must never regress to DELIVERED", MessageStatus.canTransition("READ", MessageStatus.DELIVERED))

        // Switch to Outsider Mallory:
        // 1. Querying with her own UID returns 0 messages (none of Alice & Bob's messages are visible)
        // 2. Attempting to query with Alice's UID fails with PERMISSION_DENIED
        val malloryUid = signInTestUser("mallory_$suffix@nexchat.test")
        val malloryOwnQuery = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(conv.effectiveId(), malloryUid).first()
        }
        assertTrue("Mallory must not see any of Alice & Bob's messages", malloryOwnQuery.isEmpty())

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeMessages(conv.effectiveId(), aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED when Mallory queries Alice's memberUids filter")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException())
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    @Test
    fun webRtcCallSignaling_queuesIceCandidatesBeforeRemoteDescription_andDrainsWhenReady() = runBlocking {
        val callManager = WebRtcCallManager(firestore)
        callManager.resetSignalingState()

        // Verify STUN-only ICE server configuration (zero TURN servers)
        val iceServers = callManager.buildIceServerConfigs()
        assertEquals(1, iceServers.size)
        assertTrue(iceServers.first().urls.all { it.startsWith("stun:") })
        assertFalse(iceServers.first().urls.any { it.startsWith("turn:") })

        val cand1 = IceCandidateModel(
            candidate = "candidate:1 1 UDP 2122260223 192.0.2.10 50000 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0,
            senderId = "caller_1",
            participantUids = listOf("caller_1", "receiver_1")
        )
        val cand2 = IceCandidateModel(
            candidate = "candidate:2 1 UDP 2122260223 192.0.2.11 50001 typ srflx",
            sdpMid = "0",
            sdpMLineIndex = 0,
            senderId = "caller_1",
            participantUids = listOf("caller_1", "receiver_1")
        )

        callManager.onRemoteIceCandidateReceived(cand1)
        callManager.onRemoteIceCandidateReceived(cand2)
        // Duplicate candidate should be deduplicated
        callManager.onRemoteIceCandidateReceived(cand1)

        assertFalse(callManager.isRemoteDescriptionSet)
        assertEquals(2, callManager.getPendingCandidateCount())
        assertEquals(0, callManager.getAppliedCandidateCount())

        val drained = callManager.onRemoteDescriptionReady()
        assertTrue(callManager.isRemoteDescriptionSet)
        assertEquals(2, drained.size)
        assertEquals(0, callManager.getPendingCandidateCount())
        assertEquals(2, callManager.getAppliedCandidateCount())
        assertEquals(
            com.example.data.webrtc.WebRtcIceConnectionState.CONNECTED,
            callManager.iceConnectionState.value
        )

        // Verify honest STUN-only connectivity failure transitions to FAILED with clear error message
        callManager.onIceConnectionStateChanged(com.example.data.webrtc.WebRtcIceConnectionState.FAILED)
        assertEquals(
            com.example.data.webrtc.WebRtcIceConnectionState.FAILED,
            callManager.iceConnectionState.value
        )
        assertTrue(
            callManager.callErrorBanner.value.orEmpty().contains("Direct STUN peer-to-peer connectivity could not be established")
        )
    }

    @Test
    fun mediaUpload_withoutCloudinaryConfiguration_failsFastWithClearError() = runBlocking {
        val defaultService = CloudinaryMediaService()
        assertEquals("f06cifgw", defaultService.cloudName)

        val mediaService = CloudinaryMediaService(
            cloudNameOverride = "f06cifgw",
            uploadPresetOverride = "",
            signingEndpointOverride = ""
        )
        assertFalse(mediaService.isConfigured())
        val result = mediaService.uploadBytes(
            bytes = ByteArray(16) { 1 },
            fileName = "voice.m4a",
            mimeType = "audio/mp4"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaStorageNotConfiguredException)
    }

    @Test
    fun emailAccountRegistrationAndSignIn_worksWithoutFirebaseAuthRecaptcha_andPersistsProfile() = runBlocking {
        auth.signOut()
        val repo = NexChatRepository(firestore)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        val email = "emailuser_$suffix@nexchat.test"

        // 1. Register new Email/Password account with Full Name, Mobile Number, Email ID, and 6+ digit Password in one step
        val regResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.registerEmailAccountWithProfile(
                fullName = "Creator User",
                mobileNumber = "+15550198877",
                rawEmail = email,
                password = "123456",
                rawUsername = "creator_$suffix"
            )
        }
        assertTrue("1-step Sign Up should succeed: ${regResult.exceptionOrNull()}", regResult.isSuccess)
        val authUser = regResult.getOrThrow()
        assertTrue(NexChatIds.isEmailAccountUid(authUser.uid))
        assertEquals("Creator User", authUser.displayName)

        // 2. Verify public profile and private mobile number were saved during Sign Up
        val savedProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getUserProfile(authUser.uid).getOrThrow()
        }
        assertNotNull(savedProfile)
        assertEquals("Creator User", savedProfile?.displayName)
        assertEquals("creator_$suffix", savedProfile?.username)

        val savedPrivate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getPrivateProfile(authUser.uid).getOrThrow()
        }
        assertNotNull(savedPrivate)
        assertEquals("+15550198877", savedPrivate?.phoneNumber)

        // 3. Wrong password fails
        val wrongLogin = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.signInEmailAccount(email, "999999")
        }
        assertTrue(wrongLogin.isFailure)

        // 4. Correct password signs in with identical deterministic UID and resolved displayName
        val loginResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.signInEmailAccount(email, "123456")
        }
        assertTrue(loginResult.isSuccess)
        assertEquals(authUser.uid, loginResult.getOrThrow().uid)
        assertEquals("Creator User", loginResult.getOrThrow().displayName)

        // 5. Observe conversations for registered Email account succeeds
        val convs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeConversations(authUser.uid).first()
        }
        assertTrue(convs.isEmpty())
    }
}
