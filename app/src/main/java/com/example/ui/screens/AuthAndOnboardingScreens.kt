package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.model.NexChatAuthUser
import com.example.data.model.NexChatIds
import com.example.data.repository.NexChatRepository
import com.example.ui.theme.NexEmeraldDark
import com.example.ui.theme.NexEmeraldPrimary
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages persisted cross-platform Email/Password account sessions alongside Firebase Auth Google Sign-In.
 */
object NexChatAuthSessionManager {
    private const val PREFS_NAME = "nexchat_email_auth_prefs"
    private const val KEY_UID = "email_uid"
    private const val KEY_EMAIL = "email_address"
    private const val KEY_DISPLAY_NAME = "email_display_name"

    private val _emailSession = MutableStateFlow<NexChatAuthUser?>(null)
    val emailSession: StateFlow<NexChatAuthUser?> = _emailSession.asStateFlow()

    fun restoreSession(context: Context): NexChatAuthUser? {
        val current = _emailSession.value
        if (current != null) return current
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uid = prefs.getString(KEY_UID, null)
            val email = prefs.getString(KEY_EMAIL, null)
            val displayName = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
            if (!uid.isNullOrBlank() && !email.isNullOrBlank() && NexChatIds.isEmailAccountUid(uid)) {
                val restored = NexChatAuthUser(
                    uid = uid,
                    email = email,
                    displayName = displayName.ifBlank { email.substringBefore("@") },
                    provider = "email"
                )
                _emailSession.value = restored
                restored
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveEmailSession(context: Context, user: NexChatAuthUser) {
        _emailSession.value = user
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_UID, user.uid)
                .putString(KEY_EMAIL, user.email)
                .putString(KEY_DISPLAY_NAME, user.displayName)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearSession(context: Context) {
        _emailSession.value = null
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Exception) {
        }
    }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null || NexChatAuthSessionManager.restoreSession(context) != null) {
        onAuthSuccess()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onUnauthenticated()
        return
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (_: Exception) {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(context as Activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun performFullSignOut(
    context: Context,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit
) {
    val credentialManager = CredentialManager.create(context)
    NexChatAuthSessionManager.clearSession(context)
    Firebase.auth.signOut()
    scope.launch {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.e("Auth", "Failed to clear credential state", e)
        } finally {
            onSignOutComplete()
        }
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val repository = remember(context) { NexChatRepository(context) }
    val scrollState = rememberScrollState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Sign In, 1 = Sign Up
    var fullName by remember { mutableStateOf("") }
    var mobileNumber by remember { mutableStateOf("") }
    var customUsername by remember { mutableStateOf("") }
    var usernameManuallyEdited by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF071E19),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(16.dp)
                .verticalScroll(scrollState),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        color = NexEmeraldPrimary.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.img_launcher_logo_1793941827147),
                                contentDescription = "NexChat Pro Logo",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "NexChat Pro",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (selectedTab == 0) "Sign in to your account" else "Create a new account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Top Mode Switcher: Sign In vs Sign Up
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            errorMessage = null
                            infoMessage = null
                        },
                        text = { Text("Sign In", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("tab_sign_in")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            errorMessage = null
                            infoMessage = null
                        },
                        text = { Text("Sign Up", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("tab_sign_up")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // SIGN UP EXTRA FIELDS: Full Name, Mobile Number, Unique @username
                AnimatedVisibility(visible = selectedTab == 1) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = fullName,
                            onValueChange = {
                                fullName = it
                                if (!usernameManuallyEdited) {
                                    customUsername = NexChatIds.normalizeUsername(it).take(18)
                                }
                            },
                            label = { Text("Full Name *") },
                            placeholder = { Text("Enter your full name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("signup_name_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = mobileNumber,
                            onValueChange = { mobileNumber = it },
                            label = { Text("Mobile Number *") },
                            placeholder = { Text("e.g. +91 9876543210") },
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("signup_phone_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = customUsername,
                            onValueChange = {
                                usernameManuallyEdited = true
                                customUsername = NexChatIds.normalizeUsername(it).take(24)
                            },
                            label = { Text("Unique @username (auto-filled or custom)") },
                            placeholder = { Text("e.g. rahul_nexchat") },
                            leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("signup_username_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                // SHARED FIELDS: Email ID & Password (minimum 6 digits/characters)
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        if (selectedTab == 1 && !usernameManuallyEdited && fullName.isBlank()) {
                            customUsername = NexChatIds.normalizeUsername(it.substringBefore("@")).take(18)
                        }
                    },
                    label = { Text("Email ID *") },
                    placeholder = { Text("you@example.com") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (min 6 digits/chars) *") },
                    placeholder = { Text("At least 6 digits or characters") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input")
                )

                // Error & Info Banners positioned right above the action button so they are always visible
                AnimatedVisibility(visible = errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = infoMessage != null) {
                    Surface(
                        color = NexEmeraldPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            text = infoMessage.orEmpty(),
                            color = NexEmeraldPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Submit Button (Sign In OR Sign Up)
                Button(
                    onClick = {
                        val cleanEmail = email.trim()
                        if (selectedTab == 1) {
                            // Sign Up validation: Name, Mobile Number, Email ID, and Password >= 6 digits/chars
                            if (fullName.trim().isBlank()) {
                                errorMessage = "Please enter your full name."
                                return@Button
                            }
                            if (mobileNumber.trim().length < 5) {
                                errorMessage = "Please enter your valid mobile number."
                                return@Button
                            }
                            if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
                                errorMessage = "Please enter a valid email ID."
                                return@Button
                            }
                            if (password.length < 6) {
                                errorMessage = "Password must be at least 6 digits or characters."
                                return@Button
                            }
                        } else {
                            if (cleanEmail.isBlank() || !cleanEmail.contains("@") || password.length < 6) {
                                errorMessage = "Enter a valid email ID and a password of at least 6 digits or characters."
                                return@Button
                            }
                        }

                        errorMessage = null
                        infoMessage = null
                        isLoading = true
                        scope.launch {
                            val result = if (selectedTab == 0) {
                                repository.signInEmailAccount(cleanEmail, password)
                            } else {
                                repository.registerEmailAccountWithProfile(
                                    fullName = fullName,
                                    mobileNumber = mobileNumber,
                                    rawEmail = cleanEmail,
                                    password = password,
                                    rawUsername = customUsername
                                )
                            }
                            isLoading = false
                            result.fold(
                                onSuccess = { authUser ->
                                    NexChatAuthSessionManager.saveEmailSession(context, authUser)
                                    onAuthSuccess()
                                },
                                onFailure = { err ->
                                    errorMessage = err.localizedMessage
                                        ?: "Authentication failed. Please check your details and try again."
                                }
                            )
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("email_submit_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (selectedTab == 0) "Sign In" else "Sign Up & Create Account",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Prominent Mode Switch Button: "Don't have an account? Sign Up" / "Already have an account? Sign In"
                OutlinedButton(
                    onClick = {
                        selectedTab = if (selectedTab == 0) 1 else 0
                        errorMessage = null
                        infoMessage = null
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("switch_auth_mode_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (selectedTab == 0) {
                            "Don't have an account? Sign Up"
                        } else {
                            "Already have an account? Sign In"
                        },
                        color = NexEmeraldPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (selectedTab == 0) {
                    TextButton(
                        onClick = {
                            val cleanEmail = email.trim()
                            if (cleanEmail.isBlank() || !cleanEmail.contains("@") || password.length < 6) {
                                errorMessage = "Enter your registered email ID and your new password (at least 6 digits/characters) above, then tap Reset Password."
                                return@TextButton
                            }
                            errorMessage = null
                            infoMessage = null
                            isLoading = true
                            scope.launch {
                                val res = repository.resetEmailPassword(cleanEmail, password)
                                isLoading = false
                                res.fold(
                                    onSuccess = {
                                        selectedTab = 0
                                        infoMessage = "Password updated for $cleanEmail! Tap 'Sign In' to continue."
                                    },
                                    onFailure = { err ->
                                        errorMessage = err.localizedMessage ?: "Failed to reset password."
                                    }
                                )
                            }
                        },
                        modifier = Modifier.testTag("forgot_password_button")
                    ) {
                        Text("Forgot Password? Reset with New Password")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = "  OR CONTINUE WITH  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Optional Google Sign-In Button
                OutlinedButton(
                    onClick = {
                        errorMessage = null
                        infoMessage = null
                        isLoading = true
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onAuthError = { msg ->
                                isLoading = false
                                errorMessage = msg
                            },
                            scope = scope,
                            onAuthCancelled = {
                                isLoading = false
                            }
                        )
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("google_sign_in_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Optional GitHub OAuth Provider Button
                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity ?: return@OutlinedButton
                        errorMessage = null
                        infoMessage = null
                        isLoading = true
                        val provider = OAuthProvider.newBuilder("github.com")
                        Firebase.auth.startActivityForSignInWithProvider(activity, provider.build())
                            .addOnSuccessListener {
                                isLoading = false
                                onAuthSuccess()
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                errorMessage = e.localizedMessage
                                    ?: "GitHub Sign-In is not enabled in the Firebase Console for this project."
                            }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("github_sign_in_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue with GitHub (Optional)")
                }
            }
        }
    }
}

@Composable
fun ProfileOnboardingScreen(
    repository: NexChatRepository,
    currentUid: String,
    initialDisplayName: String,
    initialEmail: String,
    errorBanner: String?,
    onCompleteProfile: (username: String, displayName: String, phoneNumber: String, about: String) -> Unit,
    onSignOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var username by remember {
        mutableStateOf(
            NexChatIds.normalizeUsername(
                initialDisplayName.ifBlank { initialEmail.substringBefore("@") }
            ).take(16)
        )
    }
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var phoneNumber by remember { mutableStateOf("+1") }
    var about by remember { mutableStateOf("Hey there! I am using NexChat Pro.") }
    var availabilityMessage by remember { mutableStateOf<String?>(null) }
    var isAvailable by remember { mutableStateOf<Boolean?>(null) }
    var checkingUsername by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Complete Your NexChat Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Claim your unique @username and set your contact details so other users on Android and Web can message and call you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = NexChatIds.normalizeUsername(it)
                        isAvailable = null
                        availabilityMessage = null
                    },
                    label = { Text("Unique Username (3–24 chars)") },
                    leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                checkingUsername = true
                                scope.launch {
                                    val res = repository.isUsernameAvailable(username, currentUid)
                                    checkingUsername = false
                                    res.fold(
                                        onSuccess = { ok ->
                                            isAvailable = ok
                                            availabilityMessage = if (ok) {
                                                "@$username is available!"
                                            } else {
                                                "@$username is already claimed."
                                            }
                                        },
                                        onFailure = { err ->
                                            isAvailable = false
                                            availabilityMessage = err.localizedMessage
                                        }
                                    )
                                }
                            }
                        ) {
                            Text(if (checkingUsername) "..." else "Check")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_username_input")
                )

                if (availabilityMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAvailable == true) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isAvailable == true) NexEmeraldPrimary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = availabilityMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAvailable == true) NexEmeraldPrimary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_display_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Phone Number (Required Profile Field)") },
                    supportingText = {
                        Text("Stored in your private profile as an unverified contact number (no SMS OTP required).")
                    },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_phone_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text("About / Bio") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_about_input")
                )

                if (errorBanner != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorBanner,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        onCompleteProfile(username, displayName, phoneNumber, about)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("onboarding_save_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NexEmeraldPrimary)
                ) {
                    Text("Claim @${username.ifBlank { "username" }} & Continue", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Sign Out")
                }
            }
        }
    }
}
