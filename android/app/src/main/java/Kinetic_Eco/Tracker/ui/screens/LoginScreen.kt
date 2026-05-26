package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.viewmodel.AuthViewModel

private const val MIN_PASSWORD_LENGTH = 6

private enum class AuthMode { SIGN_IN, SIGN_UP }

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSignInSuccess: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onGoogleSignInClick: () -> Unit,
    launchGoogleSignIn: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    // Local validation message (e.g. mismatched confirm password). Kept
    // separate from server-side errorMessage so we don't clobber it.
    var localError by remember { mutableStateOf<String?>(null) }

    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            onSignInSuccess()
        }
    }

    val isSignUp = mode == AuthMode.SIGN_UP
    val passwordsMismatch = isSignUp && confirmPassword.isNotEmpty() && password != confirmPassword
    val passwordTooShort = isSignUp && password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val displayedError = localError ?: errorMessage

    fun switchMode(target: AuthMode) {
        if (mode == target) return
        mode = target
        confirmPassword = ""
        localError = null
        viewModel.clearError()
    }

    // Wrap the form in a vertical scroll + imePadding so the soft keyboard
    // never hides the focused field (Confirm password sits below the fold on
    // shorter devices). The Column still measures itself against `fillMaxSize`
    // so Arrangement.Center keeps the form vertically centred when there's
    // room, and falls back to scrolling when the keyboard squeezes the layout.
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_app_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.extraLarge)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.kinetic),
            style = MaterialTheme.typography.displayLarge,
            color = colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.eco_tracker),
            style = MaterialTheme.typography.titleLarge,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // ── Mode toggle: Sign in ▮ Create account ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            ModeToggleButton(
                label = stringResource(R.string.auth_mode_sign_in),
                selected = !isSignUp,
                onClick = { switchMode(AuthMode.SIGN_IN) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            ModeToggleButton(
                label = stringResource(R.string.auth_mode_create_account),
                selected = isSignUp,
                onClick = { switchMode(AuthMode.SIGN_UP) },
                modifier = Modifier.weight(1f)
            )
        }

        displayedError?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Red500.copy(alpha = 0.2f)
                )
            ) {
                Text(
                    text = error,
                    color = Red500,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.hint_email)) },
            leadingIcon = {
                Icon(Icons.Default.Email, contentDescription = stringResource(R.string.hint_email))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colorScheme.onSurface,
                unfocusedTextColor = colorScheme.onSurface,
                focusedBorderColor = colorScheme.primary,
                unfocusedBorderColor = colorScheme.outline
            )
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.hint_password)) },
            placeholder = if (isSignUp) {
                { Text(stringResource(R.string.hint_password_min)) }
            } else null,
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.hint_password))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isSignUp) 12.dp else 24.dp),
            singleLine = true,
            isError = passwordTooShort,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (passwordTooShort) {
                { Text(stringResource(R.string.password_too_short), color = Red500) }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colorScheme.onSurface,
                unfocusedTextColor = colorScheme.onSurface,
                focusedBorderColor = colorScheme.primary,
                unfocusedBorderColor = colorScheme.outline
            )
        )

        if (isSignUp) {
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text(stringResource(R.string.hint_confirm_password)) },
                placeholder = { Text(stringResource(R.string.hint_confirm_password_helper)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = stringResource(R.string.hint_confirm_password)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                singleLine = true,
                isError = passwordsMismatch,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (passwordsMismatch) {
                    { Text(stringResource(R.string.passwords_do_not_match), color = Red500) }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface,
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outline
                )
            )
        }

        if (!isSignUp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        resetEmail = email
                        showResetDialog = true
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.forgot_password),
                        color = colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Primary action button (Sign in or Create account)
        val primaryEnabled = !isLoading &&
            email.isNotBlank() &&
            password.isNotBlank() &&
            (!isSignUp || (password.length >= MIN_PASSWORD_LENGTH && password == confirmPassword))

        Button(
            onClick = {
                localError = null
                if (isSignUp) {
                    when {
                        password.length < MIN_PASSWORD_LENGTH -> {
                            localError = "Password must be at least $MIN_PASSWORD_LENGTH characters."
                        }
                        password != confirmPassword -> {
                            localError = "Passwords do not match."
                        }
                        else -> viewModel.signUpWithEmail(email.trim(), password)
                    }
                } else {
                    viewModel.signInWithEmail(email.trim(), password)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 16.dp),
            enabled = primaryEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colorScheme.onPrimary
                )
            } else {
                Text(
                    text = stringResource(
                        if (isSignUp) R.string.btn_create_account else R.string.btn_email_login
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        OutlinedButton(
            onClick = launchGoogleSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isLoading,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colorScheme.onBackground
            )
        ) {
            Text(stringResource(R.string.btn_google_login), style = MaterialTheme.typography.labelLarge)
        }

        TextButton(
            onClick = {
                switchMode(if (isSignUp) AuthMode.SIGN_IN else AuthMode.SIGN_UP)
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = stringResource(
                    if (isSignUp) R.string.have_account_prompt else R.string.sign_up_prompt
                ),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(stringResource(R.string.reset_password)) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(MaterialTheme.shapes.large)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.reset_password_hint),
                            modifier = Modifier.padding(bottom = 16.dp),
                            color = colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = resetEmail,
                            onValueChange = { resetEmail = it },
                            label = { Text(stringResource(R.string.hint_email)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colorScheme.onSurface,
                                unfocusedTextColor = colorScheme.onSurface,
                                focusedBorderColor = colorScheme.primary,
                                unfocusedBorderColor = colorScheme.outline
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (resetEmail.isNotBlank()) {
                                viewModel.resetPassword(resetEmail)
                                showResetDialog = false
                            }
                        },
                        enabled = resetEmail.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.send_link))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(stringResource(R.string.cancel), color = colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = colorScheme.surface,
                titleContentColor = colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ModeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary
            )
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colorScheme.onSurfaceVariant
            )
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
