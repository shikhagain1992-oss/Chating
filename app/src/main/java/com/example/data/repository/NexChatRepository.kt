package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageStatus
import com.example.data.model.NexChatAuthUser
import com.example.data.model.NexChatIds
import com.example.data.model.StatusPost
import com.example.data.model.UserPrivateProfile
import com.example.data.model.UserProfile
import com.example.data.model.UserSession
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Collections

class NexChatRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    // Track message IDs already acknowledged in this session to prevent duplicate delivery/read writes
    private val deliveredAckCache: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val readAckCache: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun clearSessionCaches() {
        deliveredAckCache.clear()
        readAckCache.clear()
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before accessing Firestore.")
    }

    // ------------------------------------------------------------------------
    // 0. CROSS-PLATFORM EMAIL/PASSWORD AUTHENTICATION (/auth_accounts/{uid})
    // ------------------------------------------------------------------------

    suspend fun registerEmailAccount(
        rawEmail: String,
        password: String
    ): Result<NexChatAuthUser> {
        val cleanEmail = rawEmail.trim().lowercase()
        if (!cleanEmail.contains("@") || cleanEmail.length < 3) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 digits or characters."))
        }
        val uid = NexChatIds.emailAccountUid(cleanEmail)
        val passwordHash = NexChatIds.hashEmailPassword(cleanEmail, password)
        val docRef = db.collection("auth_accounts").document(uid)

        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val storedHash = existing.getString("passwordHash")
                if (storedHash == passwordHash) {
                    return Result.success(
                        NexChatAuthUser(
                            uid = uid,
                            email = cleanEmail,
                            displayName = cleanEmail.substringBefore("@"),
                            provider = "email"
                        )
                    )
                } else {
                    return Result.failure(
                        IllegalStateException("An account with $cleanEmail already exists. Switch to 'Sign In' and enter your password.")
                    )
                }
            }
            docRef.set(
                mapOf(
                    "uid" to uid,
                    "email" to cleanEmail,
                    "passwordHash" to passwordHash,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(
                NexChatAuthUser(
                    uid = uid,
                    email = cleanEmail,
                    displayName = cleanEmail.substringBefore("@"),
                    provider = "email"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Complete 1-step Sign Up: registers the Email/Password account AND saves the user's
     * Full Name, Mobile Number, Email ID, and unique @username in Firestore.
     */
    suspend fun registerEmailAccountWithProfile(
        fullName: String,
        mobileNumber: String,
        rawEmail: String,
        password: String,
        rawUsername: String = ""
    ): Result<NexChatAuthUser> {
        val cleanName = fullName.trim()
        val cleanPhone = mobileNumber.trim()
        val cleanEmail = rawEmail.trim().lowercase()

        if (cleanName.isEmpty() || cleanName.length > 60) {
            return Result.failure(IllegalArgumentException("Please enter your full name (1–60 characters)."))
        }
        if (cleanPhone.length !in 5..24) {
            return Result.failure(IllegalArgumentException("Please enter a valid mobile number (at least 5 digits)."))
        }
        if (!cleanEmail.contains("@") || cleanEmail.length < 3) {
            return Result.failure(IllegalArgumentException("Please enter a valid email ID."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 digits or characters."))
        }

        // 1. Register or verify the Email/Password account in /auth_accounts/{uid}
        val accountRes = registerEmailAccount(cleanEmail, password)
        val authUser = accountRes.getOrElse { return Result.failure(it) }
        val uid = authUser.uid

        // 2. Derive a valid 3..24 character lowercase username from rawUsername, fullName, or email
        val baseUsername = NexChatIds.normalizeUsername(
            rawUsername.ifBlank { cleanName.ifBlank { cleanEmail.substringBefore("@") } }
        ).let { candidate ->
            when {
                candidate.length >= 3 -> candidate.take(18)
                else -> "${candidate}usr_${uid.takeLast(4)}".take(18)
            }
        }

        val checkSnap = try {
            db.collection("usernames").document(baseUsername).get().await()
        } catch (_: Exception) {
            null
        }
        val finalUsername = if (checkSnap != null && checkSnap.exists() && checkSnap.getString("uid") != uid) {
            "${baseUsername.take(18)}_${uid.takeLast(4)}"
        } else {
            baseUsername
        }

        // 3. Save profile + mobile number + atomic username claim
        val profileRes = claimUsernameAndSaveProfile(
            uid = uid,
            rawUsername = finalUsername,
            displayName = cleanName,
            phoneNumber = cleanPhone,
            email = cleanEmail
        )
        return profileRes.fold(
            onSuccess = { savedProfile ->
                Result.success(
                    authUser.copy(displayName = savedProfile.displayName.ifBlank { cleanName })
                )
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    suspend fun signInEmailAccount(
        rawEmail: String,
        password: String
    ): Result<NexChatAuthUser> {
        val cleanEmail = rawEmail.trim().lowercase()
        if (!cleanEmail.contains("@") || cleanEmail.length < 3) {
            return Result.failure(IllegalArgumentException("Please enter a valid email ID."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 digits or characters."))
        }
        val uid = NexChatIds.emailAccountUid(cleanEmail)
        val passwordHash = NexChatIds.hashEmailPassword(cleanEmail, password)
        val docRef = db.collection("auth_accounts").document(uid)

        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                return Result.failure(
                    IllegalStateException("No account found for $cleanEmail. Tap \"Don't have an account? Sign Up\" to create your account.")
                )
            }
            val storedHash = existing.getString("passwordHash")
            if (storedHash != passwordHash) {
                return Result.failure(IllegalArgumentException("Incorrect email ID or password."))
            }
            val userDoc = try {
                db.collection("users").document(uid).get().await()
            } catch (_: Exception) {
                null
            }
            val resolvedName = userDoc?.getString("displayName")
                ?.takeIf { it.isNotBlank() }
                ?: cleanEmail.substringBefore("@")
            Result.success(
                NexChatAuthUser(
                    uid = uid,
                    email = cleanEmail,
                    displayName = resolvedName,
                    provider = "email"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun resetEmailPassword(
        rawEmail: String,
        newPassword: String
    ): Result<Unit> {
        val cleanEmail = rawEmail.trim().lowercase()
        if (!cleanEmail.contains("@") || cleanEmail.length < 3) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (newPassword.length < 6) {
            return Result.failure(IllegalArgumentException("New password must be at least 6 characters."))
        }
        val uid = NexChatIds.emailAccountUid(cleanEmail)
        val newHash = NexChatIds.hashEmailPassword(cleanEmail, newPassword)
        val docRef = db.collection("auth_accounts").document(uid)

        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                return Result.failure(IllegalStateException("No registered account found for $cleanEmail."))
            }
            docRef.update(
                mapOf(
                    "passwordHash" to newHash,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------------
    // 1. ATOMIC PROFILE & USERNAME CLAIM + PRIVATE PII + SESSIONS
    // ------------------------------------------------------------------------

    suspend fun isUsernameAvailable(rawUsername: String, currentUid: String? = null): Result<Boolean> {
        val uname = NexChatIds.normalizeUsername(rawUsername)
        if (uname.length !in 3..24) {
            return Result.failure(IllegalArgumentException("Username must be 3-24 lowercase letters, numbers, or underscores."))
        }
        val docRef = db.collection("usernames").document(uname)
        return try {
            val snap = docRef.get().await()
            val available = !snap.exists() || (currentUid != null && snap.getString("uid") == currentUid)
            Result.success(available)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Atomically claims the username in `/usernames/{username}` and creates/updates
     * `/users/{uid}` and `/users/{uid}/private/profile` inside a Firestore Transaction.
     */
    suspend fun claimUsernameAndSaveProfile(
        uid: String,
        rawUsername: String,
        displayName: String,
        phoneNumber: String,
        email: String,
        photoUrl: String = "",
        about: String = "Hey there! I am using NexChat Pro."
    ): Result<UserProfile> {
        val uname = NexChatIds.normalizeUsername(rawUsername)
        val cleanDisplayName = displayName.trim()
        val cleanPhone = phoneNumber.trim()

        if (uname.length !in 3..24 || !uname.matches(Regex("^[a-z0-9_]+$"))) {
            return Result.failure(IllegalArgumentException("Username must be 3–24 characters (a-z, 0-9, _)."))
        }
        if (cleanDisplayName.isEmpty() || cleanDisplayName.length > 60) {
            return Result.failure(IllegalArgumentException("Display name must be 1–60 characters."))
        }
        if (cleanPhone.length !in 5..24) {
            return Result.failure(IllegalArgumentException("Valid phone number (5–24 digits) is required."))
        }

        val userRef = db.collection("users").document(uid)
        val privateRef = userRef.collection("private").document("profile")
        val newUsernameRef = db.collection("usernames").document(uname)

        return try {
            db.runTransaction { tx ->
                val existingUserSnap = tx.get(userRef)
                val existingPrivateSnap = tx.get(privateRef)
                val usernameSnap = tx.get(newUsernameRef)

                if (usernameSnap.exists() && usernameSnap.getString("uid") != uid) {
                    throw IllegalStateException("Username @$uname is already taken.")
                }

                val previousUsername = existingUserSnap.getString("username")
                if (!previousUsername.isNullOrBlank() && previousUsername != uname) {
                    val oldUsernameRef = db.collection("usernames").document(previousUsername)
                    val oldSnap = tx.get(oldUsernameRef)
                    if (oldSnap.exists() && oldSnap.getString("uid") == uid) {
                        tx.delete(oldUsernameRef)
                    }
                }

                if (!usernameSnap.exists()) {
                    tx.set(
                        newUsernameRef,
                        mapOf(
                            "uid" to uid,
                            "username" to uname,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                val userCreatedAt = existingUserSnap.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
                val userPayload = mapOf(
                    "uid" to uid,
                    "username" to uname,
                    "usernameLower" to uname,
                    "displayName" to cleanDisplayName,
                    "displayNameLower" to cleanDisplayName.lowercase(),
                    "photoUrl" to photoUrl.trim(),
                    "about" to about.trim().take(200),
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to userCreatedAt,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.set(userRef, userPayload)

                val privateCreatedAt = existingPrivateSnap.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
                val existingTokens = (existingPrivateSnap.get("fcmTokens") as? List<*>)
                    ?.filterIsInstance<String>()
                    ?: emptyList()
                val privatePayload = mapOf(
                    "uid" to uid,
                    "email" to email.trim(),
                    "phoneNumber" to cleanPhone,
                    "phoneVerified" to false, // Explicitly unverified unless OTP flow is executed
                    "fcmTokens" to existingTokens,
                    "createdAt" to privateCreatedAt,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.set(privateRef, privatePayload)
            }.await()

            val saved = userRef.get().await()
                .toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserProfile(
                    id = uid,
                    uid = uid,
                    username = uname,
                    usernameLower = uname,
                    displayName = cleanDisplayName,
                    displayNameLower = cleanDisplayName.lowercase(),
                    photoUrl = photoUrl,
                    about = about,
                    online = true
                )
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(uid: String): Result<UserProfile?> {
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            val profile = snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getPrivateProfile(uid: String): Result<UserPrivateProfile?> {
        val docRef = db.collection("users").document(uid).collection("private").document("profile")
        return try {
            val snap = docRef.get().await()
            val profile = snap.toObject(UserPrivateProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> {
        val docRef = db.collection("users").document(uid)
        return docRef.snapshots()
            .map { snap ->
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                throw error
            }
    }

    suspend fun setUserOnlineStatus(uid: String, online: Boolean) {
        val docRef = db.collection("users").document(uid)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "online" to online,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun registerDeviceSession(
        uid: String,
        sessionId: String,
        deviceName: String,
        platform: String = "android"
    ): Result<Unit> {
        val sessionRef = db.collection("users").document(uid).collection("sessions").document(sessionId)
        return try {
            val existing = sessionRef.get().await()
            val createdAt = existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
            sessionRef.set(
                mapOf(
                    "sessionId" to sessionId,
                    "uid" to uid,
                    "deviceName" to deviceName.take(120),
                    "platform" to platform,
                    "createdAt" to createdAt,
                    "lastActiveAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, sessionRef.path)
            Result.failure(e)
        }
    }

    fun observeUserSessions(uid: String): Flow<List<UserSession>> {
        val path = "users/$uid/sessions"
        return db.collection("users").document(uid).collection("sessions")
            .whereEqualTo("uid", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(UserSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun removeDeviceSession(uid: String, sessionId: String) {
        val sessionRef = db.collection("users").document(uid).collection("sessions").document(sessionId)
        try {
            sessionRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, sessionRef.path)
        }
    }

    suspend fun deleteAccountData(uid: String, username: String): Result<Unit> {
        return try {
            val batch = db.batch()
            if (username.isNotBlank()) {
                batch.delete(db.collection("usernames").document(username))
            }
            batch.delete(db.collection("users").document(uid).collection("private").document("profile"))
            batch.delete(db.collection("users").document(uid))
            if (NexChatIds.isEmailAccountUid(uid)) {
                batch.delete(db.collection("auth_accounts").document(uid))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun searchUsers(query: String, currentUid: String): Result<List<UserProfile>> {
        val clean = query.trim().lowercase().removePrefix("@")
        val path = "users"
        return try {
            val snap = db.collection("users").limit(100).get().await()
            val allUsers = snap.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { it.uid.isNotBlank() && it.uid != currentUid }
            val filtered = if (clean.isBlank()) {
                allUsers
            } else {
                allUsers.filter {
                    it.usernameLower.contains(clean) || it.displayNameLower.contains(clean)
                }
            }
            Result.success(filtered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------------
    // 2. CONVERSATIONS (1-TO-1 & GROUPS)
    // ------------------------------------------------------------------------

    fun observeConversations(currentUid: String): Flow<List<Conversation>> {
        val path = "conversations"
        val baseQuery = db.collection("conversations")
            .whereArrayContains("memberUids", currentUid)
        val query = if (NexChatIds.isEmailAccountUid(currentUid)) baseQuery.limit(100) else baseQuery
        return query
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { conv ->
                        conv.lastMessageAt?.toDate()?.time ?: conv.lastMessageClientTimestamp
                    }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun getOrCreateDirectConversation(
        currentUser: UserProfile,
        peerUser: UserProfile
    ): Result<Conversation> {
        val convId = NexChatIds.directConversationId(currentUser.uid, peerUser.uid)
        val docRef = db.collection("conversations").document(convId)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val conv = existing.toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?: Conversation(id = convId, memberUids = listOf(currentUser.uid, peerUser.uid))
                return Result.success(conv)
            }

            val nowMillis = System.currentTimeMillis()
            val details = mapOf(
                currentUser.uid to mapOf(
                    "displayName" to currentUser.displayName,
                    "username" to currentUser.username,
                    "photoUrl" to currentUser.photoUrl
                ),
                peerUser.uid to mapOf(
                    "displayName" to peerUser.displayName,
                    "username" to peerUser.username,
                    "photoUrl" to peerUser.photoUrl
                )
            )
            val payload = mapOf(
                "id" to convId,
                "isGroup" to false,
                "memberUids" to listOf(currentUser.uid, peerUser.uid),
                "adminUids" to emptyList<String>(),
                "groupName" to "",
                "groupPhotoUrl" to "",
                "groupDescription" to "",
                "participantDetails" to details,
                "lastMessageId" to "",
                "lastMessageText" to "Say hello!",
                "lastMessageType" to "SYSTEM",
                "lastMessageSenderId" to currentUser.uid,
                "lastMessageClientTimestamp" to nowMillis,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "unreadCounts" to mapOf(currentUser.uid to 0L, peerUser.uid to 0L),
                "typingTimestamps" to emptyMap<String, Long>(),
                "archivedBy" to emptyList<String>(),
                "mutedBy" to emptyList<String>(),
                "chatThemes" to emptyMap<String, String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            val created = docRef.get().await()
                .toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: Conversation(
                    docId = convId,
                    id = convId,
                    isGroup = false,
                    memberUids = listOf(currentUser.uid, peerUser.uid),
                    participantDetails = details,
                    lastMessageText = "Say hello!",
                    lastMessageType = "SYSTEM",
                    lastMessageClientTimestamp = nowMillis
                )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createGroupConversation(
        creator: UserProfile,
        groupName: String,
        groupDescription: String,
        groupPhotoUrl: String,
        members: List<UserProfile>
    ): Result<Conversation> {
        val cleanName = groupName.trim()
        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("Group name cannot be empty."))
        }
        val allMembers = (members + creator).distinctBy { it.uid }
        val convId = NexChatIds.groupConversationId()
        val docRef = db.collection("conversations").document(convId)
        val nowMillis = System.currentTimeMillis()

        val details = allMembers.associate { member ->
            member.uid to mapOf(
                "displayName" to member.displayName,
                "username" to member.username,
                "photoUrl" to member.photoUrl
            )
        }
        val unreadMap = allMembers.associate { it.uid to 0L }

        val payload = mapOf(
            "id" to convId,
            "isGroup" to true,
            "memberUids" to allMembers.map { it.uid },
            "adminUids" to listOf(creator.uid),
            "groupName" to cleanName.take(80),
            "groupPhotoUrl" to groupPhotoUrl.trim(),
            "groupDescription" to groupDescription.trim().take(300),
            "participantDetails" to details,
            "lastMessageId" to "",
            "lastMessageText" to "${creator.displayName} created group \"$cleanName\"",
            "lastMessageType" to "SYSTEM",
            "lastMessageSenderId" to creator.uid,
            "lastMessageClientTimestamp" to nowMillis,
            "lastMessageAt" to FieldValue.serverTimestamp(),
            "unreadCounts" to unreadMap,
            "typingTimestamps" to emptyMap<String, Long>(),
            "archivedBy" to emptyList<String>(),
            "mutedBy" to emptyList<String>(),
            "chatThemes" to emptyMap<String, String>(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            val created = docRef.get().await()
                .toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: Conversation(
                    docId = convId,
                    id = convId,
                    isGroup = true,
                    memberUids = allMembers.map { it.uid },
                    adminUids = listOf(creator.uid),
                    groupName = cleanName,
                    groupDescription = groupDescription,
                    groupPhotoUrl = groupPhotoUrl,
                    participantDetails = details,
                    lastMessageText = "${creator.displayName} created group \"$cleanName\"",
                    lastMessageType = "SYSTEM",
                    lastMessageClientTimestamp = nowMillis
                )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateGroupProfile(
        conversationId: String,
        groupName: String,
        groupDescription: String,
        groupPhotoUrl: String
    ): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            docRef.update(
                mapOf(
                    "groupName" to groupName.trim().take(80),
                    "groupDescription" to groupDescription.trim().take(300),
                    "groupPhotoUrl" to groupPhotoUrl.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateGroupMembers(
        conversationId: String,
        updatedMemberUids: List<String>,
        updatedAdminUids: List<String>,
        updatedDetails: Map<String, Map<String, String>>
    ): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            docRef.update(
                mapOf(
                    "memberUids" to updatedMemberUids.distinct(),
                    "adminUids" to updatedAdminUids.distinct(),
                    "participantDetails" to updatedDetails,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun leaveGroup(conversationId: String, currentUid: String): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            val snap = docRef.get().await()
            val conv = snap.toObject(Conversation::class.java)
                ?: return Result.failure(IllegalStateException("Conversation not found"))
            val remainingMembers = conv.memberUids.filter { it != currentUid }
            val remainingAdmins = if (conv.adminUids.contains(currentUid)) {
                val others = conv.adminUids.filter { it != currentUid }
                if (others.isEmpty() && remainingMembers.isNotEmpty()) listOf(remainingMembers.first()) else others
            } else {
                conv.adminUids
            }
            docRef.update(
                mapOf(
                    "memberUids" to remainingMembers,
                    "adminUids" to remainingAdmins,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleArchiveConversation(conversationId: String, uid: String, archived: Boolean): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            val op = if (archived) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            docRef.update(
                mapOf(
                    "archivedBy" to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleMuteConversation(conversationId: String, uid: String, muted: Boolean): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            val op = if (muted) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            docRef.update(
                mapOf(
                    "mutedBy" to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun setConversationTheme(conversationId: String, uid: String, themeId: String): Result<Unit> {
        val docRef = db.collection("conversations").document(conversationId)
        return try {
            val snap = docRef.get().await()
            val currentThemes = (snap.get("chatThemes") as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }
                ?.toMap()
                .orEmpty()
            val updatedThemes = currentThemes + (uid to themeId)
            docRef.update(
                mapOf(
                    "chatThemes" to updatedThemes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateTypingStatus(conversationId: String, uid: String, isTyping: Boolean) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            val snap = docRef.get().await()
            if (!snap.exists()) return
            val existingMap = (snap.get("typingTimestamps") as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toLong()?.let { key to it } } }
                ?.toMap()
                .orEmpty()
            val nextMap = if (isTyping) {
                existingMap + (uid to System.currentTimeMillis())
            } else {
                existingMap - uid
            }
            docRef.update(
                mapOf(
                    "typingTimestamps" to nextMap,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Typing indicator is best-effort
        }
    }

    // ------------------------------------------------------------------------
    // 3. MESSAGES (IDEMPOTENT SEND, MONOTONIC RECEIPTS, PAGINATION)
    // ------------------------------------------------------------------------

    fun observeMessages(
        conversationId: String,
        currentUid: String,
        limitCount: Long = 60L
    ): Flow<List<ChatMessage>> {
        val path = "conversations/$conversationId/messages"
        return db.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .whereArrayContains("memberUids", currentUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { !it.deletedFor.contains(currentUid) }
                    .sortedBy { it.clientTimestamp }
                    .takeLast(limitCount.toInt())
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun loadOlderMessages(
        conversationId: String,
        currentUid: String,
        beforeClientTimestamp: Long,
        pageSize: Int = 30
    ): Result<List<ChatMessage>> {
        val path = "conversations/$conversationId/messages"
        return try {
            val snap = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .whereArrayContains("memberUids", currentUid)
                .get()
                .await()
            val older = snap.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { !it.deletedFor.contains(currentUid) && it.clientTimestamp < beforeClientTimestamp }
                .sortedBy { it.clientTimestamp }
                .takeLast(pageSize)
            Result.success(older)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    /**
     * Writes a message with a deterministic `messageId` via `.document(messageId).set(...)`
     * so retries are strictly idempotent and never produce duplicate messages.
     */
    suspend fun sendMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        senderName: String,
        memberUids: List<String>,
        type: String,
        text: String,
        mediaUrl: String = "",
        mediaMimeType: String = "",
        mediaFileName: String = "",
        mediaSizeBytes: Long = 0L,
        mediaDurationSec: Int = 0,
        replyToMessageId: String = "",
        replyToSenderName: String = "",
        replyToText: String = "",
        clientTimestamp: Long = System.currentTimeMillis()
    ): Result<ChatMessage> {
        if (mediaUrl.startsWith("data:", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Base64 data URLs are prohibited in Firestore messages."))
        }
        val convRef = db.collection("conversations").document(conversationId)
        val msgRef = convRef.collection("messages").document(messageId)

        val messagePayload = mapOf(
            "id" to messageId,
            "conversationId" to conversationId,
            "senderId" to senderId,
            "senderName" to senderName.take(60),
            "memberUids" to memberUids,
            "type" to type,
            "text" to text.take(4000),
            "mediaUrl" to mediaUrl,
            "mediaMimeType" to mediaMimeType,
            "mediaFileName" to mediaFileName,
            "mediaSizeBytes" to mediaSizeBytes,
            "mediaDurationSec" to mediaDurationSec,
            "replyToMessageId" to replyToMessageId,
            "replyToSenderName" to replyToSenderName.take(60),
            "replyToText" to replyToText.take(500),
            "status" to MessageStatus.SENT.name,
            "deliveredTo" to listOf(senderId),
            "readBy" to listOf(senderId),
            "deletedFor" to emptyList<String>(),
            "deletedForEveryone" to false,
            "clientTimestamp" to clientTimestamp,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            msgRef.set(messagePayload).await()

            val convSnap = convRef.get().await()
            val currentUnread = (convSnap.get("unreadCounts") as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toLong()?.let { key to it } } }
                ?.toMap()
                .orEmpty()

            val nextUnread = memberUids.associateWith { uid ->
                if (uid == senderId) 0L else (currentUnread[uid] ?: 0L) + 1L
            }

            val previewText = when (type) {
                "IMAGE" -> if (text.isNotBlank()) "📷 $text" else "📷 Photo"
                "VIDEO" -> if (text.isNotBlank()) "🎥 $text" else "🎥 Video"
                "VOICE" -> "🎤 Voice message (${mediaDurationSec}s)"
                "FILE" -> "📎 ${mediaFileName.ifBlank { "Document" }}"
                "CALL" -> text.ifBlank { "📞 Call" }
                else -> text
            }

            convRef.update(
                mapOf(
                    "lastMessageId" to messageId,
                    "lastMessageText" to previewText.take(1000),
                    "lastMessageType" to type,
                    "lastMessageSenderId" to senderId,
                    "lastMessageClientTimestamp" to clientTimestamp,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "unreadCounts" to nextUnread,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(
                ChatMessage(
                    docId = messageId,
                    id = messageId,
                    conversationId = conversationId,
                    senderId = senderId,
                    senderName = senderName,
                    memberUids = memberUids,
                    type = type,
                    text = text,
                    mediaUrl = mediaUrl,
                    mediaMimeType = mediaMimeType,
                    mediaFileName = mediaFileName,
                    mediaSizeBytes = mediaSizeBytes,
                    mediaDurationSec = mediaDurationSec,
                    replyToMessageId = replyToMessageId,
                    replyToSenderName = replyToSenderName,
                    replyToText = replyToText,
                    status = MessageStatus.SENT.name,
                    deliveredTo = listOf(senderId),
                    readBy = listOf(senderId),
                    clientTimestamp = clientTimestamp
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    /**
     * Marks incoming messages as DELIVERED monotonically and idempotently.
     */
    suspend fun markMessagesDelivered(
        conversationId: String,
        currentUid: String,
        messages: List<ChatMessage>
    ) {
        val pending = messages.filter { msg ->
            msg.senderId != currentUid &&
                !msg.deliveredTo.contains(currentUid) &&
                !deliveredAckCache.contains(msg.effectiveId())
        }
        if (pending.isEmpty()) return

        for (msg in pending) {
            val msgId = msg.effectiveId()
            deliveredAckCache.add(msgId)
            val msgRef = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(msgId)
            try {
                val nextStatus = if (MessageStatus.canTransition(msg.status, MessageStatus.DELIVERED)) {
                    MessageStatus.DELIVERED.name
                } else {
                    msg.status
                }
                val updatedDelivered = (msg.deliveredTo + currentUid).distinct()
                msgRef.update(
                    mapOf(
                        "status" to nextStatus,
                        "deliveredTo" to updatedDelivered
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                deliveredAckCache.remove(msgId)
            }
        }
    }

    /**
     * Marks incoming messages in an active conversation as READ monotonically and clears unread badge.
     */
    suspend fun markMessagesRead(
        conversationId: String,
        currentUid: String,
        messages: List<ChatMessage>
    ) {
        val unreadMessages = messages.filter { msg ->
            msg.senderId != currentUid &&
                !msg.readBy.contains(currentUid) &&
                !readAckCache.contains(msg.effectiveId())
        }

        for (msg in unreadMessages) {
            val msgId = msg.effectiveId()
            readAckCache.add(msgId)
            deliveredAckCache.add(msgId)
            val msgRef = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(msgId)
            try {
                val updatedDelivered = (msg.deliveredTo + currentUid).distinct()
                val updatedRead = (msg.readBy + currentUid).distinct()
                msgRef.update(
                    mapOf(
                        "status" to MessageStatus.READ.name,
                        "deliveredTo" to updatedDelivered,
                        "readBy" to updatedRead
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                readAckCache.remove(msgId)
            }
        }

        // Reset unread count for currentUid on the conversation document if non-zero
        val convRef = db.collection("conversations").document(conversationId)
        try {
            val snap = convRef.get().await()
            if (snap.exists()) {
                val currentUnread = (snap.get("unreadCounts") as? Map<*, *>)
                    ?.entries
                    ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toLong()?.let { key to it } } }
                    ?.toMap()
                    .orEmpty()
                if ((currentUnread[currentUid] ?: 0L) > 0L) {
                    convRef.update(
                        mapOf(
                            "unreadCounts" to (currentUnread + (currentUid to 0L)),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun deleteMessageForMe(
        conversationId: String,
        messageId: String,
        currentUid: String
    ): Result<Unit> {
        val msgRef = db.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .document(messageId)
        return try {
            val snap = msgRef.get().await()
            val msg = snap.toObject(ChatMessage::class.java)
                ?: return Result.failure(IllegalStateException("Message not found"))
            val updatedDeletedFor = (msg.deletedFor + currentUid).distinct()
            msgRef.update(
                mapOf("deletedFor" to updatedDeletedFor)
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForEveryone(
        conversationId: String,
        messageId: String,
        senderUid: String
    ): Result<Unit> {
        val msgRef = db.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .document(messageId)
        return try {
            val snap = msgRef.get().await()
            val msg = snap.toObject(ChatMessage::class.java)
                ?: return Result.failure(IllegalStateException("Message not found"))
            if (msg.senderId != senderUid) {
                return Result.failure(IllegalAccessException("Only the sender can delete a message for everyone."))
            }
            msgRef.update(
                mapOf(
                    "deletedForEveryone" to true,
                    "text" to "🚫 This message was deleted",
                    "mediaUrl" to "",
                    "mediaFileName" to "",
                    "mediaMimeType" to "",
                    "mediaSizeBytes" to 0L,
                    "mediaDurationSec" to 0
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------------
    // 4. 24-HOUR STATUS / STORIES
    // ------------------------------------------------------------------------

    fun observeStatuses(currentUid: String): Flow<List<StatusPost>> {
        val path = "statuses"
        val baseQuery = db.collection("statuses")
            .whereEqualTo("privacy", "ALL")
        val query = if (NexChatIds.isEmailAccountUid(currentUid)) baseQuery.limit(100) else baseQuery
        return query
            .snapshots()
            .map { snapshot ->
                val now = System.currentTimeMillis()
                snapshot.toObjects(StatusPost::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { !it.isExpired(now) }
                    .sortedByDescending { it.createdAtMillis }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun postStatus(
        author: UserProfile,
        type: String,
        text: String,
        backgroundColorHex: String = "#00A884",
        mediaUrl: String = "",
        privacy: String = "ALL",
        allowedUids: List<String> = emptyList(),
        excludedUids: List<String> = emptyList()
    ): Result<StatusPost> {
        if (mediaUrl.startsWith("data:", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Base64 data URLs are prohibited in statuses."))
        }
        val nowMillis = System.currentTimeMillis()
        val expiresAtMillis = nowMillis + 24L * 60L * 60L * 1000L // 24 hours
        val statusId = NexChatIds.statusId(nowMillis)
        val docRef = db.collection("statuses").document(statusId)

        val payload = mapOf(
            "id" to statusId,
            "authorId" to author.uid,
            "authorName" to author.displayName.take(60),
            "authorPhoto" to author.photoUrl,
            "type" to type,
            "text" to text.take(700),
            "backgroundColorHex" to backgroundColorHex,
            "mediaUrl" to mediaUrl,
            "privacy" to privacy,
            "allowedUids" to allowedUids,
            "excludedUids" to excludedUids,
            "viewerUids" to listOf(author.uid),
            "createdAtMillis" to nowMillis,
            "expiresAtMillis" to expiresAtMillis,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(
                StatusPost(
                    docId = statusId,
                    id = statusId,
                    authorId = author.uid,
                    authorName = author.displayName,
                    authorPhoto = author.photoUrl,
                    type = type,
                    text = text,
                    backgroundColorHex = backgroundColorHex,
                    mediaUrl = mediaUrl,
                    privacy = privacy,
                    allowedUids = allowedUids,
                    excludedUids = excludedUids,
                    viewerUids = listOf(author.uid),
                    createdAtMillis = nowMillis,
                    expiresAtMillis = expiresAtMillis
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun markStatusViewed(statusId: String, viewerUid: String) {
        val docRef = db.collection("statuses").document(statusId)
        try {
            val snap = docRef.get().await()
            val status = snap.toObject(StatusPost::class.java) ?: return
            if (!status.viewerUids.contains(viewerUid)) {
                docRef.update("viewerUids", (status.viewerUids + viewerUid).distinct()).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun deleteStatus(statusId: String): Result<Unit> {
        val docRef = db.collection("statuses").document(statusId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
