import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // The About screen shows the version it is actually running.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

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
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Every operation runs as a Worker, not a viewModelScope coroutine. A four-hundred-page
    // job has to survive the user switching to WhatsApp, and a foreground-service Worker is
    // the only thing on Android that reliably does.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

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
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // The sensor writes orientation into EXIF rather than rotating the pixels, so a portrait
    // capture decodes sideways without this.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// DEPENDENCIES DELIBERATELY NOT HERE YET
//
// The viewer still runs entirely on android.graphics.pdf, which ships with the platform and
// allocates less than PDFBox does per page. PDFBox is the write side only, behind PdfSurgeon.
//
// Never add: iText (AGPL), MuPDF (AGPL), Ghostscript (AGPL). Ghostscript is the obvious
// answer for compression and is the one most likely to be reached for by accident.
