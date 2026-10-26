package de.foxmusik.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Playback")
public class PlaybackPlugin extends Plugin {

    static PlaybackPlugin inst;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        inst = this;
    }

    // Wird vom Dienst aufgerufen, wenn Benachrichtigung / Sperrbildschirm / Kopfhörer eine Taste melden.
    static void emit(String action, long position) {
        if (inst == null) {
            return;
        }
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("position", position);
        inst.notifyListeners("action", o);
    }

    @PluginMethod
    public void update(PluginCall call) {
        final Context ctx = getContext();
        JSObject d = call.getData();
        if (d.optBoolean("stop", false)) {
            PlaybackService.playing = false;
            PlaybackService.cover = null;
            main.post(new Runnable() {
                @Override
                public void run() {
                    ctx.stopService(new Intent(ctx, PlaybackService.class));
                }
            });
            call.resolve();
            return;
        }
        if (d.optBoolean("newTrack", false)) {
            PlaybackService.cover = null;
        }
        PlaybackService.title = d.optString("title", "");
        PlaybackService.artist = d.optString("artist", "");
        PlaybackService.album = d.optString("album", "");
        PlaybackService.duration = d.optLong("duration", 0);
        PlaybackService.position = d.optLong("position", 0);
        PlaybackService.playing = d.optBoolean("playing", false);
        PlaybackService.speed = (float) d.optDouble("speed", 1.0);
        PlaybackService.lock = d.optBoolean("lock", true);
        main.post(new Runnable() {
            @Override
            public void run() {
                PlaybackService svc = PlaybackService.inst;
                if (svc != null) {
                    svc.refresh(false);
                } else if (PlaybackService.playing) {
                    try {
                        ContextCompat.startForegroundService(ctx, new Intent(ctx, PlaybackService.class));
                    } catch (Exception e) {
                        // App im Hintergrund: Start nicht erlaubt
                    }
                }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void setCover(PluginCall call) {
        try {
            byte[] raw = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            PlaybackService.cover = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            PlaybackService.cover = null;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                PlaybackService svc = PlaybackService.inst;
                if (svc != null) {
                    svc.refresh(false);
                }
            }
        });
        call.resolve();
    }
}
