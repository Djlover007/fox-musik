package de.foxmusik.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

// Musik-Wecker: weckt zur eingestellten Zeit und startet die Musik im Fox-Player.
public class FoxAlarm extends BroadcastReceiver {

    static final String ACT = "de.foxmusik.app.ALARM";
    static final String CH = "fox_alarm";
    static volatile boolean pending = false;

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static PendingIntent firePi(Context ctx) {
        Intent i = new Intent(ctx, FoxAlarm.class).setAction(ACT);
        return PendingIntent.getBroadcast(ctx, 4242, i, flags());
    }

    static PendingIntent openPi(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("fox_alarm", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 4243, i, flags());
    }

    static String schedule(Context ctx, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        prefs(ctx).edit().putLong("at", at).apply();
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, openPi(ctx)), firePi(ctx));
            return "exact";
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePi(ctx));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, firePi(ctx));
            }
            return "inexact";
        }
    }

    static void cancel(Context ctx) {
        prefs(ctx).edit().remove("at").apply();
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(firePi(ctx));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("fox_alarm", Context.MODE_PRIVATE);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.MY_PACKAGE_REPLACED".equals(a)) {
            long at = prefs(ctx).getLong("at", 0);
            if (at > System.currentTimeMillis()) {
                schedule(ctx, at);
            }
            return;
        }
        if (!ACT.equals(a)) {
            return;
        }
        prefs(ctx).edit().remove("at").apply();
        pending = true;
        if (PlaybackPlugin.inst != null) {
            PlaybackPlugin.emitData("alarm", "");
        }
        // Bildschirm einschalten und App zeigen (Vollbild-Benachrichtigung wie bei einem Wecker)
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "Wecker", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CH) : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Guten Morgen")
                .setContentText("Fox Musik weckt dich mit Musik")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openPi(ctx))
                .setFullScreenIntent(openPi(ctx), true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_MAX);
            }
            nm.notify(4244, b.build());
        } catch (Exception ignore) {
            // ignore
        }
        try {
            ctx.startActivity(new Intent(ctx, MainActivity.class).putExtra("fox_alarm", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignore) {
            // Start aus dem Hintergrund nicht erlaubt: die Benachrichtigung uebernimmt
        }
    }
}
