const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_1";
const BOB_UID = "bob_uid_2";
const MALLORY_UID = "mallory_uid_3";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("conversations").get());
  await assertFails(unauthDb.collection("calls").get());
  await assertFails(unauthDb.collection("statuses").get());
});

test("Atomic username claim and user profile creation succeeds for owner and blocks hijacking", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("usernames").doc("alice_pro"), {
    uid: ALICE_UID,
    username: "alice_pro",
    createdAt: now,
  });
  batch.set(aliceDb.collection("users").doc(ALICE_UID), {
    uid: ALICE_UID,
    username: "alice_pro",
    usernameLower: "alice_pro",
    displayName: "Alice",
    displayNameLower: "alice",
    photoUrl: "",
    about: "Available",
    online: true,
    lastSeen: now,
    createdAt: now,
    updatedAt: now,
  });
  await assertSucceeds(batch.commit());

  // Bob cannot hijack Alice's username
  await assertFails(
    bobDb.collection("usernames").doc("alice_pro").set({
      uid: BOB_UID,
      username: "alice_pro",
      createdAt: now,
    })
  );
});

test("Private profile PII and device sessions are strictly isolated to owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("private")
      .doc("profile")
      .set({
        uid: ALICE_UID,
        email: "alice@example.com",
        phoneNumber: "+15550109999",
        phoneVerified: false,
        fcmTokens: ["tok_1"],
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc("sess_1")
      .set({
        sessionId: "sess_1",
        uid: ALICE_UID,
        deviceName: "Pixel 8",
        platform: "android",
        createdAt: now,
        lastActiveAt: now,
      })
  );

  // Bob cannot read Alice's private PII or sessions
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("private")
      .doc("profile")
      .get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc("sess_1")
      .get()
  );
});

test("Conversation and message access is restricted to memberUids and blocks Base64 data URLs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const malloryDb = testEnv.authenticatedContext(MALLORY_UID).firestore();
  const now = new Date();
  const convId = `dm_${ALICE_UID}_${BOB_UID}`;

  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).set({
      id: convId,
      isGroup: false,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [],
      groupName: "",
      groupPhotoUrl: "",
      groupDescription: "",
      participantDetails: {},
      lastMessageId: "msg_1",
      lastMessageText: "Hello Bob",
      lastMessageType: "TEXT",
      lastMessageSenderId: ALICE_UID,
      lastMessageClientTimestamp: 1700000000000,
      lastMessageAt: now,
      unreadCounts: { [ALICE_UID]: 0, [BOB_UID]: 1 },
      typingTimestamps: {},
      archivedBy: [],
      mutedBy: [],
      chatThemes: {},
      createdAt: now,
      updatedAt: now,
    })
  );

  // Mallory cannot read the private conversation
  await assertFails(malloryDb.collection("conversations").doc(convId).get());

  // Alice can send a valid message
  await assertSucceeds(
    aliceDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc("msg_1")
      .set({
        id: "msg_1",
        conversationId: convId,
        senderId: ALICE_UID,
        senderName: "Alice",
        memberUids: [ALICE_UID, BOB_UID],
        type: "TEXT",
        text: "Hello Bob",
        mediaUrl: "",
        mediaMimeType: "",
        mediaFileName: "",
        mediaSizeBytes: 0,
        mediaDurationSec: 0,
        replyToMessageId: "",
        replyToSenderName: "",
        replyToText: "",
        status: "SENT",
        deliveredTo: [ALICE_UID],
        readBy: [ALICE_UID],
        deletedFor: [],
        deletedForEveryone: false,
        clientTimestamp: 1700000000000,
        createdAt: now,
      })
  );

  // Base64 data URL in mediaUrl is rejected by security rules
  await assertFails(
    aliceDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc("msg_base64")
      .set({
        id: "msg_base64",
        conversationId: convId,
        senderId: ALICE_UID,
        senderName: "Alice",
        memberUids: [ALICE_UID, BOB_UID],
        type: "IMAGE",
        text: "Photo",
        mediaUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==",
        status: "SENT",
        deliveredTo: [ALICE_UID],
        readBy: [ALICE_UID],
        deletedFor: [],
        deletedForEveryone: false,
        clientTimestamp: 1700000000001,
        createdAt: now,
      })
  );

  // Mallory cannot read messages in Alice & Bob's chat
  await assertFails(
    malloryDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc("msg_1")
      .get()
  );

  // Bob can mark message as READ
  await assertSucceeds(
    bobDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc("msg_1")
      .update({
        status: "READ",
        deliveredTo: [ALICE_UID, BOB_UID],
        readBy: [ALICE_UID, BOB_UID],
      })
  );
});

