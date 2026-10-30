plugins {
    id("com.android.application")
}

// The version is in VERSION: 0.5.0 -> code 500.
val appVersion = rootDir.resolve("VERSION").readText().trim()
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

// Release builds are signed with the key CI gets from its secrets (or a
// local one): SCREENLET_KEYSTORE, SCREENLET_KEYSTORE_PASSWORD, SCREENLET_KEY_ALIAS.
val keystore = System.getenv("SCREENLET_KEYSTORE")

android {
    namespace = "com.davideloconte.screenlet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davideloconte.screenlet"
        // Android 13: the S Pen tablets Samsung still updates.
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
            // R8: Kotlin, androidx and Material down to what the app uses.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // A release build signed with the debug key, to try R8's output on a
        // device that has the debug build installed.
        create("shrunk") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Material 3 Expressive: the look of Android 16, in the wallpaper's colours.
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
