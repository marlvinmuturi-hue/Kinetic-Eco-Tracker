package Kinetic_Eco.Tracker.services

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

data class FeedbackData(
    val userId: String,
    val userEmail: String,
    val message: String,
    val category: String = "general",
    val rating: Int? = null,
    val timestamp: Date = Date(),
    val platform: String = "android",
    val appVersion: String = "1.0.0"
)

class FeedbackService {
    private val firestore = FirebaseFirestore.getInstance()
    private val feedbackCollection = firestore.collection("feedback")

    suspend fun submitFeedback(feedback: FeedbackData): Result<String> {
        return try {
            val feedbackMap = hashMapOf(
                "userId" to feedback.userId,
                "userEmail" to feedback.userEmail,
                "message" to feedback.message,
                "category" to feedback.category,
                "rating" to feedback.rating,
                "timestamp" to feedback.timestamp,
                "platform" to feedback.platform,
                "appVersion" to feedback.appVersion,
                "status" to "new"
            )

            val documentRef = feedbackCollection.add(feedbackMap).await()
            Result.success(documentRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitQuickFeedback(
        userId: String,
        userEmail: String,
        message: String,
        category: String = "general"
    ): Result<String> {
        val feedback = FeedbackData(
            userId = userId,
            userEmail = userEmail,
            message = message,
            category = category
        )
        return submitFeedback(feedback)
    }
}
