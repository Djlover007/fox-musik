package de.foxmusik.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

// Startbildschirm-Widget: Cover, Titel, Interpret, Zurueck / Play-Pause / Weiter
public class FoxWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        update(ctx);
    }

    static void update(Context ctx) {
        updateFor(ctx, FoxWidget.class, "fox_widget", 240);
        updateFor(ctx, FoxWidgetLarge.class, "fox_widget_large", 480);
    }

    static void updateFor(Context ctx, Class<?> cls, String layoutName, int coverPx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, cls));
            if (ids == null || ids.length == 0) {
                return;
            }
            int layout = ctx.getResources().getIdentifier(layoutName, "layout", ctx.getPackageName());
            RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
            boolean has = PlaybackService.title != null && !PlaybackService.title.isEmpty();
            v.setTextViewText(id(ctx, "w_title"), has ? PlaybackService.title : "Fox Musik");
            v.setTextViewText(id(ctx, "w_artist"), has ? PlaybackService.artist : "Tippen zum Öffnen");
            Bitmap c = PlaybackService.cover;
            if (c != null) {
                v.setImageViewBitmap(id(ctx, "w_cover"), scale(c, coverPx));
            } else {
                v.setImageViewResource(id(ctx, "w_cover"), ctx.getResources().getIdentifier("ic_launcher", "mipmap", ctx.getPackageName()));
            }
            v.setImageViewResource(id(ctx, "w_play"), PlaybackService.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            boolean alive = PlaybackService.inst != null && PlaybackPlugin.inst != null;
            PendingIntent open = openApp(ctx);
            v.setOnClickPendingIntent(id(ctx, "w_root"), open);
            v.setOnClickPendingIntent(id(ctx, "w_prev"), alive ? svc(ctx, PlaybackService.A_PREV, 11) : open);
            v.setOnClickPendingIntent(id(ctx, "w_play"), alive ? svc(ctx, PlaybackService.playing ? PlaybackService.A_PAUSE : PlaybackService.A_PLAY, 12) : open);
            v.setOnClickPendingIntent(id(ctx, "w_next"), alive ? svc(ctx, PlaybackService.A_NEXT, 13) : open);
            mgr.updateAppWidget(ids, v);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static int id(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }

    private static Bitmap scale(Bitmap b, int px) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= px) {
            return b;
        }
        float k = (float) px / m;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static PendingIntent svc(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(action);
        return PendingIntent.getService(ctx, req, i, flags());
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) {
            i = new Intent(ctx, MainActivity.class);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(ctx, 10, i, flags());
    }
}
