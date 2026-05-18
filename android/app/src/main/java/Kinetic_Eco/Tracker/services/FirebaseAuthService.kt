package Kinetic_Eco.Tracker.services

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class FirebaseAuthService(private val context: Context) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    
    private val _googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getWebClientId())
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }
    
    fun getCurrentUser(): FirebaseUser? = auth.currentUser
    
    fun isUserSignedIn(): Boolean = auth.currentUser != null
    
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val result = auth.signInWithEmailAndPassword(normalizedEmail, password).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(mapAuthException(e))
        }
    }
    
    suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val result = auth.createUserWithEmailAndPassword(normalizedEmail, password).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(mapAuthException(e))
        }
    }
    
    private fun mapAuthException(e: Exception): Exception {
        val rawMessage = e.message ?: ""
        val authEx = e as? FirebaseAuthException
        val code = authEx?.errorCode ?: ""
        
        val message = when {
            code == "ERROR_USER_NOT_FOUND" -> "No account found with this email."
            code == "ERROR_WRONG_PASSWORD" -> "Incorrect password."
            code == "ERROR_INVALID_CREDENTIAL" -> "Invalid email or password. If you signed up with Google, use 'Continue with Google' to sign in."
            code.contains("INVALID_CREDENTIAL") -> "Invalid email or password. If you signed up with Google, use 'Continue with Google' to sign in."
            rawMessage.contains("supplied auth credential") || rawMessage.contains("malformed") || rawMessage.contains("has expired") ->
                "Invalid email or password. If you signed up with Google, use 'Continue with Google' to sign in."
            code == "ERROR_INVALID_EMAIL" -> "Invalid email address."
            code == "ERROR_USER_DISABLED" -> "This account has been disabled."
            code == "ERROR_TOO_MANY_REQUESTS" -> "Too many failed attempts. Please try again later."
            code == "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Please sign in instead, or use Forgot password to reset it."
            code.contains("EMAIL_ALREADY_IN_USE") -> "An account with this email already exists. Please sign in instead, or use Forgot password to reset it."
            code == "ERROR_WEAK_PASSWORD" -> "Password should be at least 6 characters."
            // ERROR_OPERATION_NOT_ALLOWED is thrown when the Email/Password
            // sign-in provider is disabled in Firebase Console. This is the
            // most common reason "only Google works" for users — surface a
            // clear, actionable message instead of the cryptic default.
            code == "ERROR_OPERATION_NOT_ALLOWED" -> "Email/password authentication is not enabled. Enable it in Firebase Console > Authentication > Sign-in method, or use Continue with Google."
            code.contains("OPERATION_NOT_ALLOWED") -> "Email/password authentication is not enabled. Enable it in Firebase Console > Authentication > Sign-in method, or use Continue with Google."
            code == "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check your connection and try again."
            else -> authEx?.message ?: "Authentication failed."
        }
        return Exception(message)
    }
    
    suspend fun signInWithGoogle(account: GoogleSignInAccount): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            val result = auth.signInWithCredential(credential).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getGoogleSignInClient(): GoogleSignInClient = _googleSignInClient
    
    suspend fun signOut() {
        auth.signOut()
        _googleSignInClient.signOut().await()
    }
    
    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            auth.sendPasswordResetEmail(normalizedEmail).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapAuthException(e))
        }
    }
    
    private fun getWebClientId(): String {
        // Get from resources (strings.xml)
        // This should be configured in Firebase Console > Project Settings > Web App
        return context.getString(Kinetic_Eco.Tracker.R.string.default_web_client_id)
    }
}

