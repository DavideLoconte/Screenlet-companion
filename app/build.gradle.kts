plugins {
    id("com.android.application")
}

val appVersion = "0.1.0"
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

// Release builds are signed with the key CI gets from its secrets (or a
// local one): SCREENLET_KEYSTORE, SCREENLET_KEYSTORE_PASSWORD, SCREENLET_KEY_ALIAS.
// Without one, the release APK is signed with the debug key (installable, not updatable from another build).
val keystore = System.getenv("SCREENLET_KEYSTORE")

android {
    namespace = "io.github.davideloconte.screenlet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.davideloconte.screenlet"
        // Android 13: Wi-Fi Direct groups with a name of ours, NEARBY_WIFI_DEVICES.
        minSdk = 33
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SCREENLET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCREENLET_KEY_ALIAS") ?: "screenlet"
                keyPassword = System.getenv("SCREENLET_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Material 3 Expressive: the look of Android 16, in the wallpaper's colours.
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
