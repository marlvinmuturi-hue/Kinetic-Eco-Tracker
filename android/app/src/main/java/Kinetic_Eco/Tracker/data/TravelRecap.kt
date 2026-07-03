package Kinetic_Eco.Tracker.data

data class TravelRecap(
    val citiesVisited: List<String>,
    val countriesVisited: List<String>,
    val countryCodes: List<String>,     // ISO-2 codes used to render emoji flags
    val longestTrip: TripRecord?,       // by distance
    val longestSession: TripRecord?,    // by duration
    val topSpeedRecord: TripRecord?,
    val mostElevationRecord: TripRecord?,
    val bestCo2Record: TripRecord?,
    val mostCaloriesRecord: TripRecord?,
    val firstTripDate: String?,         // yyyy-MM-dd of the earliest session
    val totalSessions: Int
)

data class TripRecord(
    val displayValue: String,   // pre-formatted, e.g. "42.3 km", "3h 24m"
    val sessionDate: String,    // yyyy-MM-dd
    val city: String?,
    val country: String?
)
