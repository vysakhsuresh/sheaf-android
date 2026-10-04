import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // Consumes :baselineprofile's output and adds the variants that produce it. The version is
    // named here, and again in that module, because the root build file puts AGP, Kotlin and KSP
    // on the classpath every module inherits and this plugin is not one of them.
    id("androidx.baselineprofile") version "1.5.0"
}

// Release signing credentials live only in a local, git-ignored keystore.properties (see
// keystore.properties.example) - never hardcoded here and never committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.layerbit.sheaf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.layerbit.sheaf"
        // PdfRenderer reaches back to API 21, but the floor is set by Compose and by the
        // family baseline Deja and Abhyas already established. Nothing in the toolkit needs
        // a newer platform than 26 except page-level PdfRenderer features added in 35, and
        // those are additions behind PdfEngine rather than requirements.
        minSdk = 26
        // Behind compileSdk deliberately. Raising this is not a version bump: it opts the app
        // into every behaviour change the new platform gates on targetSdk, and those have to
        // be found on a device rather than in a build. Lint calls it OldTargetApi; that is the
        // warning being accepted, not missed.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Code shrinking without resource shrinking leaves every drawable, string and style
            // of every library in the APK. There is no Resources.getIdentifier call anywhere in
            // this app, so nothing in res/ is reached by a name the shrinker cannot see.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ML Kit's OCR pipeline is an 11 MB native library and it ships one per architecture: four of
    // them, 41 MB, of which any one phone can use exactly one. Splitting them apart is the single
    // biggest thing that can be done to this app's download size, and the bundle is what does it -
    // these three flags are what a device actually receives.
    //
    // They are the defaults, and they are written down because switching any of them off is a
    // decision rather than a tidy-up. There is deliberately no `splits` block to go with them:
    // AGP will not run the resource shrinker for a build that produces split APKs, and for anyone
    // who needs a per-ABI APK, `bundletool build-apks` cuts one from the same bundle Play uses.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
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
        // The About screen shows the version it is actually running.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Supplied by org.jetbrains.kotlin.plugin.compose. Recomposition work is otherwise argued about
// rather than measured: these reports are the only place the compiler says out loud which
// composables it made skippable and which parameters it considers unstable, so every claim that a
// screen stopped recomposing gets checked against build/compose/reports/*-composables.txt.
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose/reports")
    metricsDestination = layout.buildDirectory.dir("compose/metrics")
    stabilityConfigurationFile = layout.projectDirectory.file("compose_compiler_config.conf")
}

// The baseline profile plugin derives nonMinifiedRelease and benchmarkRelease from release with
// initWith, which copies the signing config across - and release has no signing config at all
// unless keystore.properties exists. Without this, anyone who does not hold the release key gets
// two variants that build an APK no device will install, and generating a profile is the one thing
// they are for. Neither is ever shipped, so the debug key is the right key for both. It happens in
// finalizeDsl because that is the one moment where the plugin's build types already exist and the
// DSL can still be written to.
androidComponents {
    finalizeDsl { app ->
        val debugSigning = app.signingConfigs.getByName("debug")
        app.buildTypes.forEach { buildType ->
            val generated = buildType.name.startsWith("nonMinified") ||
                buildType.name.startsWith("benchmark")
            if (!generated) return@forEach

            buildType.signingConfig = debugSigning
            // The profile is generated against a build with code shrinking off, so its rules name
            // classes R8 has not renamed yet - and resource shrinking cannot run without code
            // shrinking. Set here rather than relying on the order two plugins write one DSL in.
            if (!buildType.isMinifyEnabled) buildType.isShrinkResources = false
        }
    }
}

// The profile is a text file checked in beside the sources and packaged into the APK. It is
// regenerated by a deliberate device run and never by a release build: a release that cannot be
// built without a phone attached is a release nobody can cut.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Carries the baseline profile into ART's own profile shortly after the first launch. The
    // platform does this itself from API 31; below that nothing does, and this app's floor is 26.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Reads display names and sizes back out of a SAF content:// Uri without holding the
    // document open. The platform DocumentsContract calls would do it, at the cost of a
    // cursor dance at every call site.
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Every operation runs as a Worker, not a viewModelScope coroutine. A four-hundred-page
    // job has to survive the user switching to WhatsApp, and a foreground-service Worker is
    // the only thing on Android that reliably does.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Apache-2.0. Everything structural - merge, split, page tree, encryption, content
    // streams - goes through this, behind PdfSurgeon. The platform renderer stays the
    // viewer's engine because it is faster and allocates less; this is the write side.
    //
    // Needs PDFBoxResourceLoader.init(context) before first use, which SheafApplication does.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Bundled (not the "-unbundled" / Play-Services-backed) build: the recognition model ships
    // inside the APK, so OCR never needs a model download and works with no network at all.
    // That is the whole privacy position, and roughly 5 MB of install size is what it costs.
    // The same call Deja and Abhyas made.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // CameraX drives the scanner. Photographing a page is a first-class way documents get into
    // Sheaf, so it gets a purpose-built viewfinder with corner adjustment rather than an
    // ACTION_IMAGE_CAPTURE hand-off to whatever camera app happens to be installed.
    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // The sensor writes orientation into EXIF rather than rotating the pixels, so a portrait
    // capture decodes sideways without this.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // Not a runtime dependency: this is how the plugin finds the module that generates the
    // profile. Nothing in :baselineprofile reaches the device as part of the app.
    baselineProfile(project(":baselineprofile"))
}

// DEPENDENCIES DELIBERATELY NOT HERE YET
//
// The viewer still runs entirely on android.graphics.pdf, which ships with the platform and
// allocates less than PDFBox does per page. PDFBox is the write side only, behind PdfSurgeon.
//
// Never add: iText (AGPL), MuPDF (AGPL), Ghostscript (AGPL). Ghostscript is the obvious
// answer for compression and is the one most likely to be reached for by accident.
