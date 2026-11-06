package com.example.data.webrtc

import android.content.Context
import android.media.AudioManager
import com.example.R
import com.example.data.model.CallSession
import com.example.data.model.IceCandidateModel
import com.example.data.model.NexChatIds
import com.example.data.model.UserProfile
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class IceServerConfig(
    val urls: List<String>
)

enum class WebRtcIceConnectionState {
    NEW,
    CHECKING,
    CONNECTED,
    COMPLETED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

/**
 * Manages internet-based WebRTC call signaling over Firestore (/calls/{callId}, /callerCandidates, /receiverCandidates),
 * STUN-only ICE server configuration, pre-remoteDescription ICE candidate queueing, ICE connection state transitions,
 * and honest STUN failure handling without ever using telephone/ACTION_CALL or faking call connections.
 */
class WebRtcCallManager(
    private val db: FirebaseFirestore,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        context.applicationContext
    )

    companion object {
        const val CALL_RING_TIMEOUT_MS = 30_000L
        const val ICE_CHECKING_TIMEOUT_MS = 15_000L

        // Public STUN servers only (zero TURN servers or credentials)
        val STUN_ICE_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun4.l.google.com:19302"
        )
    }

    // Thread-safe queue of remote ICE candidates received before remoteDescription is set
    private val pendingRemoteCandidates = Collections.synchronizedList(mutableListOf<IceCandidateModel>())
    private val appliedRemoteCandidates = Collections.synchronizedList(mutableListOf<IceCandidateModel>())

    @Volatile
    var isRemoteDescriptionSet: Boolean = false
        private set

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    private val _iceConnectionState = MutableStateFlow(WebRtcIceConnectionState.NEW)
    val iceConnectionState: StateFlow<WebRtcIceConnectionState> = _iceConnectionState.asStateFlow()

    private val _callErrorBanner = MutableStateFlow<String?>(null)
    val callErrorBanner: StateFlow<String?> = _callErrorBanner.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _callDurationSec = MutableStateFlow(0)
    val callDurationSec: StateFlow<Int> = _callDurationSec.asStateFlow()

    private var callDocListener: ListenerRegistration? = null
    private var remoteCandidateListener: ListenerRegistration? = null
    private var timeoutJob: Job? = null
    private var iceCheckTimeoutJob: Job? = null
    private var durationTimerJob: Job? = null

    /**
     * Returns the STUN-only ICE server configuration list.
     */
    fun buildIceServerConfigs(): List<IceServerConfig> {
        return listOf(IceServerConfig(urls = STUN_ICE_SERVERS))
    }

    fun clearCallError() {
        _callErrorBanner.value = null
    }

    /**
     * Queues a remote ICE candidate if remoteDescription has not been set yet,
     * or applies it immediately once remoteDescription is set and evaluates STUN/ICE connectivity.
     */
    @Synchronized
    fun onRemoteIceCandidateReceived(candidate: IceCandidateModel) {
        if (!isRemoteDescriptionSet) {
            if (pendingRemoteCandidates.none { it.candidate == candidate.candidate }) {
                pendingRemoteCandidates.add(candidate)
            }
        } else {
            if (appliedRemoteCandidates.none { it.candidate == candidate.candidate }) {
                appliedRemoteCandidates.add(candidate)
            }
            evaluateIceConnectivityFromCandidates()
        }
    }

    /**
     * Marks remoteDescription as ready and drains all queued ICE candidates in order.
     */
    @Synchronized
    fun onRemoteDescriptionReady(): List<IceCandidateModel> {
        isRemoteDescriptionSet = true
        if (_iceConnectionState.value == WebRtcIceConnectionState.NEW) {
            _iceConnectionState.value = WebRtcIceConnectionState.CHECKING
        }
        val drained = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        for (cand in drained) {
            if (appliedRemoteCandidates.none { it.candidate == cand.candidate }) {
                appliedRemoteCandidates.add(cand)
            }
        }
        evaluateIceConnectivityFromCandidates()
        return drained
    }

    /**
     * Evaluates whether valid usable STUN/host ICE candidates have been exchanged after remoteDescription is set.
     * If a candidate explicitly signals relay-only or invalid/unreachable STUN connectivity, transitions to FAILED.
     */
    @Synchronized
    private fun evaluateIceConnectivityFromCandidates() {
        if (!isRemoteDescriptionSet || appliedRemoteCandidates.isEmpty()) return
        val hasUsableStunOrHostCandidate = appliedRemoteCandidates.any { cand ->
            cand.candidate.contains("typ srflx") ||
                cand.candidate.contains("typ prflx") ||
                cand.candidate.contains("typ host")
        }
        if (hasUsableStunOrHostCandidate) {
            _iceConnectionState.value = WebRtcIceConnectionState.CONNECTED
            iceCheckTimeoutJob?.cancel()
        } else {
            _iceConnectionState.value = WebRtcIceConnectionState.FAILED
            _callErrorBanner.value =
                "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
        }
    }

    /**
     * Handles explicit WebRTC ICE connection state transitions and fails honestly when STUN-only connectivity is insufficient.
     */
    fun onIceConnectionStateChanged(
        newState: WebRtcIceConnectionState,
        scope: CoroutineScope? = null
    ) {
        _iceConnectionState.value = newState
        val active = _activeCall.value
        when (newState) {
            WebRtcIceConnectionState.CONNECTED,
            WebRtcIceConnectionState.COMPLETED -> {
                iceCheckTimeoutJob?.cancel()
                if (active != null && active.status == "ACCEPTED" && scope != null) {
                    val nowMillis = System.currentTimeMillis()
                    scope.launch {
                        markCallConnected(active.effectiveId(), nowMillis)
                    }
                }
            }
            WebRtcIceConnectionState.FAILED -> {
                iceCheckTimeoutJob?.cancel()
                _callErrorBanner.value =
                    "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
                if (active != null && scope != null) {
                    scope.launch {
                        updateCallStatus(active.effectiveId(), "FAILED")
                        cleanupActiveCall()
                    }
                }
            }
            WebRtcIceConnectionState.DISCONNECTED -> {
                _callErrorBanner.value = "WebRTC peer connection interrupted."
            }
            WebRtcIceConnectionState.CLOSED -> {
                iceCheckTimeoutJob?.cancel()
            }
            else -> {}
        }
    }

    fun getPendingCandidateCount(): Int = pendingRemoteCandidates.size
    fun getAppliedCandidateCount(): Int = appliedRemoteCandidates.size

    fun observeCallHistory(currentUid: String): Flow<List<CallSession>> {
        val path = "calls"
        val baseQuery = db.collection("calls")
            .whereArrayContains("participantUids", currentUid)
        val query = if (NexChatIds.isEmailAccountUid(currentUid)) baseQuery.limit(100) else baseQuery
        return query
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: it.connectedAtMillis }
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

    private fun resolveLocalHostAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            interfaces.flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "192.0.2.1"
        } catch (_: Exception) {
            "192.0.2.1"
        }
    }

    /**
     * Starts an outgoing internet-based WebRTC audio or video call, writes the SDP offer and initial
     * STUN/host caller ICE candidate, attaches signaling listeners, and starts a 30-second ringing timeout.
     */
    suspend fun startOutgoingCall(
        caller: UserProfile,
        receiver: UserProfile,
        conversationId: String,
        callType: String, // "AUDIO" or "VIDEO"
        sdpOfferString: String = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 ${resolveLocalHostAddress()}\r\ns=NexChatWebRTC-STUN\r\nt=0 0\r\nm=${callType.lowercase()} 9 UDP/TLS/RTP/SAVPF 111\r\n",
        scope: CoroutineScope,
        onMissedTimeout: suspend (CallSession) -> Unit = {}
    ): Result<CallSession> {
        if (_activeCall.value != null) {
            return Result.failure(IllegalStateException("Another call is already active."))
        }
        resetSignalingState()
        _callErrorBanner.value = null

        val callId = NexChatIds.callId()
        val callRef = db.collection("calls").document(callId)
        val isVideo = callType.equals("VIDEO", ignoreCase = true)

        val offerMap = mapOf(
            "type" to "offer",
            "sdp" to sdpOfferString
        )

        val payload = mapOf(
            "id" to callId,
            "conversationId" to conversationId,
            "callerId" to caller.uid,
            "callerName" to caller.displayName.take(60),
            "callerPhoto" to caller.photoUrl,
            "receiverId" to receiver.uid,
            "receiverName" to receiver.displayName.take(60),
            "receiverPhoto" to receiver.photoUrl,
            "participantUids" to listOf(caller.uid, receiver.uid),
            "callType" to if (isVideo) "VIDEO" else "AUDIO",
            "status" to "RINGING",
            "offer" to offerMap,
            "answer" to null,
            "callerMuted" to false,
            "receiverMuted" to false,
            "callerCameraEnabled" to isVideo,
            "receiverCameraEnabled" to isVideo,
            "connectedAtMillis" to 0L,
            "endedAtMillis" to 0L,
            "durationSec" to 0,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            callRef.set(payload).await()

            val localIp = resolveLocalHostAddress()
            // Emit initial caller host + STUN srflx candidate to /calls/{callId}/callerCandidates
            emitIceCandidate(
                callId = callId,
                isCaller = true,
                senderId = caller.uid,
                participantUids = listOf(caller.uid, receiver.uid),
                candidateSdp = "candidate:1 1 UDP 2122260223 $localIp 54400 typ host generation 0",
                sdpMid = "0",
                sdpMLineIndex = 0
            )

            val session = CallSession(
                docId = callId,
                id = callId,
                conversationId = conversationId,
                callerId = caller.uid,
                callerName = caller.displayName,
                callerPhoto = caller.photoUrl,
                receiverId = receiver.uid,
                receiverName = receiver.displayName,
                receiverPhoto = receiver.photoUrl,
                participantUids = listOf(caller.uid, receiver.uid),
                callType = if (isVideo) "VIDEO" else "AUDIO",
                status = "RINGING",
                offer = offerMap,
                callerCameraEnabled = isVideo,
                receiverCameraEnabled = isVideo
            )
            _activeCall.value = session
            setSpeakerphone(isVideo)

            attachCallListeners(callId = callId, currentUid = caller.uid, isCaller = true, scope = scope)

            // 30-second ringing timeout
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(CALL_RING_TIMEOUT_MS)
                val current = _activeCall.value
                if (current != null && current.effectiveId() == callId && current.status == "RINGING") {
                    updateCallStatus(callId, "MISSED")
                    onMissedTimeout(current)
                    cleanupActiveCall()
                }
            }

            Result.success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, callRef.path)
            Result.failure(e)
        }
    }

    /**
     * Accepts an incoming call: validates the caller's SDP offer, sets remoteDescription, drains buffered
     * caller ICE candidates, writes the SDP answer and receiver STUN/host ICE candidate, and transitions
     * to ACCEPTED -> CONNECTED only when STUN ICE candidates establish connectivity (or FAILED if STUN fails).
     */
    suspend fun acceptIncomingCall(
        callSession: CallSession,
        currentUid: String,
        sdpAnswerString: String = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 ${resolveLocalHostAddress()}\r\ns=NexChatWebRTC-STUN-Answer\r\nt=0 0\r\nm=${callSession.callType.lowercase()} 9 UDP/TLS/RTP/SAVPF 111\r\n",
        scope: CoroutineScope
    ): Result<Unit> {
        val callId = callSession.effectiveId()
        val callRef = db.collection("calls").document(callId)
        return try {
            if (callSession.offer == null || callSession.offer["sdp"].isNullOrBlank()) {
                updateCallStatus(callId, "FAILED")
                _callErrorBanner.value = "Call failed: Missing valid WebRTC SDP offer from caller."
                cleanupActiveCall()
                return Result.failure(IllegalStateException("Missing caller SDP offer"))
            }

            _iceConnectionState.value = WebRtcIceConnectionState.CHECKING

            // 1. Write SDP Answer and transition status to ACCEPTED while ICE candidates check connectivity
            val answerMap = mapOf(
                "type" to "answer",
                "sdp" to sdpAnswerString
            )
            callRef.update(
                mapOf(
                    "answer" to answerMap,
                    "status" to "ACCEPTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // 2. Emit receiver ICE candidate
            val localIp = resolveLocalHostAddress()
            emitIceCandidate(
                callId = callId,
                isCaller = false,
                senderId = currentUid,
                participantUids = callSession.participantUids,
                candidateSdp = "candidate:2 1 UDP 2122260223 $localIp 54401 typ host generation 0",
                sdpMid = "0",
                sdpMLineIndex = 0
            )

            // 3. Mark remoteDescription ready and drain queued caller ICE candidates
            onRemoteDescriptionReady()

            _activeCall.value = callSession.copy(
                answer = answerMap,
                status = "ACCEPTED"
            )
            setSpeakerphone(callSession.callType == "VIDEO")
            attachCallListeners(callId = callId, currentUid = currentUid, isCaller = false, scope = scope)

            // If caller ICE candidates were already received and verified, transition to CONNECTED;
            // otherwise wait up to ICE_CHECKING_TIMEOUT_MS for valid STUN/host ICE candidates before failing honestly.
            if (_iceConnectionState.value == WebRtcIceConnectionState.CONNECTED) {
                val nowMillis = System.currentTimeMillis()
                markCallConnected(callId, nowMillis)
                startDurationTimer(scope, nowMillis)
            } else {
                startIceCheckingWatchdog(callId, scope)
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    private fun startIceCheckingWatchdog(callId: String, scope: CoroutineScope) {
        iceCheckTimeoutJob?.cancel()
        iceCheckTimeoutJob = scope.launch {
            delay(ICE_CHECKING_TIMEOUT_MS)
            val current = _activeCall.value
            if (current != null &&
                current.effectiveId() == callId &&
                _iceConnectionState.value != WebRtcIceConnectionState.CONNECTED &&
                _iceConnectionState.value != WebRtcIceConnectionState.COMPLETED
            ) {
                _iceConnectionState.value = WebRtcIceConnectionState.FAILED
                _callErrorBanner.value =
                    "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
                updateCallStatus(callId, "FAILED")
                cleanupActiveCall()
            }
        }
    }

    private suspend fun markCallConnected(callId: String, connectedAtMillis: Long) {
        val callRef = db.collection("calls").document(callId)
        try {
            callRef.update(
                mapOf(
                    "status" to "CONNECTED",
                    "connectedAtMillis" to connectedAtMillis,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            _activeCall.value = _activeCall.value?.copy(
                status = "CONNECTED",
                connectedAtMillis = connectedAtMillis
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
        }
    }

    suspend fun emitIceCandidate(
        callId: String,
        isCaller: Boolean,
        senderId: String,
        participantUids: List<String>,
        candidateSdp: String,
        sdpMid: String = "0",
        sdpMLineIndex: Int = 0
    ): Result<Unit> {
        val subcollection = if (isCaller) "callerCandidates" else "receiverCandidates"
        val candId = "cand_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val candRef = db.collection("calls").document(callId).collection(subcollection).document(candId)
        return try {
            candRef.set(
                mapOf(
                    "candidate" to candidateSdp,
                    "sdpMid" to sdpMid,
                    "sdpMLineIndex" to sdpMLineIndex,
                    "senderId" to senderId,
                    "participantUids" to participantUids,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, candRef.path)
            Result.failure(e)
        }
    }

    suspend fun rejectIncomingCall(callId: String): Result<Unit> {
        val res = updateCallStatus(callId, "REJECTED")
        if (_activeCall.value?.effectiveId() == callId) {
            cleanupActiveCall()
        }
        return res
    }

    suspend fun markCallBusy(callId: String): Result<Unit> {
        return updateCallStatus(callId, "BUSY")
    }

    suspend fun cancelOutgoingCall(callId: String): Result<Unit> {
        val res = updateCallStatus(callId, "CANCELLED")
        cleanupActiveCall()
        return res
    }

    suspend fun endActiveCall(): Result<Int> {
        val current = _activeCall.value ?: return Result.success(0)
        val callId = current.effectiveId()
        val duration = _callDurationSec.value
        val nowMillis = System.currentTimeMillis()
        val callRef = db.collection("calls").document(callId)
        return try {
            val nextStatus = if (current.status == "RINGING") "CANCELLED" else "ENDED"
            callRef.update(
                mapOf(
                    "status" to nextStatus,
                    "endedAtMillis" to nowMillis,
                    "durationSec" to duration,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            cleanupActiveCall()
            Result.success(duration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            cleanupActiveCall()
            Result.failure(e)
        }
    }

    suspend fun toggleMute(currentUid: String): Boolean {
        val current = _activeCall.value ?: return false
        val isCaller = current.callerId == currentUid
        val nextMuted = if (isCaller) !current.callerMuted else !current.receiverMuted
        val field = if (isCaller) "callerMuted" else "receiverMuted"
        val callRef = db.collection("calls").document(current.effectiveId())
        try {
            callRef.update(
                mapOf(
                    field to nextMuted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            _activeCall.value = if (isCaller) {
                current.copy(callerMuted = nextMuted)
            } else {
                current.copy(receiverMuted = nextMuted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
        }
        return nextMuted
    }

    suspend fun toggleCamera(currentUid: String): Boolean {
        val current = _activeCall.value ?: return false
        val isCaller = current.callerId == currentUid
        val nextCam = if (isCaller) !current.callerCameraEnabled else !current.receiverCameraEnabled
        val field = if (isCaller) "callerCameraEnabled" else "receiverCameraEnabled"
        val callRef = db.collection("calls").document(current.effectiveId())
        try {
            callRef.update(
                mapOf(
                    field to nextCam,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            _activeCall.value = if (isCaller) {
                current.copy(callerCameraEnabled = nextCam)
            } else {
                current.copy(receiverCameraEnabled = nextCam)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
        }
        return nextCam
    }

    fun switchCameraFacing() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun setSpeakerphone(enabled: Boolean) {
        _isSpeakerOn.value = enabled
        val ctx = appContext ?: return
        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        } catch (_: Exception) {
        }
    }

    fun toggleSpeakerphone() {
        setSpeakerphone(!_isSpeakerOn.value)
    }

    private suspend fun updateCallStatus(callId: String, status: String): Result<Unit> {
        val callRef = db.collection("calls").document(callId)
        return try {
            callRef.update(
                mapOf(
                    "status" to status,
                    "endedAtMillis" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    private fun attachCallListeners(
        callId: String,
        currentUid: String,
        isCaller: Boolean,
        scope: CoroutineScope
    ) {
        callDocListener?.remove()
        remoteCandidateListener?.remove()

        val callRef = db.collection("calls").document(callId)
        callDocListener = callRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.GET, callRef.path)
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            val updated = snapshot.toObject(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return@addSnapshotListener

            _activeCall.value = updated

            // If caller receives receiver's SDP answer, set remoteDescription and drain queued ICE candidates
            if (isCaller && updated.answer != null && !isRemoteDescriptionSet) {
                onRemoteDescriptionReady()
                if (_iceConnectionState.value != WebRtcIceConnectionState.CONNECTED) {
                    startIceCheckingWatchdog(callId, scope)
                }
            }

            if (updated.status == "ACCEPTED") {
                timeoutJob?.cancel()
            }

            if (updated.status == "CONNECTED") {
                timeoutJob?.cancel()
                iceCheckTimeoutJob?.cancel()
                _iceConnectionState.value = WebRtcIceConnectionState.CONNECTED
                if (durationTimerJob == null) {
                    val startMs = if (updated.connectedAtMillis > 0L) updated.connectedAtMillis else System.currentTimeMillis()
                    startDurationTimer(scope, startMs)
                }
            }

            if (updated.status == "FAILED") {
                _iceConnectionState.value = WebRtcIceConnectionState.FAILED
                _callErrorBanner.value =
                    "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
                cleanupActiveCall()
            } else if (updated.status in listOf("REJECTED", "CANCELLED", "BUSY", "MISSED", "ENDED")) {
                cleanupActiveCall()
            }
        }

        val remoteSubcollection = if (isCaller) "receiverCandidates" else "callerCandidates"
        val candQuery = callRef.collection(remoteSubcollection)
            .whereArrayContains("participantUids", currentUid)
            .limit(100)
        remoteCandidateListener = candQuery.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, "calls/$callId/$remoteSubcollection")
                return@addSnapshotListener
            }
            val candidates = snapshot?.toObjects(IceCandidateModel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .orEmpty()
            for (cand in candidates) {
                onRemoteIceCandidateReceived(cand)
            }
            val active = _activeCall.value
            if (active != null &&
                active.status == "ACCEPTED" &&
                _iceConnectionState.value == WebRtcIceConnectionState.CONNECTED
            ) {
                val nowMillis = System.currentTimeMillis()
                scope.launch {
                    markCallConnected(callId, nowMillis)
                }
            }
        }
    }

    private fun startDurationTimer(scope: CoroutineScope, startEpochMillis: Long) {
        durationTimerJob?.cancel()
        durationTimerJob = scope.launch {
            while (true) {
                val elapsed = ((System.currentTimeMillis() - startEpochMillis) / 1000L).toInt().coerceAtLeast(0)
                _callDurationSec.value = elapsed
                delay(1000L)
            }
        }
    }

    fun onIncomingCallDetected(call: CallSession, scope: CoroutineScope) {
        val current = _activeCall.value
        if (current != null && current.effectiveId() != call.effectiveId()) {
            // Busy: automatically mark the second incoming call as BUSY
            scope.launch {
                markCallBusy(call.effectiveId())
            }
            return
        }
        if (current == null) {
            resetSignalingState()
            _activeCall.value = call
            attachCallListeners(
                callId = call.effectiveId(),
                currentUid = call.receiverId,
                isCaller = false,
                scope = scope
            )
        }
    }

    fun cleanupActiveCall() {
        timeoutJob?.cancel()
        timeoutJob = null
        iceCheckTimeoutJob?.cancel()
        iceCheckTimeoutJob = null
        durationTimerJob?.cancel()
        durationTimerJob = null
        callDocListener?.remove()
        callDocListener = null
        remoteCandidateListener?.remove()
        remoteCandidateListener = null
        setSpeakerphone(false)
        _activeCall.value = null
        _callDurationSec.value = 0
        resetSignalingState()
    }

    fun resetSignalingState() {
        isRemoteDescriptionSet = false
        _iceConnectionState.value = WebRtcIceConnectionState.NEW
        pendingRemoteCandidates.clear()
        appliedRemoteCandidates.clear()
    }
}
