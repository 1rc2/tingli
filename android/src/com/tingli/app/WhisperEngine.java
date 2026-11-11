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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本地离线 Whisper 转写引擎（基于 sherpa-onnx）。
 *
 * 内存策略（避免大音频 OOM 闪退）：
 *   1. 音频分块从 JS 传入，写入磁盘临时文件，不整段驻留内存；
 *   2. 解码时流式重采样到 16kHz 单声道，直接写 float 临时文件，无中间 byte[]/short[] 拷贝；
 *   3. 转写按 30 秒分段读取，每段约 1.9MB，峰值内存恒定。
 */
public class WhisperEngine {
    private static final int TARGET_RATE = 16000;
    private static final int SEG_SECONDS = 30;
    private static final int MAX_SECONDS = 3600; // 单次转写上限 1 小时
    private static final String VAD_ASSET = "models/silero_vad.onnx";

    private OfflineRecognizer recognizer;
    private String loadedModel = "";
    private volatile boolean cancelled = false;

    /** 请求中断当前转写。 */
    public void cancel() {
        cancelled = true;
    }

    // 音频收集状态
    private File audioFile;
    private FileOutputStream audioOut;

    /** 开始接收音频分块。 */
    public synchronized void beginAudio(Context ctx) throws IOException {
        closeAudio();
        audioFile = new File(ctx.getCacheDir(), "tingli_src_" + System.currentTimeMillis() + ".bin");
        audioOut = new FileOutputStream(audioFile);
    }

    /** 追加一块 base64 音频数据（块很小，解码后立即写盘释放）。 */
    public synchronized void appendAudioChunk(String b64) throws IOException {
        if (audioOut == null) throw new IOException("音频接收未初始化");
        byte[] chunk = Base64.decode(b64, Base64.DEFAULT);
        audioOut.write(chunk);
    }

    public synchronized File finishAudio() throws IOException {
        if (audioOut != null) {
            audioOut.flush();
            audioOut.close();
            audioOut = null;
        }
        return audioFile;
    }

    public synchronized void closeAudio() {
        if (audioOut != null) {
            try { audioOut.close(); } catch (IOException ignored) {}
            audioOut = null;
        }
        if (audioFile != null && audioFile.exists()) audioFile.delete();
        audioFile = null;
    }

