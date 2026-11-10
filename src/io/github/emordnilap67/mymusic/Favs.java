package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Favourites: the songs hearted in each playlist (a heart belongs to the
 * song in that playlist - "playlist/file.mp3"). Not a playlist of its own;
 * each playlist can show just its hearted songs. Kept in favorites.txt, one
 * key a line, and in the new-phone backup.
 *
 * THE SAME HEARTS ON EVERY DEVICE (when the music folder is kept the same on a phone and a tablet by a sync app). Each
 * device keeps a log of its own hearts in the music folder - ".favorites-<phone|tablet>-<id>.txt", one line a change:
 * "+" or "-", the time, the song. A device writes only its OWN log (so the sync app never makes conflict copies) and reads
 * them all; the newest line for a song wins. The first time, each device writes all its hearts into its log as "+" - so the
 * phone's and the tablet's are put together, nothing lost. Read when the app comes on screen and every minute while it is.
 */
final class Favs {
    private static Set<String> all;

    private Favs() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "favorites.txt");
    }

    private static Set<String> all(Context c) {
        if (all == null) {
            all = new LinkedHashSet<>();
            File f = file(c);
            if (f.isFile()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    try {
                        String line;
                        while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) all.add(line);
                    } finally {
                        r.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return all;
    }

    private static void save(Context c) {
        try {
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                for (String k : all(c)) w.write(k + "\n");
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /** heart on / off; returns the new state */
    static synchronized boolean set(Context c, String key, boolean on) {
        if (key == null || key.isEmpty()) return false;
        boolean changed = on ? all(c).add(key) : all(c).remove(key);
        if (changed) {
            save(c);
            log(c, (on ? "+\t" : "-\t") + System.currentTimeMillis() + "\t" + key + "\n");   // for the other devices
        }
        return on;
    }

    static synchronized boolean has(Context c, String key) {
        return key != null && all(c).contains(key);
    }

    /** every hearted song, for the page: ["playlist/file.mp3", ...] */
    static synchronized String json(Context c) {
        StringBuilder b = new StringBuilder("[");
        for (String k : all(c)) {
            if (b.length() > 1) b.append(',');
            b.append(Library.q(k));
        }
        return b.append(']').toString();
    }

    /** read again from the file (after a restore) */
    static synchronized void reload() {
        all = null;
    }

    // ---------------------------------------------------------------- the same hearts on every device

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object PEND = new Object();

    /** this device's own log in the music folder */
    private static String logName(Context c) {
        Connect.start(c);
        return ".favorites-" + Connect.myName.toLowerCase(Locale.ROOT) + "-" + Connect.myId.substring(0, 4) + ".txt";
    }

    /** a change for the log: kept on the device first (favlog_pending.txt), written to the folder as soon as it can be */
    private static void log(Context c, final String line) {
        final Context a = c.getApplicationContext();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                pending(a, line);
                Uri tree = Importer.tree(a);
                if (tree != null) flush(a, tree);
            }
        });
    }

    private static void pending(Context c, String text) {
        synchronized (PEND) {
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(new File(c.getFilesDir(), "favlog_pending.txt"), true), "UTF-8");
                try {
                    w.write(text);
                } finally {
                    w.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** the waiting lines onto the end of this device's log (append only - never rewritten); true when nothing is left waiting */
    private static boolean flush(Context c, Uri tree) {
        synchronized (PEND) {
            File f = new File(c.getFilesDir(), "favlog_pending.txt");
            if (!f.isFile() || f.length() == 0) return true;
            try {
                byte[] add = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    int got = 0, n;
                    while (got < add.length && (n = in.read(add, got, add.length - got)) > 0) got += n;
                } finally {
                    in.close();
                }
                ContentResolver r = c.getContentResolver();
                String name = logName(c), doc = Importer.names(c, tree, Importer.rootDoc(c)).get(name);
                Uri target = doc != null ? DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                        : DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, Importer.rootDoc(c)), "text/plain", name);
                if (target == null) return false;
                OutputStream o = r.openOutputStream(target, "wa");
                try {
                    o.write(add);
                } finally {
                    o.close();
                }
                f.delete();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * read every device's log - the newest line for each song wins - and this device's hearts follow. The first time, this
     * device's own hearts go into its log (merged with the others'). Slow (the folder) - never on the main thread.
     * Returns true when a heart came or went.
     */
    static boolean sync(Context c) {
        c = c.getApplicationContext();
        Uri tree = Importer.tree(c);
        if (tree == null) return false;
        SharedPreferences pr = c.getSharedPreferences("connect", Context.MODE_PRIVATE);
        if (!pr.getBoolean("favs_seeded", false)) {
            StringBuilder b = new StringBuilder();
            long now = System.currentTimeMillis();
            synchronized (Favs.class) {
                for (String k : all(c)) b.append("+\t").append(now).append('\t').append(k).append('\n');
            }
            pending(c, b.toString());
            if (!flush(c, tree)) return false;
            pr.edit().putBoolean("favs_seeded", true).apply();
        } else if (!flush(c, tree)) {
            return false;                                // lines still waiting - read again next time
        }
        Map<String, long[]> last = new HashMap<>();      // song -> {time, 1 = hearted}
        ContentResolver r = c.getContentResolver();
        for (Map.Entry<String, String> e : Importer.names(c, tree, Importer.rootDoc(c)).entrySet()) {
            if (!e.getKey().startsWith(".favorites-") || !e.getKey().endsWith(".txt")) continue;
            try {
                InputStream in = r.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.getValue()));
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split("\t", 3);
                        if (p.length < 3 || p[2].isEmpty() || !(p[0].equals("+") || p[0].equals("-"))) continue;
                        long t;
                        try {
                            t = Long.parseLong(p[1]);
                        } catch (NumberFormatException x) {
                            continue;
                        }
                        long on = p[0].equals("+") ? 1 : 0;
                        long[] was = last.get(p[2]);
                        if (was == null || t > was[0] || (t == was[0] && on == 1)) last.put(p[2], new long[]{t, on});
                    }
                } finally {
                    br.close();
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (Favs.class) {
            Set<String> s = all(c);
            boolean changed = false;
            for (Map.Entry<String, long[]> e : last.entrySet()) {
                if (e.getValue()[1] == 1) changed |= s.add(e.getKey());
                else changed |= s.remove(e.getKey());
            }
            if (changed) save(c);
            return changed;
        }
    }
}
