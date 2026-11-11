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
    // Geplante Aufnahme: nach so vielen Minuten von selbst aufhoeren (0 = hoechstens 4 Stunden)
    static volatile int maxMinutes = 0;
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
                    long maxEnd = System.currentTimeMillis() + (maxMinutes > 0 ? Math.min(240, maxMinutes) : 240) * 60L * 1000;
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

    // ---- Aufnahme an den Titelgrenzen in einzelne Dateien aufteilen ----
    private static String mimeOf(String ext) {
        return ext.equals("aac") ? "audio/aac" : ext.equals("ogg") ? "audio/ogg" : "audio/mpeg";
    }

    private static File legacyDir(Context ctx) {
        return new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Fox Radio");
    }

    private static Uri findItem(Context ctx, String file) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DISPLAY_NAME + "=? AND " + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{file, "%Fox Radio%"}, null);
            if (c != null && c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), c.getLong(0));
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    static boolean delete(Context ctx, String file) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = findItem(ctx, file);
                return u != null && ctx.getContentResolver().delete(u, null, null) > 0;
            }
            return new File(legacyDir(ctx), file).delete();
        } catch (Exception e) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] b, int want) throws java.io.IOException {
        int got = 0;
        while (got < want) {
            int n = in.read(b, got, want - got);
            if (n <= 0) {
                break;
            }
            got += n;
        }
        return got;
    }

    interface Sink {
        OutputStream open(int part) throws Exception;

        void done(int part, long bytes) throws Exception;
    }

    // Kern des Aufteilens: liest die Aufnahme einmal durch und wechselt an jeder Schnittstelle die Zieldatei.
    // Getrennt wird am naechsten vollstaendigen Ton-Paket nach der Schnittstelle, damit kein Teil knackt.
    static void splitStream(InputStream in, long[] cuts, boolean aac, boolean ogg, Sink sink) throws Exception {
        byte[] buf = new byte[32768];
        long pos = 0, partBytes = 0;
        int part = 0;
        OutputStream os = sink.open(0);
        try {
            while (true) {
                long next = part + 1 < cuts.length ? cuts[part + 1] : Long.MAX_VALUE;
                if (next <= pos) {
                    int n = readFully(in, buf, 16384);
                    if (n <= 0) {
                        break;
                    }
                    int off = ogg ? 0 : syncOffset(buf, n, aac);
                    os.write(buf, 0, off);
                    partBytes += off;
                    os.close();
                    os = null;
                    sink.done(part, partBytes);
                    part++;
                    os = sink.open(part);
                    os.write(buf, off, n - off);
                    partBytes = n - off;
                    pos += n;
                    continue;
                }
                int n = in.read(buf, 0, (int) Math.min(buf.length, next - pos));
                if (n <= 0) {
                    break;
                }
                os.write(buf, 0, n);
                pos += n;
                partBytes += n;
            }
            os.close();
            os = null;
            sink.done(part, partBytes);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    // Ergebnis je Teil: {Dateiname, Bytes}
    static java.util.List<String[]> split(final Context ctx, String file, long[] cuts, final String[] names) throws Exception {
        final java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        int dot = file.lastIndexOf('.');
        final String ext = dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "mp3";
        InputStream in;
        if (Build.VERSION.SDK_INT >= 29) {
            Uri src = findItem(ctx, file);
            if (src == null) {
                throw new Exception("Aufnahme nicht gefunden");
            }
            in = ctx.getContentResolver().openInputStream(src);
        } else {
            in = new java.io.FileInputStream(new File(legacyDir(ctx), file));
        }
        in = new java.io.BufferedInputStream(in, 65536);
        final Uri[] item = new Uri[1];
        final String[] curName = new String[1];
        try {
            splitStream(in, cuts, ext.equals("aac"), ext.equals("ogg"), new Sink() {
                @Override
                public OutputStream open(int part) throws Exception {
                    String nm = names[part].replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
                    if (nm.length() > 90) {
                        nm = nm.substring(0, 90).trim();
                    }
                    if (nm.isEmpty()) {
                        nm = "Titel " + (part + 1);
                    }
                    item[0] = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        curName[0] = nm + "." + ext;
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Audio.Media.DISPLAY_NAME, curName[0]);
                        v.put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(ext));
                        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Fox Radio");
                        v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
                        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                        item[0] = ctx.getContentResolver().insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                        return ctx.getContentResolver().openOutputStream(item[0]);
                    }
                    File dir = legacyDir(ctx);
                    dir.mkdirs();
                    File f = new File(dir, nm + "." + ext);
                    for (int k = 1; f.exists(); k++) {
                        f = new File(dir, nm + " (" + k + ")." + ext);
                    }
                    curName[0] = f.getName();
                    return new FileOutputStream(f);
                }

                @Override
                public void done(int part, long bytes) {
                    out.add(new String[]{finish(ctx, item[0], curName[0]), String.valueOf(bytes)});
                }
            });
        } finally {
            try {
                in.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return out;
    }

    private static String finish(Context ctx, Uri item, String name) {
        if (item == null) {
            return name;
        }
        try {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(item, done, null, null);
            android.database.Cursor c = ctx.getContentResolver().query(item, new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && c.getString(0) != null) {
                        return c.getString(0);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return name;
    }
}
