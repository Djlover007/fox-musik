package de.foxmusik.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Nimmt einen Radio-Stream auf und speichert ihn unter Musik/Fox Radio.
public class RadioRec {

    static volatile boolean running = false;
    static volatile boolean stop = false;
    static volatile long bytes = 0;
    static volatile long started = 0;
    static volatile String file = "";
    static volatile String error = "";

    static synchronized boolean start(final Context ctx, final String url, final String station) {
        if (running) {
            return false;
        }
        running = true;
        stop = false;
        bytes = 0;
        error = "";
        started = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Uri item = null;
                OutputStream os = null;
                InputStream in = null;
                try {
                    WebResourceResponse r = RadioProxy.handle(url);
                    in = r == null ? null : r.getData();
                    if (in == null) {
                        throw new Exception("Sender nicht erreichbar");
                    }
                    String mime = r.getMimeType() == null ? "audio/mpeg" : r.getMimeType();
                    String ext = mime.contains("aac") || mime.contains("mp4") ? "aac" : mime.contains("ogg") ? "ogg" : "mp3";
                    String clean = station.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
                    if (clean.isEmpty()) {
                        clean = "Radio";
                    }
                    String name = clean + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.GERMANY).format(new Date()) + "." + ext;
                    file = name;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                        v.put(MediaStore.Audio.Media.MIME_TYPE, ext.equals("aac") ? "audio/aac" : ext.equals("ogg") ? "audio/ogg" : "audio/mpeg");
                        v.put(MediaStore.Audio.Media.TITLE, clean + " " + new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new Date()));
                        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Fox Radio");
                        v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
                        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                        item = ctx.getContentResolver().insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                        os = ctx.getContentResolver().openOutputStream(item);
                    } else {
                        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Fox Radio");
                        dir.mkdirs();
                        File f = new File(dir, name);
                        os = new FileOutputStream(f);
                    }
                    byte[] buf = new byte[32768];
                    int n;
                    long maxEnd = System.currentTimeMillis() + 4L * 60 * 60 * 1000;
                    while (!stop && System.currentTimeMillis() < maxEnd && (n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        bytes += n;
                    }
                    os.close();
                    os = null;
                    if (item != null) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                        ctx.getContentResolver().update(item, done, null, null);
                    }
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                    if (item != null && bytes < 20000) {
                        try {
                            ctx.getContentResolver().delete(item, null, null);
                        } catch (Exception ignore) {
                            // ignore
                        }
                    } else if (item != null) {
                        try {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                            ctx.getContentResolver().update(item, done, null, null);
                        } catch (Exception ignore) {
                            // ignore
                        }
                    }
                } finally {
                    try {
                        if (os != null) {
                            os.close();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    running = false;
                }
            }
        }).start();
        return true;
    }
}
