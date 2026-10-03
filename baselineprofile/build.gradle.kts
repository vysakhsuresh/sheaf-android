plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    // The version is named here, and again in :app, because the root build file puts AGP, Kotlin
    // and KSP on the classpath every module inherits and this plugin is not one of them.
    //
    // Deliberately a version BEHIND benchmark-macro-junit4 below, which is the one thing in
    // this file that looks like an oversight and is not.
    //
    // The device-side library has to be 1.5.0, because 1.3.3 cannot generate a profile on
    // Android 15 at all: it parses `pm dump-profiles` and insists the output is either empty
    // or the single "Profile saved to ..." line, and API 35 prints two lines about flushing
    // in front of it, so the run throws after driving the whole journey. The Gradle plugin
    // cannot follow it up, because 1.5.0 fails to configure against this project's AGP and
    // KSP - `Illegal char <?> ... app\provider(?)` while creating kspNonMinifiedReleaseKotlin.
    //
    // The two are separable: the parsing happens on the phone, the plugin only orchestrates
    // and then reads the file back. Revisit when AGP and KSP are next upgraded, and put them
    // back on one version then.
    id("androidx.baselineprofile") version "1.3.3"
}

android {
    namespace = "com.layerbit.sheaf.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Not the app's 26. Generating a profile means driving the app with UI Automator and then
        // reading ART's own profile back off the device, and doing that without root starts at 28.
        // The profile this writes is still used all the way down to 26 - it is only the machine
        // that produces it that has to be newer.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // A phone someone plugged in, not a Gradle-managed virtual device: nothing in this module
    // should download a system image or start an emulator as a side effect of a build. The day
    // this has to run where no phone is attached, a managedDevices entry is the change.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
