#!/usr/bin/env bash
# 下载离线转写依赖（sherpa-onnx 引擎 + Whisper tiny/base 模型 + kotlin-stdlib）
# 幂等：已存在的文件会跳过。需要 curl 和 tar。
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"
KOTLIN_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"
TINY_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2"

echo "==> 1/3 下载 sherpa-onnx 引擎 + kotlin-stdlib"
if [ ! -f "$DIR/libs/sherpa-onnx.jar" ]; then
  mkdir -p "$DIR/libs" "$DIR/jni/arm64-v8a" "$DIR/jni/armeabi-v7a"
  curl -sSL -o "$TMP/sherpa.aar" "$AAR_URL"
  unzip -q -o "$TMP/sherpa.aar" -d "$TMP/aar"
  cp "$TMP/aar/classes.jar" "$DIR/libs/sherpa-onnx.jar"
  cp "$TMP/aar/jni/arm64-v8a/libsherpa-onnx-jni.so" "$DIR/jni/arm64-v8a/"
  cp "$TMP/aar/jni/armeabi-v7a/libsherpa-onnx-jni.so" "$DIR/jni/armeabi-v7a/"
fi
if [ ! -f "$DIR/libs/kotlin-stdlib.jar" ]; then
  mkdir -p "$DIR/libs"
  curl -sSL -o "$DIR/libs/kotlin-stdlib.jar" "$KOTLIN_URL"
fi

echo "==> 2/3 下载 Whisper tiny 模型"
if [ ! -f "$DIR/assets/models/whisper-tiny/encoder.int8.onnx" ]; then
  mkdir -p "$DIR/assets/models/whisper-tiny"
  curl -sSL -o "$TMP/tiny.tar.bz2" "$TINY_URL"
  tar -xjf "$TMP/tiny.tar.bz2" -C "$TMP"
  cp "$TMP/sherpa-onnx-whisper-tiny.en/tiny.en-encoder.int8.onnx" "$DIR/assets/models/whisper-tiny/encoder.int8.onnx"
  cp "$TMP/sherpa-onnx-whisper-tiny.en/tiny.en-decoder.int8.onnx" "$DIR/assets/models/whisper-tiny/decoder.int8.onnx"
  cp "$TMP/sherpa-onnx-whisper-tiny.en/tiny.en-tokens.txt" "$DIR/assets/models/whisper-tiny/tokens.txt"
fi

echo "==> 3/4 下载 Whisper base 模型"
if [ ! -f "$DIR/assets/models/whisper-base/encoder.int8.onnx" ]; then
  mkdir -p "$DIR/assets/models/whisper-base"
  curl -sSL -o "$TMP/base.tar.bz2" "$BASE_URL"
  tar -xjf "$TMP/base.tar.bz2" -C "$TMP"
  cp "$TMP/sherpa-onnx-whisper-base.en/base.en-encoder.int8.onnx" "$DIR/assets/models/whisper-base/encoder.int8.onnx"
  cp "$TMP/sherpa-onnx-whisper-base.en/base.en-decoder.int8.onnx" "$DIR/assets/models/whisper-base/decoder.int8.onnx"
  cp "$TMP/sherpa-onnx-whisper-base.en/base.en-tokens.txt" "$DIR/assets/models/whisper-base/tokens.txt"
fi

echo "==> 4/4 下载 Silero VAD 模型（智能断句）"
if [ ! -f "$DIR/assets/models/silero_vad.onnx" ]; then
  mkdir -p "$DIR/assets/models"
  curl -sSL -o "$DIR/assets/models/silero_vad.onnx" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
fi

echo "==> 依赖准备完成"
