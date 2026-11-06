# NexChat Pro — Complete Real-Time Cross-Platform Messaging & Calling Platform (Revised Architecture)

NexChat Pro is a production-oriented, real-time messaging and WebRTC audio/video communication platform comprising a native Jetpack Compose Android application and a responsive companion Web application backed by a single shared Firebase Authentication and Cloud Firestore backend. This revised architecture incorporates all mandatory corrections for authentication, zero-fallback media storage security, monotonic message consistency, unified WebRTC signaling, hardened security rules, and cross-platform schema parity.

### User Review & Critical Decisions (Revised)

> [!IMPORTANT]
> All 8 mandatory architectural corrections have been integrated into this revised plan. Review the updated specifications below and click **Proceed** when ready to begin implementation.

- **Correction 1 — Authentication & Atomic Username Uniqueness**:
  - **Primary Login**: Email and Password registration, login, and password reset (`sendPasswordResetEmail`), alongside **Optional Google Sign-In** (Jetpack Credential Manager on Android / GoogleAuthProvider on Web) and **Optional GitHub Sign-In** (`OAuthProvider("github.com")`).
  - *Firebase Console Requirement Note*: AI Studio's automated Firebase provisioning enables Google Sign-In by default; enabling Email/Password and GitHub OAuth requires toggling those providers on in the Firebase Console (`Authentication > Sign-in method`). If a provider is not yet enabled in the Firebase project, the client surfaces the exact Firebase configuration error rather than faking authentication.
  - **Phone Number Field**: Required as a profile contact field during onboarding/registration, stored explicitly with `phoneVerified: false`. The UI never claims the phone number is verified unless actual SMS OTP verification is completed.
  - **Atomic Username Claim**: Enforced via an atomic Firestore transaction (`runTransaction`) that reads `/usernames/{normalizedUsername}`, verifies it does not exist (or already belongs to `uid`), and atomically writes both `/usernames/{normalizedUsername}` and `/users/{uid}` in a single transaction, backed by Firestore security rules verifying `getAfter()`.
- **Correction 2 — Media Storage (No Base64 or Chunked Firestore Fallback)**:
  - **Zero Firestore Media Blobs**: Base64 encoding or chunked Firestore documents are **strictly prohibited** for images, videos, documents, or voice recordings. Firestore stores **only metadata and secure HTTPS media URLs**.
  - **Fail-Fast Configuration Check**: Media uploads use properly configured **Cloudinary** (or **Firebase Storage** when enabled). If storage configuration (`CLOUDINARY_CLOUD_NAME` and `CLOUDINARY_UPLOAD_PRESET`) is missing or invalid, attempting to send an image, video, file, voice recording, or media status immediately fails fast and displays a clear, actionable **"Upload Unavailable: Media storage is not configured"** error banner with retry/cancel options—never pretending the upload succeeded.
- **Correction 3 — Cloudinary Security Model**:
  - **No Client-Side API Secrets**: `CLOUDINARY_API_SECRET` is **never** placed in Android `BuildConfig`, `.env.example`, or Web client bundles because client binaries and web bundles can be inspected or decompiled.
  - **Restricted Unsigned Upload Preset or Signed Backend Endpoint**: Clients use either (a) a restricted unsigned upload preset (`CLOUDINARY_CLOUD_NAME` + `CLOUDINARY_UPLOAD_PRESET`) configured in the Cloudinary Console with strict file-type whitelist (`jpg,png,webp,mp4,webm,m4a,mp3,ogg,pdf`), maximum file size limits, and dedicated folder scoping (`nexchat_media/`), or (b) an optional backend signature endpoint (`CLOUDINARY_SIGN_URL`) that issues short-lived upload signatures server-side.
- **Correction 4 — Authoritative Message Consistency & Anti-Blink Engine**:
  - **Deterministic IDs**: 1-to-1 conversations always use `dm_${min(uidA, uidB)}_${max(uidA, uidB)}` across both Android and Web. Every message uses a deterministic client-generated UUID (`msg_${clientTimestamp}_${randomHex}`) written via `document(messageId).set(...)` so retries are idempotent and never create duplicate messages.
  - **Monotonic Status State Machine**: `SENDING` (client-only optimistic) $\rightarrow$ `SENT` (1 gray tick) $\rightarrow$ `DELIVERED` (2 gray ticks) $\rightarrow$ `READ` (2 blue ticks `#53BDEB`), or `FAILED` (client error with explicit Retry button). Status transitions are monotonic—a message already in `READ` can never be overwritten back to `DELIVERED` or `SENT`, and clients track in-flight delivery/read acknowledgments in an in-memory set to prevent duplicate Firestore writes.
