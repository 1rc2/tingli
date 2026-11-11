package com.tingli.app;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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
