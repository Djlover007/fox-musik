package de.foxmusik.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

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
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            ActivityCompat.requestPermissions(this, missing, 7421);
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
