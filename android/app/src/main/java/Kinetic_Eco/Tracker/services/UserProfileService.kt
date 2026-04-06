package Kinetic_Eco.Tracker.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import Kinetic_Eco.Tracker.data.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "UserProfileService"
private const val PROFILE_PHOTO_PATH = "profile_photo.jpg"
private const val TARGET_SIZE = 256

/**
 * Service for Firestore user profile (displayName, photoUrl) and Firebase Storage profile photos.
 * Path: users/{userId} in Firestore, users/{userId}/profile_photo.jpg in Storage.
 */
class UserProfileService {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun getProfile(userId: String): UserProfile? = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users").document(userId).get().await()
            val data = doc.data ?: return@withContext null
            UserProfile(
                displayName = data["displayName"] as? String,
                photoUrl = data["photoUrl"] as? String
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile", e)
            null
        }
    }

    suspend fun saveDisplayName(userId: String, displayName: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = hashMapOf<String, Any?>(
                "displayName" to displayName
            )
            firestore.collection("users").document(userId)
                .set(updates.filterValues { it != null }.mapValues { it.value!! }, SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save displayName", e)
            Result.failure(e)
        }
    }

    suspend fun uploadProfilePhoto(context: Context, userId: String, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        try {
            val storage = FirebaseStorage.getInstance()
            val ref = storage.reference.child("users").child(userId).child(PROFILE_PHOTO_PATH)

            // Strategy 1: putFile (for content/file URIs)
            val uploaded = if (uri.scheme?.lowercase() in listOf("content", "file")) {
                try {
                    ref.putFile(uri, metadata).await()
                    true
                } catch (_: Exception) {
                    false
                }
            } else false

            if (!uploaded) {
                // Strategy 2: compress + putBytes (more reliable for permission issues)
                val bytes = compressImage(context, uri)
                    ?: return@withContext Result.failure(Exception("Could not read image. Try a different photo."))
                ref.putBytes(bytes).await()
            }

            // getDownloadUrl can fail with "Object does not exist" due to propagation delay
            val downloadUrl = try {
                ref.getDownloadUrl().await().toString()
            } catch (e: Exception) {
                if (e.message?.contains("Object does not exist") == true) {
                    delay(500)
                    ref.getDownloadUrl().await().toString()
                } else throw e
            }

            firestore.collection("users").document(userId)
                .set(mapOf("photoUrl" to downloadUrl), SetOptions.merge())
                .await()
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Profile photo upload failed", e)
            Result.failure(e)
        }
    }

    private fun compressImage(context: Context, uri: Uri): ByteArray? {
        return try {
            val inputStream = openUriInputStream(context, uri) ?: return null
            val (width, height) = inputStream.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                opts.outWidth to opts.outHeight
            }
            val sampleSize = calculateInSampleSize(width, height, TARGET_SIZE)
            openUriInputStream(context, uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeStream(input, null, opts) ?: return null
                val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_SIZE, TARGET_SIZE, true)
                if (scaled != bitmap) bitmap.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                scaled.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            null
        }
    }

    /** Opens an InputStream for the given URI. Handles both content:// and file:// schemes. */
    private fun openUriInputStream(context: Context, uri: Uri): java.io.InputStream? {
        return try {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path ?: return null
                    val file = File(path)
                    if (file.exists()) file.inputStream() else null
                }
                else -> context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URI stream: ${uri.scheme} ${uri.path}", e)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var inSampleSize = 1
        if (width > targetSize || height > targetSize) {
            val halfW = width / 2
            val halfH = height / 2
            while (halfW / inSampleSize >= targetSize && halfH / inSampleSize >= targetSize) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
