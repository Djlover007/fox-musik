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
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.CookieManager;

import androidx.activity.result.ActivityResult;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
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
    // "Drueckt" die Play-Taste von Spotify (wie eine Kopfhoerer-Taste), damit Spotify als Geraet aktiv wird.
    @PluginMethod
    public void spotifyWake(PluginCall call) {
        Context ctx = getContext();
        boolean installed;
        try {
            ctx.getPackageManager().getPackageInfo("com.spotify.music", 0);
            installed = true;
        } catch (Exception e) {
            installed = false;
        }
        if (installed) {
            String cmd = call.getString("key", "play");
            int code = "pause".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
            long t = SystemClock.uptimeMillis();
            for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
                i.setPackage("com.spotify.music");
                i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, code, 0));
                try {
                    ctx.sendOrderedBroadcast(i, null);
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        JSObject o = new JSObject();
        o.put("installed", installed);
        call.resolve(o);
    }

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
        if (url == null || !(url.startsWith("spotify:") || url.startsWith("https://open.spotify.com/") || url.startsWith("https://developer.spotify.com/"))) {
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

    // ---- Spotify-Anmeldung fuer den eingebetteten Player ----
    @PluginMethod
    public void spotifyLogin(PluginCall call) {
        Intent i = new Intent(getContext(), SpotifyLoginActivity.class);
        startActivityForResult(call, i, "spotifyLoginDone");
    }

    @ActivityCallback
    private void spotifyLoginDone(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject o = new JSObject();
        o.put("loggedIn", SpotifyLoginActivity.loggedIn());
        call.resolve(o);
    }

    @PluginMethod
    public void spotifyAuth(PluginCall call) {
        Intent i = new Intent(getContext(), SpotifyLoginActivity.class);
        i.putExtra("auth_url", call.getString("url", ""));
        i.putExtra("redirect", call.getString("redirect", ""));
        startActivityForResult(call, i, "spotifyAuthDone");
    }

    @ActivityCallback
    private void spotifyAuthDone(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        Intent d = result.getData();
        if (result.getResultCode() == android.app.Activity.RESULT_OK && d != null && d.getStringExtra("url") != null) {
            JSObject o = new JSObject();
            o.put("url", d.getStringExtra("url"));
            call.resolve(o);
        } else {
            call.reject("abgebrochen");
        }
    }

    // ---- Neowake-Sparte ----
    @PluginMethod
    public void openNeowake(PluginCall call) {
        Intent i = new Intent(getContext(), NeowakeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String url = call.getString("url", "");
        if (url != null && NeowakeActivity.isNeowake(url)) {
            i.putExtra("url", url);
        }
        getContext().startActivity(i);
        call.resolve();
    }

    // Laedt eine Neowake-Seite mit der gespeicherten Anmeldung (Cookies nur an neowake.de).
    @PluginMethod
    public void neowakeFetch(final PluginCall call) {
        final String url = call.getString("url", "");
        final boolean probe = call.getBoolean("probe", false);
        final boolean withCookies = call.getBoolean("cookies", true);
        final String method = "POST".equalsIgnoreCase(call.getString("method", "GET")) ? "POST" : "GET";
        final String body = call.getString("body", "");
        final String ctype = call.getString("contentType", "application/x-www-form-urlencoded; charset=UTF-8");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        final Context ctx = getContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    String cur = url;
                    int status = 0;
                    for (int hop = 0; hop < 5; hop++) {
                        c = (HttpURLConnection) new URL(cur).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        String ua = android.webkit.WebSettings.getDefaultUserAgent(ctx);
                        c.setRequestProperty("User-Agent", ua == null ? "Mozilla/5.0" : ua.replace("; wv", ""));
                        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9");
                        CookieManager cm = CookieManager.getInstance();
                        if (withCookies && NeowakeActivity.isNeowake(cur)) {
                            String ck = cm.getCookie(cur);
                            if (ck != null) {
                                c.setRequestProperty("Cookie", ck);
                            }
                        }
                        if (probe) {
                            c.setRequestProperty("Range", "bytes=0-1");
                        }
                        if ("POST".equals(method) && hop == 0) {
                            c.setRequestMethod("POST");
                            c.setDoOutput(true);
                            c.setRequestProperty("Content-Type", ctype);
                            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                            c.setRequestProperty("Origin", "https://app.neowake.de");
                            c.setRequestProperty("Referer", "https://app.neowake.de/favoriten/");
                            byte[] out = body.getBytes("UTF-8");
                            c.setFixedLengthStreamingMode(out.length);
                            OutputStream os = c.getOutputStream();
                            os.write(out);
                            os.close();
                        }
                        status = c.getResponseCode();
                        java.util.List<String> sc = c.getHeaderFields().get("Set-Cookie");
                        if (sc != null && NeowakeActivity.isNeowake(cur)) {
                            for (String v : sc) {
                                cm.setCookie(cur, v);
                            }
                            cm.flush();
                        }
                        if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
                            cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        break;
                    }
                    String text = "";
                    if (!probe) {
                        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        if (in != null) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) > 0 && bo.size() < 6 * 1024 * 1024) {
                                bo.write(buf, 0, n);
                            }
                            in.close();
                            text = bo.toString("UTF-8");
                        }
                    }
                    JSObject o = new JSObject();
                    o.put("status", status);
                    o.put("type", c.getContentType());
                    o.put("url", cur);
                    o.put("text", text);
                    call.resolve(o);
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

    // Laedt einen Neowake-Titel herunter und legt ihn im Musik-Ordner unter "Neowake" ab.
    @PluginMethod
    public void neowakeDownload(final PluginCall call) {
        final String url = call.getString("url", "");
        final String id = call.getString("id", "");
        final String name = clean(call.getString("name", "Neowake"));
        final String album = clean(call.getString("album", "Neowake"));
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        final Context ctx = getContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                Uri item = null;
                try {
                    String cur = url;
                    int status = 0;
                    for (int hop = 0; hop < 6; hop++) {
                        c = (HttpURLConnection) new URL(cur).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(60000);
                        String ua = android.webkit.WebSettings.getDefaultUserAgent(ctx);
                        c.setRequestProperty("User-Agent", ua == null ? "Mozilla/5.0" : ua.replace("; wv", ""));
                        if (NeowakeActivity.isNeowake(cur)) {
                            String ck = CookieManager.getInstance().getCookie(cur);
                            if (ck != null) {
                                c.setRequestProperty("Cookie", ck);
                            }
                            c.setRequestProperty("Referer", "https://app.neowake.de/");
                        }
                        status = c.getResponseCode();
                        if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
                            cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        break;
                    }
                    if (status != 200) {
                        call.reject("Neowake antwortet mit " + status);
                        return;
                    }
                    String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                    if (type.contains("text/html")) {
                        call.reject("Neowake hat statt der Datei eine Webseite geschickt (angemeldet?)");
                        return;
                    }
                    String path = Uri.parse(cur).getPath() == null ? "" : Uri.parse(cur).getPath().toLowerCase();
                    String ext = path.endsWith(".m4a") ? "m4a" : path.endsWith(".wav") ? "wav" : path.endsWith(".ogg") ? "ogg" : path.endsWith(".flac") ? "flac" : "mp3";
                    String mime = ext.equals("m4a") ? "audio/mp4" : ext.equals("wav") ? "audio/wav" : ext.equals("ogg") ? "audio/ogg" : ext.equals("flac") ? "audio/flac" : "audio/mpeg";
                    String file = name + "." + ext;
                    OutputStream os;
                    File legacy = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Audio.Media.DISPLAY_NAME, file);
                        v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                        v.put(MediaStore.Audio.Media.TITLE, name);
                        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Neowake/" + album);
                        v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
                        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                        item = ctx.getContentResolver().insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                        if (item == null) {
                            call.reject("Speichern nicht moeglich");
                            return;
                        }
                        os = ctx.getContentResolver().openOutputStream(item);
                    } else {
                        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Neowake/" + album);
                        dir.mkdirs();
                        legacy = new File(dir, file);
                        os = new FileOutputStream(legacy);
                    }
                    long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int n;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != last && pct % 5 == 0) {
                                last = pct;
                                JSObject p = new JSObject();
                                p.put("id", id);
                                p.put("percent", pct);
                                notifyListeners("nwDownload", p);
                            }
                        }
                    }
                    in.close();
                    os.close();
                    if (got < 20000) {
                        throw new Exception("Datei zu klein (" + got + " Bytes)");
                    }
                    if (item != null) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                        ctx.getContentResolver().update(item, done, null, null);
                    } else if (legacy != null) {
                        android.media.MediaScannerConnection.scanFile(ctx, new String[] { legacy.getAbsolutePath() }, new String[] { mime }, null);
                    }
                    JSObject r = new JSObject();
                    r.put("size", got);
                    r.put("file", file);
                    call.resolve(r);
                } catch (Exception e) {
                    if (item != null) {
                        try {
                            ctx.getContentResolver().delete(item, null, null);
                        } catch (Exception ignore) {
                            // ignore
                        }
                    }
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    private static String clean(String s) {
        if (s == null) {
            return "Neowake";
        }
        String t = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t.isEmpty() ? "Neowake" : t;
    }

    @PluginMethod
    public void neowakeState(PluginCall call) {
        JSObject o = new JSObject();
        o.put("open", NeowakeActivity.current.get() != null);
        o.put("state", NeowakeActivity.state);
        call.resolve(o);
    }

    @PluginMethod
    public void neowakeControl(PluginCall call) {
        NeowakeActivity.control(call.getString("cmd", "toggle"));
        call.resolve();
    }

    @PluginMethod
    public void neowakeRun(final PluginCall call) {
        final String code = call.getString("code", "");
        final int timeout = call.getInt("timeout", 30000);
        if (!NeowakeActivity.run(code)) {
            call.reject("Neowake ist nicht offen");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                long end = System.currentTimeMillis() + timeout;
                while (NeowakeActivity.result.isEmpty() && System.currentTimeMillis() < end) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                JSObject o = new JSObject();
                o.put("result", NeowakeActivity.result);
                call.resolve(o);
            }
        }).start();
    }

    @PluginMethod
    public void neowakeDiag(final PluginCall call) {
        boolean ok = NeowakeActivity.collectDiag(new Runnable() {
            @Override
            public void run() {
                JSObject o = new JSObject();
                o.put("diag", NeowakeActivity.diag);
                call.resolve(o);
            }
        });
        if (!ok) {
            call.reject("Neowake ist nicht offen");
        }
    }

    @PluginMethod
    public void neowakeStatus(PluginCall call) {
        String c = CookieManager.getInstance().getCookie("https://app.neowake.de");
        JSObject o = new JSObject();
        o.put("hasSession", c != null && c.length() > 0);
        call.resolve(o);
    }

    @PluginMethod
    public void neowakeLogout(PluginCall call) {
        CookieManager cm = CookieManager.getInstance();
        for (String u : new String[] { "https://app.neowake.de", "https://neowake.de", "https://www.neowake.de" }) {
            String c = cm.getCookie(u);
            if (c == null) {
                continue;
            }
            for (String part : c.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.isEmpty()) {
                    continue;
                }
                String exp = name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                cm.setCookie(u, exp);
                cm.setCookie(u, exp + "; Domain=.neowake.de");
                cm.setCookie(u, exp + "; Domain=app.neowake.de");
            }
        }
        cm.flush();
        call.resolve();
    }

    @PluginMethod
    public void spotifyStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("loggedIn", SpotifyLoginActivity.loggedIn());
        call.resolve(o);
    }

    @PluginMethod
    public void spotifyLogout(final PluginCall call) {
        CookieManager.getInstance().removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean ok) {
                CookieManager.getInstance().flush();
                call.resolve();
            }
        });
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
