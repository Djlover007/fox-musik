package de.foxmusik.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

// Haelt die App im Hintergrund am Leben und zeigt die Mediensteuerung
// (Benachrichtigung + Sperrbildschirm). Die Musik selbst spielt weiter in der WebView.
public class PlaybackService extends Service {

    static final String CH = "fox_playback";
    static final int NID = 4711;
    static final String A_PLAY = "de.foxmusik.app.PLAY";
    static final String A_PAUSE = "de.foxmusik.app.PAUSE";
    static final String A_NEXT = "de.foxmusik.app.NEXT";
    static final String A_PREV = "de.foxmusik.app.PREV";

    static PlaybackService inst;
    static String title = "", artist = "", album = "";
    static long duration = 0, position = 0;
    static boolean playing = false, lock = true;
    static float speed = 1f;
    static Bitmap cover = null;

    private MediaSession session;
    private boolean foreground = false;
    private PowerManager.WakeLock wake;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Wiedergabe", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        session = FoxSession.get(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "foxmusik:playback");
        wake.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (A_PLAY.equals(a)) {
            PlaybackPlugin.emit("play", 0);
        } else if (A_PAUSE.equals(a)) {
            PlaybackPlugin.emit("pause", 0);
        } else if (A_NEXT.equals(a)) {
            PlaybackPlugin.emit("next", 0);
        } else if (A_PREV.equals(a)) {
            PlaybackPlugin.emit("prev", 0);
        }
        // Wurde der Dienst per startForegroundService gestartet, muss er sofort in den Vordergrund.
        refresh(a == null);
        return START_NOT_STICKY;
    }

    void refresh(boolean force) {
        try {
            FoxSession.update();
            FoxWidget.update(this);

            Notification n = buildNotification();
            if (playing || (force && !foreground)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NID, n);
                }
                foreground = true;
            } else {
                if (foreground) {
                    stopForeground(false);
                    foreground = false;
                }
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID, n);
            }
            if (playing) {
                if (!wake.isHeld()) {
                    wake.acquire(6 * 60 * 60 * 1000L);
                }
            } else if (wake.isHeld()) {
                wake.release();
            }
        } catch (Exception e) {
            // z.B. Start im Hintergrund nicht erlaubt: Wiedergabe laeuft trotzdem weiter
        }
    }

    private PendingIntent pi(String act, int req) {
        Intent i = new Intent(this, PlaybackService.class).setAction(act);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, req, i, fl);
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CH)
            : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.isEmpty() ? "Fox Musik" : title)
            .setContentText(artist)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(lock ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_SECRET);
        if (cover != null) {
            b.setLargeIcon(cover);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        }
        b.addAction(android.R.drawable.ic_media_previous, "Zurück", pi(A_PREV, 1));
        if (playing) {
            b.addAction(android.R.drawable.ic_media_pause, "Pause", pi(A_PAUSE, 2));
        } else {
            b.addAction(android.R.drawable.ic_media_play, "Wiedergabe", pi(A_PLAY, 2));
        }
        b.addAction(android.R.drawable.ic_media_next, "Weiter", pi(A_NEXT, 3));
        b.setStyle(new Notification.MediaStyle()
            .setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        playing = false;
        stopForeground(true);
        FoxSession.update();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        inst = null;
        foreground = false;
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        FoxWidget.update(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
