package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * how long each YouTube video is, as YouTube says - every one
 * the app has ever seen, from playlist reads, single look-ups and downloads.
 * A video's length never changes, so it is asked once and kept. This is
 * what lets the wrong-audio check finish every song: songs not in the
 * YouTube playlist are looked up one by one (a few at a time), and from
 * then on the check needs YouTube only for songs it has never seen.
 *
 * yt_lengths.json: {"videoid": ["title", "channel", "seconds"]}, and for a
 * video YouTube says is gone: ["", "", "-1", "when (ms)"] - asked again
 * after 90 days, in case it was only YouTube being busy.
 */
final class Lengths {
    private static final long GONE_MS = 90L * 24 * 3600 * 1000;
    private static Map<String, String[]> all;
    private static boolean dirty;

    private Lengths() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "yt_lengths.json");
    }

    private static synchronized Map<String, String[]> all(Context c) {
        if (all != null) return all;
        all = new HashMap<>();
        try {
            File f = file(c);
            if (f.isFile()) all.putAll(parse(new String(Backup.read(f), "UTF-8")));
        } catch (Exception ignored) {
            // a broken file: start again, it only costs YouTube look-ups
        }
        return all;
    }

    /** the file's text as a map (plain Java, for the tests) */
    static Map<String, String[]> parse(String json) throws Exception {
        Map<String, String[]> out = new HashMap<>();
        JSONObject o = new JSONObject(json);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONArray a = o.optJSONArray(id);
            if (a == null || a.length() < 3) continue;
            String[] v = new String[a.length() >= 4 ? 4 : 3];
            for (int i = 0; i < v.length; i++) v[i] = a.optString(i, "");
            out.put(id, v);
        }
        return out;
    }

    static String json(Map<String, String[]> m) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            JSONArray a = new JSONArray();
            for (String x : e.getValue()) a.put(x == null ? "" : x);
            o.put(e.getKey(), a);
        }
        return o.toString();
    }

    /** a length worth keeping: more than 0 seconds */
    static boolean good(String[] v) {
        if (v == null || v.length < 3) return false;
        try {
            return Double.parseDouble(v[2]) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** id -> {title, channel, seconds}: only lengths that are known (a copy, safe to read while others write) */
    static synchronized Map<String, String[]> known(Context c) {
        Map<String, String[]> out = new HashMap<>();
        for (Map.Entry<String, String[]> e : all(c).entrySet()) if (good(e.getValue())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** still worth asking YouTube about: never seen, or said to be gone more than 90 days ago */
    static synchronized boolean unknown(Context c, String id, long now) {
        String[] v = all(c).get(id);
        if (v == null) return true;
        if (good(v)) return false;
        long at = 0;
        try {
            at = v.length >= 4 ? Long.parseLong(v[3]) : 0;
        } catch (Exception ignored) {
        }
        return now - at > GONE_MS;
    }

    /** id -> {title, channel, seconds} from a playlist read or a download; empty lengths are skipped */
    static synchronized void putAll(Context c, Map<String, String[]> m) {
        Map<String, String[]> a = all(c);
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            String[] v = e.getValue();
            if (v == null || v.length < 3 || !good(v)) continue;
            String[] old = a.get(e.getKey());
            if (old != null && good(old) && old[2].equals(v[2]) && old[0].equals(v[0])) continue;
            a.put(e.getKey(), new String[]{v[0] == null ? "" : v[0], v[1] == null ? "" : v[1], v[2]});
            dirty = true;
        }
    }

    /** YouTube says this video is gone (removed, private): not asked again for 90 days */
    static synchronized void gone(Context c, String id, long now) {
        String[] old = all(c).get(id);
        if (old != null && good(old)) return;
        all(c).put(id, new String[]{"", "", "-1", String.valueOf(now)});
        dirty = true;
    }

    static synchronized void save(Context c) {
        if (!dirty) return;
        try {
            Backup.write(file(c), json(all(c)).getBytes("UTF-8"));
            dirty = false;
        } catch (Exception ignored) {
            // kept in memory; written next time
        }
    }
}
