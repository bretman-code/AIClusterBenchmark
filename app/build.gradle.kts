plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.example.aiclusterbenchmark"
 compileSdk = 35
 buildToolsVersion = "35.0.0"
 ndkVersion = "27.2.12479018"
 defaultConfig {
  applicationId = "com.example.aiclusterbenchmark"; minSdk = 27; targetSdk = 34; versionCode = 3; versionName = "0.3"
  ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
  externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared") } }
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
