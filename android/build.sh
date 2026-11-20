#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-35/android.jar"
java="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
build="${BUILD_DIR:-$PWD/build}"
mkdir -p "$build/classes" "$build/dex"
"$tools/aapt2" compile --dir res -o "$build/res.zip"
"$tools/aapt2" link "$build/res.zip" -I "$platform" --manifest AndroidManifest.xml -o "$build/resources.apk"
find src -name '*.java' -print0 | xargs -0 "$java/bin/javac" --release 8 -cp "$platform" -d "$build/classes"
"$java/bin/jar" cf "$build/classes.jar" -C "$build/classes" .
JAVA_HOME="$java" "$tools/d8" --lib "$platform" --min-api 26 --output "$build/dex" "$build/classes.jar"
cp "$build/resources.apk" "$build/unsigned.apk"
(cd "$build/dex" && zip -q -j -u "$build/unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if [[ ! -f "$build/local-test.p12" ]]; then
    "$java/bin/keytool" -genkeypair -alias passport-local -storetype PKCS12 -keystore "$build/local-test.p12" \
        -storepass local-passport -keypass local-passport -dname "CN=Passport BB Local Build" \
        -keyalg RSA -keysize 2048 -validity 3650
fi
JAVA_HOME="$java" "$tools/apksigner" sign --ks "$build/local-test.p12" --ks-pass pass:local-passport \
    --out "$build/AI-Passport-Companion-v1.4.6.apk" "$build/aligned.apk"
JAVA_HOME="$java" "$tools/apksigner" verify --verbose "$build/AI-Passport-Companion-v1.4.6.apk"
