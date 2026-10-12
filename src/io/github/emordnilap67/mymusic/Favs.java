package io.github.emordnilap67.mymusic;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Favourites: the songs hearted in each playlist (a heart belongs to the
 * song in that playlist - "playlist/file.mp3"). Not a playlist of its own;
 * each playlist can show just its hearted songs. Kept in favorites.txt, one
 * key a line, and in the new-phone backup.
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
        if (changed) save(c);
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
}
