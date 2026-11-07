/**
 * NexChat Pro — Production Companion Web Client
 * Shares the exact same Firebase project, named Firestore database, collection paths,
 * deterministic conversation/message IDs, monotonic receipts, Cloudinary signed upload (f06cifgw),
 * and STUN-only WebRTC ICE candidate queueing + ICE state handling as the native Android application.
 */
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.1.0/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  GoogleAuthProvider,
  GithubAuthProvider,
  signInWithPopup,
  signOut
} from "https://www.gstatic.com/firebasejs/11.1.0/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  query,
  where,
  limit,
  getDocs,
  onSnapshot,
  runTransaction,
  serverTimestamp
} from "https://www.gstatic.com/firebasejs/11.1.0/firebase-firestore.js";

// Shared Firebase Project & Named Enterprise Firestore Database from google-services.json / firebase_applet_config.xml
const FIREBASE_CONFIG = window.NEXCHAT_FIREBASE_CONFIG || {
  apiKey: "AIzaSyCIDWInCJ481jaSgRWqb8xiERzq_hxOvMM",
  authDomain: "gen-lang-client-0253030619.firebaseapp.com",
  projectId: "gen-lang-client-0253030619",
  storageBucket: "gen-lang-client-0253030619.firebasestorage.app"
};
const FIRESTORE_DATABASE_ID =
  window.NEXCHAT_DATABASE_ID || "ai-studio-android-nexchatp-cc276a5b-e441-4f19-876d-f228bb18ff9e";

// Cloudinary signed upload config (Cloud Name: f06cifgw). API Secret stays strictly on backend signing service.
const RUNTIME_CONFIG = window.NEXCHAT_RUNTIME_CONFIG || {
  cloudinaryCloudName: "f06cifgw",
  cloudinarySigningEndpoint: "",
  cloudinaryUploadPreset: "ml_default"
};

// Public Google STUN servers only (no TURN servers or TURN credentials)
const STUN_ICE_SERVERS = [
  "stun:stun.l.google.com:19302",
  "stun:stun1.l.google.com:19302",
  "stun:stun2.l.google.com:19302",
  "stun:stun3.l.google.com:19302",
  "stun:stun4.l.google.com:19302"
];

const app = initializeApp(FIREBASE_CONFIG);
const auth = getAuth(app);
const db = getFirestore(app, FIRESTORE_DATABASE_ID);

// Deterministic ID & SHA-256 helpers (identical to NexChatIds in Android)
async function sha256Hex(input) {
  const bytes = new TextEncoder().encode(input);
  const hashBuffer = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
async function emailAccountUid(rawEmail) {
  const clean = (rawEmail || "").trim().toLowerCase();
  const hex = await sha256Hex(clean);
  return `usr_${hex.slice(0, 24)}`;
}
async function hashEmailPassword(rawEmail, password) {
  const clean = (rawEmail || "").trim().toLowerCase();
  return sha256Hex(`${clean}:nexchat_auth_v1:${password}`);
}
function directConversationId(uidA, uidB) {
  return uidA <= uidB ? `dm_${uidA}_${uidB}` : `dm_${uidB}_${uidA}`;
}
function generateId(prefix) {
  const rand = Math.random().toString(16).substring(2, 10);
  return `${prefix}_${Date.now()}_${rand}`;
}
function normalizeUsername(raw) {
  return (raw || "").trim().toLowerCase().replace(/[^a-z0-9_]/g, "");
}

// State
let currentUser = null;
let currentProfile = null;
let activeConversationId = null;
let conversationsMap = new Map();
let serverMessagesByConv = new Map();
let pendingMessagesByConv = new Map();
let deliveredAckSet = new Set();
let readAckSet = new Set();
let replyTarget = null;

// Active Listeners (properly cancelled on account switch or conversation switch)
let unsubConversations = null;
let unsubActiveMessages = null;
let unsubCalls = null;
let unsubCallDoc = null;
let unsubRemoteCandidates = null;

// WebRTC State
let peerConnection = null;
let localStream = null;
let activeCallSession = null;
let isRemoteDescriptionSet = false;
let pendingRemoteCandidates = [];
let callTimeoutTimer = null;
let iceCheckingTimer = null;

// Voice Recording State
let mediaRecorder = null;
let recordedChunks = [];
let recordStartMs = 0;

// DOM Elements
const authScreen = document.getElementById("authScreen");
const onboardingScreen = document.getElementById("onboardingScreen");
const appShell = document.getElementById("appShell");
const authError = document.getElementById("authError");
const onboardError = document.getElementById("onboardError");
const leftBannerError = document.getElementById("leftBannerError");
const conversationListEl = document.getElementById("conversationList");
const messagesContainerEl = document.getElementById("messagesContainer");
const chatHeaderEl = document.getElementById("chatHeader");
const composerBarEl = document.getElementById("composerBar");

function showError(el, msg) {
  el.textContent = msg;
  el.classList.remove("hidden");
}
function hideError(el) {
  el.textContent = "";
  el.classList.add("hidden");
}

function cleanupAllListenersAndState() {
  if (unsubConversations) { unsubConversations(); unsubConversations = null; }
  if (unsubActiveMessages) { unsubActiveMessages(); unsubActiveMessages = null; }
  if (unsubCalls) { unsubCalls(); unsubCalls = null; }
  teardownWebRtcCall();
  conversationsMap.clear();
  serverMessagesByConv.clear();
  pendingMessagesByConv.clear();
  deliveredAckSet.clear();
  readAckSet.clear();
  activeConversationId = null;
  currentProfile = null;
  conversationListEl.innerHTML = "";
  messagesContainerEl.innerHTML = "";
}

// 1. AUTHENTICATION FLOWS (Shared Email/Password Registry + Google/GitHub)
const EMAIL_SESSION_STORAGE_KEY = "nexchat_web_email_session";

function loadPersistedEmailUser() {
  try {
    const raw = localStorage.getItem(EMAIL_SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && parsed.uid && /^usr_[a-f0-9]{24}$/.test(parsed.uid)) {
      return parsed;
    }
  } catch (_) {}
  return null;
}

function savePersistedEmailUser(userObj) {
  try {
    localStorage.setItem(EMAIL_SESSION_STORAGE_KEY, JSON.stringify(userObj));
  } catch (_) {}
}

function clearPersistedEmailUser() {
  try {
    localStorage.removeItem(EMAIL_SESSION_STORAGE_KEY);
  } catch (_) {}
}

async function handleActiveAuthUser(user) {
  cleanupAllListenersAndState();
  currentUser = user;
  if (!user) {
    authScreen.classList.remove("hidden");
    onboardingScreen.classList.add("hidden");
    appShell.classList.add("hidden");
    return;
  }

  authScreen.classList.add("hidden");
  const userSnap = await getDoc(doc(db, "users", user.uid));
  const privSnap = await getDoc(doc(db, "users", user.uid, "private", "profile"));

  if (!userSnap.exists() || !userSnap.data().username || !privSnap.exists() || !privSnap.data().phoneNumber) {
    onboardingScreen.classList.remove("hidden");
    appShell.classList.add("hidden");
    document.getElementById("onboardDisplayName").value = user.displayName || "";
    document.getElementById("onboardUsername").value = normalizeUsername(user.displayName || user.email?.split("@")[0] || "");
  } else {
    currentProfile = userSnap.data();
    enterWorkspace();
  }
}

onAuthStateChanged(auth, async (fbUser) => {
  if (fbUser) {
    await handleActiveAuthUser(fbUser);
  } else {
    const emailUser = loadPersistedEmailUser();
    await handleActiveAuthUser(emailUser);
  }
});

let isSignUpMode = false;

function setAuthMode(signUp) {
  isSignUpMode = Boolean(signUp);
  window.__isSignUpMode = isSignUpMode;
  hideError(authError);

  const tabIn = document.getElementById("tabSignIn");
  const tabUp = document.getElementById("tabSignUp");
  const extraFields = document.getElementById("signUpExtraFields");
  const btnLogin = document.getElementById("btnEmailLogin");
  const btnRegister = document.getElementById("btnEmailRegister");
  const toggleBtn = document.getElementById("btnToggleAuthMode");

  if (isSignUpMode) {
    if (tabUp) {
      tabUp.classList.add("active");
    }
    if (tabIn) {
      tabIn.classList.remove("active");
    }
    if (extraFields) {
      extraFields.classList.remove("hidden");
      extraFields.style.display = "block";
    }
    if (btnLogin) {
      btnLogin.classList.add("hidden");
      btnLogin.style.display = "none";
    }
    if (btnRegister) {
      btnRegister.classList.remove("hidden");
      btnRegister.style.display = "block";
    }
    if (toggleBtn) {
      toggleBtn.textContent = "Already have an account? Sign In";
    }
  } else {
    if (tabIn) {
      tabIn.classList.add("active");
    }
    if (tabUp) {
      tabUp.classList.remove("active");
    }
    if (extraFields) {
      extraFields.classList.add("hidden");
      extraFields.style.display = "none";
    }
    if (btnLogin) {
      btnLogin.classList.remove("hidden");
      btnLogin.style.display = "block";
    }
    if (btnRegister) {
      btnRegister.classList.add("hidden");
      btnRegister.style.display = "none";
    }
    if (toggleBtn) {
      toggleBtn.textContent = "Don't have an account? Sign Up";
    }
  }
}

window.setAuthMode = setAuthMode;
window.toggleAuthMode = () => setAuthMode(!isSignUpMode);

function attachAuthToggleListeners() {
  const tabIn = document.getElementById("tabSignIn");
  const tabUp = document.getElementById("tabSignUp");
  const toggleBtn = document.getElementById("btnToggleAuthMode");

  if (tabIn) {
    tabIn.onclick = (e) => {
      if (e) e.preventDefault();
      setAuthMode(false);
    };
  }
  if (tabUp) {
    tabUp.onclick = (e) => {
      if (e) e.preventDefault();
      setAuthMode(true);
    };
  }
  if (toggleBtn) {
    toggleBtn.onclick = (e) => {
      if (e) e.preventDefault();
      setAuthMode(!isSignUpMode);
    };
  }
}
attachAuthToggleListeners();
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", attachAuthToggleListeners);
}

