package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import Kinetic_Eco.Tracker.services.FirebaseAuthService

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val authService = FirebaseAuthService(application)
    
    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        _currentUser.value = authService.getCurrentUser()
        authService.getCurrentUser()?.let { saveFcmTokenForUser(it.uid) }
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            _currentUser.value = auth.currentUser
        }
    }
    
    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            // Sign-up is now an explicit user action via signUpWithEmail()
            // (the LoginScreen has separate Sign in / Create account modes),
            // so we no longer silently create a new account on a failed sign-in.
            authService.signInWithEmail(email, password).fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isLoading.value = false
                    saveFcmTokenForUser(user.uid)
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Sign in failed"
                    _isLoading.value = false
                }
            )
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            authService.signUpWithEmail(email, password).fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isLoading.value = false
                    saveFcmTokenForUser(user.uid)
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Sign up failed"
                    _isLoading.value = false
                }
            )
        }
    }
    
    fun signInWithGoogle(account: GoogleSignInAccount) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            val result = authService.signInWithGoogle(account)
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isLoading.value = false
                    saveFcmTokenForUser(user.uid)
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Google sign in failed"
                    _isLoading.value = false
                }
            )
        }
    }
    
    /**
     * Sign the user out.
     *
     * The state-mutation-then-cleanup ordering matters: we set [_currentUser]
     * to null *synchronously* before launching the actual Firebase / Google
     * sign-out so that any UI observers (e.g. LoginScreen's
     * `LaunchedEffect(currentUser)`) see the user as logged out the moment
     * the caller navigates to Login. Doing the Firebase work first inside a
     * coroutine left a ~50 ms window where the navigation had already taken
     * us to Login but `currentUser` was still the old value, which made
     * Login bounce us right back to MainTabs and forced users to tap Logout
     * twice. The auth-state listener installed in [init] still picks up the
     * eventual sign-out and confirms the null state, so this is purely an
     * "update UI first, finalize backend after" optimisation, not a
     * functional change.
     */
    fun signOut() {
        _currentUser.value = null
        viewModelScope.launch {
            authService.signOut()
        }
    }
    
    fun resetPassword(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            val result = authService.resetPassword(email)
            result.fold(
                onSuccess = {
                    _errorMessage.value = "Password reset email sent"
                    _isLoading.value = false
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Failed to send reset email"
                    _isLoading.value = false
                }
            )
        }
    }
    
    fun getGoogleSignInClient() = authService.getGoogleSignInClient()

    /** Surfaces a failure from the Google Sign-In activity result (e.g. ApiException
     *  before [signInWithGoogle] is ever reached) so the UI doesn't appear to hang. */
    fun reportGoogleSignInError(message: String) {
        _errorMessage.value = message
        _isLoading.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun saveFcmTokenForUser(uid: String) {
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                val data = mapOf(
                    "token" to token,
                    "updatedAt" to System.currentTimeMillis(),
                    "platform" to "android"
                )
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("fcmTokens").document(token)
                    .set(data)
                    .await()
            } catch (e: Exception) {
                // non-fatal — token will be saved on next refresh
            }
        }
    }
}