- **Correction 5 — Unified WebRTC Signaling & ICE Queueing**:
  - **Identical Signaling Schema**: Both Android and Web use `/calls/{callId}` with structured `offer` and `answer` maps, plus dedicated `/calls/{callId}/callerCandidates/{id}` and `/calls/{callId}/receiverCandidates/{id}` subcollections.
  - **RemoteDescription Gate**: Incoming ICE candidates are buffered in a thread-safe queue until `peerConnection.remoteDescription != null` is confirmed, then drained sequentially.
  - **Real TURN Support & Honest Network Reporting**: Public STUN servers are configured alongside optional TURN credentials (`TURN_URLS`, `TURN_USERNAME`, `TURN_CREDENTIAL`). When TURN credentials are not supplied, the UI and documentation explicitly note that STUN-only peer connections may fail across symmetric NATs or restrictive mobile carrier firewalls.

---

### 1. Overview & Core Concept

- **What It Does**: Delivers real-time 1-to-1 and group chat, hold-to-record voice notes, image/video/file attachments, 24-hour expiring Status/Stories, and WebRTC peer-to-peer audio and video calling across native Android and responsive Web clients sharing a single Firebase backend.
- **Target Audience / Persona**: Users requiring a fast, transparent, cross-platform messaging system where every delivery tick, online status, media attachment, and call state reflects genuine server-verified state.
- **Key Value**: Eliminates UI flickering/disappearing chats via stable lifecycle-scoped listeners and optimistic reconciliation, and eliminates stuck WebRTC calls via deterministic SDP/ICE state sequencing.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Authentication, Onboarding & Session Isolation**:
     - Users register or sign in using **Email & Password** (primary), or optionally via **Google Sign-In** or **GitHub Sign-In**, with support for password reset (`sendPasswordResetEmail`) and full account deletion.
     - During registration or first sign-in, users complete their profile with a **unique `@username`** (validated and claimed atomically via Firestore transaction), **Display Name**, **Phone Number** (required contact field, explicitly labeled *Unverified* unless OTP is configured), and optional **Profile Photo**.
     - Each login registers a session document under `/users/{uid}/sessions/{sessionId}`. If a new device or browser signs in, existing sessions display a non-intrusive security alert banner showing the new device platform and timestamp.
     - Logging out or switching accounts immediately tears down all active Firestore snapshot listeners, clears in-memory ViewModel caches, clears Credential Manager state, and resets the navigation graph so zero messages or contacts from the previous user ever appear.
  2. **WhatsApp-Style Responsive Workspace**:
     - *Android & Mobile Web*: Clean tabbed workspace (**Chats**, **Groups**, **Status**, **Calls**, **Settings**) with unread count badges, pinned/archived chat sections, mute indicators, and real-time online/last-seen headers.
     - *Desktop Web & Android Tablet/Foldable*: Split-pane layout with a persistent left sidebar (`conversation list + search + filter pills`) and a right-hand active conversation panel.
  3. **Flicker-Free Messaging & Media Flow**:
     - Message list binds to a single lifecycle-aware Firestore listener for the current window (`limit(40)`) with upward scroll pagination (`startAfter`) for older history.
     - Sending a message renders immediately in the list (`SENDING`), reconciles seamlessly by matching `message.id` when the Firestore snapshot arrives (`SENT`), updates to `DELIVERED` when the recipient's client acknowledges receipt, and turns blue (`READ`) when the recipient views the conversation.
     - Hold-to-record voice messages capture AAC/Opus audio with live duration timer and release-to-send; if Cloudinary is configured, upload progress is shown inline before writing the message document; if unconfigured, a clear **Storage Not Configured** error state is shown with zero fake uploads.
  4. **WebRTC Audio & Video Calling**:
     - Outgoing and incoming call screens support **Accept**, **Reject**, **Cancel**, **End Call**, **Mic Mute/Unmute**, **Speaker Toggle**, **Camera On/Off**, and **Front/Rear Camera Switch**.
     - Enforces a 30-second ringing timeout (marking unanswered calls as `MISSED` and writing a call log entry), automatic `BUSY` rejection if the callee is already in an active call, and full `PeerConnection` + media track disposal on termination.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Emerald & Deep Slate modern messaging UI with high-contrast legibility and zero visual clutter.
  - *Color Palette*:
    - `--background`: `#0B141A` (Dark Slate) / `#F0F2F5` (Light Canvas)
    - `--surface`: `#111B21` (Dark Header/Card) / `#FFFFFF` (Light Surface)
    - `--primary`: `#00A884` (Emerald Action & Active States)
    - `--outgoing-bubble`: `#005C4B` (Dark) / `#D9FDD3` (Light)
    - `--incoming-bubble`: `#202C33` (Dark) / `#FFFFFF` (Light)
    - `--read-tick`: `#53BDEB` (Authoritative Read Receipt Blue)
    - `--error`: `#F15C6D` (Failed Send / Call Reject / Upload Error)
  - *Typography*: **Plus Jakarta Sans** for headings/navigation and **Inter** for chat bubbles, timestamps, and metadata.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Atomic Firestore Transaction for Username Uniqueness**
  - *Chosen Approach*: Claiming or changing a username executes `db.runTransaction { tx -> ... }` reading `/usernames/{usernameLower}` and `/users/{uid}`. If `/usernames/{usernameLower}` does not exist, the transaction writes `{ uid, username: usernameLower, updatedAt: serverTimestamp() }` and updates `/users/{uid}.username = usernameLower` atomically. Firestore security rules reinforce this using `getAfter(/databases/$(database)/documents/usernames/$(request.resource.data.username)).data.uid == request.auth.uid`.
  - *Trade-Off*: Requires online connectivity during initial username claim (since Firestore transactions cannot complete offline), which is appropriate and necessary to guarantee global uniqueness.