document.getElementById("authFullName").addEventListener("input", (e) => {
  const unameInput = document.getElementById("authUsername");
  if (!unameInput.dataset.customized) {
    unameInput.value = normalizeUsername(e.target.value).slice(0, 20);
  }
});
document.getElementById("authUsername").addEventListener("input", (e) => {
  e.target.dataset.customized = e.target.value.trim().length > 0 ? "1" : "";
});

document.getElementById("btnEmailLogin").addEventListener("click", async () => {
  hideError(authError);
  const cleanEmail = document.getElementById("authEmail").value.trim().toLowerCase();
  const pass = document.getElementById("authPassword").value;
  if (!cleanEmail.includes("@") || pass.length < 6) {
    showError(authError, "Enter a valid Email ID and a Password of at least 6 digits/characters.");
    return;
  }
  try {
    const uid = await emailAccountUid(cleanEmail);
    const passwordHash = await hashEmailPassword(cleanEmail, pass);
    const accountRef = doc(db, "auth_accounts", uid);
    const snap = await getDoc(accountRef);
    if (!snap.exists()) {
      showError(authError, `No account found for ${cleanEmail}. Click "Don't have an account? Sign Up" to register.`);
      return;
    }
    if (snap.data().passwordHash !== passwordHash) {
      showError(authError, "Incorrect email or password.");
      return;
    }
    const profileSnap = await getDoc(doc(db, "users", uid));
    const resolvedName = (profileSnap.exists() && profileSnap.data().displayName) || cleanEmail.split("@")[0];
    const emailUser = {
      uid,
      email: cleanEmail,
      displayName: resolvedName,
      photoURL: (profileSnap.exists() && profileSnap.data().avatarUrl) || ""
    };
    savePersistedEmailUser(emailUser);
    await handleActiveAuthUser(emailUser);
  } catch (e) {
    showError(authError, e.message);
  }
});

