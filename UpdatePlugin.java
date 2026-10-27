package de.foxmusik.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// App-Update: laedt die neue APK vom GitHub-Release und startet die Installation.
@CapacitorPlugin(name = "Updater")
public class UpdatePlugin extends Plugin {

    private static final String ACT_RESULT = "de.foxmusik.app.INSTALL_RESULT";
    private BroadcastReceiver rx;

    private synchronized void registerRx(Context ctx) {
        if (rx != null) {
            return;
        }
        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(confirm);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    return;
                }
                JSObject o = new JSObject();
                o.put("status", st);
                o.put("message", msg == null ? "" : msg);
                notifyListeners("installResult", o);
            }
        };
        IntentFilter f = new IntentFilter(ACT_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(rx, f);
        }
    }

    // Installation ueber den PackageInstaller: liefert bei Fehlern die genaue Ursache zurueck.
    private void installViaSession(Context ctx, File f) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            InputStream in = new FileInputStream(f);
            OutputStream out = session.openWrite("update.apk", 0, f.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            session.fsync(out);
            in.close();
            out.close();
            registerRx(ctx);
            Intent cb = new Intent(ACT_RESULT).setPackage(ctx.getPackageName());
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, cb, fl);
            session.commit(pend.getIntentSender());
        } finally {
            session.close();
        }
    }

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

    // Speichert die Sicherung (JSON) im Ordner "Downloads" bzw. oeffnet bei aelteren Geraeten das Teilen-Menue.
    @PluginMethod
    public void saveText(PluginCall call) {
        try {
            Context ctx = getContext();
            String name = call.getString("name", "fox-musik-sicherung.json");
            String text = call.getString("text", "");
            byte[] data = text.getBytes("UTF-8");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) {
                    call.reject("Speichern nicht moeglich");
                    return;
                }
                OutputStream os = ctx.getContentResolver().openOutputStream(u);
                os.write(data);
                os.close();
                JSObject r = new JSObject();
                r.put("where", "Downloads");
                call.resolve(r);
            } else {
                File f = new File(ctx.getCacheDir(), name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".foxupdate", f);
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("application/json");
                s.putExtra(Intent.EXTRA_STREAM, uri);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent ch = Intent.createChooser(s, "Sicherung speichern");
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(ch);
                JSObject r = new JSObject();
                r.put("where", "Teilen");
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // Oeffnet die Spotify-App (oder open.spotify.com) fuer eine Suche.
    @PluginMethod
    public void openUrl(PluginCall call) {
        Context ctx = getContext();
        String url = call.getString("url", "");
        String fb = call.getString("fallback", "");
        if (tryOpen(ctx, url) || tryOpen(ctx, fb)) {
            call.resolve();
        } else {
            call.reject("Keine App gefunden");
        }
    }

    private boolean tryOpen(Context ctx, String url) {
        if (url == null || !(url.startsWith("spotify:") || url.startsWith("https://open.spotify.com/"))) {
            return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
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
            try {
                installViaSession(ctx, f);
                call.resolve();
                return;
            } catch (Exception e) {
                // Fallback: klassische Installation ueber den Paketinstaller
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
