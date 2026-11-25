package de.foxmusik.app;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;

// Radio zurueckspulen: schreibt den laufenden Sender in eine Datei im Cache mit.
// Die App liest daraus Stueck fuer Stueck und speist es selbst in den Player ein.
// Gelesen wird ueber denselben Weg wie bei der Aufnahme (RadioProxy.handle), der auf den Handys funktioniert.
public class TsBuf {
    static final long MAX = 160L * 1024 * 1024;
    static volatile int gen = 0;
    static volatile boolean running = false;
    static volatile long bytes = 0;
    static volatile long started = 0;
    static volatile String ext = "";
    static volatile String error = "";
    static volatile File file = null;
    private static volatile InputStream curIn = null;

    static synchronized void start(Context ctx, final String url) {
        stopNow();
        final int my = ++gen;
        for (File old : ctx.getCacheDir().listFiles()) {
            if (old.getName().startsWith("fox-zeitversetzt-")) {
                old.delete();
            }
        }
        final File f = new File(ctx.getCacheDir(), "fox-zeitversetzt-" + my + ".bin");
        file = f;
        bytes = 0;
        ext = "";
        error = "";
        started = System.currentTimeMillis();
        running = true;
        new Thread(() -> {
            InputStream in = null;
            RandomAccessFile out = null;
            try {
                WebResourceResponse r = RadioProxy.handle(url);
                in = r == null ? null : r.getData();
                if (in == null) {
                    throw new Exception("Sender nicht erreichbar");
                }
                curIn = in;
                String mime = r.getMimeType() == null ? "audio/mpeg" : r.getMimeType().toLowerCase();
                if (mime.contains("mpegurl") || mime.startsWith("text/")) {
                    throw new Exception("Sender sendet eine Playlist statt eines Streams");
                }
                if (mime.contains("ogg") || mime.contains("opus") || mime.contains("flac")) {
                    throw new Exception("Format wird nicht unterstützt (" + mime + ")");
                }
                String e = mime.contains("aac") || mime.contains("mp4") ? "aac" : "mp3";
                out = new RandomAccessFile(f, "rw");
                byte[] buf = new byte[32768];
                int n;
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                while (gen == my && head.size() < 16000 && (n = in.read(buf)) > 0) {
                    head.write(buf, 0, n);
                }
                byte[] hb = head.toByteArray();
                int off = RadioRec.syncOffset(hb, hb.length, e.equals("aac"));
                out.write(hb, off, hb.length - off);
                if (gen != my) {
                    return;
                }
                bytes = hb.length - off;
                ext = e;
                long pos = bytes;
                while (gen == my && pos < MAX) {
                    n = in.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    pos += n;
                    if (gen == my) {
                        bytes = pos;
                    }
                }
                if (gen == my) {
                    error = pos >= MAX ? "Puffer voll" : "Verbindung zum Sender beendet";
                }
            } catch (Exception ex) {
                if (gen == my) {
                    error = String.valueOf(ex.getMessage());
                }
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (Exception ignore) {
                    // ignore
                }
                if (gen == my) {
                    running = false;
                }
            }
        }, "FoxZeitversetzt").start();
    }

    static synchronized void stopNow() {
        gen++;
        running = false;
        InputStream in = curIn;
        curIn = null;
        if (in != null) {
            new Thread(() -> {
                try {
                    in.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }).start();
        }
    }

    // Liest ab Byte "from" hoechstens "max" Bytes. Mit align wird ab dem naechsten vollstaendigen Ton-Paket geliefert.
    // Rueckgabe: {tatsaechlicher Start, Daten}
    static Object[] read(long from, int max, boolean align) throws Exception {
        File f = file;
        long b = bytes;
        if (f == null || from >= b || max <= 0) {
            return new Object[]{from, new byte[0]};
        }
        int len = (int) Math.min(max, b - from);
        byte[] d = new byte[len];
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(from);
            r.readFully(d);
        } finally {
            r.close();
        }
        if (align) {
            int off = RadioRec.syncOffset(d, d.length, "aac".equals(ext));
            if (off > 0) {
                byte[] x = new byte[d.length - off];
                System.arraycopy(d, off, x, 0, x.length);
                return new Object[]{from + off, x};
            }
        }
        return new Object[]{from, d};
    }
}