document.getElementById("btnEmailRegister").addEventListener("click", async () => {
  hideError(authError);
  const fullName = document.getElementById("authFullName").value.trim();
  const mobile = document.getElementById("authMobile").value.trim();
  const rawUsername = document.getElementById("authUsername").value.trim();
  const cleanEmail = document.getElementById("authEmail").value.trim().toLowerCase();
  const pass = document.getElementById("authPassword").value;

  if (fullName.length < 2) {
    showError(authError, "Please enter your full name (at least 2 characters).");
    return;
  }
  const phoneDigits = mobile.replace(/\D/g, "");
  if (phoneDigits.length < 7) {
    showError(authError, "Please enter a valid mobile number (at least 7 digits).");
    return;
  }
  if (!cleanEmail.includes("@") || !cleanEmail.includes(".")) {
    showError(authError, "Please enter a valid Email ID.");
    return;
  }
  if (pass.length < 6) {
    showError(authError, "Password must be at least 6 digits/characters.");
    return;
  }

  try {
    const uid = await emailAccountUid(cleanEmail);
    const passwordHash = await hashEmailPassword(cleanEmail, pass);
    const accountRef = doc(db, "auth_accounts", uid);
    const existing = await getDoc(accountRef);
    if (existing.exists()) {
      if (existing.data().passwordHash !== passwordHash) {
        showError(authError, `An account with ${cleanEmail} already exists. Please Sign In with your password.`);
        return;
      }
    } else {
      await setDoc(accountRef, {
        uid,
        email: cleanEmail,
        passwordHash,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    }

    const baseCandidate = normalizeUsername(rawUsername || fullName || cleanEmail.split("@")[0]).slice(0, 18);
    let candidateUsername = baseCandidate.length >= 3 ? baseCandidate : `user_${uid.slice(-6)}`;
    const existingUnameSnap = await getDoc(doc(db, "usernames", candidateUsername));
    if (existingUnameSnap.exists() && existingUnameSnap.data().uid !== uid) {
      candidateUsername = `${candidateUsername.slice(0, 17)}_${uid.slice(-4)}`;
    }

    await runTransaction(db, async (tx) => {
      const unameRef = doc(db, "usernames", candidateUsername);
      const userRef = doc(db, "users", uid);
      const privRef = doc(db, "users", uid, "private", "profile");

      const unameSnap = await tx.get(unameRef);
      if (unameSnap.exists() && unameSnap.data().uid !== uid) {
        throw new Error(`Username @${candidateUsername} is already taken.`);
      }
      const userSnap = await tx.get(userRef);
      const prevUsername = userSnap.exists() ? userSnap.data().username : "";
      if (prevUsername && prevUsername !== candidateUsername) {
        tx.delete(doc(db, "usernames", prevUsername));
      }
      tx.set(unameRef, {
        uid,
        username: candidateUsername,
        updatedAt: serverTimestamp()
      });
      tx.set(userRef, {
        uid,
        username: candidateUsername,
        displayName: fullName,
        about: "Hey there! I am using NexChat Pro.",
        avatarUrl: "",
        online: true,
        lastSeen: serverTimestamp(),
        createdAt: userSnap.exists() ? userSnap.data().createdAt : serverTimestamp(),
        updatedAt: serverTimestamp()
      });
      tx.set(privRef, {
        uid,
        email: cleanEmail,
        phoneNumber: mobile.startsWith("+") ? `+${phoneDigits}` : phoneDigits,
        phoneVerified: false,
        blockedUids: [],
        fcmTokens: [],
        updatedAt: serverTimestamp()
      });
    });

    const emailUser = {
      uid,
      email: cleanEmail,
      displayName: fullName,
      photoURL: ""
    };
    savePersistedEmailUser(emailUser);
    await handleActiveAuthUser(emailUser);
  } catch (e) {
    showError(authError, e.message);
  }
});

document.getElementById("btnGoogleLogin").addEventListener("click", async () => {
  hideError(authError);
  try {
    await signInWithPopup(auth, new GoogleAuthProvider());
  } catch (e) {
    showError(authError, e.message);
  }
});

document.getElementById("btnGithubLogin").addEventListener("click", async () => {
  hideError(authError);
  try {
    await signInWithPopup(auth, new GithubAuthProvider());
  } catch (e) {
    showError(authError, e.message);
  }
});

document.getElementById("btnResetPassword").addEventListener("click", async () => {
  hideError(authError);
  const cleanEmail = document.getElementById("authEmail").value.trim().toLowerCase();
  const newPass = document.getElementById("authPassword").value;
  if (!cleanEmail.includes("@") || newPass.length < 6) {
    showError(authError, "Enter your registered email and your new password (at least 6 characters) above first.");
    return;
  }
  try {
    const uid = await emailAccountUid(cleanEmail);
    const accountRef = doc(db, "auth_accounts", uid);
    const snap = await getDoc(accountRef);
    if (!snap.exists()) {
      showError(authError, `No registered account found for ${cleanEmail}.`);
      return;
    }
    const passwordHash = await hashEmailPassword(cleanEmail, newPass);
    await updateDoc(accountRef, {
      passwordHash,
      updatedAt: serverTimestamp()
    });
    alert(`Password updated for ${cleanEmail}. Click 'Sign In with Email' to continue.`);
  } catch (e) {
    showError(authError, e.message);
  }
});

// 2. ATOMIC USERNAME CLAIM & PROFILE ONBOARDING
document.getElementById("btnSaveOnboarding").addEventListener("click", async () => {
  hideError(onboardError);
  if (!currentUser) return;
  const uname = normalizeUsername(document.getElementById("onboardUsername").value);
  const displayName = document.getElementById("onboardDisplayName").value.trim();
  const phone = document.getElementById("onboardPhone").value.trim();
  const about = document.getElementById("onboardAbout").value.trim() || "Hey there! I am using NexChat Pro.";

  if (uname.length < 3 || uname.length > 24) {
    showError(onboardError, "Username must be 3–24 lowercase characters (a-z, 0-9, _).");
    return;
  }
  if (!displayName) {
    showError(onboardError, "Display name is required.");
    return;
  }
  if (phone.length < 5) {
    showError(onboardError, "A valid contact phone number is required.");
    return;
  }

  try {
    const userRef = doc(db, "users", currentUser.uid);
    const privRef = doc(db, "users", currentUser.uid, "private", "profile");
    const unameRef = doc(db, "usernames", uname);

    await runTransaction(db, async (tx) => {
      const unameSnap = await tx.get(unameRef);
      if (unameSnap.exists() && unameSnap.data().uid !== currentUser.uid) {
        throw new Error(`Username @${uname} is already taken.`);
      }
      if (!unameSnap.exists()) {
        tx.set(unameRef, {
          uid: currentUser.uid,
          username: uname,
          createdAt: serverTimestamp()
        });
      }
      tx.set(userRef, {
        uid: currentUser.uid,
        username: uname,
        usernameLower: uname,
        displayName,
        displayNameLower: displayName.toLowerCase(),
        photoUrl: currentUser.photoURL || "",
        about,
        online: true,
        lastSeen: serverTimestamp(),
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
      tx.set(privRef, {
        uid: currentUser.uid,
        email: currentUser.email || "",
        phoneNumber: phone,
        phoneVerified: false,
        fcmTokens: [],
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    });

    const saved = await getDoc(userRef);
    currentProfile = saved.data();
    enterWorkspace();
  } catch (e) {
    showError(onboardError, e.message);
  }
});

document.getElementById("btnOnboardLogout").addEventListener("click", async () => {
  clearPersistedEmailUser();
  await signOut(auth);
  await handleActiveAuthUser(null);
});
document.getElementById("btnLogout").addEventListener("click", async () => {
  if (currentUser) {
    try {
      await updateDoc(doc(db, "users", currentUser.uid), {
        online: false,
        lastSeen: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    } catch (_) {}
  }
  clearPersistedEmailUser();
  await signOut(auth);
  await handleActiveAuthUser(null);
});

// 3. REAL-TIME WORKSPACE & ANTI-BLINK MESSAGING
async function enterWorkspace() {
  onboardingScreen.classList.add("hidden");
  appShell.classList.remove("hidden");
  document.getElementById("currentUserBadge").textContent = `@${currentProfile.username} • Online`;

  if ("Notification" in window && Notification.permission === "default") {
    Notification.requestPermission().catch(() => {});
  }

  // Register Web Session
  const sessId = `web_${currentUser.uid.substring(0, 6)}`;
  try {
    await setDoc(doc(db, "users", currentUser.uid, "sessions", sessId), {
      sessionId: sessId,
      uid: currentUser.uid,
      deviceName: navigator.userAgent.slice(0, 80),
      platform: "web",
      createdAt: serverTimestamp(),
      lastActiveAt: serverTimestamp()
    });
  } catch (_) {}

  // Attach single persistent conversation list listener
  const convQuery = query(
    collection(db, "conversations"),
    where("memberUids", "array-contains", currentUser.uid),
    limit(100)
  );
  unsubConversations = onSnapshot(convQuery, (snapshot) => {
    snapshot.docs.forEach((docSnap) => {
      conversationsMap.set(docSnap.id, docSnap.data());
    });
    renderConversationList();
  });

  // Attach incoming call listener
  const callsQuery = query(
    collection(db, "calls"),
    where("participantUids", "array-contains", currentUser.uid),
    limit(100)
  );
  unsubCalls = onSnapshot(callsQuery, (snapshot) => {
    snapshot.docChanges().forEach((change) => {
      const data = change.doc.data();
      if (data.receiverId === currentUser.uid && data.status === "RINGING" && !activeCallSession) {
        onIncomingCall(data);
      }
    });
  });
}

function renderConversationList() {
  const sorted = Array.from(conversationsMap.values()).sort(
    (a, b) => (b.lastMessageClientTimestamp || 0) - (a.lastMessageClientTimestamp || 0)
  );

  // Preserve DOM nodes by stable conversation ID to prevent blinking
  sorted.forEach((conv) => {
    let row = document.getElementById(`conv_${conv.id}`);
    if (!row) {
      row = document.createElement("div");
      row.id = `conv_${conv.id}`;
      row.className = "conv-item";
      row.addEventListener("click", () => selectConversation(conv.id));
      conversationListEl.appendChild(row);
    }
    row.className = `conv-item ${conv.id === activeConversationId ? "active" : ""}`;
    const peerUid = (conv.memberUids || []).find((u) => u !== currentUser.uid) || currentUser.uid;
    const details = (conv.participantDetails || {})[peerUid] || {};
    const title = conv.isGroup ? conv.groupName : details.displayName || `@${details.username || "User"}`;
    const unread = ((conv.unreadCounts || {})[currentUser.uid]) || 0;

    row.innerHTML = `
      <div class="avatar">${(title[0] || "U").toUpperCase()}</div>
      <div class="conv-meta">
        <div class="conv-title-row">
          <span class="conv-title">${title}</span>
          ${unread > 0 ? `<span class="unread-badge">${unread}</span>` : ""}
        </div>
        <div class="conv-preview">${conv.lastMessageText || "No messages yet"}</div>
      </div>
    `;
  });
}

function selectConversation(convId) {
  if (activeConversationId === convId && unsubActiveMessages) return;
  if (unsubActiveMessages) {
    unsubActiveMessages();
    unsubActiveMessages = null;
  }
  activeConversationId = convId;
  appShell.classList.add("chat-open");
  chatHeaderEl.classList.remove("hidden");
  composerBarEl.classList.remove("hidden");
  renderConversationList();

  const conv = conversationsMap.get(convId);
  if (conv) {
    const peerUid = (conv.memberUids || []).find((u) => u !== currentUser.uid) || currentUser.uid;
    const details = (conv.participantDetails || {})[peerUid] || {};
    document.getElementById("activeChatTitle").textContent =
      conv.isGroup ? conv.groupName : details.displayName || `@${details.username || "Chat"}`;
    document.getElementById("activeChatSubtitle").textContent =
      conv.isGroup ? `${conv.memberUids.length} members` : `@${details.username || ""}`;
  }

  const msgQuery = query(
    collection(db, "conversations", convId, "messages"),
    where("memberUids", "array-contains", currentUser.uid)
  );
  unsubActiveMessages = onSnapshot(msgQuery, (snapshot) => {
    const msgs = snapshot.docs
      .map((d) => d.data())
      .filter((m) => !(m.deletedFor || []).includes(currentUser.uid))
      .sort((a, b) => a.clientTimestamp - b.clientTimestamp);

    serverMessagesByConv.set(convId, msgs);
    // Drop confirmed pending messages
    const serverIds = new Set(msgs.map((m) => m.id));
    const pending = (pendingMessagesByConv.get(convId) || []).filter(
      (p) => p.status === "FAILED" || !serverIds.has(p.id)
    );
    pendingMessagesByConv.set(convId, pending);

    renderActiveMessages(convId);
    acknowledgeReadMessages(convId, msgs);
  });
}

function renderActiveMessages(convId) {
  if (activeConversationId !== convId) return;
  const serverList = serverMessagesByConv.get(convId) || [];
  const pendingList = pendingMessagesByConv.get(convId) || [];
  const combined = [...serverList, ...pendingList].sort((a, b) => a.clientTimestamp - b.clientTimestamp);

  messagesContainerEl.innerHTML = "";
  combined.forEach((msg) => {
    const isMe = msg.senderId === currentUser.uid;
    const row = document.createElement("div");
    row.className = `msg-row ${isMe ? "outgoing" : "incoming"}`;

    const otherRead = (msg.readBy || []).some((u) => u !== msg.senderId) || msg.status === "READ";
    const otherDelivered = (msg.deliveredTo || []).some((u) => u !== msg.senderId) || msg.status === "DELIVERED";
    let tickHtml = "";
    if (isMe) {
      if (msg.status === "SENDING") tickHtml = "<span>⏳</span>";
      else if (msg.status === "FAILED") tickHtml = `<span style="color:var(--danger);">⚠️ Failed</span>`;
      else if (otherRead) tickHtml = `<span class="tick-read">✓✓</span>`;
      else if (otherDelivered) tickHtml = `<span>✓✓</span>`;
      else tickHtml = `<span>✓</span>`;
    }

    const timeStr = new Date(msg.clientTimestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    row.innerHTML = `
      <div class="msg-bubble">
        ${msg.mediaUrl ? `<div style="margin-bottom:6px;"><a href="${msg.mediaUrl}" target="_blank" rel="noopener noreferrer" style="color:var(--read-blue);">Open Attachment (${msg.type})</a></div>` : ""}
        <div>${msg.text}</div>
        <div class="msg-footer">
          <span>${timeStr}</span>
          ${tickHtml}
        </div>
      </div>
    `;
    messagesContainerEl.appendChild(row);
  });
  messagesContainerEl.scrollTop = messagesContainerEl.scrollHeight;
}

async function acknowledgeReadMessages(convId, messages) {
  for (const msg of messages) {
    if (msg.senderId !== currentUser.uid && !(msg.readBy || []).includes(currentUser.uid) && !readAckSet.has(msg.id)) {
      readAckSet.add(msg.id);
      try {
        await updateDoc(doc(db, "conversations", convId, "messages", msg.id), {
          status: "READ",
          deliveredTo: Array.from(new Set([...(msg.deliveredTo || []), currentUser.uid])),
          readBy: Array.from(new Set([...(msg.readBy || []), currentUser.uid]))
        });
      } catch (_) {
        readAckSet.delete(msg.id);
      }
    }
  }
}

// 4. CLOUDINARY SIGNED MEDIA UPLOAD (f06cifgw — NO BASE64 FALLBACK)
function isConfiguredSecret(val) {
  return Boolean(val && val.trim() && !val.startsWith("MY_") && !val.startsWith("YOUR_"));
}

async function uploadMediaViaCloudinary(fileOrBlob, fileName, mimeType, folder) {
  const cloudName = (RUNTIME_CONFIG.cloudinaryCloudName || "f06cifgw").trim();
  const signingEndpoint = (RUNTIME_CONFIG.cloudinarySigningEndpoint || "").trim();
  const uploadPreset = (RUNTIME_CONFIG.cloudinaryUploadPreset || "ml_default").trim();

  if (!isConfiguredSecret(signingEndpoint) && !isConfiguredSecret(uploadPreset)) {
    throw new Error(
      "Upload Unavailable — Storage Not Configured: Configure cloudinarySigningEndpoint for cloud f06cifgw."
    );
  }

  let resourceType = "raw";
  if (mimeType.startsWith("image/")) resourceType = "image";
  else if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) resourceType = "video";

  const formData = new FormData();
  formData.append("file", fileOrBlob, fileName);

  let targetCloud = cloudName;
  const isCustomSigning = isConfiguredSecret(signingEndpoint) && !signingEndpoint.includes("api.cloudinary.com");

  if (isCustomSigning) {
    const idToken = currentUser ? await currentUser.getIdToken() : "";
    const timestamp = Math.floor(Date.now() / 1000);
    const signResp = await fetch(signingEndpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(idToken ? { Authorization: `Bearer ${idToken}` } : {})
      },
      body: JSON.stringify({
        folder,
        timestamp,
        resource_type: resourceType,
        cloud_name: cloudName
      })
    });
    if (!signResp.ok) {
      throw new Error(`Signing service returned HTTP ${signResp.status}`);
    }
    const signData = await signResp.json();
    const apiKey = signData.apiKey || signData.api_key;
    if (!signData.signature || !apiKey) {
      throw new Error("Signing response missing signature or api_key");
    }
    targetCloud = signData.cloudName || signData.cloud_name || cloudName;
    formData.append("api_key", apiKey);
    formData.append("timestamp", String(signData.timestamp || timestamp));
    formData.append("signature", signData.signature);
    formData.append("folder", signData.folder || folder);
  } else {
    formData.append("folder", folder);
    formData.append("upload_preset", isConfiguredSecret(uploadPreset) ? uploadPreset : "ml_default");
  }

  let uploadResp = await fetch(`https://api.cloudinary.com/v1_1/${targetCloud}/${resourceType}/upload`, {
    method: "POST",
    body: formData
  });
  if (!uploadResp.ok && !isCustomSigning) {
    const fallbackType = resourceType === "video" ? "raw" : "auto";
    const fallbackData = new FormData();
    fallbackData.append("file", fileOrBlob, fileName);
    fallbackData.append("upload_preset", isConfiguredSecret(uploadPreset) ? uploadPreset : "ml_default");
    uploadResp = await fetch(`https://api.cloudinary.com/v1_1/${targetCloud}/${fallbackType}/upload`, {
      method: "POST",
      body: fallbackData
    });
  }
  if (!uploadResp.ok) {
    throw new Error(`Cloudinary upload failed with HTTP ${uploadResp.status}`);
  }
  const uploaded = await uploadResp.json();
  if (!uploaded.secure_url || !uploaded.secure_url.startsWith("https://")) {
    throw new Error("Cloudinary response missing https secure_url");
  }
  return uploaded;
}

// Optimistic Idempotent Send
document.getElementById("btnSendMessage").addEventListener("click", () => sendCurrentTextMessage());
document.getElementById("messageInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter") sendCurrentTextMessage();
});

document.getElementById("btnAttachFile").addEventListener("click", () => {
  document.getElementById("fileAttachmentInput").click();
});

document.getElementById("fileAttachmentInput").addEventListener("change", async (e) => {
  const file = e.target.files?.[0];
  e.target.value = "";
  if (!file || !activeConversationId || !currentProfile) return;
  hideError(leftBannerError);

  const mime = file.type || "application/octet-stream";
  const msgType = mime.startsWith("image/") ? "IMAGE" : mime.startsWith("video/") ? "VIDEO" : "FILE";
  try {
    const uploaded = await uploadMediaViaCloudinary(
      file,
      file.name,
      mime,
      `nexchat_pro/conversations/${activeConversationId}`
    );
    await sendStructuredMessage({
      type: msgType,
      text: file.name,
      mediaUrl: uploaded.secure_url,
      mediaMimeType: mime,
      mediaFileName: file.name,
      mediaSizeBytes: uploaded.bytes || file.size,
      mediaDurationSec: Math.round(uploaded.duration || 0)
    });
  } catch (err) {
    showError(leftBannerError, err.message);
  }
});

document.getElementById("btnRecordVoice").addEventListener("click", async () => {
  if (!activeConversationId || !currentProfile) return;
  hideError(leftBannerError);
  if (mediaRecorder && mediaRecorder.state === "recording") {
    mediaRecorder.stop();
    document.getElementById("btnRecordVoice").textContent = "🎤";
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    recordedChunks = [];
    recordStartMs = Date.now();
    mediaRecorder = new MediaRecorder(stream);
    mediaRecorder.ondataavailable = (ev) => {
      if (ev.data.size > 0) recordedChunks.push(ev.data);
    };
    mediaRecorder.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop());
      const durationSec = Math.max(1, Math.round((Date.now() - recordStartMs) / 1000));
      const blob = new Blob(recordedChunks, { type: "audio/webm" });
      try {
        const uploaded = await uploadMediaViaCloudinary(
          blob,
          `voice_${Date.now()}.webm`,
          "audio/webm",
          `nexchat_pro/conversations/${activeConversationId}`
        );
        await sendStructuredMessage({
          type: "VOICE",
          text: `Voice message (${durationSec}s)`,
          mediaUrl: uploaded.secure_url,
          mediaMimeType: "audio/webm",
          mediaFileName: `voice_${Date.now()}.webm`,
          mediaSizeBytes: uploaded.bytes || blob.size,
          mediaDurationSec: durationSec
        });
      } catch (err) {
        showError(leftBannerError, err.message);
      }
    };
    mediaRecorder.start();
    document.getElementById("btnRecordVoice").textContent = "⏹️";
  } catch (_) {
    showError(leftBannerError, "Microphone permission is required to record voice notes.");
  }
});

async function sendCurrentTextMessage() {
  const input = document.getElementById("messageInput");
  const text = input.value.trim();
  if (!text || !activeConversationId || !currentProfile) return;
  input.value = "";
  await sendStructuredMessage({ type: "TEXT", text });
}

async function sendStructuredMessage({
  type,
  text,
  mediaUrl = "",
  mediaMimeType = "",
  mediaFileName = "",
  mediaSizeBytes = 0,
  mediaDurationSec = 0
}) {
  const conv = conversationsMap.get(activeConversationId);
  if (!conv) return;

  const clientTimestamp = Date.now();
  const msgId = generateId("msg");
  const optimistic = {
    id: msgId,
    conversationId: activeConversationId,
    senderId: currentUser.uid,
    senderName: currentProfile.displayName,
    memberUids: conv.memberUids,
    type,
    text,
    mediaUrl,
    mediaMimeType,
    mediaFileName,
    mediaSizeBytes,
    mediaDurationSec,
    replyToMessageId: replyTarget?.id || "",
    replyToSenderName: replyTarget?.senderName || "",
    replyToText: replyTarget?.text || "",
    status: "SENDING",
    deliveredTo: [currentUser.uid],
    readBy: [currentUser.uid],
    deletedFor: [],
    deletedForEveryone: false,
    clientTimestamp
  };

  const pending = pendingMessagesByConv.get(activeConversationId) || [];
  pendingMessagesByConv.set(activeConversationId, [...pending, optimistic]);
  renderActiveMessages(activeConversationId);

  try {
    await setDoc(doc(db, "conversations", activeConversationId, "messages", msgId), {
      ...optimistic,
      status: "SENT",
      createdAt: serverTimestamp()
    });
    const nextUnread = {};
    (conv.memberUids || []).forEach((uid) => {
      nextUnread[uid] = uid === currentUser.uid ? 0 : (((conv.unreadCounts || {})[uid]) || 0) + 1;
    });
    await updateDoc(doc(db, "conversations", activeConversationId), {
      lastMessageId: msgId,
      lastMessageText: text,
      lastMessageType: type,
      lastMessageSenderId: currentUser.uid,
      lastMessageClientTimestamp: clientTimestamp,
      lastMessageAt: serverTimestamp(),
      unreadCounts: nextUnread,
      updatedAt: serverTimestamp()
    });
  } catch (e) {
    optimistic.status = "FAILED";
    renderActiveMessages(activeConversationId);
    showError(leftBannerError, `Failed to send message: ${e.message}`);
  }
}

// Find User & Start 1-to-1 Chat
document.getElementById("btnFindUser").addEventListener("click", async () => {
  hideError(leftBannerError);
  const q = normalizeUsername(document.getElementById("userSearchInput").value);
  if (!q) {
    showError(leftBannerError, "Enter a @username to start a conversation.");
    return;
  }
  const snap = await getDocs(
    query(collection(db, "users"), where("usernameLower", "==", q), limit(1))
  );
  if (snap.empty) {
    showError(leftBannerError, `No user found with username @${q}`);
    return;
  }
  const peer = snap.docs[0].data();
  if (peer.uid === currentUser.uid) return;

  const convId = directConversationId(currentUser.uid, peer.uid);
  const convRef = doc(db, "conversations", convId);
  const existing = await getDoc(convRef);
  if (!existing.exists()) {
    const now = Date.now();
    await setDoc(convRef, {
      id: convId,
      isGroup: false,
      memberUids: [currentUser.uid, peer.uid],
      adminUids: [],
      groupName: "",
      groupPhotoUrl: "",
      groupDescription: "",
      participantDetails: {
        [currentUser.uid]: {
          displayName: currentProfile.displayName,
          username: currentProfile.username,
          photoUrl: currentProfile.photoUrl || ""
        },
        [peer.uid]: {
          displayName: peer.displayName,
          username: peer.username,
          photoUrl: peer.photoUrl || ""
        }
      },
      lastMessageId: "",
      lastMessageText: "Say hello!",
      lastMessageType: "SYSTEM",
      lastMessageSenderId: currentUser.uid,
      lastMessageClientTimestamp: now,
      lastMessageAt: serverTimestamp(),
      unreadCounts: { [currentUser.uid]: 0, [peer.uid]: 0 },
      typingTimestamps: {},
      archivedBy: [],
      mutedBy: [],
      chatThemes: {},
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  }
  selectConversation(convId);
});

// 5. WEBRTC AUDIO & VIDEO CALLING (PUBLIC STUN ONLY + ICE CONNECTION STATE HANDLING)
function buildRtcConfig() {
  return {
    iceServers: [{ urls: STUN_ICE_SERVERS }]
  };
}

function attachIceStateHandler(pc, callId) {
  const callRef = doc(db, "calls", callId);
  pc.oniceconnectionstatechange = async () => {
    const state = pc.iceConnectionState;
    document.getElementById("callStatusLabel").textContent =
      `${activeCallSession?.status || "CALL"} • ICE: ${state.toUpperCase()}`;

    if (state === "connected" || state === "completed") {
      if (iceCheckingTimer) { clearTimeout(iceCheckingTimer); iceCheckingTimer = null; }
      if (activeCallSession && activeCallSession.status === "ACCEPTED") {
        await updateDoc(callRef, {
          status: "CONNECTED",
          connectedAtMillis: Date.now(),
          updatedAt: serverTimestamp()
        }).catch(() => {});
      }
    } else if (state === "failed") {
      if (iceCheckingTimer) { clearTimeout(iceCheckingTimer); iceCheckingTimer = null; }
      showError(
        leftBannerError,
        "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
      );
      await updateDoc(callRef, {
        status: "FAILED",
        endedAtMillis: Date.now(),
        updatedAt: serverTimestamp()
      }).catch(() => {});
      teardownWebRtcCall();
    }
  };
}

async function queueOrApplyRemoteCandidate(candData) {
  if (!isRemoteDescriptionSet || !peerConnection) {
    pendingRemoteCandidates.push(candData);
    return;
  }
  try {
    await peerConnection.addIceCandidate(
      new RTCIceCandidate({
        candidate: candData.candidate,
        sdpMid: candData.sdpMid,
        sdpMLineIndex: candData.sdpMLineIndex
      })
    );
  } catch (_) {}
}

async function drainPendingRemoteCandidates() {
  isRemoteDescriptionSet = true;
  const queued = [...pendingRemoteCandidates];
  pendingRemoteCandidates = [];
  for (const c of queued) {
    await queueOrApplyRemoteCandidate(c);
  }
}

document.getElementById("btnAudioCall").addEventListener("click", () => startWebRtcCall("AUDIO"));
document.getElementById("btnVideoCall").addEventListener("click", () => startWebRtcCall("VIDEO"));

async function startWebRtcCall(callType) {
  if (!activeConversationId || activeCallSession) return;
  const conv = conversationsMap.get(activeConversationId);
  if (!conv || conv.isGroup) return;
  const peerUid = conv.memberUids.find((u) => u !== currentUser.uid);
  const peerDetails = (conv.participantDetails || {})[peerUid] || {};

  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: callType === "VIDEO"
    });
  } catch (_) {
    showError(leftBannerError, `${callType} call requires microphone${callType === "VIDEO" ? " and camera" : ""} permissions.`);
    return;
  }

  document.getElementById("localVideo").srcObject = localStream;
  peerConnection = new RTCPeerConnection(buildRtcConfig());
  isRemoteDescriptionSet = false;
  pendingRemoteCandidates = [];

  const callId = generateId("call");
  const callRef = doc(db, "calls", callId);
  attachIceStateHandler(peerConnection, callId);

  localStream.getTracks().forEach((track) => peerConnection.addTrack(track, localStream));
  peerConnection.ontrack = (event) => {
    document.getElementById("remoteVideo").srcObject = event.streams[0];
  };

  peerConnection.onicecandidate = async (event) => {
    if (event.candidate) {
      const candId = generateId("cand");
      await setDoc(doc(db, "calls", callId, "callerCandidates", candId), {
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid || "0",
        sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
        senderId: currentUser.uid,
        participantUids: [currentUser.uid, peerUid],
        createdAt: serverTimestamp()
      });
    }
  };

  const offer = await peerConnection.createOffer();
  await peerConnection.setLocalDescription(offer);

  const sessionPayload = {
    id: callId,
    conversationId: activeConversationId,
    callerId: currentUser.uid,
    callerName: currentProfile.displayName,
    callerPhoto: currentProfile.photoUrl || "",
    receiverId: peerUid,
    receiverName: peerDetails.displayName || "User",
    receiverPhoto: peerDetails.photoUrl || "",
    participantUids: [currentUser.uid, peerUid],
    callType,
    status: "RINGING",
    offer: { type: "offer", sdp: offer.sdp },
    answer: null,
    callerMuted: false,
    receiverMuted: false,
    callerCameraEnabled: callType === "VIDEO",
    receiverCameraEnabled: callType === "VIDEO",
    connectedAtMillis: 0,
    endedAtMillis: 0,
    durationSec: 0,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };

  await setDoc(callRef, sessionPayload);
  activeCallSession = sessionPayload;
  showCallOverlay(sessionPayload, true);

  // 30-second ringing timeout
  callTimeoutTimer = setTimeout(async () => {
    if (activeCallSession && activeCallSession.status === "RINGING") {
      await updateDoc(callRef, { status: "MISSED", updatedAt: serverTimestamp() });
      teardownWebRtcCall();
    }
  }, 30000);

  unsubCallDoc = onSnapshot(callRef, async (snap) => {
    if (!snap.exists()) return;
    const data = snap.data();
    activeCallSession = data;
    document.getElementById("callStatusLabel").textContent = data.status;

    if (data.answer && !isRemoteDescriptionSet && peerConnection) {
      await peerConnection.setRemoteDescription(new RTCSessionDescription(data.answer));
      await drainPendingRemoteCandidates();
    }
    if (data.status === "FAILED") {
      showError(
        leftBannerError,
        "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
      );
      teardownWebRtcCall();
    } else if (["REJECTED", "CANCELLED", "BUSY", "MISSED", "ENDED"].includes(data.status)) {
      teardownWebRtcCall();
    }
  });

  const recCandQuery = query(
    collection(db, "calls", callId, "receiverCandidates"),
    where("participantUids", "array-contains", currentUser.uid),
    limit(100)
  );
  unsubRemoteCandidates = onSnapshot(recCandQuery, (snap) => {
    snap.docChanges().forEach((change) => {
      if (change.type === "added") {
        queueOrApplyRemoteCandidate(change.doc.data());
      }
    });
  });
}

function onIncomingCall(callData) {
  activeCallSession = callData;
  showCallOverlay(callData, false);

  const callRef = doc(db, "calls", callData.id);
  unsubCallDoc = onSnapshot(callRef, (snap) => {
    if (!snap.exists()) return;
    const data = snap.data();
    activeCallSession = data;
    document.getElementById("callStatusLabel").textContent = data.status;
    if (data.status === "FAILED") {
      showError(
        leftBannerError,
        "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
      );
      teardownWebRtcCall();
    } else if (["REJECTED", "CANCELLED", "BUSY", "MISSED", "ENDED"].includes(data.status)) {
      teardownWebRtcCall();
    }
  });
}

document.getElementById("btnAcceptCall").addEventListener("click", async () => {
  if (!activeCallSession) return;
  const callData = activeCallSession;
  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: callData.callType === "VIDEO"
    });
  } catch (_) {
    showError(
      leftBannerError,
      `Microphone${callData.callType === "VIDEO" ? " and Camera" : ""} permissions are required to accept the call.`
    );
    return;
  }

  document.getElementById("localVideo").srcObject = localStream;
  peerConnection = new RTCPeerConnection(buildRtcConfig());
  isRemoteDescriptionSet = false;
  pendingRemoteCandidates = [];
  attachIceStateHandler(peerConnection, callData.id);

  localStream.getTracks().forEach((track) => peerConnection.addTrack(track, localStream));
  peerConnection.ontrack = (event) => {
    document.getElementById("remoteVideo").srcObject = event.streams[0];
  };

  peerConnection.onicecandidate = async (event) => {
    if (event.candidate) {
      const candId = generateId("cand");
      await setDoc(doc(db, "calls", callData.id, "receiverCandidates", candId), {
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid || "0",
        sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
        senderId: currentUser.uid,
        participantUids: callData.participantUids,
        createdAt: serverTimestamp()
      });
    }
  };

  const callerCandQuery = query(
    collection(db, "calls", callData.id, "callerCandidates"),
    where("participantUids", "array-contains", currentUser.uid),
    limit(100)
  );
  unsubRemoteCandidates = onSnapshot(callerCandQuery, (snap) => {
    snap.docChanges().forEach((change) => {
      if (change.type === "added") {
        queueOrApplyRemoteCandidate(change.doc.data());
      }
    });
  });

  await peerConnection.setRemoteDescription(new RTCSessionDescription(callData.offer));
  await drainPendingRemoteCandidates();

  const answer = await peerConnection.createAnswer();
  await peerConnection.setLocalDescription(answer);

  // Transition to ACCEPTED while STUN ICE candidates negotiate direct connectivity
  await updateDoc(doc(db, "calls", callData.id), {
    answer: { type: "answer", sdp: answer.sdp },
    status: "ACCEPTED",
    updatedAt: serverTimestamp()
  });

  // 15-second STUN ICE connectivity watchdog
  iceCheckingTimer = setTimeout(async () => {
    if (
      peerConnection &&
      peerConnection.iceConnectionState !== "connected" &&
      peerConnection.iceConnectionState !== "completed"
    ) {
      showError(
        leftBannerError,
        "Call connection failed: Direct STUN peer-to-peer connectivity could not be established across the current networks."
      );
      await updateDoc(doc(db, "calls", callData.id), {
        status: "FAILED",
        endedAtMillis: Date.now(),
        updatedAt: serverTimestamp()
      }).catch(() => {});
      teardownWebRtcCall();
    }
  }, 15000);

  document.getElementById("btnAcceptCall").classList.add("hidden");
  document.getElementById("btnRejectCall").classList.add("hidden");
});

