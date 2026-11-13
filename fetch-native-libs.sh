#!/bin/sh
# The upstream repo's .gitignore excludes the prebuilt native libraries (jniLibs/) and the
# QEMU firmware (assets/roms, QEMU_VERSION), so they are not in the source tree.
# This downloads the official Limbo 6.0.1 x86 APK and extracts them from it.
set -e
URL=https://github.com/limboemu/limbo/releases/download/v6.0.1-LimboEmulator/limbo-android-x86-6.0.1-qemu-5.1.0.apk
TMP=$(mktemp -d)
curl -L --fail -o "$TMP/limbo.apk" "$URL"

# native libraries (QEMU, SDL, glib, ...)
mkdir -p limbo-android-x86/src/main/jniLibs
unzip -q -o "$TMP/limbo.apk" 'lib/*' -d "$TMP/x"
cp -r "$TMP"/x/lib/* limbo-android-x86/src/main/jniLibs/

# firmware/BIOS files, keymaps and the QEMU version marker
mkdir -p limbo-android-lib/src/main/assets
unzip -q -o "$TMP/limbo.apk" 'assets/roms/*' 'assets/QEMU_VERSION' -d "$TMP/a"
cp -r "$TMP"/a/assets/roms limbo-android-lib/src/main/assets/
cp "$TMP"/a/assets/QEMU_VERSION limbo-android-lib/src/main/assets/

rm -rf "$TMP"
echo "Native libs and firmware ready."
