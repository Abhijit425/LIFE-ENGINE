# Building LIFE ENGINE

## Requirements
- JDK 17
- Android SDK with platform 35 and build-tools (Android Studio Ladybug or newer installs these)
- No API keys, accounts or paid services

## Debug APK (local)
```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The debug build is signed with `keystore/debug.keystore` (a standard, public debug key committed on purpose so every
CI build can update the previous install without uninstalling). It is not a secret.

## Debug APK (CI)
Every push to `main` runs `.github/workflows/android.yml`:
1. `assembleDebug` and `testDebugUnitTest`
2. installs the APK on an Android 11 tablet emulator (Pixel C profile), launches it, performs camera gestures,
   captures screenshots + logcat, fails on crash
3. uploads the APK as a workflow artifact and to the `debug-latest` GitHub release
4. pushes screenshots/logs to the `ci-results` branch

## Release APK
Signing credentials are not stored in this repository. Create your own upload key once:
```bash
keytool -genkeypair -v -keystore lifeengine-release.jks -alias lifeengine \
  -keyalg RSA -keysize 4096 -validity 10000
```
Then create `keystore.properties` in the project root (git-ignored):
```properties
storeFile=lifeengine-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=lifeengine
keyPassword=YOUR_KEY_PASSWORD
```
and build:
```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
Without `keystore.properties`, `assembleRelease` produces an unsigned APK
(`app-release-unsigned.apk`) that Android will not install until it is signed.

Keep the `.jks` file and passwords safe: losing them means future updates cannot replace the installed app.

## Desktop preview (optional, for shader work)
The world generator is plain Java and the shaders are GLSL ES 3.00, so both run on a desktop:
```bash
javac -d tools/out app/src/main/java/com/abhijit/lifeengine/core/*.java tools/*.java
java -cp tools/out PreviewExport 2026 preview/out
pip install moderngl pillow numpy
python3 preview/render.py '[{"eye":[210,30,-244],"target":[270,25,-544],"hour":9.5,"out_png":"shot.png"}]'
```
This renders with the exact shader files shipped in the APK (Mesa/EGL headless works).
