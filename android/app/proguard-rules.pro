# ============================================================================
# R8 / ProGuard rules for Kinetic Eco Tracker (release shrinking + optimization)
# ============================================================================
# Enabled with isMinifyEnabled=true (see app/build.gradle.kts). R8 renames and
# removes members it thinks are unused; anything touched by REFLECTION or by
# NAME-BASED (de)serialization must be kept, or it breaks silently at runtime.
#
# This app's reflection surface:
#   - Gson serializes model classes by field name (Room JSON columns + caches).
#   - Enums are persisted by .name / read back via valueOf() (Firestore, prefs).
#   - Firestore itself uses MANUAL maps (string-literal keys), so it needs no
#     model keeps — but the enums and Gson models it round-trips still do.
# Existing users already have data on disk written with the REAL field/enum
# names (current shipping build is un-minified), so renaming these would make
# that data unreadable after an R8 release. Keeping the data package verbatim
# guarantees forward/backward compatibility.

# --- Attributes ---------------------------------------------------------------
# Signature: required for Gson TypeToken<...> generic resolution.
# *Annotation*/Inner/Enclosing: keep annotations & nested-class metadata.
# SourceFile/LineNumberTable: readable crash stack traces (Crashlytics uploads
#   the mapping file via its Gradle plugin, but keeping line numbers is belt-and-braces).
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- App data models & enums (name-based (de)serialization) -------------------
# Keep classes AND members verbatim so Gson field names and enum constant names
# never change. Covers KmMilestone, ActivitySegment, RoutePoint, SessionEntity,
# ActivityBreakdownEntity, and enums ActivityType / Gender / UnitSystem /
# LeaderboardCategory (persisted via valueOf).
-keep class Kinetic_Eco.Tracker.data.** { *; }

# Defensive: keep every enum's values()/valueOf() app-wide (used with runtime strings).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Honour androidx @Keep anywhere it's used.
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# --- Gson --------------------------------------------------------------------
# Official Gson R8 rules: keep its reflective internals and TypeToken machinery.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# Prevent R8 from stripping fields of any @SerializedName-annotated class.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Firebase / Google Play services -----------------------------------------
# Broad keep (already present pre-R8). Safe; the SDKs also ship consumer rules.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- osmdroid (maps) ----------------------------------------------------------
# osmdroid touches resources/config reflectively in places; keep it whole to be safe.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- Kotlin coroutines --------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- Repackaging --------------------------------------------------------------
# Move every obfuscated class into the root package. Shortens the type names in the
# dex string pool (a small size win) and flattens the package tree, which makes the
# output harder to read. Only affects classes that are already being renamed — the
# -keep rules above (data models, Gson, Firebase, GMS, osmdroid) pin their own names
# and are left where they are, so no name-based (de)serialization is at risk.
# proguard-android-optimize.txt already supplies -allowaccessmodification, which is
# what lets repackaging across the old package boundaries actually pay off.
# Crash reports stay readable: the AAB embeds the R8 mapping at
# BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map, so Play
# deobfuscates automatically.
-repackageclasses
-dontwarn kotlinx.coroutines.**