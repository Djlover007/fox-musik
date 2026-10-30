package de.foxmusik.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

// Eigene Sparte: zeigt die Neowake-Web-App mit dem eigenen Konto des Nutzers.
// Die Anmeldung speichert Neowake selbst (Cookies, "Eingeloggt bleiben"), Fox Musik speichert kein Passwort.
public class NeowakeActivity extends Activity {

    static final String START = "https://app.neowake.de/dashboard/";
    private WebView web;

    // Bruecke zu Fox Musik: was gerade laeuft + Diagnose
    static volatile String state = "";
    static volatile String diag = "";
    static WeakReference<NeowakeActivity> current = new WeakReference<NeowakeActivity>(null);

    static final String BRIDGE_JS =
        "(function(){if(window.__foxnw)return;window.__foxnw=1;" +
        "function clean(u){try{var x=new URL(u,location.href);return x.origin+x.pathname;}catch(e){return String(u||'').split('?')[0];}}" +
        "function media(){var a=[].slice.call(document.querySelectorAll('audio,video'));var p=a.filter(function(m){return !m.paused;});return p[0]||window.__foxnwLast||a[0]||null;}" +
        "function txt(sel){var e=document.querySelector(sel);return e?(e.innerText||'').trim().slice(0,120):'';}" +
        "function title(){var cands=['.playlist-title','.track-title','.song-title','.player-title','[class*=title][class*=play]','[class*=Title][class*=Play]'];for(var i=0;i<cands.length;i++){var t=txt(cands[i]);if(t&&t.indexOf('{{')<0)return t;}return '';}" +
        "function send(){var m=media();var s={playing:!!(m&&!m.paused),src:m?clean(m.currentSrc||m.src):'',t:m?m.currentTime:0,d:m&&isFinite(m.duration)?m.duration:0,title:title(),page:document.title,url:clean(location.href)};try{FoxNW.report(JSON.stringify(s));}catch(e){}}" +
        "['play','pause','ended','loadedmetadata'].forEach(function(ev){document.addEventListener(ev,function(e){if(e.target&&(e.target.tagName==='AUDIO'||e.target.tagName==='VIDEO')){window.__foxnwLast=e.target;}send();},true);});" +
        "var _p=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){window.__foxnwLast=this;setTimeout(send,300);return _p.apply(this,arguments);};" +
        "var _A=window.Audio;if(_A){window.Audio=function(s){var a=new _A(s);window.__foxnwLast=a;['play','pause','ended','loadedmetadata'].forEach(function(ev){a.addEventListener(ev,send);});return a;};window.Audio.prototype=_A.prototype;}" +
        "setInterval(send,3000);send();" +
        "window.__foxnwCtl=function(c){var m=media();if(!m)return;if(c==='toggle'){if(m.paused)m.play();else m.pause();}else if(c==='play')m.play();else if(c==='pause')m.pause();else if(c==='back')m.currentTime=Math.max(0,m.currentTime-15);else if(c==='fwd')m.currentTime=m.currentTime+15;setTimeout(send,300);};" +
        "window.__foxnwDiag=function(){var r=performance.getEntriesByType('resource').map(function(e){return e.initiatorType+' '+clean(e.name);});var u={};r=r.filter(function(x){if(u[x])return false;u[x]=1;return true;});" +
        "var m=[].slice.call(document.querySelectorAll('audio,video,source')).map(function(e){return e.tagName+' '+clean(e.currentSrc||e.src||'');});" +
        "var links=[].slice.call(document.querySelectorAll('a[href]')).map(function(a){return (a.innerText||'').trim().slice(0,40)+' -> '+clean(a.href);}).filter(function(x){return x.indexOf('neowake')>-1;}).slice(0,80);" +
        "var cls={};[].slice.call(document.querySelectorAll('[class]')).forEach(function(e){String(e.className).split(/\\s+/).forEach(function(c){if(/play|track|song|album|list|title|audio|media|collection/i.test(c))cls[c]=1;});});" +
        "var g=Object.keys(window).filter(function(k){return /app|vue|store|state|player|data|config/i.test(k)&&k.slice(0,2)!=='on';}).slice(0,40);" +
        "return JSON.stringify({url:clean(location.href),title:document.title,resources:r.slice(-150),media:m,links:links,classes:Object.keys(cls).slice(0,120),globals:g});};" +
        "})();";

    class Bridge {
        @JavascriptInterface
        public void report(String json) {
            state = json == null ? "" : json;
        }
    }

    static void control(final String cmd) {
        final NeowakeActivity a = current.get();
        if (a == null || a.web == null) {
            return;
        }
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.web != null) {
                    a.web.evaluateJavascript("window.__foxnwCtl&&window.__foxnwCtl(" + org.json.JSONObject.quote(cmd) + ")", null);
                }
            }
        });
    }

    static boolean collectDiag(final Runnable done) {
        final NeowakeActivity a = current.get();
        if (a == null || a.web == null) {
            return false;
        }
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.web == null) {
                    done.run();
                    return;
                }
                a.web.evaluateJavascript("window.__foxnwDiag?window.__foxnwDiag():''", new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        diag = v == null ? "" : v;
                        done.run();
                    }
                });
            }
        });
        return true;
    }

    static boolean isNeowake(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host != null && (host.equals("neowake.de") || host.endsWith(".neowake.de"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        String ua = s.getUserAgentString();
        if (ua != null) {
            s.setUserAgentString(ua.replace("; wv", ""));
        }
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "FoxNW");
        current = new WeakReference<NeowakeActivity>(this);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return external(req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return external(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                v.evaluateJavascript(BRIDGE_JS, null);
            }
        });
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START);
        }
    }

    // Links ausserhalb von neowake.de (Shop, Datenschutz, Mail ...) im Browser bzw. passender App oeffnen.
    private boolean external(String url) {
        if (url == null || isNeowake(url) || url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) {
            return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // ignore
        }
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) {
            web.saveState(out);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Absichtlich kein web.onPause(): Neowake-Audio soll bei ausgeschaltetem Bildschirm weiterlaufen.
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            // Neowake im Hintergrund weiterlaufen lassen (Audio spielt weiter) und Fox Musik nach vorne holen.
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        }
    }

    @Override
    protected void onDestroy() {
        state = "";
        if (web != null) {
            web.removeJavascriptInterface("FoxNW");
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
