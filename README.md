# AI Cluster Benchmark v0.1 (prototype)

Android 8.1+ (API 27). Kotlin app with CPU math, RAM copy, short repeatability test and JSON export. No internet/root required.

## Build
Open in Android Studio (JDK 17, Android SDK 35, Build Tools 35.0.0), sync and select **Build > Build APK(s)**. Or upload the project files to a GitHub repository and run **Actions > Build Android APK > Run workflow**; download the resulting artifact. The workflow pins Gradle 8.9 for AGP 8.7.3. Minimum API is 27; target API is 34. This project contains no native libraries and is not restricted to a specific CPU ABI.

On this computer, run `../build-apk.ps1` from PowerShell using the downloaded tools in the parent `.tools` folder. It runs `assembleDebug lintDebug`.

## Validation (2026-11-13)

Debug APK built successfully; Android Lint: 0 errors, 10 warnings (target API and localization). APK v2 signature and ZIP alignment verified. No phones connected; runtime behavior on HONOR Play and POCO C31 is not yet verified. See TESTING-RU.md for installation and comparison steps.

JSON export uses the Android document picker. Completed reports survive activity recreation; interrupted runs stop when the activity is destroyed.

## Caveats
No llama.cpp/LLM inference yet. No real temperature sensor, long thermal test, Wi-Fi throughput or cluster test yet. CPU benchmark uses one thread and measures elapsed time; RAM copy throughput is affected by cache and is not theoretical DRAM bandwidth (arrays are allocated before timing). Compare devices only under similar conditions.
