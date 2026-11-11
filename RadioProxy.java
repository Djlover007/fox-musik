package de.foxmusik.app;

import android.webkit.WebResourceResponse;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Reicht Radio-Streams (auch http und alte ICY-Server) ueber https://localhost/_radio durch.
// Liest dabei den Liedtitel aus den ICY-Metadaten ("Was laeuft gerade").
public class RadioProxy {

    static final Map<String, String> NOW = new ConcurrentHashMap<String, String>();
    static final Map<String, Long> LAST = new ConcurrentHashMap<String, Long>();
    // Endgueltige Adresse nach allen Weiterleitungen (fuer die Titel-Schnittstelle des Streaming-Anbieters)
    static final Map<String, String> FINAL = new ConcurrentHashMap<String, String>();
    // Wann der Titel zuletzt wirklich vom Sender kam (damit ein alter Titel nicht ewig als aktuell gilt)
    static final Map<String, Long> NOWT = new ConcurrentHashMap<String, Long>();

    // Holt alle 15 Sekunden kurz den aktuellen Liedtitel (liest nur bis zum ersten Metadaten-Block).
    static void refresh(final String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Long last = LAST.get(url);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 15000) {
            return;
        }
        LAST.put(url, now);
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    WebResourceResponse r = handle(url);
                    in = r == null ? null : r.getData();
                    if (in == null || !(in instanceof IcyStream)) {
                        return;
                    }
                    byte[] buf = new byte[8192];
                    long read = 0;
                    String before = NOW.get(url);
                    long until = System.currentTimeMillis() + 12000;
                    while (read < 200000 && System.currentTimeMillis() < until) {
                        int n = in.read(buf, 0, buf.length);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                        if (((IcyStream) in).titleSeen) {
                            break;
                        }
                    }
                } catch (Exception ignore) {
                    // ignore
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }
        }).start();
    }

    static WebResourceResponse handle(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        Map<String, String> out = new HashMap<String, String>();
        out.put("Access-Control-Allow-Origin", "*");
        out.put("Cache-Control", "no-store");
        try {
            String cur = url;
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c;
                int status;
                try {
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "FoxMusik/1.0");
                    c.setRequestProperty("Icy-MetaData", "1");
                    c.setRequestProperty("Accept", "*/*");
                    status = c.getResponseCode();
                } catch (IOException e) {
                    String m = String.valueOf(e.getMessage());
                    if (cur.startsWith("http://") && (m.contains("ICY") || m.contains("status line") || m.contains("Unexpected"))) {
                        return raw(cur, url, out);
                    }
                    throw e;
                }
                if (status < 100 && cur.startsWith("http://")) {
                    c.disconnect();
                    return raw(cur, url, out);
                }
                if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
                    cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (status >= 400) {
                    return new WebResourceResponse("text/plain", "utf-8", status, "Error", out, null);
                }
                String type = c.getContentType() == null ? "audio/mpeg" : c.getContentType().split(";")[0].trim().toLowerCase();
                if (type.startsWith("text/html")) {
                    return new WebResourceResponse("text/plain", "utf-8", 415, "Unsupported", out, null);
                }
                FINAL.put(url, cur);
                InputStream in = c.getInputStream();
                int metaint = 0;
                try {
                    metaint = Integer.parseInt(String.valueOf(c.getHeaderField("icy-metaint")).trim());
                } catch (Exception ignore) {
                    metaint = 0;
                }
                if (metaint > 0 && !type.startsWith("image/")) {
                    in = new IcyStream(in, metaint, url);
                }
                if (type.startsWith("image/")) {
                    String len = c.getHeaderField("Content-Length");
                    if (len != null) {
                        out.put("Content-Length", len);
                    }
                }
                return new WebResourceResponse(type, null, 200, "OK", out, in);
            }
            return new WebResourceResponse("text/plain", "utf-8", 508, "Loop", out, null);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", out, null);
        }
    }

    // Alte SHOUTcast-Server antworten mit "ICY 200 OK", das versteht HttpURLConnection nicht.
    private static WebResourceResponse raw(String cur, String key, Map<String, String> out) throws IOException {
        FINAL.put(key, cur);
        URL u = new URL(cur);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), 12000);
        s.setSoTimeout(30000);
        String path = (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath()) + (u.getQuery() != null ? "?" + u.getQuery() : "");
        OutputStream os = s.getOutputStream();
        os.write(("GET " + path + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\nUser-Agent: FoxMusik/1.0\r\nIcy-MetaData: 1\r\nAccept: */*\r\n\r\n").getBytes("ISO-8859-1"));
        os.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String line;
        int metaint = 0;
        String type = "audio/mpeg";
        boolean first = true;
        while ((line = readLine(in)) != null && line.length() > 0) {
            if (first) {
                first = false;
                if (!line.contains("200")) {
                    s.close();
                    return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", out, null);
                }
                continue;
            }
            int i = line.indexOf(':');
            if (i < 0) {
                continue;
            }
            String k = line.substring(0, i).trim().toLowerCase();
            String v = line.substring(i + 1).trim();
            if (k.equals("icy-metaint")) {
                try {
                    metaint = Integer.parseInt(v);
                } catch (Exception ignore) {
                    metaint = 0;
                }
            } else if (k.equals("content-type")) {
                type = v.split(";")[0].trim().toLowerCase();
            }
        }
        InputStream body = metaint > 0 ? new IcyStream(in, metaint, key) : in;
        return new WebResourceResponse(type, null, 200, "OK", out, body);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.append((char) c);
            }
            if (b.length() > 4096) {
                break;
            }
        }
        return c == -1 && b.length() == 0 ? null : b.toString();
    }

    // Entfernt die ICY-Metadaten aus dem Audiostrom und merkt sich den Liedtitel.
    static class IcyStream extends FilterInputStream {
        private final int metaint;
        private final String key;
        private int until;
        volatile boolean seenMeta;
        volatile boolean titleSeen = false;

        IcyStream(InputStream in, int metaint, String key) {
            super(in);
            this.metaint = metaint;
            this.key = key;
            this.until = metaint;
        }

        private void readMeta() throws IOException {
            int lb = in.read();
            if (lb < 0) {
                return;
            }
            int len = lb * 16;
            if (len > 0) {
                byte[] m = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(m, got, len - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
                String s;
                try {
                    s = new String(m, 0, got, "UTF-8");
                    if (s.contains("�")) {
                        s = new String(m, 0, got, "ISO-8859-1");
                    }
                } catch (Exception e) {
                    s = "";
                }
                int a = s.indexOf("StreamTitle='");
                if (a >= 0) {
                    int b = s.indexOf("';", a + 13);
                    String t = (b > a ? s.substring(a + 13, b) : s.substring(a + 13)).trim();
                    if (t.length() > 0) {
                        NOW.put(key, t);
                        NOWT.put(key, System.currentTimeMillis());
                        titleSeen = true;
                    }
                }
            }
            until = metaint;
            seenMeta = true;
        }

        @Override
        public int read() throws IOException {
            if (until == 0) {
                readMeta();
            }
            int r = in.read();
            if (r >= 0) {
                until--;
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (until == 0) {
                readMeta();
            }
            int n = in.read(b, off, Math.min(len, until));
            if (n > 0) {
                until -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] tmp = new byte[4096];
            long done = 0;
            while (done < n) {
                int r = read(tmp, 0, (int) Math.min(tmp.length, n - done));
                if (r <= 0) {
                    break;
                }
                done += r;
            }
            return done;
        }

        @Override
        public int available() throws IOException {
            return Math.min(in.available(), until);
        }
    }
}
