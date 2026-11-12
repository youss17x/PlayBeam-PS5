plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.youss17x.playbeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youss17x.playbeam"
        minSdk = 26
        targetSdk = 35
        // a release build can pass -PversionName=1.2.0 -PversionCode=12
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("versionName") as String?) ?: "0.9.0"
    }

    // One fixed key for every build, so new versions install over old ones.
    // In GitHub Actions it comes from the repository secrets (see .github/workflows).
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("playbeam") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        val key = if (keystorePath != null) signingConfigs.getByName("playbeam") else null
        debug {
            if (key != null) signingConfig = key
        }
        release {
            isMinifyEnabled = false
            signingConfig = key ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // FTP client
    implementation("commons-net:commons-net:3.11.1")
}
