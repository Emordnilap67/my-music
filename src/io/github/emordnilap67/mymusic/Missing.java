package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Songs a playlist could not get because they are gone from YouTube
 * (removed, private, blocked), kept so "Find another copy" can look for a
 * different upload of the same song. Only songs whose names YouTube still
 * shows are kept. missing.json:
 *   { "Chill": [ {"id", "t" title, "a" artist, "d" seconds, "e" why}, ... ] }
 */
final class Missing {
    private static JSONObject all;

    private Missing() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "missing.json");
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
        MainActivity.pendingChanged();        // the page redraws its lists
    }

    /** a name YouTube still shows ("[Deleted video]" and "[Private video]" have none) */
    static boolean named(String title) {
        String t = title == null ? "" : title.trim();
        return !t.isEmpty() && !t.startsWith("[");
    }

    /** these songs could not come into this playlist: {id, title, artist, seconds, why} */
    static synchronized void add(Context c, String folder, List<String[]> items) {
        if (items.isEmpty()) return;
        try {
            JSONArray a = all(c).optJSONArray(folder);
            if (a == null) a = new JSONArray();
            for (String[] it : items) {
                if (!named(it[1])) continue;
                boolean have = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && it[0].equals(o.optString("id"))) have = true;
                }
                if (have) continue;
                JSONObject o = new JSONObject();
                o.put("id", it[0]);
                o.put("t", it[1]);
                o.put("a", it[2]);
                long d = 0;
                try {
                    d = Math.round(Double.parseDouble(it[3]));
                } catch (Exception ignored) {
                }
                o.put("d", d);
                o.put("e", it[4]);
                a.put(o);
            }
            if (a.length() > 0) all(c).put(folder, a);
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** found another copy, or let it go ("*" = all of this playlist's) */
    static synchronized void remove(Context c, String folder, String id) {
        if ("*".equals(id)) {
            all(c).remove(folder);
        } else {
            JSONArray a = all(c).optJSONArray(folder);
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    a.remove(i);
                    break;
                }
            }
            if (a.length() == 0) all(c).remove(folder);
        }
        save(c);
    }

    /**
     * local first: a song that is in the playlist folder is not gone,
     * whatever YouTube says - drop it from the list. Returns how many went.
     */
    static synchronized int pruneLocal(Context c) {
        int n = 0;
        try {
            java.util.List<String> folders = new java.util.ArrayList<>();
            java.util.Iterator<String> it = all(c).keys();
            while (it.hasNext()) folders.add(it.next());
            for (String folder : folders) {
                JSONArray a = all(c).optJSONArray(folder);
                if (a == null) continue;
                LocalMatch local = LocalMatch.cached(c, folder);
                JSONArray keep = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    if (local.has(o.optString("id"), o.optString("t"), o.optString("a"), true)) n++;
                    else keep.put(o);
                }
                if (keep.length() == a.length()) continue;
                if (keep.length() == 0) all(c).remove(folder);
                else all(c).put(folder, keep);
            }
        } catch (Exception ignored) {
        }
        if (n > 0) save(c);
        return n;
    }

    private static final java.util.concurrent.ExecutorService BG = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile Object prunedFor;

    /** prune once per library read, off the page's thread (it can take a moment on 2,500 songs) */
    static void pruneLater(final Context c) {
        final Object lib = Library.get(c);
        if (lib == prunedFor) return;
        prunedFor = lib;
        BG.execute(new Runnable() {
            @Override
            public void run() {
                pruneLocal(c.getApplicationContext());       // saves and tells the page if anything went
            }
        });
    }

    /** the ids to remember as dealt with ("*" = all of this playlist's) */
    static synchronized java.util.List<String> ids(Context c, String folder, String id) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!"*".equals(id)) {
            out.add(id);
            return out;
        }
        JSONArray a = all(c).optJSONArray(folder);
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(o.optString("id"));
        }
        return out;
    }

    /** read again from the file (after a restore) */
    static synchronized void reload() {
        all = null;
    }

    static synchronized String json(Context c) {
        return all(c).toString();
    }
}