- **Decision 2: Strict External Media Storage (Cloudinary Unsigned Preset / Signed Endpoint) Without Firestore Blobs**
  - *Chosen Approach*: Media files (images, videos, documents, voice recordings, and media statuses) are uploaded via HTTPS `multipart/form-data` to Cloudinary (`https://api.cloudinary.com/v1_1/{cloudName}/{resourceType}/upload`) using an unsigned preset (`CLOUDINARY_UPLOAD_PRESET`) or a backend-signed payload, returning a `secure_url`, `public_id`, `bytes`, `format`, and `duration`. Firestore documents store only those metadata fields.
  - *Security Implications & Trade-Offs*:
    - Placing `CLOUDINARY_API_SECRET` in client code is insecure because APKs and JS bundles are public-facing artifacts.
    - Using a **Restricted Unsigned Upload Preset** in Cloudinary avoids exposing any secret in the client while allowing direct uploads, provided the preset in the Cloudinary dashboard is configured with: (1) **Allowed formats**: `jpg, png, webp, mp4, webm, m4a, mp3, ogg, pdf, txt, zip`, (2) **Max file size**: e.g. `25 MB`, (3) **Target folder**: `nexchat_pro/`, and (4) **Disallow public ID overwriting**.
    - For strict enterprise deployments requiring signed uploads, an optional `CLOUDINARY_SIGNING_ENDPOINT` (Cloud Function / backend URL) can be supplied so the client requests a short-lived signature with its Firebase ID token (`Authorization: Bearer <idToken>`) before uploading.
    - If neither is configured in `.env` / Web config, media uploads fail transparently with an explicit configuration notice rather than silently storing data in Firestore.

- **Decision 3: Single Authoritative Message Status Model & Anti-Blink State Pipeline**
  - *Chosen Approach*:
    - Conversation list listener is scoped once per authenticated `uid` Inside a repository-level shared flow (`whereArrayContains("memberUids", uid)`), sorted client-side by `lastMessageAt` to avoid composite index crashes or list clears during snapshot updates.
    - Active chat message listener subscribes only to `/conversations/{conversationId}/messages` ordered by `clientTimestamp DESC` with `limit(pageSize)`.
    - Optimistic messages live in a separate `pendingMessages` map keyed by `conversationId` and `messageId`. The UI merges `serverMessages + remainingPendingMessages` by `id` (where server state always wins once `id` appears in the snapshot) and keys every row by `message.id`.
  - *Trade-Off*: Client-side merge of optimistic and server snapshots adds a small pure-function combiner in the ViewModel, completely eliminating chat screen blinking, scroll jumping, and disappearing messages.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

#### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                SHARED FIREBASE PROJECT & NAMED FIRESTORE DATABASE                       │
│                                                                                         │
│  ┌────────────────────┐  ┌──────────────────────┐  ┌─────────────────────────────────┐  │
│  │ Firebase Auth      │  │ /users/{uid}         │  │ /conversations/{convId}         │  │
│  │ • Email/Password   │  │ • /sessions/{sessId} │  │ • /messages/{msgId}             │  │
│  │ • Google (Optional)│  │ /usernames/{uname}   │  │ /statuses/{statusId}            │  │
│  │ • GitHub (Optional)│  │ (Atomic Transaction) │  │ /calls/{callId}                 │  │
│  └─────────┬──────────┘  └──────────┬───────────┘  │ • /callerCandidates/{candId}    │  │
│            │                        │              │ • /receiverCandidates/{candId}  │  │
│            └────────────────────────┼──────────────┴─────────────────┬───────────────┘  │
│                                     ▼                                │                  │
│  ┌───────────────────────────────────────────────────────────────────┴───────────────┐  │
│  │                  Hardened Security Rules (firestore.rules)                        │  │
│  └──────────────────────────┬────────────────────────────────────────┬───────────────┘  │
└─────────────────────────────┼────────────────────────────────────────┼──────────────────┘
                              │                                        │
        ┌─────────────────────▼─────────────────────┐    ┌─────────────▼──────────────────┐
        │     NATIVE ANDROID CLIENT (Kotlin/M3)     │    │  RESPONSIVE WEB CLIENT (Web/JS)│
        │  ┌─────────────────────────────────────┐  │    │  ┌──────────────────────────┐  │
        │  │ AuthRepository & SessionTracker     │  │    │  │ Shared Firebase Config   │  │
        │  │ ChatRepository (Idempotent + Paged) │  │    │  │ Identical Collection/Doc │  │
        │  │ CallRepository & WebRtcCallEngine   │  │    │  │ Schema & Deterministic   │  │
        │  │ CloudinaryMediaService (No Secret)  │  │    │  │ Conversation/Message IDs │  │
        │  └──────────────────┬──────────────────┘  │    │  └─────────────┬────────────┘  │
        │                     ▼                     │    │                ▼               │
        │  ┌─────────────────────────────────────┐  │    │  ┌──────────────────────────┐  │
        │  │ ViewModels (Lifecycle-Scoped Flows, │  │    │  │ Split-Pane Desktop &     │  │
        │  │ Optimistic Reconciler, ICE Queue)   │  │    │  │ Mobile Responsive Views  │  │
        │  └─────────────────────────────────────┘  │    │  │ WebRTC + Voice Recorder  │  │
        └───────────────────────────────────────────┘    └────────────────────────────────┘
