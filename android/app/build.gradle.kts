import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

// Release signing is configured only when android/keystore.properties exists
// (it is git-ignored). Without it, the release build is left unsigned so CI and
// fresh clones still build. See CONTRIBUTING.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.brucia"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.brucia"
        minSdk = 26
        targetSdk = 34
        // Single source of truth: the repo-root VERSION file (same one the firmware reads via
        // pre_build_version.py). Bump VERSION once and both the app and the firmware pick it up.
        val bruciaVersion = rootProject.file("../VERSION").readText().trim()
        val vparts = bruciaVersion.split(".").map { it.toIntOrNull() ?: 0 }
        versionCode = (vparts.getOrElse(0) { 0 }) * 10000 + (vparts.getOrElse(1) { 0 }) * 100 + (vparts.getOrElse(2) { 0 })
        versionName = bruciaVersion
    }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is used by the in-app OTA updater to compare the
        // installed version against the latest GitHub release.
        buildConfig = true
    }
    lint {
        // False positive for a pure-Compose app (ComponentActivity +
        // rememberLauncherForActivityResult, no Fragments involved).
        disable += "InvalidFragmentVersionForActivityResult"
    }
    packaging {
        resources {
            // The three BouncyCastle jars each ship an identical multi-release OSGI
            // manifest; keep the first and drop the duplicates.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ZXing core: pure-Java QR encoder for the offline Wi-Fi QR generator in Utilities.
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Room persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Map
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // BouncyCastle: X.509 certificate generation for the on-phone MITM proxy
    // (root CA + per-host leaf certs). jdk18on build, used directly without
    // registering the provider globally to avoid clashing with Android's "BC".
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
