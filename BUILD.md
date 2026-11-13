# Building this Limbo fork (live shared folder)

Based on limboemu/limbo v6.0.1. The shared folder feature is pure Java/resources, so the native
QEMU/SDL libraries are taken from the official 6.0.1 release APK (already in
`limbo-android-x86/src/main/jniLibs`; `./fetch-native-libs.sh` re-downloads them).

## Option A: Android Studio (easiest)
1. Install Android Studio and open this folder.
2. SDK Manager: install Android SDK Platform 29 and Build-Tools 29.0.3.
3. Use JDK 11 (File > Settings > Build > Gradle > Gradle JDK). JDK 17+ fails with this old AGP.
4. Gradle JDK/wrapper: if prompted, use Gradle 7.0.2.
5. Select the `limbo-android-x86` run configuration, then Build > Build APK(s).
   Output: `limbo-android-x86/build/outputs/apk/debug/limbo-android-x86-debug.apk`

## Option B: command line
    # needs JDK 11, Android SDK (ANDROID_HOME) with platform 29 + build-tools 29.0.3, Gradle 7.0.2
    gradle :limbo-android-x86:assembleDebug

## Option C: GitHub Actions (no local setup)
Push this folder to a GitHub repo, open Actions > "Build APK" > Run workflow, then download the
`limbo-shared-folder-apk` artifact.

## Install notes
- A debug build has a different signature from the official APK, so uninstall the official Limbo
  first (or it will refuse to update). Back up your machines/disks before uninstalling.
- Usage of the shared folder: see SHARED_FOLDER.md.
