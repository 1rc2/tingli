package com.tingli.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 本地离线 Whisper 转写引擎（基于 sherpa-onnx）。
 * 音频从 base64 传入，解码为 PCM 后交给 sherpa-onnx 转写。
 */
public class WhisperEngine {
    private OfflineRecognizer recognizer;
    private String loadedModel = "";

    private static class Pcm {
        float[] samples;
        int sampleRate;
    }

    /** 初始化或切换模型；modelDir 形如 "models/whisper-tiny"。 */
    public synchronized void init(Context ctx, String modelDir) throws Exception {
        if (modelDir.equals(loadedModel) && recognizer != null) return;
        release();
        AssetManager am = ctx.getAssets();

        OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
        whisper.setEncoder(modelDir + "/encoder.int8.onnx");
        whisper.setDecoder(modelDir + "/decoder.int8.onnx");
        whisper.setLanguage("en");
        whisper.setTask("transcribe");
        whisper.setTailPaddings(1000);

        OfflineModelConfig model = new OfflineModelConfig();
        model.setWhisper(whisper);
        int cores = Runtime.getRuntime().availableProcessors();
        model.setNumThreads(Math.max(1, Math.min(4, cores)));
        model.setDebug(false);
        model.setProvider("cpu");
        model.setTokens(modelDir + "/tokens.txt");

        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setModelConfig(model);
        cfg.setDecodingMethod("greedy_search");

        recognizer = new OfflineRecognizer(am, cfg);
        loadedModel = modelDir;
    }

    public synchronized void release() {
        if (recognizer != null) {
            try { recognizer.release(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        loadedModel = "";
    }

    /** 转写 base64 音频，返回 JSON：{ok, text, duration}。 */
    public String transcribe(Context ctx, String modelDir, String base64Audio) throws Exception {
        byte[] audio = Base64.decode(base64Audio, Base64.DEFAULT);
        File tmp = File.createTempFile("tingli_audio", ".bin", ctx.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(audio);
        }
        Pcm pcm;
        try {
            pcm = decode(tmp.getAbsolutePath());
        } finally {
            tmp.delete();
        }

        init(ctx, modelDir);

        OfflineStream stream = recognizer.createStream();
        String text;
        try {
            stream.acceptWaveform(pcm.samples, pcm.sampleRate);
            recognizer.decode(stream);
            OfflineRecognizerResult r = recognizer.getResult(stream);
            text = r.getText();
        } finally {
            stream.release();
        }

        double duration = pcm.samples.length / (double) pcm.sampleRate;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("text", text == null ? "" : text.trim());
        o.put("duration", duration);
        return o.toString();
    }

    /** 用 MediaExtractor + MediaCodec 把任意压缩音频解码成 16kHz 单声道 PCM float。 */
    private Pcm decode(String path) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(path);
        int trackIndex = -1;
        MediaFormat format = null;
        String mime = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) {
                trackIndex = i;
                format = f;
                mime = m;
                break;
            }
        }
        if (trackIndex < 0) {
            extractor.release();
            throw new Exception("未找到音频轨道");
        }
        extractor.selectTrack(trackIndex);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        int sampleRate = 16000;
        int channels = 1;
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    int sz = extractor.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sz, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outIndex >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                if (info.size > 0 && outBuf != null) {
                    byte[] chunk = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    outBuf.get(chunk);
                    pcm.write(chunk);
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat nf = codec.getOutputFormat();
                if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
        }
        codec.stop();
        codec.release();
        extractor.release();

        // 16bit PCM → 单声道 float（多声道下混取平均）
        byte[] bytes = pcm.toByteArray();
        ShortBuffer sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int total = sb.remaining();
        short[] all = new short[total];
        sb.get(all);
        if (channels < 1) channels = 1;
        int frames = total / channels;
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += all[i * channels + c];
            }
            samples[i] = (sum / (float) channels) / 32768.0f;
        }

        Pcm r = new Pcm();
        r.samples = samples;
        r.sampleRate = sampleRate;
        return r;
    }
}
