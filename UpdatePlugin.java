package de.foxmusik.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// App-Update: laedt die neue APK vom GitHub-Release und startet die Installation.
@CapacitorPlugin(name = "Updater")
public class UpdatePlugin extends Plugin {

    @PluginMethod
    public void info(PluginCall call) {
        try {
            Context ctx = getContext();
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            JSObject o = new JSObject();
            o.put("versionCode", code);
            o.put("versionName", pi.versionName);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    File out = new File(getContext().getCacheDir(), "update.apk");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "FoxMusik");
                    int code = c.getResponseCode();
                    if (code != 200) {
                        call.reject("HTTP " + code);
                        return;
                    }
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[32768];
                    long got = 0;
                    int n;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != last) {
                                last = pct;
                                JSObject p = new JSObject();
                                p.put("percent", pct);
                                notifyListeners("progress", p);
                            }
                        }
                    }
                    fo.close();
                    in.close();
                    if (total > 0 && got != total) {
                        call.reject("Download unvollstaendig (" + got + " von " + total + " Bytes)");
                        return;
                    }
                    if (got < 100000) {
                        call.reject("Download zu klein (" + got + " Bytes), das ist keine APK");
                        return;
                    }
                    JSObject r = new JSObject();
                    r.put("size", got);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    private static long codeOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    // Prueft die heruntergeladene APK, bevor Android sie oeffnet, und liefert eine klare Fehlermeldung.
    private String check(Context ctx, File f) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
            PackageInfo ai = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
            if (ai == null) {
                return "Android kann die Datei nicht lesen (" + f.length() + " Bytes). Sie ist defekt oder passt nicht zu diesem Handy.";
            }
            if (!ctx.getPackageName().equals(ai.packageName)) {
                return "Falsche App in der Datei: " + ai.packageName;
            }
            PackageInfo cur = pm.getPackageInfo(ctx.getPackageName(), flags);
            if (codeOf(ai) <= codeOf(cur)) {
                return "Die Datei ist nicht neuer (Version " + codeOf(ai) + ", installiert " + codeOf(cur) + ").";
            }
            if (Build.VERSION.SDK_INT >= 28 && ai.signingInfo != null && cur.signingInfo != null) {
                Signature[] a = ai.signingInfo.getApkContentsSigners();
                Signature[] b = cur.signingInfo.getApkContentsSigners();
                if (a != null && b != null && a.length > 0 && b.length > 0 && !a[0].equals(b[0])) {
                    return "Die Signatur passt nicht zur installierten App. Deinstalliere Fox Musik einmal und installiere die neue Version.";
                }
            }
        } catch (Exception e) {
            // Pruefung nicht moeglich: Installation trotzdem versuchen
        }
        return null;
    }

    @PluginMethod
    public void install(PluginCall call) {
        try {
            Context ctx = getContext();
            File f = new File(ctx.getCacheDir(), "update.apk");
            if (!f.exists()) {
                call.reject("Keine Update-Datei");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(s);
                call.reject("permission");
                return;
            }
            String err = check(ctx, f);
            if (err != null) {
                call.reject(err);
                return;
            }
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".foxupdate", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
