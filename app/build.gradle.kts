plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.fireplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fireplayer"
        minSdk = 22
        targetSdk = 35
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "0")
        ndk {
            // Fire TV devices are ARM; dropping x86 keeps the APK small.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            // The screenshot run's emulator is x86.
            System.getenv("EXTRA_ABIS")?.let { abiFilters += it.split(',') }
        }
    }

    signingConfigs {
        // FirePlayer's fixed release key (this repository's, as for every release before), so each release installs
        // over the one on the TVs.
        create("release") {
            storeFile = file("fireplayer-release.jks")
            storePassword = "fireplayer"
            keyAlias = "fireplayer"
            keyPassword = "fireplayer"
        }
        // The test build's own identity, kept in the repository (a test key with Android's standard development
        // passwords, not the release key): every session signs test builds the same, so a test build already on a
        // TV can be updated in place instead of being reinstalled and set up again.
        create("test") {
            storeFile = file("fireplayer-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Test builds are signed with the repository's test key (see signingConfigs).
            signingConfig = signingConfigs.getByName("test")
            // A separate app beside the user's FirePlayer: its own package, name, icon and Continue Watching address
            // (app.fireplayer.next.continue), so Glass Home and the installed FirePlayer are never touched.
            applicationIdSuffix = ".next"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // TitleInfo's tests never touch the Android parts it can use.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.ass.media)
    implementation(libs.ffmpeg.decoder)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kxml2)
}