document.getElementById("btnRejectCall").addEventListener("click", async () => {
  if (!activeCallSession) return;
  await updateDoc(doc(db, "calls", activeCallSession.id), {
    status: "REJECTED",
    updatedAt: serverTimestamp()
  });
  teardownWebRtcCall();
});

document.getElementById("btnToggleMic").addEventListener("click", async () => {
  if (!localStream || !activeCallSession) return;
  const audioTrack = localStream.getAudioTracks()[0];
  if (!audioTrack) return;
  audioTrack.enabled = !audioTrack.enabled;
  const isCaller = activeCallSession.callerId === currentUser.uid;
  await updateDoc(doc(db, "calls", activeCallSession.id), {
    [isCaller ? "callerMuted" : "receiverMuted"]: !audioTrack.enabled,
    updatedAt: serverTimestamp()
  }).catch(() => {});
});

document.getElementById("btnToggleCam").addEventListener("click", async () => {
  if (!localStream || !activeCallSession) return;
  const videoTrack = localStream.getVideoTracks()[0];
  if (!videoTrack) return;
  videoTrack.enabled = !videoTrack.enabled;
  const isCaller = activeCallSession.callerId === currentUser.uid;
  await updateDoc(doc(db, "calls", activeCallSession.id), {
    [isCaller ? "callerCameraEnabled" : "receiverCameraEnabled"]: videoTrack.enabled,
    updatedAt: serverTimestamp()
  }).catch(() => {});
});

