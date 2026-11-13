import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.example.aiclusterbenchmark"
 compileSdk = 35
 buildToolsVersion = "35.0.0"
 ndkVersion = "27.2.12479018"
 androidResources { noCompress += "gguf" }
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

// The release APK includes weights; source control keeps only the reproducible download recipe.
val prepareBundledModel by tasks.registering {
 doLast {
  val target=file("src/main/assets/models/smollm2-135m-q4_k_m.gguf")
  val expected="2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d"
  fun sha(file:File):String {
   val digest=MessageDigest.getInstance("SHA-256")
   file.inputStream().use { input -> val buffer=ByteArray(128*1024); while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) } }
   return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  }
  if(!target.exists()) {
   target.parentFile.mkdirs(); val temp=File(target.parentFile,"download.tmp")
   try {
    URI("https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf?download=true").toURL().openConnection().apply { connectTimeout=30000; readTimeout=60000 }.getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
    check(sha(temp)==expected) { "Bundled model SHA-256 mismatch" }
    Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
   } finally { temp.delete() }
  }
  check(sha(target)==expected) { "Bundled model SHA-256 mismatch" }
 }
}
tasks.named("preBuild") { dependsOn(prepareBundledModel) }
