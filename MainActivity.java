package de.foxmusik.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;

import java.util.ArrayList;

public class MainActivity extends BridgeActivity {

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private String[] missingPermissions() {
        ArrayList<String> list = new ArrayList<String>();
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED) {
            list.add(audioPermission());
        }
        // Ab Android 13 braucht die Mediensteuerung (Benachrichtigung) eine eigene Erlaubnis.
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaScanPlugin.class);
        registerPlugin(PlaybackPlugin.class);
        registerPlugin(UpdatePlugin.class);
        super.onCreate(savedInstanceState);
        setupSpotifyWebView();
        setupAudioProxy();
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            ActivityCompat.requestPermissions(this, missing, 7421);
        }
    }

    // Neowake-Audio ueber https://localhost/_nwproxy?u=... durchreichen (siehe NwProxy)
    private void setupAudioProxy() {
        try {
            getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if (u != null && "/_radio".equals(u.getPath())) {
                        WebResourceResponse r = RadioProxy.handle(u.getQueryParameter("u"));
                        if (r != null) {
                            return r;
                        }
                    }
                    if (u != null && "/_nwproxy".equals(u.getPath())) {
                        WebResourceResponse r = NwProxy.handle(u.getQueryParameter("u"), req.getRequestHeaders());
                        if (r != null) {
                            return r;
                        }
                    }
                    return super.shouldInterceptRequest(view, req);
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    // Erlaubt dem eingebetteten Spotify-Player Anmelde-Cookies und geschuetzte Wiedergabe (ganze Titel).
    private void setupSpotifyWebView() {
        try {
            WebView wv = getBridge().getWebView();
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
            // Radio: viele Sender senden noch ueber http
            wv.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            wv.setWebChromeClient(new BridgeWebChromeClient(getBridge()) {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                            request.grant(new String[] { PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID });
                            return;
                        }
                    }
                    super.onPermissionRequest(request);
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Timer der WebView weiterlaufen lassen, damit der naechste Titel auch bei gesperrtem Bildschirm startet.
        try {
            getBridge().getWebView().resumeTimers();
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7421) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        getBridge().getWebView().evaluateJavascript(
                            "window.dispatchEvent(new Event('fox-permission'))", null);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            });
        }
    }
}
