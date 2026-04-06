package Kinetic_Eco.Tracker.data.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.KmMilestone
import Kinetic_Eco.Tracker.data.RoutePoint

class Converters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromBreakdownMap(value: Map<ActivityType, ActivityBreakdownEntity>): String {
        return gson.toJson(value)
    }
    
    @TypeConverter
    fun toBreakdownMap(value: String): Map<ActivityType, ActivityBreakdownEntity> {
        val mapType = object : TypeToken<Map<ActivityType, ActivityBreakdownEntity>>() {}.type
        return gson.fromJson(value, mapType)
    }
    
    @TypeConverter
    fun fromKmMilestones(value: List<KmMilestone>): String {
        return gson.toJson(value)
    }
    
    @TypeConverter
    fun toKmMilestones(value: String): List<KmMilestone> {
        if (value.isBlank() || value == "[]") return emptyList()
        val listType = object : TypeToken<List<KmMilestone>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }

    @TypeConverter
    fun fromRoutePath(value: List<RoutePoint>): String = gson.toJson(value)

    @TypeConverter
    fun toRoutePath(value: String): List<RoutePoint> {
        if (value.isBlank() || value == "[]") return emptyList()
        val listType = object : TypeToken<List<RoutePoint>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }
}



