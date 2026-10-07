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
 * How often and when each song was played (for "Most played" and "Recently
 * played"). A song counts once it has played 30 seconds (half of it, for
 * songs under a minute), like Spotify. Kept in plays.json as
 * { "playlist/file.mp3": [times played, when last played (ms)] }.
 */
final class Plays {
    private static JSONObject all;

    private Plays() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "plays.json");
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
                        byte[] buf = new byte[65536];
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

    /** one more play of this song, now */
    static synchronized void count(Context c, String key) {
        if (key == null || key.isEmpty()) return;
        try {
            JSONArray a = all(c).optJSONArray(key);
            long n = a == null ? 0 : a.optLong(0, 0);
            JSONArray v = new JSONArray();
            v.put(n + 1);
            v.put(System.currentTimeMillis());
            all(c).put(key, v);
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

    /** everything, for the page */
    static synchronized String json(Context c) {
        return all(c).toString();
    }
}
