package io.github.emordnilap67.mymusic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Sync extends BroadcastReceiver {
    static final long EVERY_MS = 6L * 3600 * 1000;
    /** new songs per check, at most */
    static final int MAX = 40;
    private static final String TICK = "io.github.emordnilap67.mymusic.SYNC";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    /** playlist folder (as named on the phone) -> its YouTube playlist link; only folders that still exist */
    static Map<String, String> links(Context c) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, ?> all = c.getSharedPreferences("lists", Context.MODE_PRIVATE).getAll();
        File root = new File(Library.rootPath());
        String[] names = root.list();
        if (names == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!(e.getValue() instanceof String) || !e.getKey().matches("[A-Za-z0-9_-]{10,64}")) continue;
            if (e.getKey().startsWith("RD")) continue;     // a YouTube Mix changes by itself all the time - never followed
            String folder = (String) e.getValue();
            for (String n : names) {
                if (n.equalsIgnoreCase(folder) && new File(root, n).isDirectory()) {
                    out.put(n, "https://www.youtube.com/playlist?list=" + e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    static String run(Context c) {
        Context a = c.getApplicationContext();
        Map<String, String> l = links(a);
        prefs(a).edit().putLong("at", System.currentTimeMillis()).apply();
        if (l.isEmpty() || !Ytdl.available(a) || Importer.tree(a) == null) return "";
        for (Map.Entry<String, String> e : l.entrySet()) DownloadService.sync(a, e.getValue(), e.getKey());
        return join(l.keySet());
    }

    static String join(Collection<String> names) {
        List<String> n = new ArrayList<>(names);
        if (n.size() <= 1) return n.isEmpty() ? "" : n.get(0);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < n.size() - 1; k++) b.append(k > 0 ? ", " : "").append(n.get(k));
        return b.append(" and ").append(n.get(n.size() - 1)).toString();
    }

    /** the app was opened: a check that is due runs now (the app is in front, so Android allows it) */
    static void maybe(Context c) {
        long last = prefs(c).getLong("at", 0);
        if (System.currentTimeMillis() - last >= EVERY_MS) {
            try {
                run(c);
            } catch (Exception ignored) {
            }
        }
        schedule(c);
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 41, new Intent(c, Sync.class).setAction(TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** the next check: 6 hours after the last one (at least 15 minutes from now) */
    static void schedule(Context c) {
        try {
            if (links(c).isEmpty()) return;
            long now = System.currentTimeMillis();
            long at = Math.max(prefs(c).getLong("at", 0) + EVERY_MS, now + 15L * 60 * 1000);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(final Context c, Intent i) {
        final String act = i == null ? null : i.getAction();
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(act);
        if (!boot && !TICK.equals(act)) return;          // exported only so the restart reaches it: nothing else counts
        final PendingResult done = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!boot) {
                        try {
                            Sync.run(c);
                        } catch (Exception notAllowed) {
                            // Android would not start it with the app closed: the next app open does it
                            prefs(c).edit().putLong("at", 0).apply();
                        }
                    }
                    schedule(c);
                } finally {
                    done.finish();
                }
            }
        }).start();
    }

    // ------------------------------------------------ what each YouTube playlist held at the last check

    private static File seenFile(Context c, String listId) {
        File d = new File(c.getFilesDir(), "sync");
        d.mkdirs();
        return new File(d, listId.replaceAll("[^A-Za-z0-9_-]", "_") + ".txt");
    }

    /** the video ids at the last check, or null if never checked */
    static Set<String> seen(Context c, String listId) {
        File f = seenFile(c, listId);
        if (!f.isFile()) return null;
        Set<String> out = new HashSet<>();
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                for (String s : new String(b, 0, n, "UTF-8").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    static void saveSeen(Context c, String listId, Collection<String> ids) {
        try {
            File f = seenFile(c, listId), tmp = new File(f.getPath() + ".tmp");
            StringBuilder b = new StringBuilder();
            for (String id : ids) b.append(id).append('\n');
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(b.toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /**
     * the order a check fetches in, as indexes into the songs it found:
     * songs that were not in the playlist at the last check first, then
     * from the end of the playlist (where new songs usually go); at most max.
     * Plain Java, for the tests.
     */
    static List<Integer> order(List<String> ids, Set<String> seenBefore, int max) {
        List<Integer> fresh = new ArrayList<>(), older = new ArrayList<>();
        for (int k = ids.size() - 1; k >= 0; k--) {
            if (seenBefore != null && !seenBefore.contains(ids.get(k))) fresh.add(k);
            else older.add(k);
        }
        fresh.addAll(older);
        return fresh.size() > max ? new ArrayList<>(fresh.subList(0, max)) : fresh;
    }
}
