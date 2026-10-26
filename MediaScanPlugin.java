package de.foxmusik.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaScan")
public class MediaScanPlugin extends Plugin {

    private String permissionName() {
        return Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(getContext(), permissionName())
            == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void requestAccess(final PluginCall call) {
        if (hasPermission() || getActivity() == null) {
            call.resolve();
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ActivityCompat.requestPermissions(getActivity(), new String[] { permissionName() }, 7421);
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (!hasPermission()) {
            call.reject("permission");
            return;
        }
        try {
            String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            };
            String selection = "(" + MediaStore.Audio.Media.IS_MUSIC + " != 0 OR "
                + MediaStore.Audio.Media.DATA + " LIKE '%.mp3') AND "
                + MediaStore.Audio.Media.DURATION + " > 30000";
            JSArray list = new JSArray();
            Cursor c = getContext().getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            );
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String path = c.getString(5);
                        if (path == null) {
                            continue;
                        }
                        JSObject o = new JSObject();
                        o.put("id", c.getLong(0));
                        o.put("title", clean(c.getString(1)));
                        o.put("artist", clean(c.getString(2)));
                        o.put("album", clean(c.getString(3)));
                        o.put("duration", c.getLong(4) / 1000.0);
                        o.put("path", path);
                        list.put(o);
                    }
                } finally {
                    c.close();
                }
            }
            JSObject ret = new JSObject();
            ret.put("songs", list);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("scan-failed: " + e.getMessage());
        }
    }

    private String clean(String s) {
        if (s == null || s.equals("<unknown>")) {
            return "";
        }
        return s;
    }
}
