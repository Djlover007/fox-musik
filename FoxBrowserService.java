package de.foxmusik.app;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Android Auto: zeigt Bibliothek, Favoriten, Playlists und Radiosender an.
// Abgespielt wird ueber die gemeinsame Mediensitzung im Fox-Player der App.
public class FoxBrowserService extends MediaBrowserService {

    static volatile JSONObject tree = new JSONObject();
    static FoxBrowserService inst;

    static void setTree(String json) {
        try {
            tree = new JSONObject(json);
        } catch (Exception e) {
            return;
        }
        FoxBrowserService s = inst;
        if (s != null) {
            try {
                s.notifyChildrenChanged("root");
                JSONArray names = tree.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        s.notifyChildrenChanged(names.getString(i));
                    }
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        setSessionToken(FoxSession.get(this).getSessionToken());
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> out = new ArrayList<MediaBrowser.MediaItem>();
        JSONArray arr = tree.optJSONArray(parentId);
        if (arr == null || arr.length() == 0) {
            if ("root".equals(parentId)) {
                out.add(item("__open", "Fox Musik öffnen", "Öffne die App einmal auf dem Handy, dann erscheint hier deine Musik", false));
            }
            result.sendResult(out);
            return;
        }
        for (int i = 0; i < arr.length() && i < 400; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(item(o.optString("id"), o.optString("t"), o.optString("s"), o.optBoolean("b", false)));
        }
        result.sendResult(out);
    }

    private static MediaBrowser.MediaItem item(String id, String title, String sub, boolean browsable) {
        MediaDescription d = new MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build();
        return new MediaBrowser.MediaItem(d, browsable ? MediaBrowser.MediaItem.FLAG_BROWSABLE : MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }
}
