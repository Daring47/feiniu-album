package com.feiniu.album;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 飞牛相册 原生 App（安卓）
 *
 * 原理：内嵌相册网页，界面由 WebView 渲染；
 * 但所有「网络请求」（飞牛接口 + 缩略图/原图）都交给原生层执行：
 *   1). 原生 HTTP 不受浏览器 CORS 限制，可直接带 Cookie 调飞牛接口；
 *   2). 图片/视频走 shouldInterceptRequest 拦截，由原生下载后回给 WebView，
 *       不依赖 file:// → http:// 的混合内容策略，也不会丢 Cookie。
 */
public class MainActivity extends Activity {

    private static final String TAG = "FnosAlbum";
    private static final String APP_URL = "file:///android_asset/index.html";
    /** 远程调试开关（Chrome 访问 chrome://inspect 可看控制台），排查问题时临时置 true */
    private static final boolean DEBUG_WEBVIEW = false;

    private WebView webView;
    private String baseHost = "";                       // 当前飞牛地址 scheme://host:port
    private final JsBridge bridge = new JsBridge();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> pageCookies = new HashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG_WEBVIEW) {
            try { WebView.setWebContentsDebuggingEnabled(true); } catch (Throwable ignored) {}
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.parseColor("#0b0d10"));
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNav(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNav(request.getUrl().toString());
            }

            /** 图片/视频等资源：直接由原生 fetch，绕过 file:// → http:// 的限制，并带上 Cookie */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return intercept(request.getUrl().toString(), request.getMethod());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 登录完成后飞牛会跳回自身地址；一旦拿到 fnos-token 就回 App 页面
                if (url != null && url.startsWith("http") && !url.contains("android_asset")) {
                    syncCookies(url);
                    String tok = bridge.getToken();
                    if (tok != null && !tok.isEmpty()) {
                        Log.d(TAG, "登录完成，返回 App");
                        view.post(() -> view.loadUrl(APP_URL));
                    }
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) { /* no-op */ }
        });

        // 让 WebView 铺满屏幕（页面自行用 safe-area 处理刘海/状态栏）
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView.loadUrl(APP_URL);
    }

    /** 需要在系统浏览器里打开的域名（NAS 外网登录页等） */
    private static boolean isExternal(String url) {
        return url.contains("fnos.net")
                || url.contains("5ddd.com")
                || url.contains("feiniu.com");
    }

    private boolean handleNav(String url) {
        if (url == null) return false;
        syncCookies(url);
        if (url.startsWith("http") && isExternal(url)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            } catch (Exception ignored) {}
        }
        return false;   // 其余都在 WebView 内打开
    }

    private void syncCookies(String url) {
        try {
            String c = CookieManager.getInstance().getCookie(url);
            if (c != null && !c.isEmpty()) pageCookies.put(url, c);
        } catch (Exception ignored) {}
    }

    private static String hostOf(String url) {
        try {
            URI u = new URI(url);
            int p = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (p != -1 ? ":" + p : "");
        } catch (Exception e) {
            return url;
        }
    }

    /** 当前飞牛地址下的 Cookie（按 host 取，匹配不到再退回最近一次页面 Cookie） */
    private String cookiesFor(String url) {
        String c = null;
        try { c = CookieManager.getInstance().getCookie(url); } catch (Exception ignored) {}
        if (c == null || c.isEmpty()) c = pageCookies.get(url);
        if (c == null || c.isEmpty()) {
            String h = hostOf(url);
            for (Map.Entry<String, String> e : pageCookies.entrySet()) {
                if (hostOf(e.getKey()).equals(h)) {
                    String v = e.getValue();
                    if (v != null && !v.isEmpty()) return v;
                }
            }
            if (!baseHost.isEmpty()) {
                String b = CookieManager.getInstance().getCookie(baseHost);
                if (b == null || b.isEmpty()) b = pageCookies.get(baseHost);
                if (b != null && !b.isEmpty()) c = b;
            }
        }
        return c == null ? "" : c;
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".bmp")) return "image/bmp";
        if (u.endsWith(".heic") || u.endsWith(".heif")) return "image/heic";
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4";
        if (u.endsWith(".webm")) return "video/webm";
        if (u.endsWith(".mov")) return "video/quicktime";
        if (u.contains("/v/")) return "video/mp4";
        return "image/jpeg";
    }

    private static boolean isMediaUrl(String url) {
        String h = hostOf(url);
        String u = url.toLowerCase(Locale.ROOT);
        if (!h.startsWith("http")) return false;
        String[] hints = {"/p/api/v1/stream/", "/p/t/", "/api/v1/stream", "/stream/",
                "person_poster", "/thumbnail", "/thumb", "/cover"};
        for (String k : hints) if (u.contains(k)) return true;
        String[] exts = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".mp4", ".webm", ".mov"};
        for (String e : exts) if (u.contains(e)) return true;
        return false;
    }

    private WebResourceResponse intercept(String url, String method) {
        HttpURLConnection conn = null;
        try {
            if (url == null || !url.startsWith("http")) return null;
            if ("HEAD".equalsIgnoreCase(method)) return null;
            // 人物/相册封面用 <img src> 加载，需要拦；接口请求走 JS 桥，不会走到这里
            if (!isMediaUrl(url)) return null;

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FlyNiuAlbum/1.0");
            conn.setRequestProperty("Accept", "image/*,video/*,*/*;q=0.8");
            conn.setRequestProperty("Referer", baseHost.isEmpty() ? hostOf(url) + "/" : baseHost + "/");
            String ck = cookiesFor(url);
            if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);

            int status = conn.getResponseCode();
            InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                conn.disconnect();
                return null;
            }

            String mime = conn.getContentType();
            if (mime == null || mime.isEmpty()) mime = guessMime(url);
            int semi = mime.indexOf(';');
            if (semi > 0) mime = mime.substring(0, semi).trim();

            Map<String, String> rsp = new HashMap<>();
            rsp.put("Access-Control-Allow-Origin", "*");
            String cache = conn.getHeaderField("Cache-Control");
            rsp.put("Cache-Control", (cache == null || cache.isEmpty()) ? "max-age=3600" : cache);

            // 注意：不能调用 conn.disconnect()，但流会随内容读取完毕自然结束
            return new WebResourceResponse(mime, null, status, "OK", rsp, is);
        } catch (Exception e) {
            Log.w(TAG, "intercept fail: " + url + " :: " + e);
            if (conn != null) conn.disconnect();
            return null;   // 交给 WebView 自己再试
        }
    }

    private void postResult(String id, int status, String body) {
        try {
            JSONObject out = new JSONObject();
            out.put("status", status);
            out.put("body", body == null ? "" : body);
            final String res = out.toString();
            ui.post(() -> {
                if (webView == null) return;
                webView.evaluateJavascript(
                        "window.__bridgeCbs&&window.__bridgeCbs['" + id + "'](" + JSONObject.quote(res) + ")", null);
            });
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pageCookies.clear();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 暴露给网页的 JS 桥接（@JavascriptInterface 方法可从 JS 调用） */
    public class JsBridge {

        /** 网页保存服务器时告知原生飞牛地址，用于读取该域 Cookie */
        @JavascriptInterface
        public void setBase(String url) {
            baseHost = hostOf(url);
            Log.d(TAG, "setBase -> " + baseHost);
        }

        /** 供页面判断是否运行在原生 App 内 */
        @JavascriptInterface
        public boolean isNative() { return true; }

        /** 页面侧的错误提示（写日志，便于排查 Cookie 失效 / 地址不合法等） */
        @JavascriptInterface
        public void notifyError(String msg) {
            Log.w(TAG, "JS notifyError: " + msg);
        }

        /** 返回当前飞牛登录 Cookie（fnos-token），无则返回空串 */
        @JavascriptInterface
        public String getToken() {
            String cookie = "";
            try {
                if (!baseHost.isEmpty()) cookie = CookieManager.getInstance().getCookie(baseHost);
            } catch (Exception ignored) {}
            if (cookie == null || cookie.isEmpty()) {
                for (String c : pageCookies.values()) {
                    if (c != null && c.contains("fnos-token=")) { cookie = c; break; }
                }
            }
            if (cookie == null || cookie.isEmpty()) return "";
            for (String part : cookie.split(";")) {
                String p = part.trim();
                if (p.startsWith("fnos-token=")) {
                    return Uri.decode(p.substring("fnos-token=".length()));
                }
            }
            return "";
        }

        /**
         * 原生发起 HTTP 请求（绕过 CORS）。
         * @param id  回调标识，结果通过 window.__bridgeCbs[id](json) 回传
         * @param method GET/POST
         * @param url  完整请求地址
         * @param headersJson 请求头 JSON 字符串
         * @param body POST 请求体（可为空）
         */
        @JavascriptInterface
        public void http(final String id, final String method, final String url,
                         final String headersJson, final String body) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(25000);
                    conn.setReadTimeout(25000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FlyNiuAlbum/1.0");
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                    if (headersJson != null && !headersJson.isEmpty()) {
                        JSONObject hj = new JSONObject(headersJson);
                        java.util.Iterator<String> it = hj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            try { conn.setRequestProperty(k, hj.getString(k)); } catch (Exception ignored) {}
                        }
                    }

                    String ck = cookiesFor(url);
                    if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);

                    if (!"GET".equalsIgnoreCase(method) && body != null && !body.isEmpty()) {
                        conn.setDoOutput(true);
                        byte[] data = body.getBytes(StandardCharsets.UTF_8);
                        conn.setFixedLengthStreamingMode(data.length);
                        try (OutputStream os = conn.getOutputStream()) { os.write(data); }
                    }

                    int status = conn.getResponseCode();
                    InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    postResult(id, status, readStream(is));
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    Log.w(TAG, "bridge http error(" + url + "): " + m);
                    postResult(id, 0, "{\"code\":-1,\"msg\":" + JSONObject.quote(m) + "}");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        /** 带二进制（Base64）结果回调的请求，供网页做本地导出（下载）用 */
        @JavascriptInterface
        public void httpB64(final String id, final String method, final String url,
                            final String headersJson, final String body) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(25000);
                    conn.setReadTimeout(60000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FlyNiuAlbum/1.0");
                    if (headersJson != null && !headersJson.isEmpty()) {
                        JSONObject hj = new JSONObject(headersJson);
                        java.util.Iterator<String> it = hj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            try { conn.setRequestProperty(k, hj.getString(k)); } catch (Exception ignored) {}
                        }
                    }
                    String ck = cookiesFor(url);
                    if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);

                    int status = conn.getResponseCode();
                    InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    byte[] bytes = readBytes(is);
                    String mime = conn.getContentType();
                    if (mime == null) mime = "application/octet-stream";

                    JSONObject out = new JSONObject();
                    out.put("status", status);
                    out.put("mime", mime);
                    out.put("body", "");
                    out.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    final String res = out.toString();
                    ui.post(() -> {
                        if (webView == null) return;
                        webView.evaluateJavascript(
                                "window.__bridgeCbs&&window.__bridgeCbs['" + id + "'](" + JSONObject.quote(res) + ")", null);
                    });
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    Log.w(TAG, "bridge httpB64 error(" + url + "): " + m);
                    postResult(id, 0, "{\"code\":-1,\"msg\":" + JSONObject.quote(m) + "}");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        private byte[] readBytes(InputStream is) throws Exception {
            if (is == null) return new byte[0];
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
            return bo.toByteArray();
        }

        private String readStream(InputStream is) {
            if (is == null) return "";
            try {
                return new String(readBytes(is), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
