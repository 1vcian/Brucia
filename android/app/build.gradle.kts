import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
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
    compileSdk = 37

    defaultConfig {
        applicationId = "app.brucia"
        minSdk = 26
        // targetSdk 36 (Android 16) reviewed: back is handled only through BackHandler /
        // OnBackPressedDispatcher (predictive back on by default, onBackPressed and KEYCODE_BACK no
        // longer delivered), no orientation/resizability/aspect-ratio locks (ignored on sw600dp+),
        // already edge to edge with no opt-out. See CHANGELOG and TESTS.md for the phone checks.
        targetSdk = 36
        // Single source of truth: the repo-root VERSION file (same one the firmware reads via
        // pre_build_version.py). Bump VERSION once and both the app and the firmware pick it up.
        val bruciaVersion = rootProject.file("../VERSION").readText().trim()
        val vparts = bruciaVersion.split(".").map { it.toIntOrNull() ?: 0 }
        // Keep the version-code epoch introduced during stabilization; never reuse
        // lower codes even when development version names change.
        versionCode = 1000000 + (vparts.getOrElse(0) { 0 }) * 10000 + (vparts.getOrElse(1) { 0 }) * 100 + (vparts.getOrElse(2) { 0 })
        versionName = bruciaVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // Opt-in JaCoCo for host unit tests: `./gradlew -Pbrucia.coverage :app:createDebugUnitTestCoverageReport`
            // (report in app/build/reports/coverage/test/debug). Off by default so the normal
            // testDebugUnitTest/assembleDebug run is not instrumented. See TESTS.md "Unit-test coverage".
            enableUnitTestCoverage = providers.gradleProperty("brucia.coverage").isPresent
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
    sourceSets {
        // Exported Room schemas (1-3) for MigrationTestHelper in instrumentation tests.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    packaging {
        resources {
            // The three BouncyCastle jars each ship an identical multi-release OSGI
            // manifest (never read on Android); drop them.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // BouncyCastle 1.86 ships the same META-INF/LICENSE.md in each jar
            // (byte-identical in bcprov/bcpkix/bcutil 1.86); keep one copy in the APK.
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // kotlinx-serialization-core is not used by app code; savedstate/lifecycle bring 1.7.3.
    // room-testing reads the schema JSON with 1.8+ and the test APK runs on the app's classes
    // (1.7.3 gives AbstractMethodError in MigrationTestHelper), so it is raised for every
    // build type: release ships the same version the debug and instrumentation builds run.
    // 1.12.0 checked with connectedDebugAndroidTest (BruciaMigrationTest) on the API 36 emulator.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // ZXing core: pure-Java QR encoder for the offline Wi-Fi QR generator in Utilities.
    implementation("com.google.zxing:core:3.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    // Instrumentation tests (emulator/device): ConnectionService lifecycle, Activity recreation.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Compose UI tests on the emulator (MITM row keys need real layout); the manifest artifact
    // declares the empty test Activity in the debug build only.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test 1.12.1 brings espresso-core 3.5.0, which calls InputManager.getInstance (gone on
    // API 36) when it waits for idle; 3.7.0 matches the androidx.test 1.7 line above.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room persistence
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    androidTestImplementation("androidx.room:room-testing:2.8.5")

    // Map
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // BouncyCastle: X.509 certificate generation for the on-phone MITM proxy
    // (root CA + per-host leaf certs). jdk18on build, used directly without
    // registering the provider globally to avoid clashing with Android's "BC".
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
}
