package de.foxmusik.app;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Reicht Neowake-Audio ueber https://localhost/_nwproxy durch. Dadurch gilt die Datei fuer die App
// als "eigene" Quelle, und Equalizer, Lautstaerke-Boost und Stille-Erkennung wirken auch bei Streams.
public class NwProxy {

    static boolean allowed(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            return "https".equals(u.getScheme()) && h != null
                && (h.equals("neowake.de") || h.endsWith(".neowake.de") || h.equals("brain-plus.de") || h.endsWith(".brain-plus.de"));
        } catch (Exception e) {
            return false;
        }
    }

    static WebResourceResponse handle(String url, Map<String, String> reqHeaders) {
        if (url == null || !allowed(url)) {
            return null;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FoxMusik");
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    if ("range".equalsIgnoreCase(e.getKey())) {
                        c.setRequestProperty("Range", e.getValue());
                    }
                }
            }
            if (NeowakeActivity.isNeowake(url)) {
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) {
                    c.setRequestProperty("Cookie", ck);
                }
            }
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) {
                status = 502;
            }
            Map<String, String> out = new HashMap<String, String>();
            for (String k : new String[] { "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag" }) {
                String v = c.getHeaderField(k);
                if (v != null) {
                    out.put(k, v);
                }
            }
            if (!out.containsKey("Accept-Ranges")) {
                out.put("Accept-Ranges", "bytes");
            }
            out.put("Access-Control-Allow-Origin", "*");
            out.put("Cache-Control", "no-store");
            String type = c.getContentType();
            String mime = type == null ? "audio/mpeg" : type.split(";")[0].trim();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String reason = status == 206 ? "Partial Content" : status == 200 ? "OK" : "Error";
            return new WebResourceResponse(mime, null, status, reason, out, in);
        } catch (Exception e) {
            Map<String, String> out = new HashMap<String, String>();
            out.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", out, null);
        }
    }
}
