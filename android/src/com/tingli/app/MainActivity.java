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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends Activity {

    private static final String LOCAL_ORIGIN = "https://app.local/";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

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
                        return new WebResourceResponse("text/html", "utf-8", is);
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
