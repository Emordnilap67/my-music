#!/usr/bin/env bash
# Builds MY MUSIC's APK on GitHub (or any Linux PC with the Android SDK).
#   bash tools/build-ci.sh            -> out/MyMusic-<version>.apk
# Signing: KEYSTORE_B64 (base64 of a PKCS12 keystore, key alias "release")
# and KEYSTORE_PASS from the repository's secrets. Without them a throwaway
# key is made, and the APK says so in its file name.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT=""
for d in $(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V -r); do
  if [ -x "$d/aapt2" ] && [ -x "$d/d8" ] && [ -x "$d/apksigner" ] && [ -x "$d/zipalign" ]; then BT="$d"; break; fi
done
[ -n "$BT" ] || { echo "No Android build-tools found in $SDK"; exit 1; }
PLAT=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | grep -E '/android-[0-9]+$' | sort -V | tail -1)
AJ="$PLAT/android.jar"
[ -f "$AJ" ] || { echo "No android.jar found in $SDK/platforms"; exit 1; }
echo "build-tools: $BT"
echo "platform:    $PLAT"

VC=$(sed -n 1p VERSION | tr -d '[:space:]')
VN=$(sed -n 2p VERSION | tr -d '[:space:]')
echo "MY MUSIC $VN (code $VC)"

B=build ; OUT=out
rm -rf "$B" "$OUT" ; mkdir -p "$B/classes" "$B/lib" "$OUT"

fetch() {   # url file min-bytes
  curl -fsSL --retry 3 --max-time 900 -o "$2" "$1"
  [ "$(stat -c %s "$2")" -ge "$3" ] || { echo "Download too small: $1"; exit 1; }
}

# 1. fonts (SIL Open Font License, from github.com/google/fonts)
mkdir -p assets/fonts
fetch "https://github.com/google/fonts/raw/main/ofl/cinzel/Cinzel%5Bwght%5D.ttf" assets/fonts/Cinzel.ttf 10000
fetch "https://github.com/google/fonts/raw/main/ofl/outfit/Outfit%5Bwght%5D.ttf" assets/fonts/Outfit.ttf 10000

# 2. the built-in downloader: Python 3.11 + ffmpeg for Android phones from the
#    youtubedl-android project (GPL-3.0, pinned), and the newest yt-dlp
REPO="https://github.com/JunkFood02/youtubedl-android/raw/d7c6ea110e4c6191fe1958402a62e69fdaee6674"
fetch "$REPO/library/src/main/jniLibs/arm64-v8a/libpython.so"     "$B/lib/libpython.so"      4000
fetch "$REPO/library/src/main/jniLibs/arm64-v8a/libpython.zip.so" "$B/lib/libpython.zip.so"  10000000
fetch "$REPO/ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.so"      "$B/lib/libffmpeg.so"      200000
fetch "$REPO/ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so"  "$B/lib/libffmpeg.zip.so"  15000000
fetch "$REPO/ffmpeg/src/main/jniLibs/arm64-v8a/libffprobe.so"     "$B/lib/libffprobe.so"     200000
fetch "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp" assets/yt-dlp 1000000

# 3. resources, manifest and the page
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VN" \
  -A assets "$B/res.zip"

# 4. Java (Java 8 language level, like on the phone)
javac -source 8 -target 8 -bootclasspath "$AJ" -Xlint:-options -nowarn -encoding UTF-8 \
  -d "$B/classes" $(find src -name '*.java')
( cd "$B/classes" && jar cf ../classes.jar . )

# 5. Android code
"$BT/d8" --release --min-api 26 --lib "$AJ" --output "$B" "$B/classes.jar"
[ -f "$B/classes.dex" ] || { echo "d8 made no classes.dex"; exit 1; }

# 6. pack, align, sign
python3 tools/pack.py "$B/base.apk" "$B/classes.dex" "$B/unsigned.apk" "$B/lib"
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
NAME="MyMusic-$VN"
if [ -n "${KEYSTORE_B64:-}" ] && [ -n "${KEYSTORE_PASS:-}" ]; then
  echo "$KEYSTORE_B64" | base64 -d > "$B/release.keystore"
  KS="$B/release.keystore" ; PASS="$KEYSTORE_PASS"
else
  echo "No signing secrets: using a throwaway key (updates of this APK need an uninstall first)"
  KS="$B/temp.keystore" ; PASS="temporary"
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" -alias release \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=MY MUSIC test build" >/dev/null
  NAME="$NAME-test-signed"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --ks-key-alias release \
  --out "$OUT/$NAME.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT/$NAME.apk"
rm -f "$B/release.keystore"
ls -la "$OUT"
echo "apk=$OUT/$NAME.apk" >> "${GITHUB_OUTPUT:-/dev/null}"
