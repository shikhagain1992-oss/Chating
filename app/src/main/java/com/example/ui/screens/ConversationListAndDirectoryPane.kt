package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.Conversation
import com.example.data.model.UserProfile
import com.example.ui.theme.NexEmeraldBright
import com.example.ui.theme.NexEmeraldPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatFilterTab(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    GROUPS("Groups"),
    ARCHIVED("Archived")
}

@Composable
fun ConversationListPane(
    conversations: List<Conversation>,
    activeConversationId: String?,
    currentUid: String,
    peerProfiles: Map<String, UserProfile>,
    directoryUsers: List<UserProfile>,
    onlyGroupsMode: Boolean = false,
    onSelectConversation: (String) -> Unit,
    onStartDirectChat: (UserProfile) -> Unit,
    onCreateGroupChat: (name: String, description: String, members: List<UserProfile>) -> Unit,
    onSearchDirectory: (String) -> Unit,
    onToggleArchive: (Conversation) -> Unit,
    onToggleMute: (Conversation) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember {
        mutableStateOf(if (onlyGroupsMode) ChatFilterTab.GROUPS else ChatFilterTab.ALL)
    }
    var showNewDirectDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }

    val filteredConversations = remember(conversations, searchQuery, selectedFilter, currentUid, onlyGroupsMode) {
        conversations.filter { conv ->
            val isArchived = conv.archivedBy.contains(currentUid)
            val matchesTab = when {
                onlyGroupsMode -> conv.isGroup && !isArchived
                selectedFilter == ChatFilterTab.ARCHIVED -> isArchived
                selectedFilter == ChatFilterTab.UNREAD -> !isArchived && conv.unreadCountFor(currentUid) > 0
                selectedFilter == ChatFilterTab.GROUPS -> !isArchived && conv.isGroup
                else -> !isArchived
            }
            val title = conv.displayTitleFor(currentUid)
            val subtitle = conv.displaySubtitleFor(currentUid)
            val matchesQuery = searchQuery.isBlank() ||
                title.contains(searchQuery, ignoreCase = true) ||
                subtitle.contains(searchQuery, ignoreCase = true) ||
                conv.lastMessageText.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesQuery
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search Bar & Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search chats or messages...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_search_input")
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        onSearchDirectory("")
                        showNewGroupDialog = true
                    },
                    modifier = Modifier.testTag("create_group_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.GroupAdd,
                        contentDescription = "New Group",
                        tint = NexEmeraldPrimary
                    )
                }
            }

            if (!onlyGroupsMode) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ChatFilterTab.entries) { tab ->
                        FilterChip(
                            selected = selectedFilter == tab,
                            onClick = { selectedFilter = tab },
                            label = { Text(tab.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (onlyGroupsMode) Icons.Default.Group else Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (onlyGroupsMode) "No group chats yet" else "No conversations found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Start a real-time chat with another user by @username or create a group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onSearchDirectory("")
                                if (onlyGroupsMode) showNewGroupDialog = true else showNewDirectDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (onlyGroupsMode) "Create Group" else "Find User & Start Chat")
                        }
                    }
                }
            } else {
                // Stable keys prevent conversation list blinking or remounting
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("conversation_list")
                ) {
                    items(
                        items = filteredConversations,
                        key = { it.effectiveId() }
                    ) { conversation ->
                        val peerUid = conversation.peerUidFor(currentUid)
                        val peerProfile = peerUid?.let { peerProfiles[it] }
                        ConversationRowItem(
                            conversation = conversation,
                            currentUid = currentUid,
                            isSelected = conversation.effectiveId() == activeConversationId,
                            isPeerOnline = peerProfile?.online == true,
                            onClick = { onSelectConversation(conversation.effectiveId()) },
                            onToggleArchive = { onToggleArchive(conversation) },
                            onToggleMute = { onToggleMute(conversation) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                onSearchDirectory("")
                if (onlyGroupsMode) showNewGroupDialog = true else showNewDirectDialog = true
            },
            containerColor = NexEmeraldPrimary,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("new_chat_fab")
        ) {
            Icon(
                imageVector = if (onlyGroupsMode) Icons.Default.GroupAdd else Icons.Default.PersonAdd,
                contentDescription = "New Chat"
            )
        }
    }

    if (showNewDirectDialog) {
        UserDirectoryDialog(
            users = directoryUsers,
            onSearch = onSearchDirectory,
            onSelectUser = { user ->
                showNewDirectDialog = false
                onStartDirectChat(user)
            },
            onDismiss = { showNewDirectDialog = false }
        )
    }

    if (showNewGroupDialog) {
        CreateGroupDialog(
            users = directoryUsers,
            onSearch = onSearchDirectory,
            onCreateGroup = { name, desc, members ->
                showNewGroupDialog = false
                onCreateGroupChat(name, desc, members)
            },
            onDismiss = { showNewGroupDialog = false }
        )
    }
}

