#!/bin/sh
# Downloads the official Limbo 6.0.1 x86 APK and extracts its prebuilt QEMU/SDL libraries
# into the Gradle jniLibs folder. Only needed if limbo-android-x86/src/main/jniLibs is empty.
set -e
URL=https://github.com/limboemu/limbo/releases/download/v6.0.1-LimboEmulator/limbo-android-x86-6.0.1-qemu-5.1.0.apk
TMP=$(mktemp -d)
curl -L -o "$TMP/limbo.apk" "$URL"
mkdir -p limbo-android-x86/src/main/jniLibs
unzip -q -o "$TMP/limbo.apk" 'lib/*' -d "$TMP/x"
cp -r "$TMP"/x/lib/* limbo-android-x86/src/main/jniLibs/
rm -rf "$TMP"
echo "Native libs ready."
