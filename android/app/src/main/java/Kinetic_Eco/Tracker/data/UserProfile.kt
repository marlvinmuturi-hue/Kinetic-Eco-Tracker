package Kinetic_Eco.Tracker.data

/**
 * User profile stored in Firestore at users/{userId}.
 * displayName and photoUrl are optional; existing users keep their data.
 */
data class UserProfile(
    val displayName: String? = null,
    val photoUrl: String? = null
)
