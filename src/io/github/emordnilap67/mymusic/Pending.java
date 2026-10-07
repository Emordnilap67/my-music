package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Songs on their way into a playlist. When a playlist is
 * shared, every song it will get is listed here at once, so the playlist
 * shows in MY MUSIC straight away with all of them; each one drops off
 * when it is in the folder (the real song shows instead). Kept in
 * pending.json so a wait for YouTube survives the app closing.
 *
 *   { "Chill": { "link": "...", "want": "Chill", "title": "Chill Mix",
 *                     "retry": 0 | when to try again (ms), "note": "...",
 *                     "items": [ {"id","t","a","d","s": "w" waiting | "x" can't, "e": why} ] },
 *     "?PLxxxx":    { ... a new playlist still waiting for its name ... } }
 */
final class Pending {
    private static JSONObject all;

    private Pending() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "pending.json");
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

    private static void save(Context c) {
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
        MainActivity.pendingChanged();
    }

    /** everything, for the page */
    static synchronized String json(Context c) {
        return all(c).toString();
    }

    /** the list of songs about to come into this playlist */
    static synchronized void start(Context c, String folder, String link, String want, String title, List<String[]> items) {
        try {
            JSONObject e = new JSONObject();
            e.put("link", link);
            e.put("want", want == null ? "" : want);
            e.put("title", title == null ? "" : title);
            e.put("retry", 0L);
            e.put("note", "");
            JSONArray a = new JSONArray();
            for (String[] it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it[0]);
                o.put("t", it[1]);
                o.put("a", it[2]);
                double secs = 0;
                try {
                    secs = Double.parseDouble(it[3]);
                } catch (Exception ignored) {
                }
                o.put("d", Math.round(secs));
                o.put("s", "w");
                a.put(o);
            }
            e.put("items", a);
            all(c).put(folder, e);
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** make sure a link waits under this name (keeps any list it already has) */
    static synchronized void keep(Context c, String key, String link, String want) {
        try {
            if (!all(c).has(key)) {
                JSONObject e = new JSONObject();
                e.put("link", link);
                e.put("want", want == null ? "" : want);
                e.put("title", "");
                e.put("retry", 0L);
                e.put("note", "");
                e.put("items", new JSONArray());
                all(c).put(key, e);
            }
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** this song is in the folder now */
    static synchronized void done(Context c, String folder, String id) {
        JSONObject e = all(c).optJSONObject(folder);
        if (e == null) return;
        JSONArray a = e.optJSONArray("items");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                a.remove(i);
                save(c);
                return;
            }
        }
    }

    /** this song cannot come in (removed, private, ...) */
    static synchronized void failed(Context c, String folder, String id, String why) {
        JSONObject e = all(c).optJSONObject(folder);
        if (e == null) return;
        JSONArray a = e.optJSONArray("items");
        if (a == null) return;
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("s", "x");
                    o.put("e", why);
                    save(c);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** YouTube paused it: the rest waits until then */
    static synchronized void waitFor(Context c, String folder, long at, String note) {
        JSONObject e = all(c).optJSONObject(folder);
        if (e == null) return;
        try {
            e.put("retry", at);
            e.put("note", note);
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** finished (or given up): the playlist shows only its real songs again */
    static synchronized void finish(Context c, String folder) {
        if (folder == null || all(c).remove(folder) == null) return;
        save(c);
    }

    static synchronized void clear(Context c) {
        if (all(c).length() == 0) return;
        all = new JSONObject();
        save(c);
    }

    /** links to fetch again: waits that are due (or all of them), and ones cut off when the app was closed */
    static synchronized List<String[]> toResume(Context c) {
        List<String[]> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Iterator<String> it = all(c).keys();
        boolean changed = false;
        while (it.hasNext()) {
            String k = it.next();
            JSONObject e = all(c).optJSONObject(k);
            if (e == null) continue;
            long at = e.optLong("retry", 0);
            if (at > now + 60000) continue;            // not yet
            out.add(new String[]{e.optString("link"), e.optString("want")});
            try {
                e.put("retry", 0L);
                e.put("note", "");
                changed = true;
            } catch (Exception ignored) {
            }
        }
        if (changed) save(c);
        return out;
    }

    /** the soonest wait still ahead (0 = none) */
    static synchronized long nextWait(Context c) {
        long best = 0, now = System.currentTimeMillis();
        Iterator<String> it = all(c).keys();
        while (it.hasNext()) {
            JSONObject e = all(c).optJSONObject(it.next());
            long at = e == null ? 0 : e.optLong("retry", 0);
            if (at > now && (best == 0 || at < best)) best = at;
        }
        return best;
    }

    static synchronized boolean any(Context c) {
        return all(c).length() > 0;
    }
}
