package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
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
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            _currentUser.value = auth.currentUser
        }
    }
    
    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            var result = authService.signInWithEmail(email, password)
            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.message ?: ""
                if (errorMsg.contains("Invalid email or password")) {
                    result = authService.signUpWithEmail(email, password)
                    result.fold(
                        onSuccess = { user ->
                            _currentUser.value = user
                            _isLoading.value = false
                        },
                        onFailure = { signUpError ->
                            val signUpMsg = signUpError.message ?: ""
                            _errorMessage.value = when {
                                signUpMsg.contains("already exists") || signUpMsg.contains("EMAIL_ALREADY_IN_USE") ->
                                    "Account exists. If you signed up with Google, use 'Continue with Google'. Otherwise check your password or use Forgot password."
                                else -> signUpMsg
                            }
                            _isLoading.value = false
                        }
                    )
                    return@launch
                }
            }
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isLoading.value = false
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
            
            val result = authService.signUpWithEmail(email, password)
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isLoading.value = false
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
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Google sign in failed"
                    _isLoading.value = false
                }
            )
        }
    }
    
    fun signOut() {
        viewModelScope.launch {
            authService.signOut()
            _currentUser.value = null
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
    
    fun clearError() {
        _errorMessage.value = null
    }
}



