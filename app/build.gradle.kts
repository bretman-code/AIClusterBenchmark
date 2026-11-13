plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.example.aiclusterbenchmark"
 compileSdk = 35
 buildToolsVersion = "35.0.0"
 defaultConfig { applicationId = "com.example.aiclusterbenchmark"; minSdk = 27; targetSdk = 34; versionCode = 1; versionName = "0.1" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
