package io.github.emordnilap67.mymusic;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * When YouTube pauses the downloads, MY MUSIC tries again by itself: an alarm an hour later starts the downloader for whatever is
 * still waiting in Pending. If Android will not let it start from the
 * background, a notification says "Tap to keep downloading" instead.
 */
public class Retry extends BroadcastReceiver {
    static final String A_RESUME = "io.github.emordnilap67.mymusic.RESUME";
    private static final int NOTE_ID = 197;

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 31, new Intent(c, Retry.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, long at) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            // an exact alarm is what lets Android start the download again while the app is closed
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c));
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c) {
        try {
            ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c));
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTE_ID);
        } catch (Exception ignored) {
        }
    }

    /** app opened: pick up anything due (or cut off), and make sure the next wait has its alarm */
    static void check(Context c) {
        if (!DownloadService.alive && Pending.any(c)) {
            long next = Pending.nextWait(c);
            boolean due = Pending.json(c).contains("\"retry\":0") || next == 0;
            if (due) {
                try {
                    DownloadService.resume(c);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (next > 0) schedule(c, next);
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            DownloadService.resume(c);
        } catch (Exception e) {
            // not allowed from the background on this phone: one tap brings it back
            Intent open = new Intent(c, MainActivity.class).setAction(A_RESUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 32, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new android.app.NotificationChannel("progress", "Download progress",
                        NotificationManager.IMPORTANCE_LOW));
                int small = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
                if (small == 0) small = android.R.drawable.stat_sys_download;
                Notification n = new Notification.Builder(c, "progress")
                        .setSmallIcon(small)
                        .setContentTitle("YouTube is ready again")
                        .setContentText("Tap to keep downloading your songs")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTE_ID, n);
            } catch (Exception ignored) {
            }
        }
    }
}
