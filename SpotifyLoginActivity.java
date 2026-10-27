package de.foxmusik.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Anmeldefenster fuer Spotify. Die Cookies landen im gemeinsamen CookieManager der App,
// dadurch ist auch der eingebettete Spotify-Player in Fox Musik angemeldet.
public class SpotifyLoginActivity extends Activity {

    private WebView web;
    private String redirect;

    static boolean loggedIn() {
        String c = CookieManager.getInstance().getCookie("https://open.spotify.com");
        return c != null && c.contains("sp_dc=");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Bei Spotify anmelden");
        web = new WebView(this);
        setContentView(web);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        String ua = s.getUserAgentString();
        if (ua != null) {
            s.setUserAgentString(ua.replace("; wv", ""));
        }
        redirect = getIntent().getStringExtra("redirect");
        final String authUrl = getIntent().getStringExtra("auth_url");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return catchRedirect(req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return catchRedirect(url);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (catchRedirect(url)) {
                    v.stopLoading();
                    return;
                }
                check(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                check(url);
            }
        });
        if (authUrl != null && authUrl.startsWith("https://accounts.spotify.com/")) {
            web.loadUrl(authUrl);
        } else {
            web.loadUrl("https://accounts.spotify.com/de/login?continue=https%3A%2F%2Fopen.spotify.com%2F");
        }
    }

    // OAuth: Spotify leitet nach der Freigabe auf die Redirect-Adresse um, die wir hier abfangen.
    private boolean catchRedirect(String url) {
        if (redirect == null || url == null || !url.startsWith(redirect)) {
            return false;
        }
        Intent data = new Intent();
        data.putExtra("url", url);
        setResult(RESULT_OK, data);
        finish();
        return true;
    }

    private void check(String url) {
        if (redirect != null) {
            return;
        }
        if (url == null || url.contains("accounts.spotify.com")) {
            return;
        }
        if (loggedIn()) {
            CookieManager.getInstance().flush();
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            setResult(loggedIn() ? RESULT_OK : RESULT_CANCELED);
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
