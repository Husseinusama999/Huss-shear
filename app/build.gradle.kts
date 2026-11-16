plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
  namespace = "com.huss.shear"; compileSdk = 34
  defaultConfig { applicationId = "com.huss.shear"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0"; ndk { abiFilters += "arm64-v8a" } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
  implementation("com.google.zxing:core:3.5.3")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
  implementation("io.github.junkfood02.youtubedl-android:library:0.16.0")
  implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.16.0")
}