@Composable
private fun ConversationRowItem(
    conversation: Conversation,
    currentUid: String,
    isSelected: Boolean,
    isPeerOnline: Boolean,
    onClick: () -> Unit,
    onToggleArchive: () -> Unit,
    onToggleMute: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val title = conversation.displayTitleFor(currentUid)
    val photoUrl = conversation.displayPhotoFor(currentUid)
    val unreadCount = conversation.unreadCountFor(currentUid)
    val typingNames = conversation.isTypingFor(currentUid)
    val isMuted = conversation.mutedBy.contains(currentUid)
    val isArchived = conversation.archivedBy.contains(currentUid)

    val bgColor = if (isSelected) {
        NexEmeraldPrimary.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("conv_item_${conversation.effectiveId()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar + Online Dot
        Box(modifier = Modifier.size(52.dp)) {
            AvatarCircle(
                name = title,
                photoUrl = photoUrl,
                isGroup = conversation.isGroup,
                size = 52.dp
            )
            if (!conversation.isGroup && isPeerOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(NexEmeraldBright)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                val timeStr = remember(conversation.lastMessageAt, conversation.lastMessageClientTimestamp) {
                    val ms = conversation.lastMessageAt?.toDate()?.time
                        ?: conversation.lastMessageClientTimestamp
                    if (ms > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) else ""
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unreadCount > 0) NexEmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (typingNames.isNotEmpty()) {
                    Text(
                        text = "${typingNames.joinToString(", ")} typing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexEmeraldPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    if (conversation.lastMessageSenderId == currentUid && conversation.lastMessageType != "SYSTEM") {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Sent",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = conversation.lastMessageText.ifBlank { "No messages yet" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isMuted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(
                        containerColor = NexEmeraldPrimary,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = "Chat options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isArchived) "Unarchive Chat" else "Archive Chat") },
                            leadingIcon = {
                                Icon(
                                    if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleArchive()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute Notifications" else "Mute Notifications") },
                            leadingIcon = {
                                Icon(
                                    if (isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.NotificationsOff,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleMute()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarCircle(
    name: String,
    photoUrl: String,
    isGroup: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = NexEmeraldPrimary.copy(alpha = 0.2f)
    ) {
        if (photoUrl.isNotBlank() && photoUrl.startsWith("https://")) {
            AsyncImage(
                model = photoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                if (isGroup) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = name,
                        tint = NexEmeraldPrimary
                    )
                } else {
                    val initials = name.trim()
                        .removePrefix("@")
                        .split(" ")
                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        .take(2)
                        .joinToString("")
                        .ifBlank { "U" }
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexEmeraldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun UserDirectoryDialog(
    users: List<UserProfile>,
    onSearch: (String) -> Unit,
    onSelectUser: (UserProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start New Chat") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    placeholder = { Text("Search by @username or display name") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (users.isEmpty()) {
                    Text(
                        text = "No other registered users match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(users, key = { it.uid }) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectUser(user) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AvatarCircle(
                                    name = user.displayName,
                                    photoUrl = user.photoUrl,
                                    size = 40.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "@${user.username} • ${if (user.online) "Online" else "Offline"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (user.online) NexEmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
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

@Composable
fun CreateGroupDialog(
    users: List<UserProfile>,
    onSearch: (String) -> Unit,
    onCreateGroup: (name: String, description: String, members: List<UserProfile>) -> Unit,
    onDismiss: () -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }
    val selectedMembers = remember { mutableStateListOf<UserProfile>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Group") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = groupDescription,
                    onValueChange = { groupDescription = it },
                    label = { Text("Group Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Select Group Members (${selectedMembers.size} selected)",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(users, key = { it.uid }) { user ->
                        val isChecked = selectedMembers.any { it.uid == user.uid }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        selectedMembers.removeAll { it.uid == user.uid }
                                    } else {
                                        selectedMembers.add(user)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (isChecked) NexEmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(user.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("@${user.username}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onCreateGroup(groupName, groupDescription, selectedMembers.toList())
                    }
                },
                enabled = groupName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
            ) {
                Text("Create Group")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