    /** 初始化或切换模型；modelDir 形如 "models/whisper-tiny"。 */
    public synchronized void init(Context ctx, String modelDir) {
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

    public interface Progress {
        void onProgress(int done, int total);
    }

    /**
     * 解码临时音频文件并分段转写。
     * 返回 JSON：{ok, text, duration}。
     */
    public String transcribe(Context ctx, String modelDir, String modelTag, File src, Progress progress) throws Exception {
        cancelled = false;
        File pcmFile = new File(ctx.getCacheDir(), "tingli_pcm_" + System.currentTimeMillis() + ".f32");
        double duration;
        try {
            duration = decodeToPcmFile(src, pcmFile);
        } finally {
            if (src != null && src.exists()) src.delete();
        }
        try {
            init(ctx, modelDir);
            JSONArray segments = new JSONArray();
            StringBuilder allText = new StringBuilder();
            boolean usedVad = false;

            // 优先用 VAD 按语音段切分：不切断单词，且段起止时间即真实时间戳
            if (hasAsset(ctx, VAD_ASSET)) {
                try {
                    recognizeWithVad(ctx, pcmFile, segments, allText, progress);
                    usedVad = segments.length() > 0;
                } catch (Throwable e) {
                    if (cancelled) throw new Exception("已取消");
                    segments = new JSONArray();
                    allText.setLength(0);
                    usedVad = false;
                }
            }
            // 回退：无 VAD 模型时按 30 秒固定分段
            if (!usedVad) {
                recognizeFixed(pcmFile, segments, allText, progress);
            }

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", allText.toString().trim());
            o.put("duration", duration);
            o.put("model", modelTag == null ? "" : modelTag);
            o.put("segments", segments);
            o.put("vad", usedVad);
            return o.toString();
        } finally {
            if (pcmFile.exists()) pcmFile.delete();
        }
    }

    /** 用 Silero VAD 流式检测语音段，逐段转写并累计真实时间戳。内存恒定。 */
    private void recognizeWithVad(Context ctx, File pcmFile, JSONArray segments,
                                  StringBuilder allText, Progress progress) throws Exception {
        SileroVadModelConfig silero = new SileroVadModelConfig(
                VAD_ASSET, 0.5f, 0.25f, 0.25f, 512, 20f);
        TenVadModelConfig ten = new TenVadModelConfig("", 0.5f, 0.25f, 0.25f, 512, 20f);
        VadModelConfig cfg = new VadModelConfig(silero, ten, TARGET_RATE, 1, "cpu", false);

        Vad vad = new Vad(ctx.getAssets(), cfg);
        long totalFrames = pcmFile.length() / 4;
        long readFrames = 0;

        try (DataInputStream din = openPcm(pcmFile)) {
            final int WIN = 512;
            float[] window = new float[WIN];
            while (true) {
                int n = 0;
                for (int i = 0; i < WIN; i++) {
                    try {
                        window[i] = din.readFloat();
                        n++;
                    } catch (IOException eof) {
                        break;
                    }
                }
                if (n == 0) break;
                if (cancelled) throw new InterruptedException("已取消");
                float[] chunk = (n == WIN) ? window : Arrays.copyOf(window, n);
                vad.acceptWaveform(chunk);
                readFrames += n;
                drainVad(vad, segments, allText);
                if (progress != null && totalFrames > 0) {
                    progress.onProgress((int) Math.min(100, readFrames * 100 / totalFrames), 100);
                }
            }
            vad.flush();
            drainVad(vad, segments, allText);
        } finally {
            try { vad.release(); } catch (Throwable ignored) {}
        }
    }

    /** 取出 VAD 已就绪的语音段并转写。 */
    private void drainVad(Vad vad, JSONArray segments, StringBuilder allText) {
        while (!vad.empty()) {
            SpeechSegment seg = vad.front();
            vad.pop();
            float[] samples = seg.getSamples();
            if (samples == null || samples.length == 0) continue;

            String text = recognizeSamples(samples);
            if (text == null) continue;
            text = text.trim();
            if (text.isEmpty()) continue;

            double startSec = seg.getStart() / (double) TARGET_RATE;
            double endSec = startSec + samples.length / (double) TARGET_RATE;
            appendSentences(segments, allText, text, startSec, endSec);
        }
    }

    /** 单个语音段转写（可返回 null 表示无结果）。 */
    private String recognizeSamples(float[] samples) {
        OfflineStream stream = null;
        try {
            stream = recognizer.createStream();
            stream.acceptWaveform(samples, TARGET_RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult r = recognizer.getResult(stream);
            return r == null ? null : r.getText();
        } catch (Throwable e) {
            return null;
        } finally {
            if (stream != null) {
                try { stream.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 无 VAD 时的回退：按 30 秒固定分段转写。 */
    private void recognizeFixed(File pcmFile, JSONArray segments,
                                StringBuilder allText, Progress progress) throws IOException {
        long totalFrames = pcmFile.length() / 4;
        int segFrames = TARGET_RATE * SEG_SECONDS;
        int totalSegs = (int) Math.max(1, (totalFrames + segFrames - 1) / segFrames);

        try (DataInputStream din = openPcm(pcmFile)) {
            long remaining = totalFrames;
            int done = 0;
            while (remaining > 0) {
                int n = (int) Math.min(segFrames, remaining);
                float[] buf = new float[n];
                int filled = 0;
                for (int i = 0; i < n; i++) {
                    try {
                        buf[i] = din.readFloat();
                        filled++;
                    } catch (IOException eof) {
                        break;
                    }
                }
                if (filled <= 0) break;
                if (cancelled) throw new IOException("已取消");

                String text = recognizeSamples(filled == n ? buf : Arrays.copyOf(buf, filled));
                if (text != null) {
                    text = text.trim();
                    if (!text.isEmpty()) {
                        double startSec = (totalFrames - remaining) / (double) TARGET_RATE;
                        double endSec = startSec + filled / (double) TARGET_RATE;
                        appendSentences(segments, allText, text, startSec, endSec);
                    }
                }

                remaining -= filled;
                done++;
                if (progress != null) progress.onProgress(done, totalSegs);
            }
        }
    }

    /** 按句末标点把一段文本切成句子，段内按字符比例分配起止时间。 */
    private void appendSentences(JSONArray segments, StringBuilder allText,
                                 String text, double startSec, double endSec) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            buf.append(ch);
            if (ch == '.' || ch == '!' || ch == '?') {
                String t = buf.toString().trim();
                if (!t.isEmpty()) parts.add(t);
                buf.setLength(0);
            }
        }
        String rest = buf.toString().trim();
        if (!rest.isEmpty()) parts.add(rest);
        if (parts.isEmpty()) return;

        int totalChars = 0;
        for (String p : parts) totalChars += p.length();
        if (totalChars <= 0) totalChars = 1;

        double span = Math.max(0.1, endSec - startSec);
        double acc = 0;
        for (String p : parts) {
            double s = startSec + acc / totalChars * span;
            acc += p.length();
            double e = startSec + acc / totalChars * span;
            try {
                JSONObject o = new JSONObject();
                o.put("start", Math.round(s * 1000) / 1000.0);
                o.put("end", Math.round(e * 1000) / 1000.0);
                o.put("text", p);
                segments.put(o);
            } catch (Exception ignored) {}
            if (allText.length() > 0) allText.append(' ');
            allText.append(p);
        }
    }

    private DataInputStream openPcm(File pcmFile) throws IOException {
        return new DataInputStream(new java.io.BufferedInputStream(
                new java.io.FileInputStream(pcmFile), 1 << 16));
    }

    private boolean hasAsset(Context ctx, String path) {
        InputStream is = null;
        try {
            is = ctx.getAssets().open(path);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * MediaCodec 解码 + 流式重采样（线性插值）到 16kHz 单声道，
     * 逐块写入 float 临时文件，避免整段驻留内存。
     * 返回音频时长（秒）。
     */
    private double decodeToPcmFile(File src, File pcmFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        DataOutputStream out = null;
        try {
            extractor.setDataSource(src.getAbsolutePath());
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
            if (trackIndex < 0) throw new IOException("未找到音频轨道（文件可能不是音频或已损坏）");
            extractor.selectTrack(trackIndex);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int srcRate = 44100;
            int channels = 2;
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
            if (channels < 1) channels = 1;
            if (srcRate <= 0) srcRate = 44100;

            out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(pcmFile), 1 << 16));

            // 重采样状态
            final double ratio = srcRate / (double) TARGET_RATE;
            double pos = 0;          // 块内浮点索引
            float prevLast = 0f;     // 上一块最后样本（跨块插值用）
            boolean hasPrev = false;
            long writtenFrames = 0;

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
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            outBuf.order(ByteOrder.LITTLE_ENDIAN);
                            int shortCount = info.size / 2;
                            short[] pcm16 = new short[shortCount];
                            outBuf.asShortBuffer().get(pcm16);

                            int frames = shortCount / channels;
                            float[] mono = new float[frames];
                            for (int i = 0; i < frames; i++) {
                                int sum = 0;
                                int base = i * channels;
                                for (int c = 0; c < channels; c++) sum += pcm16[base + c];
                                mono[i] = (sum / (float) channels) / 32768.0f;
                            }

                            // 线性插值重采样
                            while (true) {
                                int k = (int) Math.floor(pos);
                                if (k >= frames - 1) break;
                                float s0;
                                float s1;
                                if (k < 0) {
                                    s0 = hasPrev ? prevLast : (frames > 0 ? mono[0] : 0f);
                                    s1 = frames > 0 ? mono[0] : 0f;
                                } else {
                                    s0 = mono[k];
                                    s1 = mono[k + 1];
                                }
                                float frac = (float) (pos - k);
                                out.writeFloat(s0 + (s1 - s0) * frac);
                                writtenFrames++;
                                pos += ratio;
                            }
                            pos -= frames;
                            if (frames > 0) {
                                prevLast = mono[frames - 1];
                                hasPrev = true;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        pos = 0; // 后面不再有数据
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            && nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) != srcRate) {
                        // 极少数编码器输出采样率变化，简单起见按新值重算比例不现实，保持原比例
                        srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = Math.max(1, nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                }
            }

            out.flush();
            if (writtenFrames <= 0) throw new IOException("音频解码失败或文件为空");
            if (writtenFrames > (long) TARGET_RATE * MAX_SECONDS) {
                throw new IOException("音频超过 1 小时，请分段后再转写");
            }
            return writtenFrames / (double) TARGET_RATE;
        } finally {
            if (out != null) { try { out.close(); } catch (IOException ignored) {} }
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            extractor.release();
        }
    }
}
