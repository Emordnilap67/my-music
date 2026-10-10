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
import java.util.Set;

/**
 * Which YouTube video each song file came from, so a backup can list
 * them and a new phone can fetch them again. Read once from the file's
 * tags (yt-dlp writes the link there), then kept: ids.json
 *   {"/storage/emulated/0/Music/MY MUSIC/Chill/Artist - Song.mp3": ["videoid" or "", file size]}
 */
final class Ids {
    private static JSONObject all;
    private static boolean dirty;

    private Ids() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "ids.json");
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

    /** a song the downloader just saved: no need to read its tags later */
    static synchronized void put(Context c, String path, String id, long size) {
        try {
            all(c).put(path, new JSONArray().put(id == null ? "" : id).put(size));
            dirty = true;
        } catch (Exception ignored) {
        }
    }

    /** the YouTube id of this song file, "" if it has none (read from its tags the first time) */
    static synchronized String of(Context c, String path) {
        if (path == null) return "";
        File f = new File(path);
        long size = f.length();
        JSONArray a = all(c).optJSONArray(path);
        if (a != null && a.optLong(1) == size) return a.optString(0);
        String id = Id3.read(f, false).id;
        put(c, path, id, size);
        return id;
    }

    /** the id if already known (no reading of the file), else null */
    static synchronized String cached(Context c, String path) {
        if (path == null) return null;
        JSONArray a = all(c).optJSONArray(path);
        return a != null && a.optLong(1) == new File(path).length() ? a.optString(0) : null;
    }

    /** forget files that are gone, and write the list down */
    static synchronized void save(Context c, Set<String> paths) {
        if (paths != null) {
            Iterator<String> it = all(c).keys();
            java.util.List<String> old = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (!paths.contains(k)) old.add(k);
            }
            for (String k : old) all(c).remove(k);
            if (!old.isEmpty()) dirty = true;
        }
        if (!dirty) return;
        try {
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(all(c).toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (tmp.renameTo(f)) dirty = false;
            else tmp.delete();
        } catch (Exception ignored) {
        }
    }
}
