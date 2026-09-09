package com.mokano.mokatube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private static final String HOME = "https://www.youtube.com/?persist_app=1&app=desktop";

    private static final String JS_SETUP =
            "(function(){" +
            " if(window.__mokaInstalled)return; window.__mokaInstalled=true;" +
            " var css=document.createElement('style');" +
            " css.textContent='a:focus,button:focus,input:focus,ytd-rich-item-renderer:focus{outline:3px solid #fff!important;outline-offset:3px!important;}';" +
            " document.documentElement.appendChild(css);" +
            " document.addEventListener('click',function(e){" +
            "   var a=e.target.closest&&e.target.closest('a[href]'); if(!a)return;" +
            "   var h=a.href||'';" +
            "   if(h.indexOf('/watch?')>-1||h.indexOf('/shorts/')>-1||h.indexOf('youtu.be/')>-1){" +
            "     e.preventDefault(); e.stopPropagation();" +
            "     if(window.MokaBridge) MokaBridge.openVideo(h);" +
            "   }" +
            " },true);" +
            " window.__mokaNav=function(dir){" +
            "   var q='a[href],button,input,textarea,[role=button],[tabindex]:not([tabindex=\\\"-1\\\"])';" +
            "   var els=[].slice.call(document.querySelectorAll(q)).filter(function(el){" +
            "     var r=el.getBoundingClientRect(),s=getComputedStyle(el);" +
            "     return r.width>8&&r.height>8&&r.bottom>0&&r.top<innerHeight&&s.visibility!='hidden'&&s.display!='none';" +
            "   });" +
            "   if(!els.length)return;" +
            "   var cur=document.activeElement; if(els.indexOf(cur)<0){els[0].focus();return;}" +
            "   var r=cur.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2,best=null,bs=1e12;" +
            "   els.forEach(function(el){if(el===cur)return;var p=el.getBoundingClientRect(),x=p.left+p.width/2,y=p.top+p.height/2,dx=x-cx,dy=y-cy,ok=false,pri=0,sec=0;" +
            "     if(dir=='l'&&dx<-4){ok=true;pri=-dx;sec=Math.abs(dy)}" +
            "     if(dir=='r'&&dx>4){ok=true;pri=dx;sec=Math.abs(dy)}" +
            "     if(dir=='u'&&dy<-4){ok=true;pri=-dy;sec=Math.abs(dx)}" +
            "     if(dir=='d'&&dy>4){ok=true;pri=dy;sec=Math.abs(dx)}" +
            "     if(ok){var sc=pri+sec*2.2;if(sc<bs){bs=sc;best=el}}});" +
            "   if(best){best.focus();best.scrollIntoView({block:'nearest',inline:'nearest'});}" +
            " };" +
            "})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new MokaBridge(), "MokaBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectRemoteSupport();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String url = uri.toString();
                if (isVideoUrl(url)) {
                    openInSmartTube(url);
                    return true;
                }
                String host = uri.getHost();
                if (host == null || !(host.endsWith("youtube.com") || host.equals("youtu.be")
                        || host.endsWith("google.com") || host.endsWith("googleusercontent.com"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });

        if (state == null) webView.loadUrl(HOME);
    }

    private boolean isVideoUrl(String url) {
        return url.contains("youtube.com/watch?")
                || url.contains("youtube.com/shorts/")
                || url.contains("youtu.be/");
    }

    private void injectRemoteSupport() {
        if (webView != null) webView.evaluateJavascript(JS_SETUP, null);
    }

    private boolean launchPackage(String pkg, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage(pkg);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void openInSmartTube(String url) {
        Uri uri = Uri.parse(url);
        boolean ok =
                launchPackage("org.smarttube.stable", uri) ||
                launchPackage("org.smarttube.beta", uri) ||
                launchPackage("app.smarttube.fdroid", uri) ||
                launchPackage("com.liskovsoft.smarttubetv2.tv", uri);

        if (!ok) {
            Toast.makeText(this,
                    "Instala SmartTube para reproducir sin anuncios. Abriendo en YouTube Web.",
                    Toast.LENGTH_LONG).show();
            webView.loadUrl(url);
        }
    }

    public class MokaBridge {
        @JavascriptInterface
        public void openVideo(final String url) {
            runOnUiThread(() -> openInSmartTube(url));
        }
    }

    private void nav(String d) {
        if (webView != null) {
            webView.evaluateJavascript("window.__mokaNav&&window.__mokaNav('" + d + "')", null);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && webView != null) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  nav("l"); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: nav("r"); return true;
                case KeyEvent.KEYCODE_DPAD_UP:    nav("u"); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  nav("d"); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    webView.evaluateJavascript("(function(){var e=document.activeElement;if(e&&e.click){e.click();return true}return false})()", null);
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("MokaBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
