package de.foxmusik.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Party-Modus: kleiner Webserver im WLAN. Gaeste oeffnen die Seite per QR-Code
// und koennen sich Titel aus der Bibliothek wuenschen.
public class PartyServer {
    static final int PORT = 8642;
    static volatile String html = "";
    static volatile String songs = "[]";
    static volatile String now = "{}";
    private static ServerSocket ss;
    private static Thread th;
    private static ExecutorService pool;
    private static final Map<String, Long> last = new HashMap<>();

    static synchronized boolean running() {
        return ss != null && !ss.isClosed();
    }

    static synchronized void start() throws Exception {
        if (running()) {
            return;
        }
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new java.net.InetSocketAddress(PORT));
        ss = s;
        pool = Executors.newFixedThreadPool(4);
        th = new Thread(() -> {
            while (running()) {
                try {
                    final Socket c = ss.accept();
                    pool.execute(() -> handle(c));
                } catch (Exception e) {
                    // geschlossen
                }
            }
        }, "FoxParty");
        th.start();
    }

    static synchronized void stop() {
        try {
            if (ss != null) {
                ss.close();
            }
        } catch (Exception e) {
            // ignore
        }
        ss = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    static String ip() {
        String best = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName() == null ? "" : ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String h = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) {
                        return h;
                    }
                    if (a.isSiteLocalAddress() && best == null && !n.startsWith("rmnet")) {
                        best = h;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return best;
    }

    static String qr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) {
            return m;
        }
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            try {
                if (i > 0) {
                    m.put(URLDecoder.decode(p.substring(0, i), "UTF-8"), URLDecoder.decode(p.substring(i + 1), "UTF-8"));
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return m;
    }

    private static void send(Socket c, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n" +
            "Content-Type: " + type + "; charset=utf-8\r\n" +
            "Content-Length: " + b.length + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n";
        OutputStream os = c.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }

    private static void handle(Socket c) {
        try {
            c.setSoTimeout(8000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) {
                c.close();
                return;
            }
            String l;
            while ((l = r.readLine()) != null && l.length() > 0) {
                // Header ueberspringen
            }
            String[] parts = line.split(" ");
            String full = parts.length > 1 ? parts[1] : "/";
            String path = full, q = null;
            int qi = full.indexOf('?');
            if (qi >= 0) {
                path = full.substring(0, qi);
                q = full.substring(qi + 1);
            }
            if (path.equals("/") || path.equals("/index.html")) {
                send(c, 200, "text/html", html);
            } else if (path.equals("/songs")) {
                send(c, 200, "application/json", songs);
            } else if (path.equals("/now")) {
                send(c, 200, "application/json", now);
            } else if (path.equals("/wish")) {
                Map<String, String> m = query(q);
                String id = m.get("id"), name = m.get("n");
                String who = c.getInetAddress() == null ? "?" : c.getInetAddress().getHostAddress();
                long t = System.currentTimeMillis();
                Long prev;
                synchronized (last) {
                    prev = last.get(who);
                    if (prev == null || t - prev > 8000) {
                        last.put(who, t);
                    }
                }
                if (prev != null && t - prev <= 8000) {
                    send(c, 200, "application/json", "{\"ok\":false,\"wait\":true}");
                } else if (id == null || id.length() == 0 || id.length() > 200) {
                    send(c, 400, "application/json", "{\"ok\":false}");
                } else {
                    String nm = name == null ? "" : name.replace("\n", " ");
                    if (nm.length() > 30) {
                        nm = nm.substring(0, 30);
                    }
                    PlaybackPlugin.emitData("party", id + "\n" + nm);
                    send(c, 200, "application/json", "{\"ok\":true}");
                }
            } else {
                send(c, 404, "text/plain", "Nicht gefunden");
            }
            c.close();
        } catch (Exception e) {
            try {
                c.close();
            } catch (Exception x) {
                // ignore
            }
        }
    }
}
