# NexChat Pro — Setup, Security, & Deployment Guide

## 1. Shared Firebase Project & Database Configuration

Both the native Android application (`app/`) and the companion Website (`web/`) connect to the same provisioned Firebase backend:

- **Firebase Project ID**: `gen-lang-client-0253030619`
- **Named Firestore Database ID**: `ai-studio-android-nexchatp-cc276a5b-e441-4f19-876d-f228bb18ff9e`
- **Android Package Name**: `com.aistudio.nexchatpro.kxmzqp`
- **Security Rules Files**:
  - Cloud Firestore Rules: `/firestore.rules`
  - Firebase Storage Rules: `/storage.rules`
  - Schema Blueprint: `/firebase-blueprint.json`

### Enabling Optional Firebase Auth Providers in Console
AI Studio automatically provisions **Google Sign-In** for Android. To enable **Email/Password** and **GitHub OAuth** in the Firebase Console:
1. Open the [Firebase Console](https://console.firebase.google.com/project/gen-lang-client-0253030619/authentication/providers).
2. Under **Sign-in method**, enable **Email/Password**.
3. Optionally enable **GitHub** and supply your GitHub OAuth Client ID and Client Secret.

---

## 2. Environment Variables & Secrets (`.env.example`)

Configure these keys in the **Secrets panel in AI Studio** (never commit `.env` or expose private API secrets in client bundles):

| Variable | Value / Purpose | Security Requirement |
| :--- | :--- | :--- |
| `CLOUDINARY_CLOUD_NAME` | `f06cifgw` | Public Cloud Name identifier |
| `CLOUDINARY_SIGNING_ENDPOINT` | Backend signing service URL | Verifies Firebase Auth ID token and returns `{ signature, timestamp, apiKey, folder }` using the backend-only `CLOUDINARY_API_SECRET`. **Never** place `CLOUDINARY_API_SECRET` in Android or Web client code. |
| `CLOUDINARY_UPLOAD_PRESET` | *(Optional fallback)* Restricted upload preset | Only used if `CLOUDINARY_SIGNING_ENDPOINT` is not set |

### WebRTC STUN-Only Configuration
- Audio and video calls are 100% internet-based WebRTC calls (`ACTION_CALL` / carrier telephony is never used).
- Both Android and Web clients use public Google STUN servers (`stun:stun.l.google.com:19302` through `stun4.l.google.com:19302`). No TURN servers or TURN credentials are used.
- If STUN peer-to-peer connectivity cannot be established across symmetric NATs/firewalls, the call transitions honestly to `FAILED` and displays a clear connection error banner.

---

## 3. Deploying Firestore & Storage Security Rules

```bash
# Run local Tier-1 JavaScript security rules tests against the emulator:
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js

# Deploy Firestore security rules to the live database:
firebase deploy --only firestore:rules
```

---

## 4. Building & Installing the Native Android APK

```bash
# Build Debug APK:
gradle :app:assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

To install on a physical Android device via USB debugging:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Deploying the Companion Website (`web/`)

The companion Website in `/web` (`index.html`, `styles.css`, `app.js`) is a zero-build ES module web application using the official Firebase v11 modular SDK:

```bash
# Serve locally for testing:
npx serve web -l 3000

# Or deploy to Firebase Hosting / Cloud Run / static hosting:
firebase deploy --only hosting
```