```

---

#### Canonical Cross-Platform Firestore Schema (Shared by Android & Website)

Both the Android app (`app/src/main/java/com/example/...`) and the companion Website (`web/`) MUST read and write these exact collection paths and field names:

1. **Users Collection — `/users/{uid}`**
   - `uid`: `String` (matches `request.auth.uid`)
   - `username`: `String` (lowercase alphanumeric + underscores, $3\text{–}24$ chars, claimed atomically with `/usernames/{username}`)
   - `displayName`: `String` ($1\text{–}60$ chars)
   - `SearchKeywords` / `usernameLower` / `displayNameLower`: `String` (for prefix search by username or display name)
   - `email`: `String`
   - `phoneNumber`: `String` (required profile contact field, e.g. `+15550192834`)
   - `phoneVerified`: `Boolean` (`false` unless verified via phone auth)
   - `photoUrl`: `String` (HTTPS URL or `""`)
   - `about`: `String` (status bio, default `"Hey there! I am using NexChat Pro."`)
   - `online`: `Boolean`
   - `lastSeen`: `Timestamp?` (`ServerTimestamp`)
   - `fcmTokens`: `List<String>` (device push tokens for current user)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)
   - `updatedAt`: `Timestamp?` (`ServerTimestamp`)

2. **Username Claims Collection — `/usernames/{username}`**
   - Document ID: lowercase `username`
   - `uid`: `String` (owner's `request.auth.uid`)
   - `username`: `String` (matches document ID)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)

3. **User Sessions Subcollection — `/users/{uid}/sessions/{sessionId}`**
   - `sessionId`: `String`
   - `uid`: `String`
   - `deviceName`: `String` (e.g., `"Android Pixel 8"` or `"Chrome on Linux"`)
   - `platform`: `String` (`"android"` | `"web"`)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)
   - `lastActiveAt`: `Timestamp?` (`ServerTimestamp`)

4. **Conversations Collection — `/conversations/{conversationId}`**
   - **Deterministic ID Rule**:
     - For 1-to-1 chats: `dm_${min(uid1, uid2)}_${max(uid1, uid2)}`
     - For Group chats: `grp_${timestampMillis}_${randomHex}`
   - `id`: `String` (matches `conversationId`)
   - `isGroup`: `Boolean`
   - `memberUids`: `List<String>` (all active member UIDs; used for security rule access checks)
   - `adminUids`: `List<String>` (empty for 1-to-1; contains creator and promoted admins for groups)
   - `groupName`: `String` (`""` for 1-to-1)
   - `groupPhotoUrl`: `String` (`""` for 1-to-1)
   - `groupDescription`: `String` (`""` for 1-to-1)
   - `participantDetails`: `Map<String, Map<String, String>>` (`uid -> { displayName, username, photoUrl }`)
   - `lastMessageId`: `String`
   - `lastMessageText`: `String`
   - `lastMessageType`: `String` (`"TEXT"` | `"IMAGE"` | `"VIDEO"` | `"FILE"` | `"VOICE"` | `"CALL"` | `"SYSTEM"`)
   - `lastMessageSenderId`: `String`
   - `lastMessageAt`: `Timestamp?` (`ServerTimestamp`)
   - `lastMessageClientTimestamp`: `Long` (epoch millis for stable fallback sorting)
   - `unreadCounts`: `Map<String, Long>` (`uid -> count`)
   - `typingTimestamps`: `Map<String, Long>` (`uid -> epochMillis`; considered typing if `now - ts < 5000`)
   - `archivedBy`: `List<String>` (UIDs that archived this chat)
   - `mutedBy`: `List<String>` (UIDs that muted notifications for this chat)
   - `chatThemes`: `Map<String, String>` (`uid -> themeId`, e.g., `"emerald"`, `"midnight"`, `"amber"`, `"rose"`, `"mono"`)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)
   - `updatedAt`: `Timestamp?` (`ServerTimestamp`)

5. **Messages Subcollection — `/conversations/{conversationId}/messages/{messageId}`**
   - **Deterministic ID Rule**: `msg_${clientTimestamp}_${random8Chars}` generated by sender before first send attempt.
   - `id`: `String` (matches `messageId`)
   - `conversationId`: `String`
   - `senderId`: `String`
   - `senderName`: `String`
   - `memberUids`: `List<String>` (denormalized copy of conversation's `memberUids` at send time for fast, zero-lookup security rule enforcement)
   - `type`: `String` (`"TEXT"` | `"IMAGE"` | `"VIDEO"` | `"FILE"` | `"VOICE"` | `"CALL"` | `"SYSTEM"`)
   - `text`: `String`
   - `mediaUrl`: `String` (HTTPS URL from Cloudinary/Firebase Storage, or `""` for text)
   - `mediaMimeType`: `String` (`""` if none)
   - `mediaFileName`: `String` (`""` if none)
   - `mediaSizeBytes`: `Long` (`0` if none)
   - `mediaDurationSec`: `Int` (`0` if none, used for voice/video)
   - `replyToMessageId`: `String` (`""` if not a reply)
   - `replyToSenderName`: `String` (`""` if not a reply)
   - `replyToText`: `String` (`""` if not a reply)
   - `status`: `String` (`"SENT"` | `"DELIVERED"` | `"READ"` in Firestore; `"SENDING"` and `"FAILED"` are local optimistic states prior to server commit)
   - `deliveredTo`: `List<String>` (UIDs that have received the message)
   - `readBy`: `List<String>` (UIDs that have opened and read the message)
   - `deletedFor`: `List<String>` (UIDs that hid this message via "Delete for Me")
   - `deletedForEveryone`: `Boolean` (if `true`, `text` is cleared to `"This message was deleted"` and `mediaUrl` is cleared to `""`)
   - `clientTimestamp`: `Long` (epoch millis when user tapped send)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)

6. **WebRTC Calls Collection — `/calls/{callId}`**
   - **Deterministic/Unique ID**: `call_${timestampMillis}_${random8Chars}`
   - `id`: `String`
   - `conversationId`: `String`
   - `callerId`: `String`
   - `callerName`: `String`
   - `callerPhoto`: `String`
   - `receiverId`: `String`
   - `receiverName`: `String`
   - `receiverPhoto`: `String`
   - `participantUids`: `List<String>` (`[callerId, receiverId]`)
   - `callType`: `String` (`"AUDIO"` | `"VIDEO"`)
   - `status`: `String` (`"RINGING"` | `"ACCEPTED"` | `"CONNECTED"` | `"REJECTED"` | `"CANCELLED"` | `"BUSY"` | `"MISSED"` | `"ENDED"` | `"FAILED"`)
   - `offer`: `Map<String, String>?` (`{ "type": "offer", "sdp": "..." }` or `null`)
   - `answer`: `Map<String, String>?` (`{ "type": "answer", "sdp": "..." }` or `null`)
   - `callerMuted`: `Boolean`
   - `receiverMuted`: `Boolean`
   - `callerCameraEnabled`: `Boolean`
   - `receiverCameraEnabled`: `Boolean`
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)
   - `connectedAtMillis`: `Long` (`0` until connected)
   - `endedAtMillis`: `Long` (`0` until ended)
   - `durationSec`: `Int` (`0` until ended)

7. **WebRTC ICE Candidates Subcollections**:
   - **Caller Candidates**: `/calls/{callId}/callerCandidates/{candidateId}` (written by `callerId`, observed by `receiverId`)
   - **Receiver Candidates**: `/calls/{callId}/receiverCandidates/{candidateId}` (written by `receiverId`, observed by `callerId`)
   - Document Fields for both subcollections:
     - `candidate`: `String` (SDP candidate string)
     - `sdpMid`: `String`
     - `sdpMLineIndex`: `Int`
     - `senderId`: `String`
     - `createdAt`: `Timestamp?` (`ServerTimestamp`)

8. **Statuses / Stories Collection — `/statuses/{statusId}`**
   - `id`: `String`
   - `authorId`: `String`
   - `authorName`: `String`
   - `authorPhoto`: `String`
   - `type`: `String` (`"TEXT"` | `"IMAGE"` | `"VIDEO"`)
   - `text`: `String`
   - `backgroundColorHex`: `String` (e.g., `"#00A884"`)
   - `mediaUrl`: `String` (HTTPS URL from Cloudinary/Storage or `""`)
   - `privacy`: `String` (`"ALL"` | `"ONLY_SHARE_WITH"` | `"EXCEPT"`)
   - `allowedUids`: `List<String>`
   - `excludedUids`: `List<String>`
   - `viewerUids`: `List<String>`
   - `createdAtMillis`: `Long`
   - `expiresAtMillis`: `Long` (`createdAtMillis + 86_400_000L` — 24 hours)
   - `createdAt`: `Timestamp?` (`ServerTimestamp`)

---

#### Complete Security Rules Specification (`firestore.rules` & `storage.rules`)

**1. Cloud Firestore Security Rules (`firestore.rules`)**:
- **Zero Public Access**: Every rule requires `request.auth != null`.
- **User Profiles (`/users/{userId}`)**:
  - `allow read`: Any authenticated user (required so users can search contacts by username/displayName, stripped of sensitive session data which lives in `/sessions`).
  - `allow create, update`: Only `request.auth.uid == userId`, validating schema fields (`displayName`, `username`, `phoneNumber`, `email`) and verifying that `/usernames/$(request.resource.data.username)` is owned by `request.auth.uid` via `getAfter()`.
  - `allow delete`: Only `request.auth.uid == userId`.
- **User Sessions (`/users/{userId}/sessions/{sessionId}`)**:
  - `allow read, write, delete`: Strictly `request.auth.uid == userId` (no other user can inspect device sessions).
- **Username Uniqueness Claims (`/usernames/{username}`)**:
  - `allow read`: Any authenticated user (to check availability).
  - `allow create`: `request.auth.uid == request.resource.data.uid` and document does not already exist.
  - `allow update`: `request.auth.uid == resource.data.uid && request.auth.uid == request.resource.data.uid`.
  - `allow delete`: `request.auth.uid == resource.data.uid`.
- **Conversations (`/conversations/{conversationId}`)**:
  - `allow read`: `request.auth.uid in resource.data.memberUids` (or on `get` of a non-existent deterministic `dm_uid1_uid2` doc where `request.auth.uid` is one of the two UIDs in the ID, allowing safe existence checks before creating a 1-to-1 chat).
  - `allow create`: `request.auth.uid in request.resource.data.memberUids` and valid schema.
  - `allow update`: `request.auth.uid in resource.data.memberUids`. If group administrative fields (`groupName`, `groupPhotoUrl`, `groupDescription`, `adminUids`) are modified, or if other members are added/removed, requires `request.auth.uid in resource.data.adminUids` (except a non-admin member removing only their own UID from `memberUids` when leaving a group).
- **Messages (`/conversations/{conversationId}/messages/{messageId}`)**:
  - `allow read`: `request.auth.uid in resource.data.memberUids`.
  - `allow create`: `request.auth.uid in request.resource.data.memberUids && request.resource.data.senderId == request.auth.uid` and verifies `request.auth.uid in get(/databases/$(database)/documents/conversations/$(conversationId)).data.memberUids`.
  - `allow update`: `request.auth.uid in resource.data.memberUids`. Only `senderId == request.auth.uid` can set `deletedForEveryone = true`; any member can update delivery/read receipts (`status`, `deliveredTo`, `readBy`) or append their own UID to `deletedFor`.
- **WebRTC Calls (`/calls/{callId}`)**:
  - `allow read`: `request.auth.uid == resource.data.callerId || request.auth.uid == resource.data.receiverId`.
  - `allow create`: `request.auth.uid == request.resource.data.callerId && request.resource.data.callerId != request.resource.data.receiverId && request.resource.data.participantUids.hasAll([request.resource.data.callerId, request.resource.data.receiverId])`.
  - `allow update`: `request.auth.uid == resource.data.callerId || request.auth.uid == resource.data.receiverId`, with immutable `callerId`, `receiverId`, and `callType`.
  - **Caller Candidates (`/calls/{callId}/callerCandidates/{candId}`)**:
    - `allow read`: Caller or Receiver of parent `/calls/{callId}`.
    - `allow create`: Only `callerId` of parent `/calls/{callId}` (`request.resource.data.senderId == request.auth.uid`).
  - **Receiver Candidates (`/calls/{callId}/receiverCandidates/{candId}`)**:
    - `allow read`: Caller or Receiver of parent `/calls/{callId}`.
    - `allow create`: Only `receiverId` of parent `/calls/{callId}` (`request.resource.data.senderId == request.auth.uid`).
- **Statuses (`/statuses/{statusId}`)**:
  - `allow read`: `request.auth != null` and (`resource.data.authorId == request.auth.uid` || `resource.data.privacy == 'ALL'` || (`resource.data.privacy == 'ONLY_SHARE_WITH' && request.auth.uid in resource.data.allowedUids`) || (`resource.data.privacy == 'EXCEPT' && !(request.auth.uid in resource.data.excludedUids)`)).
  - `allow create, delete`: `request.auth.uid == request.resource.data.authorId` (or `resource.data.authorId` on delete).
  - `allow update`: Author can update any field; permitted viewers can only append their own `request.auth.uid` to `viewerUids`.

**2. Firebase Storage Security Rules (`storage.rules`)**:
- Provided in the workspace for deployments using Firebase Storage alongside or instead of Cloudinary:
  - `/users/{userId}/profile/{fileName}`: `allow read: if request.auth != null; allow write: if request.auth != null && request.auth.uid == userId && request.resource.size < 10 * 1024 * 1024;`
  - `/conversations/{conversationId}/{senderId}/{fileName}`: `allow read: if request.auth != null && request.auth.uid in firestore.get(/databases/(default)/documents/conversations/$(conversationId)).data.memberUids; allow write: if request.auth != null && request.auth.uid == senderId && request.resource.size < 25 * 1024 * 1024;`
  - `/statuses/{authorId}/{fileName}`: `allow read: if request.auth != null; allow write: if request.auth != null && request.auth.uid == authorId && request.resource.size < 20 * 1024 * 1024;`

---

#### WebRTC End-to-End Call Signaling & State Machine

1. **Pre-Flight Checks**:
   - Verify network availability (`ConnectivityManager` on Android / `navigator.onLine` on Web) without false positives.
   - Verify permissions (`RECORD_AUDIO` for Audio; `RECORD_AUDIO` + `CAMERA` for Video). If Video permission is denied, abort and explain which permission is missing—never silently downgrade a video call to audio.
   - Check if a ` PeerConnection` already exists; if so, refuse duplicate creation.
2. **Outgoing Call (`Caller`)**:
   - Create `RTCPeerConnection` with configured STUN servers (`stun:stun.l.google.com:19302`, `stun:stun1.l.google.com:19302`) and optional TURN servers (`BuildConfig.TURN_SERVER_URL`, `BuildConfig.TURN_USERNAME`, `BuildConfig.TURN_CREDENTIAL`).
   - Attach local audio track (and video track if `VIDEO`).
   - Collect local ICE candidates (`onIceCandidate`) and write each to `/calls/{callId}/callerCandidates`.
   - Create SDP Offer, call `setLocalDescription(offer)`, and write `/calls/{callId}` with `status = "RINGING"` and `offer = { type: "offer", sdp: offer.sdp }`.
   - Start a 30-second ringing timeout job; if still `RINGING` after 30 seconds, update `/calls/{callId}` to `status = "MISSED"`, record a missed call message in the conversation, and tear down `PeerConnection`.
   - Listen to `/calls/{callId}`:
     - When `answer != null` and `peerConnection.remoteDescription == null`: call `setRemoteDescription(answer)`, set `isRemoteDescriptionSet = true`, and drain all buffered candidates from `pendingRemoteCandidates`.
     - When `status` becomes `REJECTED`, `BUSY`, `ENDED`, or `FAILED`: immediately clean up tracks and close `PeerConnection`.
   - Listen to `/calls/{callId}/receiverCandidates`:
     - If `isRemoteDescriptionSet == false`, push candidate into `pendingRemoteCandidates`.
     - If `isRemoteDescriptionSet == true`, call `peerConnection.addIceCandidate(candidate)` immediately.
3. **Incoming Call (`Receiver`)**:
   - Active listener on `/calls` where `receiverId == currentUid && status == "RINGING"` detects the incoming call.
   - If the receiver is already in another active call, automatically update the new call document to `status = "BUSY"` and ignore.
   - Otherwise, display Incoming Call UI (and high-priority Android call notification).
   - If Receiver taps **Reject**: update `/calls/{callId}` to `status = "REJECTED"`.
   - If Receiver taps **Accept**:
     - Request microphone (and camera if `VIDEO`) permissions.
     - Initialize `RTCPeerConnection`, attach local tracks, and start writing local ICE candidates to `/calls/{callId}/receiverCandidates`.
     - Read `callDoc.offer`, call `setRemoteDescription(offer)`, mark `isRemoteDescriptionSet = true`, and drain any buffered `callerCandidates`.
     - Create SDP Answer, call `setLocalDescription(answer)`, and update `/calls/{callId}` with `answer = { type: "answer", sdp: answer.sdp }` and `status = "ACCEPTED"`.
     - When `IceConnectionState` reaches `CONNECTED` or `COMPLETED`, update `status = "CONNECTED"` and start the call duration timer.

---

#### Mandatory Testing & Verification Strategy

We will execute automated multi-tier verification and report all results transparently in the Final Acceptance Report:

1. **Tier 1 — Firestore Security Rules Tests (`node --test firestore.test.js`)**:
   - Verify unauthenticated access is rejected across all collections.
   - Verify atomic username uniqueness and prevention of username hijacking.
   - Verify private 1-to-1 and group conversations cannot be read or written by non-members.
   - Verify non-admins cannot add/remove group members or edit group profile metadata.
   - Verify WebRTC call documents and ICE candidate subcollections are strictly restricted to `callerId` and `receiverId`.
   - Verify session documents under `/users/{uid}/sessions` are private to `{uid}`.
2. **Tier 2 — Android Repository & Anti-Flicker Robolectric Tests (`*RuleTest.kt` & Unit Tests)**:
   - **Deterministic Conversation ID Parity Test**: Verify Android and Web generate identical `dm_uid1_uid2` IDs regardless of who initiates the chat.
   - **Optimistic Reconciliation & Deduplication Test**: Verify sending a message with `msg_id` displays immediately as `SENDING`, transitions to `SENT` without duplication when the snapshot arrives, and transitions to `FAILED` with retry support if rejected.
   - **Monotonic Status Guard Test**: Verify a message in `READ` state never regresses to `DELIVERED` or `SENT`.
   - **No Chat Blinking / Listener Stability Test**: Verify opening/closing chats and receiving new messages updates state smoothly without clearing existing messages or recreating duplicate listeners.
   - **Account Switching Isolation Test**: Verify signing out of User A and signing into User B immediately clears all cached conversations, messages, and listeners from User A.
   - **WebRTC Signaling & ICE Queueing Test**: Verify incoming ICE candidates arriving before `remoteDescription` are queued in memory and applied only after `remoteDescription` is set, and verify timeout/reject/busy state transitions.
   - **Unconfigured Media Storage Fail-Fast Test**: Verify attempting to upload media without Cloudinary credentials returns a clear `StorageNotConfigured` error instead of faking an upload.
3. **Tier 3 — Build & Physical Device Honest Reporting**:
   - Execute `compile_applet` and Gradle build tasks to verify the native Android debug APK compiles cleanly.
   - Any test requiring two physical Android phones, real mobile carrier networks, or live FCM background push delivery on physical hardware will be explicitly reported as `NOT TESTED` with exact instructions for physical-device verification, in strict compliance with Section 14 and Section 17.
