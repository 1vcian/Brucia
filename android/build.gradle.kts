// AGP 9 compiles Kotlin itself (built-in Kotlin, no org.jetbrains.kotlin.android plugin); the
// Compose compiler plugin below puts KGP 2.4.21 on the classpath, which AGP then uses.
// Paparazzi 2.0.0-alpha05.1: the only line that renders on compileSdk 36+/AGP 9 (1.3.5 crashes on
// the API 36+ android.jar); it is test-only and does not ship in the APK.
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("app.cash.paparazzi") version "2.0.0-alpha05.1" apply false
}
