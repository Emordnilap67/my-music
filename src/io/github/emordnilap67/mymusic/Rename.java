package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rename playlist: a playlist's new name. Importer.renamePlaylist gives the folder its new name; this moves
 * everything the app keeps under the old one - plays, hearts, hidden songs, lyrics fixes, loudness, song ids,
 * lyrics already written into the songs, the picture, the YouTube link and its switch, waiting downloads, the
 * never-came and wrong-audio lists. A song's key
 * is "Playlist/File.mp3", so every key that starts with the old name gets the new one.
 */
final class Rename {
    private Rename() {}

    /** this key under the new name, or the key as it was (not in that playlist) */
    static String re(String k, String pre, String to) {
        if (k == null || k.length() < pre.length() || !k.regionMatches(true, 0, pre, 0, pre.length())) return k;
        return to + k.substring(pre.length());
    }

    /** the old playlist's data, all of it, under the new name; from and to are folder names (no slash) */
    static void stores(Context c, String from, String to) {
        final String pre = from + "/", now = to + "/";
        File dir = c.getApplicationContext().getFilesDir();
        synchronized (Plays.class) {
            try {
                File f = new File(dir, "plays.json");
                if (f.isFile()) {
                    JSONObject o = json(f);
                    for (String k : keys(o)) {
                        String n = re(k, pre, now);
                        if (n.equals(k)) continue;
                        JSONArray a = o.optJSONArray(k), b = o.optJSONArray(n), v = new JSONArray();
                        o.remove(k);
                        if (a == null) continue;
                        v.put(a.optLong(0, 0) + (b == null ? 0 : b.optLong(0, 0)));        // counts add up, like Plays.move
                        v.put(Math.max(a.optLong(1, 0), b == null ? 0 : b.optLong(1, 0)));
                        o.put(n, v);
                    }
                    Backup.write(f, o.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            } finally {
                Plays.reload();
            }
        }
        synchronized (Favs.class) {
            try {
                lines(new File(dir, "favorites.txt"), pre, now, true);
            } finally {
                Favs.reload();
            }
        }
        synchronized (Library.class) {
            lines(new File(dir, "hidden.txt"), pre, now, true);
        }
        synchronized (LyricsFix.class) {
            try {
                File f = new File(dir, "lyrics_fix.json");
                if (f.isFile()) {
                    JSONObject o = json(f);
                    for (String k : keys(o)) {
                        String n = re(k, pre, now);
                        if (n.equals(k)) continue;
                        Object v = o.remove(k);
                        if (!o.has(n)) o.put(n, v);                // one already under the new name is newer - it stays
                    }
                    Backup.write(f, o.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            } finally {
                LyricsFix.reload();
            }
        }
        synchronized (Missing.class) {
            try {
                folderKeys(new File(dir, "missing.json"), from, to, null);
            } finally {
                Missing.reload();
            }
        }
        synchronized (Pending.class) {
            try {
                folderKeys(new File(dir, "pending.json"), from, to, Pending.SEP);       // "Folder" or "Folder" + SEP + list id
            } finally {
                Pending.reload();
            }
        }
        synchronized (Mismatch.class) {
            try {
                File f = new File(dir, "mismatch.json");
                if (f.isFile()) {
                    JSONObject o = json(f);
                    for (String k : keys(o)) {
                        if (!k.equalsIgnoreCase(from)) continue;
                        JSONObject mine = o.optJSONObject(k);
                        o.remove(k);
                        if (mine == null || o.has(to)) continue;
                        JSONArray bad = mine.optJSONArray("bad");
                        for (int i = 0; bad != null && i < bad.length(); i++) {
                            JSONObject b = bad.optJSONObject(i);
                            if (b != null) b.put("key", re(b.optString("key", ""), pre, now));
                        }
                        o.put(to, mine);
                    }
                    Backup.write(f, o.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
        Ids.renamePl(c, Library.rootPath() + "/" + pre, Library.rootPath() + "/" + now);
        Loud.renamePl(c, pre, now);
        Embed.renamePl(c, pre, now);
        // the YouTube playlist it follows, and its Check YouTube switch
        SharedPreferences lp = c.getSharedPreferences("lists", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = lp.edit();
        for (Map.Entry<String, ?> x : lp.getAll().entrySet())
            if (x.getValue() instanceof String && ((String) x.getValue()).equalsIgnoreCase(from)) e.putString(x.getKey(), to);
        e.apply();
        if (!Sync.off(c, from)) {                          // switched on (off until it is turned on) - it stays on
            Sync.setOff(c, from, true);
            Sync.setOff(c, to, false);
        }
        // the picture: one he picked moves over; a built-in one (assets/covers) is copied in under the new name
        File pick = Covers.file(c, from), next = Covers.file(c, to);
        if (next.isFile()) {
            // a picture picked under the new name already: it stays
        } else if (pick.isFile()) {
            next.getParentFile().mkdirs();
            if (!pick.renameTo(next)) copyAsset(c, null, pick, next);
        } else if (Covers.hasAsset(c, from)) {
            next.getParentFile().mkdirs();
            copyAsset(c, Covers.assetName(from), null, next);
        }
        Heard.reset();
    }

    // ------------------------------------------------------------------ small helpers

    private static JSONObject json(File f) throws Exception {
        String s = new String(Backup.read(f), "UTF-8").trim();
        return s.isEmpty() ? new JSONObject() : new JSONObject(s);
    }

    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    /**
     * the player is not running: the list it saved (PlayerService.saveQueue) under the new keys, so it comes back
     * whole when it starts (a running player does this itself - renamedPl)
     */
    static void queueFiles(Context c, String from, String to) {
        final String pre = from + "/", now = to + "/";
        File dir = c.getApplicationContext().getFilesDir();
        for (String n : new String[]{"base.txt", "order.txt", "upnext.txt", "heard.txt"}) lines(new File(dir, n), pre, now, false);
        SharedPreferences p = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"cur", "lcur", "extra"}) e.putString(k, re(p.getString(k, ""), pre, now));
        if (p.getString("src", "").equalsIgnoreCase(from)) e.putString("src", to);
        e.apply();
    }

    /** a file of keys, one a line (favourites, hidden songs; the player's lists keep their order and repeats) */
    private static void lines(File f, String pre, String now, boolean once) {
        if (!f.isFile()) return;
        try {
            java.util.Collection<String> out = once ? new LinkedHashSet<String>() : new ArrayList<String>();
            for (String l : new String(Backup.read(f), "UTF-8").split("\n")) {
                l = l.replace("\r", "");
                if (!l.trim().isEmpty()) out.add(re(l, pre, now));
            }
            StringBuilder b = new StringBuilder();
            for (String l : out) b.append(l).append('\n');
            Backup.write(f, b.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** a json file keyed by playlist name (sep: also "Name" + sep + more) */
    private static void folderKeys(File f, String from, String to, String sep) {
        if (!f.isFile()) return;
        try {
            JSONObject o = json(f);
            String lowFrom = from.toLowerCase(Locale.ROOT);
            for (String k : keys(o)) {
                String n = null;
                if (k.equalsIgnoreCase(from)) n = to;
                else if (sep != null && k.toLowerCase(Locale.ROOT).startsWith(lowFrom + sep)) n = to + k.substring(from.length());
                if (n == null) continue;
                Object v = o.remove(k);
                if (!o.has(n)) o.put(n, v);
            }
            Backup.write(f, o.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static void copyAsset(Context c, String asset, File src, File dst) {
        try {
            InputStream in = asset != null ? c.getAssets().open(asset) : new java.io.FileInputStream(src);
            File tmp = new File(dst.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                o.close();
                in.close();
            }
            if (!tmp.renameTo(dst)) tmp.delete();
            else if (src != null) src.delete();
        } catch (Exception ignored) {
        }
    }
}
