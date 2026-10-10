package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * "Search by name" in Lyrics: the song name and artist to look the
 * lyrics up by, kept per song. lyrics_fix.json: {"playlist/file": ["Song", "Artist"]}
 */
final class LyricsFix {
    private static JSONObject all;

    private LyricsFix() {}

    static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "lyrics_fix.json");
    }

    private static JSONObject all(Context c) {
        if (all == null) {
            all = new JSONObject();
            File f = file(c);
            if (f.isFile()) {
                try {
                    InputStream in = new FileInputStream(f);
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    all = new JSONObject(b.toString("UTF-8"));
                } catch (Exception ignored) {
                    all = new JSONObject();
                }
            }
        }
        return all;
    }

    /** {song, artist} to search by, or null */
    static synchronized String[] get(Context c, String key) {
        JSONArray a = all(c).optJSONArray(key);
        if (a == null || a.length() < 2) return null;
        return new String[]{a.optString(0), a.optString(1)};
    }

    /** blank song and artist: back to the song's own name */
    static synchronized void put(Context c, String key, String track, String artist) {
        try {
            String t = track == null ? "" : track.trim(), a = artist == null ? "" : artist.trim();
            if (t.isEmpty() && a.isEmpty()) all(c).remove(key);
            else all(c).put(key, new JSONArray().put(t).put(a));
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(all(c).toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /** read again from the file (after a restore) */
    static synchronized void reload() {
        all = null;
    }
}
