package io.github.emordnilap67.mymusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads in progress: the card on the home screen (as JSON for the
 * page), the running notification, and the "Added N songs" note at the end.
 */
final class Progress {
    private Progress() {}

    /** downloads in progress: folder -> {phase, done, total, text} (as JSON) */
    static final Map<String, String> running = new LinkedHashMap<>();

    /** when each folder's last download finished: late reports are ignored */
    static final Map<String, Long> finished = new LinkedHashMap<>();

    static synchronized String progressJson() {
        StringBuilder b = new StringBuilder("[");
        for (String v : running.values()) {
            if (b.length() > 1) b.append(',');
            b.append(v);
        }
        return b.append(']').toString();
    }

    static synchronized boolean justFinished(String folder) {
        Long t = finished.get(folder);
        return t != null && System.currentTimeMillis() - t < 30000;
    }

    static synchronized void update(String folder, String phase, int done, int total, String text) {
        if (phase == null) {
            running.remove(folder);
            finished.put(folder, System.currentTimeMillis());
            return;
        }
        if (!"Starting".equals(text) && justFinished(folder)) return;
        if ("Starting".equals(text)) finished.remove(folder);
        else running.put(folder, "{\"folder\":" + Library.q(folder) + ",\"phase\":" + Library.q(phase)
                + ",\"done\":" + done + ",\"total\":" + total + ",\"text\":" + Library.q(text) + "}");
    }

    static int noteId(String folder) {
        return 200 + (folder.hashCode() & 0xffff);
    }

    private static int small(Context c, int fallback) {
        int id = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        return id == 0 ? fallback : id;
    }

    private static PendingIntent open(Context c) {
        return PendingIntent.getActivity(c, 15, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** the running notification (also shown when the app is closed) */
    static Notification progressNote(Context c, String folder, String phase, int done, int total, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("progress", "Download progress", NotificationManager.IMPORTANCE_LOW));
        String line = "reading".equals(phase) ? "Reading the playlist..."
                : String.format(Locale.US, "%,d of %,d", done, total);
        if (text != null && !text.isEmpty()) line += " - " + text;
        return new Notification.Builder(c, "progress").setSmallIcon(small(c, android.R.drawable.stat_sys_download))
                .setContentTitle("Downloading into " + folder).setContentText(line)
                .setProgress(Math.max(total, 0), Math.max(done, 0), total <= 0)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(c)).build();
    }

    /** what came in (or why not), when a download ends */
    static void announce(Context c, String msg, String folder) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_DEFAULT));
            // the playlist as the title, and the whole message when pulled down
            String title = folder == null || folder.isEmpty() || folder.equals(DownloadService.READING) ? "MY MUSIC" : folder;
            Notification n = new Notification.Builder(c, "downloads").setSmallIcon(small(c, android.R.drawable.stat_sys_download_done))
                    .setContentTitle(title).setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setContentIntent(open(c)).setAutoCancel(true).build();
            nm.notify(folder == null ? 199 : noteId(folder), n);
        } catch (Exception ignored) {
        }
    }
}
