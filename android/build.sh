#!/usr/bin/env bash
# 手动构建 Android APK（不依赖 Gradle/Android Studio）
# 需要：JDK 17+、Android SDK（platforms;android-34 + build-tools;34.0.0）
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
if [ -z "$BT" ]; then echo "未找到 build-tools，请先 sdkmanager 安装"; exit 1; fi
BT="$SDK/build-tools/$BT"
PLATFORM="$SDK/platforms/android-34/android.jar"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/build"
PKG="com.tingli.app"
PKG_PATH="com/tingli/app"
# full = 双模型 + 双 ABI；lite = 仅 tiny + 仅 arm64（体积小约一半）
VARIANT="${VARIANT:-full}"
if [ "$VARIANT" = "lite" ]; then
  OUT_APK="$DIR/tingli-lite.apk"
else
  OUT_APK="$DIR/tingli.apk"
fi

# 检查离线转写依赖，缺失则自动下载
if [ ! -f "$DIR/libs/sherpa-onnx.jar" ] || [ ! -f "$DIR/libs/kotlin-stdlib.jar" ] \
   || [ ! -d "$DIR/jni/arm64-v8a" ] || [ ! -d "$DIR/assets/models/whisper-tiny" ] \
   || [ ! -d "$DIR/assets/models/whisper-base" ]; then
  echo "缺少离线转写依赖，运行 deps.sh 下载..."
  bash "$DIR/deps.sh"
fi

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
  -0 onnx \
  --auto-add-overlay

echo "==> 3/6 编译 Java"
javac -source 8 -target 8 \
  -classpath "$PLATFORM:$DIR/libs/sherpa-onnx.jar:$DIR/libs/kotlin-stdlib.jar" \
  -d "$OUT/obj" \
  "$OUT/gen/$PKG_PATH/R.java" \
  "$DIR/src/$PKG_PATH/MainActivity.java" \
  "$DIR/src/$PKG_PATH/TranscribeService.java" \
  "$DIR/src/$PKG_PATH/WhisperEngine.java"

echo "==> 4/6 dex 化"
"$BT/d8" --lib "$PLATFORM" --output "$OUT/apk" \
  $(find "$OUT/obj" -name '*.class') \
  "$DIR/libs/sherpa-onnx.jar" \
  "$DIR/libs/kotlin-stdlib.jar"

echo "==> 5/6 打包 classes.dex + native 库 + 对齐（变体：$VARIANT）"
cp "$OUT/app.unsigned.apk" "$OUT/app.dexed.apk"
mkdir -p "$OUT/apk/lib/arm64-v8a"
cp "$DIR/jni/arm64-v8a/libsherpa-onnx-jni.so" "$OUT/apk/lib/arm64-v8a/"
if [ "$VARIANT" = "lite" ]; then
  # 精简版：仅 arm64 + 仅 tiny 模型
  (cd "$OUT/apk" && zip -q -X "$OUT/app.dexed.apk" classes.dex \
    "lib/arm64-v8a/libsherpa-onnx-jni.so")
  (cd "$OUT/apk" && zip -q -d "$OUT/app.dexed.apk" "assets/models/whisper-base/*")
else
  mkdir -p "$OUT/apk/lib/armeabi-v7a"
  cp "$DIR/jni/armeabi-v7a/libsherpa-onnx-jni.so" "$OUT/apk/lib/armeabi-v7a/"
  (cd "$OUT/apk" && zip -q -X "$OUT/app.dexed.apk" classes.dex \
    "lib/arm64-v8a/libsherpa-onnx-jni.so" \
    "lib/armeabi-v7a/libsherpa-onnx-jni.so")
fi
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
  --out "$OUT_APK" "$OUT/app.aligned.apk"

"$BT/apksigner" verify --verbose "$OUT_APK"
echo "==> 完成：$OUT_APK"
