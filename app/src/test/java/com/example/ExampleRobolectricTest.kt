package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.data.model.MessageStatus
import com.example.data.model.NexChatIds
import com.example.data.remote.CloudinaryMediaService
import com.example.data.remote.MediaStorageNotConfiguredException
import com.example.ui.notification.NexChatNotificationHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name and database id from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NexChat Pro", appName)
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue(dbId.isNotBlank())
    }

    @Test
    fun `deterministic 1-to-1 conversation ID is identical regardless of caller order`() {
        val id1 = NexChatIds.directConversationId("uid_charlie", "uid_alice")
        val id2 = NexChatIds.directConversationId("uid_alice", "uid_charlie")
        assertEquals("dm_uid_alice_uid_charlie", id1)
        assertEquals(id1, id2)
    }

    @Test
    fun `client message IDs are deterministic format and unique across rapid calls`() {
        val id1 = NexChatIds.messageId(1700000000000L)
        val id2 = NexChatIds.messageId(1700000000000L)
        assertTrue(id1.startsWith("msg_1700000000000_"))
        assertTrue(id2.startsWith("msg_1700000000000_"))
        assertNotEquals(id1, id2)
    }

    @Test
    fun `monotonic message status ordering prevents status regression`() {
        assertTrue(MessageStatus.SENDING.rank < MessageStatus.SENT.rank)
        assertTrue(MessageStatus.SENT.rank < MessageStatus.DELIVERED.rank)
        assertTrue(MessageStatus.DELIVERED.rank < MessageStatus.READ.rank)
        assertFalse(MessageStatus.canTransition("READ", MessageStatus.DELIVERED))

        val msgRead = ChatMessage(
            id = "msg_1",
            senderId = "uid_alice",
            status = "SENT",
            deliveredTo = listOf("uid_alice", "uid_bob"),
            readBy = listOf("uid_alice", "uid_bob")
        )
        assertEquals(MessageStatus.READ, msgRead.resolvedStatus())
    }

    @Test
    fun `unconfigured Cloudinary storage fails fast without Base64 fallback`() = runTest {
        val unconfiguredService = CloudinaryMediaService(
            cloudNameOverride = "",
            uploadPresetOverride = "",
            signingEndpointOverride = ""
        )
        assertFalse(unconfiguredService.isConfigured())
        val result = unconfiguredService.uploadBytes(
            bytes = ByteArray(64) { 1 },
            fileName = "test.jpg",
            mimeType = "image/jpeg"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaStorageNotConfiguredException)
    }

    @Test
    fun `notification helper creates message and call channels without error`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NexChatNotificationHelper.ensureChannels(context)
    }
}