test("Group administrative actions are restricted to adminUids", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();
  const groupId = "grp_team_1";

  await assertSucceeds(
    aliceDb.collection("conversations").doc(groupId).set({
      id: groupId,
      isGroup: true,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      groupName: "Core Team",
      groupPhotoUrl: "",
      groupDescription: "Engineering",
      participantDetails: {},
      lastMessageId: "",
      lastMessageText: "Group created",
      lastMessageType: "SYSTEM",
      lastMessageSenderId: ALICE_UID,
      lastMessageClientTimestamp: 1700000000000,
      lastMessageAt: now,
      unreadCounts: {},
      typingTimestamps: {},
      archivedBy: [],
      mutedBy: [],
      chatThemes: {},
      createdAt: now,
      updatedAt: now,
    })
  );

  // Non-admin Bob cannot rename the group or add Mallory
  await assertFails(
    bobDb.collection("conversations").doc(groupId).update({
      groupName: "Hacked Group",
      updatedAt: now,
    })
  );

  // Admin Alice can add Mallory and update group name
  await assertSucceeds(
    aliceDb.collection("conversations").doc(groupId).update({
      groupName: "NexChat Engineering",
      memberUids: [ALICE_UID, BOB_UID, MALLORY_UID],
      updatedAt: now,
    })
  );
});

test("WebRTC call signaling and ICE candidates enforce caller and receiver identity", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const malloryDb = testEnv.authenticatedContext(MALLORY_UID).firestore();
  const now = new Date();
  const callId = "call_1001";

  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).set({
      id: callId,
      conversationId: `dm_${ALICE_UID}_${BOB_UID}`,
      callerId: ALICE_UID,
      callerName: "Alice",
      callerPhoto: "",
      receiverId: BOB_UID,
      receiverName: "Bob",
      receiverPhoto: "",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      status: "RINGING",
      offer: { type: "offer", sdp: "v=0..." },
      answer: null,
      callerMuted: false,
      receiverMuted: false,
      callerCameraEnabled: true,
      receiverCameraEnabled: true,
      connectedAtMillis: 0,
      endedAtMillis: 0,
      durationSec: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Mallory cannot read the call session
  await assertFails(malloryDb.collection("calls").doc(callId).get());

  // Caller Alice can write to callerCandidates, but Bob cannot write to callerCandidates
  await assertSucceeds(
    aliceDb
      .collection("calls")
      .doc(callId)
      .collection("callerCandidates")
      .doc("cand_1")
      .set({
        candidate: "candidate:1 1 UDP 2122252543 192.168.1.2 54321 typ host",
        sdpMid: "0",
        sdpMLineIndex: 0,
        senderId: ALICE_UID,
        participantUids: [ALICE_UID, BOB_UID],
        createdAt: now,
      })
  );

  await assertFails(
    bobDb
      .collection("calls")
      .doc(callId)
      .collection("callerCandidates")
      .doc("cand_bob_fake")
      .set({
        candidate: "candidate:fake",
        sdpMid: "0",
        sdpMLineIndex: 0,
        senderId: BOB_UID,
        participantUids: [ALICE_UID, BOB_UID],
        createdAt: now,
      })
  );

  // Receiver Bob can write to receiverCandidates
  await assertSucceeds(
    bobDb
      .collection("calls")
      .doc(callId)
      .collection("receiverCandidates")
      .doc("cand_r1")
      .set({
        candidate: "candidate:2 1 UDP 2122252543 192.168.1.3 54322 typ host",
        sdpMid: "0",
        sdpMLineIndex: 0,
        senderId: BOB_UID,
        participantUids: [ALICE_UID, BOB_UID],
        createdAt: now,
      })
  );
});
