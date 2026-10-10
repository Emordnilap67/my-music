package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Songs YouTube says are gone. A song that fails as removed /
 * private / blocked in two separate tries is not asked for again for 90
 * days, so a playlist with dead songs stops spending its YouTube requests
 * on them every time it retries (and gets paused less). One failure is
 * not enough: a busy YouTube can make a song look gone for a while.
 * dead.json: {"videoid": [times failed, last failure ms, why]}
 */
final class Dead {
    static final int TRIES = 2;
    static final long KEEP_MS = 90L * 24 * 3600 * 1000;
    private static final long SAME_RUN_MS = 30L * 60 * 1000;
    private static JSONObject all;

    private Dead() {}

    static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "dead.json");
    }

    private static JSONObject all(Context c) {
        if (all == null) {
            all = new JSONObject();
            File f = c == null ? null : file(c);
            if (f != null && f.isFile()) {
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

    private static void save(Context c) {
        if (c == null) return;
        try {
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

    /** the reasons that mean the video itself is gone (not YouTube being busy) */
    static boolean gone(String why) {
        return "Private video".equals(why) || "Removed from YouTube".equals(why) || "Blocked for copyright".equals(why)
                || "Needs YouTube Premium".equals(why) || "Age-restricted".equals(why);
    }

    /** "[Private video]", "[Deleted video]": a playlist's placeholder for a song that is certainly gone */
    static boolean placeholder(String title) {
        String t = title == null ? "" : title.trim();
        return t.startsWith("[") && t.endsWith("]") && t.toLowerCase().contains("video");
    }

    static synchronized void fail(Context c, String id, String why, long now) {
        try {
            JSONArray a = all(c).optJSONArray(id);
            int n = a == null ? 0 : a.optInt(0);
            long last = a == null ? 0 : a.optLong(1);
            if (a == null || now - last > SAME_RUN_MS) n++;
            all(c).put(id, new JSONArray().put(n).put(now).put(why == null ? "" : why));
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** skip this song: it failed as gone in two tries, the last within 90 days */
    static synchronized boolean skip(Context c, String id, long now) {
        JSONArray a = all(c).optJSONArray(id);
        return a != null && a.optInt(0) >= TRIES && now - a.optLong(1) < KEEP_MS;
    }

    static synchronized void forget(Context c, String id) {
        if (all(c).remove(id) != null) save(c);
    }

    /** drop entries older than 90 days, so the file stays small */
    static synchronized void tidy(Context c, long now) {
        boolean changed = false;
        Iterator<String> it = all(c).keys();
        java.util.List<String> old = new java.util.ArrayList<>();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = all(c).optJSONArray(k);
            if (a == null || now - a.optLong(1) > KEEP_MS) old.add(k);
        }
        for (String k : old) {
            all(c).remove(k);
            changed = true;
        }
        if (changed) save(c);
    }

    static synchronized void reload() {
        all = null;
    }

    static synchronized String json(Context c) {
        return all(c).toString();
    }
}
