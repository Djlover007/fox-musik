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
    // Titelwechsel waehrend der Aufnahme: Position in Bytes + Text aus dem Sender ("Interpret - Titel")
    static final java.util.List<String[]> tracks = java.util.Collections.synchronizedList(new java.util.ArrayList<String[]>());

    // Sucht den Anfang des ersten vollstaendigen Ton-Pakets, damit die Datei nicht mitten in einem Paket beginnt (das knackt).
    private static int adtsLen(byte[] d, int i, int len) {
        if (i + 6 >= len || (d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xF6) != 0xF0) {
            return -1;
        }
        int l = ((d[i + 3] & 3) << 11) | ((d[i + 4] & 0xFF) << 3) | ((d[i + 5] & 0xFF) >> 5);
        return l > 7 ? l : -1;
    }

    private static final int[] BR1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BR2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SR1 = {44100, 48000, 32000};

    private static int mp3Len(byte[] d, int i, int len) {
        if (i + 4 >= len || (d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xE0) != 0xE0) {
            return -1;
        }
        int ver = (d[i + 1] >> 3) & 3, layer = (d[i + 1] >> 1) & 3;
        int bi = (d[i + 2] >> 4) & 0xF, si = (d[i + 2] >> 2) & 3, pad = (d[i + 2] >> 1) & 1;
        if (ver == 1 || layer != 1 || bi == 0 || bi == 15 || si == 3) {
            return -1;
        }
        int br = (ver == 3 ? BR1[bi] : BR2[bi]) * 1000;
        int sr = SR1[si] >> (ver == 3 ? 0 : ver == 2 ? 1 : 2);
        return (ver == 3 ? 144 : 72) * br / sr + pad;
    }

    static int syncOffset(byte[] d, int len, boolean aac) {
        for (int i = 0; i + 8 < len; i++) {
            int a = aac ? adtsLen(d, i, len) : mp3Len(d, i, len);
            if (a > 0 && i + a + 8 < len) {
                int b = aac ? adtsLen(d, i + a, len) : mp3Len(d, i + a, len);
                if (b > 0 && i + a + b + 8 < len && (aac ? adtsLen(d, i + a + b, len) : mp3Len(d, i + a + b, len)) > 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    static synchronized boolean start(final Context ctx, final String url, final String station) {
        if (running) {
            return false;
        }
        running = true;
        stop = false;
        bytes = 0;
        error = "";
        tracks.clear();
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
                    if (mime.contains("mpegurl") || mime.startsWith("text/")) {
                        throw new Exception("Dieser Sender sendet in einem Format, das sich nicht aufnehmen lässt");
                    }
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
                    // Anfang puffern und erst ab dem ersten vollstaendigen Ton-Paket schreiben
                    java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                    while (!stop && head.size() < 24000 && (n = in.read(buf)) > 0) {
                        head.write(buf, 0, n);
                    }
                    byte[] hb = head.toByteArray();
                    int off = ext.equals("ogg") ? 0 : syncOffset(hb, hb.length, ext.equals("aac"));
                    os.write(hb, off, hb.length - off);
                    bytes += hb.length - off;
                    String lastT = null;
                    while (!stop && System.currentTimeMillis() < maxEnd) {
                        String t = RadioProxy.NOW.get(url);
                        if (t != null && t.length() > 0 && !t.equals(lastT)) {
                            lastT = t;
                            tracks.add(new String[]{String.valueOf(tracks.isEmpty() ? 0 : bytes), t});
                        }
                        n = in.read(buf);
                        if (n <= 0) {
                            break;
                        }
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
