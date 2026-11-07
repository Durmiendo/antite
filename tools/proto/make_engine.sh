#!/bin/bash
# Builds an engine directory from the GeckoView AAR and pushes it to the device.
# Usage: tools/proto/make_engine.sh SERIAL [ABI]
set -euo pipefail
SERIAL=$1
ABI=${2:-x86_64}
SDK=${ANDROID_HOME:-$HOME/android-tools/sdk}
JAVA_HOME=${JAVA_HOME:-$HOME/android-tools/jdk}
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
AAR=$(find ~/.gradle/caches/modules-2 -name "geckoview-159*.aar" | head -1)
OUT=build/proto-engine
rm -rf "$OUT"
mkdir -p "$OUT/aar" "$OUT/engine/lib"
unzip -q "$AAR" classes.jar "jni/$ABI/*" assets/omni.ja -d "$OUT/aar"
"$SDK/build-tools/36.0.0/d8" --release --min-api 30 --lib "$SDK/platforms/android-36/android.jar" \
    --output "$OUT/engine" "$OUT/aar/classes.jar"
cp "$OUT/aar/jni/$ABI/"*.so "$OUT/engine/lib/"
(cd "$OUT/aar" && zip -q -0 -r ../engine/gecko.zip assets/omni.ja)
ls -la "$OUT/engine" "$OUT/engine/lib" | head -30
adb -s "$SERIAL" shell rm -rf /data/local/tmp/antite-engine
adb -s "$SERIAL" push "$OUT/engine" /data/local/tmp/antite-engine >/dev/null
adb -s "$SERIAL" shell chmod -R a+rX /data/local/tmp/antite-engine
echo "pushed to /data/local/tmp/antite-engine"
