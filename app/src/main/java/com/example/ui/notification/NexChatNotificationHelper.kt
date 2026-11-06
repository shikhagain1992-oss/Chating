package com.example.ui.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import java.util.Collections

object NexChatNotificationHelper {

    const val CHANNEL_MESSAGES = "nexchat_direct_messages"
    const val CHANNEL_GROUPS = "nexchat_group_messages"
    const val CHANNEL_CALLS = "nexchat_incoming_calls"

    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_CALL_ID = "extra_call_id"

    private val notifiedItemIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Direct Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time notifications for incoming 1-to-1 messages"
                enableVibration(true)
            }

            val groupChannel = NotificationChannel(
                CHANNEL_GROUPS,
                "Group Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Real-time notifications for group conversations"
                enableVibration(true)
            }

            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alerts for incoming audio and video calls"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    null
                )
            }

            manager.createNotificationChannels(listOf(msgChannel, groupChannel, callChannel))
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showMessageNotification(
        context: Context,
        messageId: String,
        conversationId: String,
        title: String,
        body: String,
        isGroup: Boolean
    ) {
        if (!canPostNotifications(context)) return
        if (!notifiedItemIds.add("msg_$messageId")) return // Deduplicate

        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isGroup) CHANNEL_GROUPS else CHANNEL_MESSAGES
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(conversationId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerName: String,
        callType: String
    ) {
        if (!canPostNotifications(context)) return
        if (!notifiedItemIds.add("call_$callId")) return

        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming NexChat $callType Call")
            .setContentText("$callerName is calling you...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(callId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    fun clearDedupCache() {
        notifiedItemIds.clear()
    }
}
