#!/usr/bin/env bash
# 手动构建 Android APK（不依赖 Gradle/Android Studio）
# 需要：JDK 17+、Android SDK（platforms;android-34 + build-tools;34.0.0）
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-34/android.jar"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/build"
PKG="com.tingli.app"
PKG_PATH="com/tingli/app"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/apk"

echo "==> 1/6 编译资源"
"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/res.flata"

echo "==> 2/6 链接资源 + 生成 R.java（含 assets）"
"$BT/aapt2" link \
  -o "$OUT/app.unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$DIR/AndroidManifest.xml" \
  -R "$OUT/res.flata" \
  --java "$OUT/gen" \
  -A "$DIR/assets" \
  --auto-add-overlay

echo "==> 3/6 编译 Java"
javac -source 8 -target 8 -classpath "$PLATFORM" \
  -d "$OUT/obj" \
  "$OUT/gen/$PKG_PATH/R.java" \
  "$DIR/src/$PKG_PATH/MainActivity.java"

echo "==> 4/6 dex 化"
"$BT/d8" --lib "$PLATFORM" --output "$OUT/apk" \
  $(find "$OUT/obj" -name '*.class')

echo "==> 5/6 打包 classes.dex + 对齐"
cp "$OUT/app.unsigned.apk" "$OUT/app.dexed.apk"
(cd "$OUT/apk" && zip -q -X "$OUT/app.dexed.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/app.dexed.apk" "$OUT/app.aligned.apk"

echo "==> 6/6 签名"
if [ ! -f "$DIR/debug.keystore" ]; then
  keytool -genkeypair -v \
    -keystore "$DIR/debug.keystore" \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign \
  --ks "$DIR/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$DIR/tingli.apk" "$OUT/app.aligned.apk"

"$BT/apksigner" verify --verbose "$DIR/tingli.apk"
echo "==> 完成：$DIR/tingli.apk"
