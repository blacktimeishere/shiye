package com.shiye.reader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQ = 1001;
    private static final String SITE_HOST = "https://www.wenku8.net/";
    private static final String SITE_WORKER_URL = "https://www.wenku8.net/book/2580.htm";

    private WebView webView;          // 主 App（file:// 单文件原型）
    private WebView siteWorker;       // 隐藏同源工作 WebView：执行站内 fetch
    private WebView siteView;         // 可见站点 WebView：用户登录 / 网页模式
    private LinearLayout siteLayer;   // 站点 WebView + 顶部工具条
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;
    private volatile boolean siteWorkerReady = false;
    private String siteCbId = null;
    private final ArrayList<String[]> pendingFetches = new ArrayList<String[]>();
    private String pendingNavigateCb = null;
    private Runnable navTimeout = null;
    private String siteUa = null;
    private long lastBackAt = 0L;        // 首次按返回的时间，用于「再按一次退出」
    private long lastBackHandledAt = 0L; // 防止 onKeyDown / onBackPressed 双触发

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            getWindow().setStatusBarColor(0xFFF5F1E8);
        }

        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        siteUa = s.getUserAgentString();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return null;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        /* 全局 Cookie：主 WebView、站点登录窗、隐藏工作窗共享 */
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        /* ── 隐藏工作 WebView：站内同源 fetch（真实浏览器 TLS + Cookie） ── */
        siteWorker = new WebView(this);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT);
        siteWorker.setLayoutParams(wp);
        root.addView(siteWorker);
        configureSiteWebView(siteWorker);
        siteWorker.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void deliver(final String cbId, final String resultJson) {
                deliverToApp(cbId, resultJson);
            }
        }, "ShiyeSiteBridge");
        siteWorker.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (navTimeout != null) { try { siteWorker.removeCallbacks(navTimeout); } catch (Throwable t) { /* ignore */ } navTimeout = null; }
                final boolean sitePage = url != null && url.indexOf("wenku8.net") >= 0 && url.startsWith("https");
                if (pendingNavigateCb != null) {
                    final String cb = pendingNavigateCb;
                    pendingNavigateCb = null;
                    view.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                siteWorker.evaluateJavascript(
                                    "(function(){try{var d=document.documentElement;" +
                                    "ShiyeSiteBridge.deliver(" + jsonQuote(cb) +
                                    ",JSON.stringify({ok:!!d,status:200,url:location.href,title:document.title,text:d?d.outerHTML:''}));}catch(e){" +
                                    "ShiyeSiteBridge.deliver(" + jsonQuote(cb) + ",JSON.stringify({ok:false,status:0,text:'',error:String(e)}));}})()", null);
                            } catch (Throwable t) { /* ignore */ }
                            siteWorker.postDelayed(new Runnable() {
                                @Override public void run() {
                                    try { siteWorker.loadUrl(SITE_WORKER_URL); } catch (Throwable t) { /* ignore */ }
                                }
                            }, 300);
                        }
                    }, 1200);
                }
                /* 只有真正落在 https 站页面才算就绪，且留一点时间给页面脚本与 Cookie 落盘 */
                if (sitePage) {
                    siteWorker.postDelayed(new Runnable() {
                        @Override public void run() {
                            siteWorkerReady = true;
                            synchronized (pendingFetches) {
                                for (String[] job : pendingFetches) runSiteFetch(job[0], job[1], job[2]);
                                pendingFetches.clear();
                            }
                        }
                    }, 700);
                }
            }
        });

        /* ── 可见站点 WebView：登录 / 网页模式 ── */
        siteLayer = new LinearLayout(this);
        siteLayer.setOrientation(LinearLayout.VERTICAL);
        siteLayer.setBackgroundColor(0xFFF5F1E8);
        siteLayer.setVisibility(View.GONE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFFF5F1E8);
        bar.setPadding(16, 26, 12, 12);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("轻小说文库 · 站内登录");
        title.setTextColor(0xFF1D1B16);
        title.setTextSize(14);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        bar.addView(title);
        Button done = new Button(this);
        done.setText("完成登录");
        done.setAllCaps(false);
        done.setTextSize(12);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishSiteLogin(); }
        });
        bar.addView(done);
        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextSize(12);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                siteLayer.setVisibility(View.GONE);
                deliverToApp(siteCbId == null ? "" : siteCbId,
                        "{\"ok\":false,\"cancelled\":true,\"cookie\":\"\",\"text\":\"\"}");
            }
        });
        bar.addView(close);
        siteLayer.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        siteView = new WebView(this);
        configureSiteWebView(siteView);
        siteView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void deliver(final String cbId, final String resultJson) {
                deliverToApp(cbId, resultJson);
            }
        }, "ShiyeSiteBridge");
        siteLayer.addView(siteView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(siteLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public String appName() { return "拾页"; }

            @android.webkit.JavascriptInterface
            public void vibrate(long ms) {
                try {
                    Vibrator v;
                    if (Build.VERSION.SDK_INT >= 31) {
                        VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                        v = vm.getDefaultVibrator();
                    } else {
                        v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    }
                    if (v == null || !v.hasVibrator()) return;
                    long dur = Math.max(1, Math.min(ms, 500));
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(dur);
                    }
                } catch (Throwable t) { /* ignore */ }
            }

            @android.webkit.JavascriptInterface
            public void fetchUrl(final String url, final String cbId) {
                fetchUrlWithTimeout(url, cbId, 12000, 15000);
            }

            @android.webkit.JavascriptInterface
            public void fetchUrlWithTimeout(final String url, final String cbId,
                                            final int connectMs, final int readMs) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        String payload;
                        HttpURLConnection conn = null;
                        try {
                            URL u = new URL(url);
                            conn = (HttpURLConnection) u.openConnection();
                            conn.setConnectTimeout(Math.max(1000, connectMs));
                            conn.setReadTimeout(Math.max(1000, readMs));
                            conn.setInstanceFollowRedirects(true);
                            conn.setRequestProperty("User-Agent", siteUa == null
                                    ? "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                                    : siteUa);
                            conn.setRequestProperty("Accept",
                                    "application/json, text/html;q=0.9, text/plain;q=0.8, */*;q=0.7");
                            /* 站点登录产生的 Cookie 同步给通用书源请求 */
                            String cookie = null;
                            try { cookie = CookieManager.getInstance().getCookie(url); } catch (Throwable t) { /* ignore */ }
                            if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);
                            int code = conn.getResponseCode();
                            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                            String text = "";
                            if (is != null) {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                                is.close();
                                String ct = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();
                                String charset = "UTF-8";
                                if (ct.contains("gbk") || ct.contains("gb2312")) charset = "GBK";
                                else if (ct.contains("gb18030")) charset = "GB18030";
                                else if (ct.contains("big5")) charset = "Big5";
                                text = new String(bos.toByteArray(), charset);
                            }
                            if (text.length() > 2000000) text = text.substring(0, 2000000);
                            payload = "{\"ok\":" + (code >= 200 && code < 400)
                                    + ",\"status\":" + code + ",\"text\":" + jsonQuote(text) + "}";
                        } catch (Throwable t) {
                            payload = "{\"ok\":false,\"status\":0,\"text\":\"\",\"error\":"
                                    + jsonQuote(String.valueOf(t.getMessage())) + "}";
                        } finally {
                            if (conn != null) try { conn.disconnect(); } catch (Throwable t) { /* ignore */ }
                        }
                        final String result = payload;
                        deliverToApp(cbId, result, true);
                    }
                }, "shiye-novel-fetch").start();
            }

            /* 公版书库整本纯文本：比搜索接口慢、体积大，单独放宽超时与上限。
               回调仍走 __nsCb，前端 gkNet 用 nsPending 接收。 */
            @android.webkit.JavascriptInterface
            public void fetchBook(final String url, final String cbId) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        String payload;
                        HttpURLConnection conn = null;
                        try {
                            URL u = new URL(url);
                            conn = (HttpURLConnection) u.openConnection();
                            conn.setConnectTimeout(20000);
                            conn.setReadTimeout(60000);
                            conn.setInstanceFollowRedirects(true);
                            conn.setRequestProperty("User-Agent", siteUa == null
                                    ? "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                                    : siteUa);
                            conn.setRequestProperty("Accept", "text/plain, text/html;q=0.8, */*;q=0.5");
                            int code = conn.getResponseCode();
                            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                            String text = "";
                            if (is != null) {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    bos.write(buf, 0, n);
                                    if (bos.size() > 9000000) break;
                                }
                                is.close();
                                String ct = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();
                                String charset = "UTF-8";
                                if (ct.contains("gbk") || ct.contains("gb2312")) charset = "GBK";
                                else if (ct.contains("gb18030")) charset = "GB18030";
                                else if (ct.contains("big5")) charset = "Big5";
                                text = new String(bos.toByteArray(), charset);
                            }
                            if (text.length() > 8000000) text = text.substring(0, 8000000);
                            payload = "{\"ok\":" + (code >= 200 && code < 400)
                                    + ",\"status\":" + code + ",\"text\":" + jsonQuote(text) + "}";
                        } catch (Throwable t) {
                            payload = "{\"ok\":false,\"status\":0,\"text\":\"\",\"error\":"
                                    + jsonQuote(String.valueOf(t.getMessage())) + "}";
                        } finally {
                            if (conn != null) try { conn.disconnect(); } catch (Throwable t) { /* ignore */ }
                        }
                        deliverToApp(cbId, payload, true);
                    }
                }, "shiye-gutenberg-book").start();
            }

            /* ── 站点会话：打开可见登录窗 ── */
            @android.webkit.JavascriptInterface
            public void openSite(final String url, final String cbId) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            siteCbId = cbId;
                            siteLayer.setVisibility(View.VISIBLE);
                            siteLayer.bringToFront();
                            siteView.loadUrl(url == null || url.length() == 0
                                    ? "https://www.wenku8.net/login.php" : url);
                        } catch (Throwable t) { /* ignore */ }
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void closeSite(final String cbId) { finishSiteLogin(cbId); }

            @android.webkit.JavascriptInterface
            public void siteCookie(final String cbId) {
                String cookie = "";
                try { cookie = CookieManager.getInstance().getCookie(SITE_HOST); } catch (Throwable t) { /* ignore */ }
                deliverToApp(cbId, "{\"ok\":true,\"cookie\":" + jsonQuote(cookie == null ? "" : cookie) + ",\"text\":\"\"}");
            }

            @android.webkit.JavascriptInterface
            public void siteLogout(final String cbId) {
                try {
                    CookieManager cm2 = CookieManager.getInstance();
                    String all = cm2.getCookie(SITE_HOST);
                    if (all != null) {
                        String[] parts = all.split(";");
                        for (String p : parts) {
                            String name = p.trim().split("=")[0];
                            cm2.setCookie(SITE_HOST, name + "=; Max-Age=0; path=/; domain=.wenku8.net");
                        }
                        if (Build.VERSION.SDK_INT >= 21) cm2.flush();
                    }
                } catch (Throwable t) { /* ignore */ }
                deliverToApp(cbId, "{\"ok\":true,\"cookie\":\"\",\"text\":\"\"}");
            }

            /* ── 站内同源请求：fetch 模式（GBK 解码、支持 POST） ── */
            @android.webkit.JavascriptInterface
            public void siteFetch(final String url, final String optionsJson, final String cbId) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!siteWorkerReady) {
                            synchronized (pendingFetches) { pendingFetches.add(new String[]{url, optionsJson, cbId}); }
                            /* 已经在站页面上时别重复导航，否则会打断正在进行的 Cloudflare 校验 */
                            String cur = null;
                            try { cur = siteWorker.getUrl(); } catch (Throwable t) { /* ignore */ }
                            if (cur == null || cur.indexOf("wenku8.net") < 0) {
                                try { siteWorker.loadUrl(SITE_WORKER_URL); } catch (Throwable t) { /* ignore */ }
                            }
                            return;
                        }
                        runSiteFetch(url, optionsJson, cbId);
                    }
                });
            }

            /* ── 站内请求兜底：真实导航模式（应对 fetch 被 WAF / 混合内容拦下） ── */
            @android.webkit.JavascriptInterface
            public void siteNavigate(final String url, final String cbId) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pendingNavigateCb = cbId;
                        siteWorkerReady = false;
                        armNavTimeout(cbId);
                        try { siteWorker.loadUrl(url); } catch (Throwable t) { /* ignore */ }
                    }
                });
            }

            /* 站内表单 POST：由浏览器按页面 GBK 编码中文，避免 fetch 只能发 UTF-8 的问题 */
            @android.webkit.JavascriptInterface
            public void siteFormPost(final String url, final String fieldsJson, final String cbId) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pendingNavigateCb = cbId;
                        siteWorkerReady = false;
                        armNavTimeout(cbId);
                        /* 用 setAttribute + 原型方法：站点表单里有 name="submit"/"action" 的字段，
                           直接 f.action / f.submit() 会被同名 input 遮蔽而报错。 */
                        final String expr = "(function(){try{" +
                            "var f=document.createElement('form');f.setAttribute('method','POST');" +
                            "f.setAttribute('action'," + jsonQuote(url) + ");" +
                            "var kv=" + (fieldsJson == null || fieldsJson.length() == 0 ? "{}" : fieldsJson) + ";" +
                            "Object.keys(kv).forEach(function(k){var i=document.createElement('input');" +
                            "i.setAttribute('type','hidden');i.setAttribute('name',k);i.value=kv[k];f.appendChild(i);});" +
                            "document.body.appendChild(f);HTMLFormElement.prototype.submit.call(f);return true;" +
                            "}catch(e){ShiyeSiteBridge.deliver(" + jsonQuote(cbId) +
                            ",JSON.stringify({ok:false,status:0,text:'',error:String(e)}));return false;}})()";
                        try { siteWorker.evaluateJavascript(expr, null); } catch (Throwable t) {
                            deliverToApp(cbId, "{\"ok\":false,\"status\":0,\"text\":\"\",\"error\":"
                                    + jsonQuote(String.valueOf(t.getMessage())) + "}");
                        }
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void siteReady(final String cbId) {
                deliverToApp(cbId, "{\"ok\":" + siteWorkerReady + ",\"cookie\":"
                        + jsonQuote(safeCookie()) + ",\"text\":\"\"}");
            }
        }, "ShiyeNative");

        webView.loadUrl("file:///android_asset/index.html");

        /* 预热站点会话，让 Cloudflare 校验在后台完成 */
        siteWorker.postDelayed(new Runnable() {
            @Override public void run() {
                try { siteWorker.loadUrl(SITE_WORKER_URL); } catch (Throwable t) { /* ignore */ }
            }
        }, 1200);
    }

    /* 站点表单/导航被 WAF 拦住时 onPageFinished 可能永远不来，
       看门狗负责回传失败并把工作 WebView 拉回宿主页，避免后续请求全卡住。 */
    private void armNavTimeout(final String cbId) {
        if (navTimeout != null) { try { siteWorker.removeCallbacks(navTimeout); } catch (Throwable t) { /* ignore */ } }
        navTimeout = new Runnable() {
            @Override public void run() {
                if (pendingNavigateCb == null) return;
                final String cb = pendingNavigateCb;
                pendingNavigateCb = null;
                deliverToApp(cb, "{\"ok\":false,\"status\":0,\"text\":\"\",\"error\":\"站点页面加载超时\"}");
                try { siteWorkerReady = false; siteWorker.loadUrl(SITE_WORKER_URL); } catch (Throwable t) { /* ignore */ }
            }
        };
        try { siteWorker.postDelayed(navTimeout, 12000); } catch (Throwable t) { /* ignore */ }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureSiteWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (siteUa != null) s.setUserAgentString(siteUa);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(w, true); } catch (Throwable t) { /* ignore */ }
        }
    }

    private String safeCookie() {
        try {
            String c = CookieManager.getInstance().getCookie(SITE_HOST);
            return c == null ? "" : c;
        } catch (Throwable t) { return ""; }
    }

    private void finishSiteLogin() { finishSiteLogin(siteCbId); }

    private void finishSiteLogin(final String cbId) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { siteLayer.setVisibility(View.GONE); } catch (Throwable t) { /* ignore */ }
                try {
                    siteView.evaluateJavascript(
                        "(function(){try{ShiyeSiteBridge.deliver(" + jsonQuote(cbId == null ? "" : cbId) +
                        ",JSON.stringify({ok:true,cancelled:false,cookie:document.cookie,url:location.href," +
                        "title:document.title,text:(document.body?document.body.innerText:'').slice(0,4000)}));}catch(e){" +
                        "ShiyeSiteBridge.deliver(" + jsonQuote(cbId == null ? "" : cbId) +
                        ",JSON.stringify({ok:false,error:String(e),cookie:'',text:''}));}})()", null);
                } catch (Throwable t) {
                    deliverToApp(cbId, "{\"ok\":false,\"error\":" + jsonQuote(String.valueOf(t.getMessage()))
                            + ",\"cookie\":\"\",\"text\":\"\"}");
                }
                /* 登录窗关掉后，工作窗也刷新一次以拿到新 Cookie */
                siteWorker.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { siteWorkerReady = false; siteWorker.loadUrl(SITE_WORKER_URL); } catch (Throwable t) { /* ignore */ }
                    }
                }, 400);
            }
        });
    }

    /* siteView 也需要一个回传通道（与 siteWorker 同名，统一 deliver） */
    private void deliverToApp(final String cbId, final String resultJson) {
        if (webView == null || cbId == null || cbId.length() == 0) return;
        final String expr = "window.__siteCb && window.__siteCb(" + jsonQuote(cbId) + "," + resultJson + ")";
        webView.post(new Runnable() {
            @Override public void run() {
                try { webView.evaluateJavascript(expr, null); } catch (Throwable t) { /* ignore */ }
            }
        });
    }

    /* fetchUrl 走的是 __nsCb，单独一个转发入口 */
    private void deliverToApp(final String cbId, final String resultJson, final boolean novelChannel) {
        if (webView == null) return;
        final String expr = "window.__nsCb && window.__nsCb(" + jsonQuote(cbId) + "," + resultJson + ")";
        webView.post(new Runnable() {
            @Override public void run() {
                try { webView.evaluateJavascript(expr, null); } catch (Throwable t) { /* ignore */ }
            }
        });
    }

    private void runSiteFetch(final String url, final String optionsJson, final String cbId) {
        final String opt = optionsJson == null || optionsJson.length() == 0 ? "{}" : optionsJson;
        final String expr = "(function(){try{" +
            "var o=" + opt + ";" +
            "var ctl=o.charset||'gbk';" +
            "var init={method:o.method||'GET',credentials:'include',headers:o.headers||{},redirect:'follow'};" +
            "if(o.body!==undefined&&o.body!==null)init.body=o.body;" +
            "fetch(o.url||" + jsonQuote(url) + ",init).then(function(r){" +
            "  return r.arrayBuffer().then(function(b){" +
            "    var t=new TextDecoder(ctl).decode(b);" +
            "    ShiyeSiteBridge.deliver(" + jsonQuote(cbId) + ",JSON.stringify({ok:r.ok,status:r.status,url:r.url,text:t}));" +
            "  });" +
            "}).catch(function(e){" +
            "  ShiyeSiteBridge.deliver(" + jsonQuote(cbId) + ",JSON.stringify({ok:false,status:0,text:'',error:String(e)}));" +
            "});" +
            "}catch(e){ShiyeSiteBridge.deliver(" + jsonQuote(cbId) +
            ",JSON.stringify({ok:false,status:0,text:'',error:String(e)}));}})()";
        try { siteWorker.evaluateJavascript(expr, null); } catch (Throwable t) {
            deliverToApp(cbId, "{\"ok\":false,\"status\":0,\"text\":\"\",\"error\":"
                    + jsonQuote(String.valueOf(t.getMessage())) + "}");
        }
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        /* 返回键必须先拦下：这个 App 是单页 WebView，canGoBack() 恒为 false，
           走系统默认会直接 finish()，用户感觉「一点返回就退出」。 */
        if (keyCode == KeyEvent.KEYCODE_BACK) { handleAppBack(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        /* 手势返回 / 新版返回分发会走这里，同样交给统一逻辑 */
        handleAppBack();
    }

    private void handleAppBack() {
        long now = System.currentTimeMillis();
        if (now - lastBackHandledAt < 350L) return;   // 一次按键只处理一次
        lastBackHandledAt = now;
        /* 网页登录窗在最上层时，先让它自己后退 / 关闭 */
        if (siteLayer != null && siteLayer.getVisibility() == View.VISIBLE) {
            if (siteView != null && siteView.canGoBack()) siteView.goBack();
            else siteLayer.setVisibility(View.GONE);
            return;
        }
        if (webView == null) { confirmExit(); return; }
        webView.evaluateJavascript(
            "(function(){try{return (window.ShiyeBack&&window.ShiyeBack.consume&&window.ShiyeBack.consume())?'true':'false';}catch(e){return 'false';}})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    boolean consumed = value != null && value.indexOf("true") >= 0;
                    if (consumed) { lastBackAt = 0L; return; }   // 前端已经关掉一层
                    if (webView.canGoBack()) { webView.goBack(); return; }
                    confirmExit();
                }
            });
    }

    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000L) {
            lastBackAt = 0L;
            finish();
        } else {
            lastBackAt = now;
            try {
                Toast.makeText(MainActivity.this, "再按一次返回键退出拾页", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) { /* ignore */ }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        if (siteView != null) { siteView.destroy(); siteView = null; }
        if (siteWorker != null) { siteWorker.destroy(); siteWorker = null; }
        super.onDestroy();
    }
}