document.getElementById("btnHangupCall").addEventListener("click", async () => {
  if (!activeCallSession) return;
  const nextStatus = activeCallSession.status === "RINGING" ? "CANCELLED" : "ENDED";
  await updateDoc(doc(db, "calls", activeCallSession.id), {
    status: nextStatus,
    endedAtMillis: Date.now(),
    updatedAt: serverTimestamp()
  });
  teardownWebRtcCall();
});

function showCallOverlay(callData, isCaller) {
  document.getElementById("callOverlay").classList.remove("hidden");
  document.getElementById("callTypeBadge").textContent = `NexChat WebRTC ${callData.callType} • STUN Only`;
  document.getElementById("callPeerName").textContent = isCaller ? callData.receiverName : callData.callerName;
  document.getElementById("callStatusLabel").textContent = callData.status;
  if (!isCaller && callData.status === "RINGING") {
    document.getElementById("btnAcceptCall").classList.remove("hidden");
    document.getElementById("btnRejectCall").classList.remove("hidden");
  } else {
    document.getElementById("btnAcceptCall").classList.add("hidden");
    document.getElementById("btnRejectCall").classList.add("hidden");
  }
}

function teardownWebRtcCall() {
  if (callTimeoutTimer) { clearTimeout(callTimeoutTimer); callTimeoutTimer = null; }
  if (iceCheckingTimer) { clearTimeout(iceCheckingTimer); iceCheckingTimer = null; }
  if (unsubCallDoc) { unsubCallDoc(); unsubCallDoc = null; }
  if (unsubRemoteCandidates) { unsubRemoteCandidates(); unsubRemoteCandidates = null; }
  if (peerConnection) { peerConnection.close(); peerConnection = null; }
  if (localStream) { localStream.getTracks().forEach((t) => t.stop()); localStream = null; }
  activeCallSession = null;
  isRemoteDescriptionSet = false;
  pendingRemoteCandidates = [];
  document.getElementById("callOverlay").classList.add("hidden");
}

// Theme & Mobile Back Toggle
document.getElementById("btnToggleTheme").addEventListener("click", () => {
  document.body.classList.toggle("light-theme");
});
document.getElementById("btnMobileBack").addEventListener("click", () => {
  appShell.classList.remove("chat-open");
  activeConversationId = null;
});
