package com.tingli.app;

import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends Activity {

    private static final String LOCAL_ORIGIN = "https://app.local/";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private WhisperEngine whisperEngine = new WhisperEngine();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // 让 localStorage 生效（保存 API Key）
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 允许波形/录音播放不强制手势
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 预授权麦克风（录音功能），targetSdk>=23 需运行时申请
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 200);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 把打包在 assets 里的 index.html 用一个伪 https 源提供，
                // 这样页面里的动态 import()/fetch 指向 CDN 时不会因 file:// 源被拦截。
                String url = request.getUrl().toString();
                if (url.equals(LOCAL_ORIGIN) || url.equals(LOCAL_ORIGIN + "index.html")) {
                    try {
                        InputStream is = getAssets().open("index.html");
                        // 加 COOP/COEP 头启用 crossOriginIsolated → 让页面可用 SharedArrayBuffer，
                        // 从而 Whisper 走多线程 WASM，转写提速（WebView 需 Chrome 92+）。
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Cross-Origin-Opener-Policy", "same-origin");
                        headers.put("Cross-Origin-Embedder-Policy", "require-corp");
                        return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, is);
                    } catch (IOException e) {
                        return null;
                    }
                }
                // 本地库文件：https://app.local/library/<name> → 直接读应用私有目录
                if (url.startsWith(LOCAL_ORIGIN + "library/")) {
                    try {
                        String name = java.net.URLDecoder.decode(
                                url.substring((LOCAL_ORIGIN + "library/").length()), "UTF-8");
                        if (name.contains("/") || name.contains("..")) return null;
                        File f = new File(new File(getFilesDir(), "library"), name);
                        if (!f.exists()) return null;
                        Map<String, String> h = new HashMap<>();
                        h.put("Access-Control-Allow-Origin", "*");
                        h.put("Accept-Ranges", "bytes");
                        String mime = name.toLowerCase().endsWith(".mp3") ? "audio/mpeg"
                                : name.toLowerCase().endsWith(".m4a") ? "audio/mp4"
                                : name.toLowerCase().endsWith(".wav") ? "audio/wav" : "audio/*";
                        InputStream is = new java.io.FileInputStream(f);
                        return new WebResourceResponse(mime, null, 200, "OK", h, is);
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null; // 其余请求走真实网络（CDN / 模型下载 / 豆包 API）
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 授予麦克风等权限，保证页面内录音（getUserMedia）可用
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, 100);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // 暴露原生存储接口：API Key 存 Android 侧，页面不回显明文，只能覆盖更换
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void saveApiKey(String key) {
                getSharedPreferences("tingli", MODE_PRIVATE).edit().putString("apiKey", key).apply();
            }
            @android.webkit.JavascriptInterface
            public String getApiKey() {
                return getSharedPreferences("tingli", MODE_PRIVATE).getString("apiKey", "");
            }
            @android.webkit.JavascriptInterface
            public String readClipboard() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null || !cm.hasPrimaryClip()) return "";
                    ClipData clip = cm.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) return "";
                    CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                } catch (Throwable e) {
                    return "";
                }
            }
            @android.webkit.JavascriptInterface
            public void audioBegin() {
                try {
                    whisperEngine.beginAudio(MainActivity.this);
                } catch (Throwable ignored) {}
            }

            @android.webkit.JavascriptInterface
            public void audioChunk(String base64Chunk) {
                try {
                    whisperEngine.appendAudioChunk(base64Chunk);
                } catch (Throwable ignored) {}
            }

            @android.webkit.JavascriptInterface
            public void transcribeCancel() {
                try { whisperEngine.closeAudio(); } catch (Throwable ignored) {}
            }

            @android.webkit.JavascriptInterface
            public void cancelTranscribe() {
                whisperEngine.cancel();
            }

            /* ---------- 本地音频库 ---------- */

            @android.webkit.JavascriptInterface
            public boolean hasStoragePermission() {
                if (Build.VERSION.SDK_INT < 23) return true;
                return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            }

            @android.webkit.JavascriptInterface
            public void requestStoragePermission() {
                if (Build.VERSION.SDK_INT >= 23) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 200);
                        }
                    });
                }
            }

            /** 扫描本机文件名含「听力」或 listening 的音频，返回 {list:[{id,name,size,duration}]}。 */
            @android.webkit.JavascriptInterface
            public String scanLocalAudio() {
                try {
                    if (!hasStoragePermission()) return "{\"needPermission\":true}";
                    org.json.JSONArray arr = new org.json.JSONArray();
                    String[] proj = {
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.DISPLAY_NAME,
                            MediaStore.Audio.Media.SIZE,
                            MediaStore.Audio.Media.DURATION
                    };
                    String col = MediaStore.Audio.Media.DISPLAY_NAME;
                    String sel = col + " LIKE ? OR " + col + " LIKE ?";
                    String[] args = {"%听力%", "%listening%"};
                    Cursor c = getContentResolver().query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                            col + " ASC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("id", c.getLong(0));
                            o.put("name", c.getString(1) == null ? "" : c.getString(1));
                            o.put("size", c.getLong(2));
                            o.put("duration", c.getLong(3));
                            arr.put(o);
                        }
                        c.close();
                    }
                    org.json.JSONObject out = new org.json.JSONObject();
                    out.put("list", arr);
                    return out.toString();
                } catch (Throwable e) {
                    return "{\"error\":\"" + (e.getMessage() == null ? "扫描失败" : e.getMessage().replace("\"", "'")) + "\"}";
                }
            }

            /** 把选中的音频复制进应用私有库，返回 {name}。 */
            @android.webkit.JavascriptInterface
            public String importLocalAudio(long id) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    String displayName = null;
                    Cursor c = getContentResolver().query(uri,
                            new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) displayName = c.getString(0);
                        c.close();
                    }
                    if (displayName == null || displayName.trim().isEmpty()) displayName = "audio_" + id + ".mp3";
                    String safe = displayName.replaceAll("[\\\\/:*?\"<>|]", "_");

                    File dir = new File(getFilesDir(), "library");
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, safe);
                    in = getContentResolver().openInputStream(uri);
                    out = new java.io.FileOutputStream(dest);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", safe);
                    o.put("size", dest.length());
                    return o.toString();
                } catch (Throwable e) {
                    return "{\"error\":\"" + (e.getMessage() == null ? "导入失败" : e.getMessage().replace("\"", "'")) + "\"}";
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                }
            }

            /** 列出应用本地库中的音频。 */
            @android.webkit.JavascriptInterface
            public String listLibrary() {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    File dir = new File(getFilesDir(), "library");
                    File[] files = dir.listFiles();
                    if (files != null) {
                        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                            @Override public int compare(File a, File b) {
                                return Long.compare(b.lastModified(), a.lastModified());
                            }
                        });
                        for (File f : files) {
                            if (!f.isFile()) continue;
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("name", f.getName());
                            o.put("size", f.length());
                            arr.put(o);
                        }
                    }
                    org.json.JSONObject out = new org.json.JSONObject();
                    out.put("list", arr);
                    return out.toString();
                } catch (Throwable e) {
                    return "{\"error\":\"读取本地库失败\"}";
                }
            }

            @android.webkit.JavascriptInterface
            public boolean deleteLibraryFile(String name) {
                try {
                    if (name == null || name.contains("/") || name.contains("..")) return false;
                    File f = new File(new File(getFilesDir(), "library"), name);
                    return f.delete();
                } catch (Throwable e) {
                    return false;
                }
            }

            @android.webkit.JavascriptInterface
            public String libraryUrl(String name) {
                try {
                    return LOCAL_ORIGIN + "library/" + java.net.URLEncoder.encode(name, "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }

            @android.webkit.JavascriptInterface
            public void transcribeNative(final String modelId, final String callbackId) {
                // 本地离线 Whisper 转写（sherpa-onnx），完全不走网络。
                // 音频已通过 audioBegin/audioChunk 分块写入磁盘，这里不再接收大字符串。
                new Thread(new Runnable() {
                    @Override public void run() {
                        String result;
                        try {
                            String modelTag = "base".equals(modelId) ? "base" : "tiny";
                            String modelDir = "models/whisper-" + modelTag;
                            // 精简版没有内置 base 模型时自动回退 tiny
                            if (!hasAssetDir(modelDir)) {
                                modelTag = "tiny";
                                modelDir = "models/whisper-tiny";
                            }
                            File src = whisperEngine.finishAudio();
                            if (src == null || !src.exists() || src.length() == 0) {
                                throw new IOException("没有接收到音频数据，请重新上传");
                            }
                            result = whisperEngine.transcribe(MainActivity.this, modelDir, modelTag, src,
                                new WhisperEngine.Progress() {
                                    @Override public void onProgress(final int done, final int total) {
                                        runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                String js = "window.__onTranscribeProgress && window.__onTranscribeProgress("
                                                        + jsonStr(callbackId) + "," + done + "," + total + ")";
                                                webView.evaluateJavascript(js, null);
                                            }
                                        });
                                    }
                                });
                        } catch (OutOfMemoryError e) {
                            result = "{\"ok\":false,\"error\":\"内存不足，请选择 tiny 模型或使用较短的音频\"}";
                        } catch (Throwable e) {
                            String m = e.getMessage() == null ? "转写失败" : e.getMessage();
                            result = "{\"ok\":false,\"error\":\"" + m.replace("\\", " ").replace("\"", "'") + "\"}";
                        } finally {
                            try { whisperEngine.closeAudio(); } catch (Throwable ignored) {}
                        }
                        final String fresult = result;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                String cb = "window.__onNativeTranscribe && window.__onNativeTranscribe(" + jsonStr(callbackId) + ", " + fresult + ")";
                                webView.evaluateJavascript(cb, null);
                            }
                        });
                    }
                }).start();
            }

            @android.webkit.JavascriptInterface
            public void callDeepSeek(final String messagesJson, final String callbackId) {
                // 原生侧直连 DeepSeek，绕开 WebView 的 CORS 限制
                new Thread(new Runnable() {
                    @Override public void run() {
                        String result;
                        try {
                            String apiKey = getSharedPreferences("tingli", MODE_PRIVATE).getString("apiKey", "");
                            if (apiKey.isEmpty()) {
                                result = "{\"error\":\"请先填写 DeepSeek API Key\"}";
                            } else {
                                URL url = new URL("https://api.deepseek.com/chat/completions");
                                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty("Content-Type", "application/json");
                                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                                conn.setDoOutput(true);
                                conn.setConnectTimeout(30000);
                                conn.setReadTimeout(120000);
                                String body = "{\"model\":\"deepseek-chat\",\"messages\":" + messagesJson + ",\"temperature\":0.3}";
                                OutputStream os = conn.getOutputStream();
                                os.write(body.getBytes("UTF-8"));
                                os.close();
                                int code = conn.getResponseCode();
                                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                                String resp = readStream(is);
                                result = "{\"status\":" + code + ",\"body\":" + resp + "}";
                            }
                        } catch (Exception e) {
                            String m = e.getMessage() == null ? "network error" : e.getMessage();
                            result = "{\"error\":\"" + m.replace("\\", " ").replace("\"", "'") + "\"}";
                        }
                        final String fresult = result;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                String cb = "window.__onDeepSeek && window.__onDeepSeek(" + jsonStr(callbackId) + ", " + fresult + ")";
                                webView.evaluateJavascript(cb, null);
                            }
                        });
                    }
                }).start();
            }
        }, "AndroidBridge");

        webView.loadUrl(LOCAL_ORIGIN + "index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 100) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{ Uri.parse(dataString) };
                    } else if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** 检查 assets 下是否存在某个模型目录（精简版可能未内置 base）。 */
    private boolean hasAssetDir(String dir) {
        try {
            String[] files = getAssets().list(dir);
            return files != null && files.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }

    private String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
