# NearCall V2

Offline nearby communication prototype for Android.

## GitHub upload structure

Upload the CONTENTS of this folder to the ROOT of your GitHub repository. The repository root must look like:

```text
.github/workflows/android.yml
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/nearcall/v2/MainActivity.kt
app/src/main/res/values/strings.xml
app/src/main/res/values/styles.xml
build.gradle.kts
gradle.properties
settings.gradle.kts
.gitignore
README.md
```

Do NOT upload only the `app` folder, and do NOT put everything inside an extra `NearCallV2_fixed` folder in the repository.

## Build APK on GitHub

1. Open the repository on GitHub.
2. Go to **Actions**.
3. Select **Build NearCall V2 APK**.
4. Click **Run workflow**.
5. When it finishes, open the completed workflow run.
6. Download the artifact named **NearCallV2-debug-apk**.
7. Inside the downloaded artifact is `app-debug.apk`.

The workflow installs Gradle 8.9 and JDK 17, so a Gradle wrapper JAR is not required for the GitHub build.

## Important

This is a prototype. Nearby Connections can use available Bluetooth/Wi-Fi transports, but this code does not guarantee 1 km range or a production-grade 30-user mesh. Voice is a basic PCM prototype and needs further engineering/testing for reliable calls.
