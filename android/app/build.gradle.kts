import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics") version "3.0.3"
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

android {
    namespace = "Kinetic_Eco.Tracker"
    compileSdk = 35

    lint {
        // Compose's runtime lint detectors crash on this project because the Kotlin compiler is newer
        // than the kotlinx-metadata bundled in the current AGP/lint (checkMetadataVersionForRead throws),
        // aborting the whole run before useful checks (NewApi etc.) report. Disable the Compose checks
        // that read composable metadata. Real fix: bump AGP so its lint matches the Kotlin version.
        disable += "StateFlowValueCalledInComposition"
        disable += "CoroutineCreationDuringComposition"

        // Snapshot existing issues so only NEW problems fail future builds. Regenerate by deleting
        // lint-baseline.xml and re-running lint. As issues are fixed, prune stale entries from it.
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "com.kineticecotracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "V1.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FUNCTIONS_BASE_URL", "\"https://us-central1-gen-lang-client-0114974661.cloudfunctions.net\"")
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    
    compileOptions {
        // Backport java.time (and other newer JDK APIs) to minSdk 24/25 — without this the app
        // crashes with NoClassDefFoundError on Android 7.x when it touches java.time (Analysis screens).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core library desugaring runtime — backports java.time etc. for minSdk 24/25 (see compileOptions).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // ViewModel & LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // WorkManager — durable, retryable background sync of un-synced sessions to Firestore
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    
    // Firebase (Native - not WebView)
    implementation(platform("com.google.firebase:firebase-bom:34.14.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    
    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // AdMob (banner)
    implementation("com.google.android.gms:play-services-ads:25.4.0")
    // User Messaging Platform (UMP) — GDPR/consent gathering before requesting ads
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
    
    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // OsmDroid for OSM maps (offline-capable via tile cache)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
