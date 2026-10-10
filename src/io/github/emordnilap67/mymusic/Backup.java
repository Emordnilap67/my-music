package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The new-phone kit. MY MUSIC keeps a backup of everything
 * that is not a song - play counts, hidden songs, playlist pictures, which
 * YouTube playlist goes where, songs gone from YouTube, lyrics fixes, the
 * sort order - plus a list of every song and the YouTube video it came
 * from, in your music folder / MY MUSIC backup. It refreshes by itself when the
 * app is closed (when something changed), keeps the one before it, and
 * puts a copy of the app there too. Copy the music folder to a new phone,
 * install MY MUSIC.apk from that folder, tap Restore - and if the songs
 * did not come along, Get songs back downloads them again.
 */
final class Backup {
    static final String DIR = "MY MUSIC backup";
    /** a download job that fetches the songs a backup lists ("mmids:" + playlist) */
    static final String IDS = "mmids:";
    static final String ZIP = "backup.zip", PREV = "backup (previous).zip", NEW = "backup new.zip";
    static final String APK = "MY MUSIC.apk", README = "How to restore.txt";
    private static final int FORMAT = 1;
    private static final long AUTO_GAP_MS = 10L * 60 * 1000;
    private static final String[] FILES = {"plays.json", "hidden.txt", "missing.json", "dead.json", "lyrics_fix.json"};
    private static final Object LOCK = new Object();
    private static volatile boolean running;

    private Backup() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("backup", Context.MODE_PRIVATE);
    }

    /** the page's own settings (sort order, lyrics open, ...), kept for the next backup */
    static void pagePrefs(Context c, String json) {
        if (json != null && json.length() < 20000) prefs(c).edit().putString("page", json).apply();
    }

    // ================================================================ making one

    /** the app was closed: a fresh backup if anything changed (looked at most every 10 minutes) */
    static void auto(final Context c) {
        final Context a = c.getApplicationContext();
        if (running || Importer.tree(a) == null) return;
        if (System.currentTimeMillis() - prefs(a).getLong("tried", 0) < AUTO_GAP_MS) return;
        prefs(a).edit().putLong("tried", System.currentTimeMillis()).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                make(a, false);
            }
        }).start();
    }

    /** writes the backup; returns a line for the page */
    static String make(Context c, boolean force) {
        return make(c, force, false);
    }

    /** anyway = replace even a backup from another phone, or one with far more songs */
    static String make(Context c, boolean force, boolean anyway) {
        synchronized (LOCK) {
            running = true;
            try {
                return makeLocked(c, force, anyway);
            } catch (Exception e) {
                return "Backup failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                running = false;
            }
        }
    }

    private static String makeLocked(Context c, boolean force, boolean anyway) throws Exception {
        Uri tree = Importer.tree(c);
        if (tree == null) return "Pick your music folder first (Music folder, on the home screen)";
        int[] counts = new int[2];
        Map<String, byte[]> files = collect(c, counts);
        String hash = sha1(files);
        SharedPreferences p = prefs(c);
        String dirId = Importer.folderId(c, tree, DIR);
        if (dirId == null) return "Could not make the folder " + DIR + " in your music folder";
        ContentResolver r = c.getContentResolver();
        Map<String, String[]> have = children(r, tree, dirId);
        long now = System.currentTimeMillis();
        String done;
        String[] curZip = have.get(ZIP.toLowerCase(Locale.ROOT));
        // never write over a backup this phone has not made or restored (a new phone's first
        // backup must not push the old phone's away), or one with far more songs than are here
        String held = anyway ? null : holdBack(c, r, tree, curZip, counts[0]);
        if (held != null) {
            done = held;
        } else if (!force && hash.equals(p.getString("hash", "")) && curZip != null) {
            done = "The backup is up to date";
        } else {
            byte[] zip = zip(c, files, now, counts[0]);
            String[] old = have.get(NEW.toLowerCase(Locale.ROOT));
            if (old != null) delete(r, tree, old[0]);
            Uri nu = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, dirId), "application/zip", NEW);
            if (nu == null) return "Could not write the backup";
            OutputStream o = r.openOutputStream(nu, "w");
            try {
                o.write(zip);
            } finally {
                o.close();
            }
            // keep the one before: backup.zip -> backup (previous).zip, then the new one in its place
            String[] prev = have.get(PREV.toLowerCase(Locale.ROOT)), cur = have.get(ZIP.toLowerCase(Locale.ROOT));
            if (prev != null) delete(r, tree, prev[0]);
            if (cur != null) rename(r, tree, cur[0], PREV);
            rename(r, tree, DocumentsContract.getDocumentId(nu), ZIP);
            p.edit().putString("hash", hash).putLong("last", now).putInt("songs", counts[0]).putInt("playlists", counts[1]).apply();
            done = "Backed up: " + counts[0] + (counts[0] == 1 ? " song" : " songs") + " in " + counts[1]
                    + (counts[1] == 1 ? " playlist" : " playlists") + ", play counts and settings";
        }
        extras(c, r, tree, dirId, children(r, tree, dirId));
        return done;
    }

    /** everything that goes in the zip, by name (the zip's time stamp is added when it is written) */
    private static Map<String, byte[]> collect(Context c, int[] counts) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        File dir = c.getFilesDir();
        StringBuilder meta = new StringBuilder("{\"format\":").append(FORMAT);
        meta.append(",\"install\":").append(Library.q(install(c)));
        meta.append(",\"app\":").append(Library.q(version(c)));
        meta.append(",\"device\":").append(Library.q(Build.MANUFACTURER + " " + Build.MODEL));
        String page = prefs(c).getString("page", "{}");
        try {
            new JSONObject(page);
        } catch (Exception e) {
            page = "{}";
        }
        meta.append(",\"page\":").append(page);
        meta.append(",\"lists\":{");
        Map<String, ?> lists = new TreeMap<>(c.getSharedPreferences("lists", Context.MODE_PRIVATE).getAll());
        boolean first = true;
        for (Map.Entry<String, ?> e : lists.entrySet()) {
            if (!(e.getValue() instanceof String)) continue;
            if (!first) meta.append(',');
            first = false;
            meta.append(Library.q(e.getKey())).append(':').append(Library.q((String) e.getValue()));
        }
        meta.append("}}");
        out.put("backup.json", meta.toString().getBytes("UTF-8"));
        for (String n : FILES) {
            File f = new File(dir, n);
            if (f.isFile()) out.put(n, read(f));
        }
        File[] covers = new File(dir, "covers").listFiles();
        if (covers != null) {
            java.util.Arrays.sort(covers);
            for (File f : covers) if (f.isFile() && f.getName().endsWith(".jpg")) out.put("covers/" + f.getName(), read(f));
        }
        out.put("manifest.json", manifest(c, lists, counts).getBytes("UTF-8"));
        return out;
    }

    /**
     * every song, by playlist, with the YouTube video it came from:
     *   {"Chill": {"link": "https://www.youtube.com/playlist?list=...", "songs": [["Artist - Song.mp3", "videoid", 215], ...]}}
     */
    private static String manifest(Context c, Map<String, ?> lists, int[] counts) {
        Library lib = Library.get(c);
        Map<String, List<Library.Song>> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Library.Pl p : lib.playlists) for (Library.Song s : p.songs) add(by, s);
        for (Library.Song s : lib.hiddenSongs) add(by, s);
        Map<String, String> links = new HashMap<>();
        for (Map.Entry<String, ?> e : lists.entrySet()) {
            if (e.getValue() instanceof String) links.put(((String) e.getValue()).toLowerCase(Locale.ROOT), e.getKey());
        }
        StringBuilder b = new StringBuilder("{");
        int songs = 0, n = 0;
        for (Map.Entry<String, List<Library.Song>> e : by.entrySet()) {
            List<Library.Song> list = e.getValue();
            java.util.Collections.sort(list, new java.util.Comparator<Library.Song>() {
                @Override
                public int compare(Library.Song x, Library.Song y) {
                    return x.file.compareToIgnoreCase(y.file);
                }
            });
            if (n++ > 0) b.append(',');
            String li = links.get(e.getKey().toLowerCase(Locale.ROOT));
            b.append(Library.q(e.getKey())).append(":{\"link\":")
                    .append(Library.q(li == null ? "" : "https://www.youtube.com/playlist?list=" + li)).append(",\"songs\":[");
            for (int i = 0; i < list.size(); i++) {
                Library.Song s = list.get(i);
                if (i > 0) b.append(',');
                b.append('[').append(Library.q(s.file)).append(',').append(Library.q(Ids.of(c, s.path)))
                        .append(',').append(Math.round(s.durMs / 1000.0)).append(']');
                songs++;
            }
            b.append("]}");
        }
        Ids.save(c, lib.paths);
        counts[0] = songs;
        counts[1] = n;
        return b.append('}').toString();
    }

    private static void add(Map<String, List<Library.Song>> by, Library.Song s) {
        if (s.pl == null || s.file == null || s.pl.isEmpty()) return;
        List<Library.Song> l = by.get(s.pl);
        if (l == null) by.put(s.pl, l = new ArrayList<>());
        l.add(s);
    }

    private static byte[] zip(Context c, Map<String, byte[]> files, long now, int songs) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        try {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                byte[] data = e.getValue();
                if (e.getKey().equals("backup.json")) {
                    String s = new String(data, "UTF-8");
                    data = ("{\"made\":" + now + ",\"songs\":" + songs + "," + s.substring(1)).getBytes("UTF-8");
                }
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(now);
                z.putNextEntry(ze);
                z.write(data);
                z.closeEntry();
            }
        } finally {
            z.close();
        }
        return bo.toByteArray();
    }

    /** the how-to note, and a copy of the app itself (only when it changed) */
    private static void extras(Context c, ContentResolver r, Uri tree, String dirId, Map<String, String[]> have) {
        try {
            String ver = version(c);
            String[] rd = have.get(README.toLowerCase(Locale.ROOT));
            if (rd == null || !ver.equals(prefs(c).getString("readme", ""))) {
                if (rd != null) delete(r, tree, rd[0]);
                Uri u = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, dirId), "text/plain", README);
                if (u != null) {
                    OutputStream o = r.openOutputStream(u, "w");
                    try {
                        o.write(readme().getBytes("UTF-8"));
                    } finally {
                        o.close();
                    }
                    prefs(c).edit().putString("readme", ver).apply();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            File src = new File(c.getApplicationInfo().sourceDir);
            String[] ap = have.get(APK.toLowerCase(Locale.ROOT));
            long stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            if (!src.isFile() || ap != null && ap[2] != null && Long.parseLong(ap[2]) == src.length()
                    && prefs(c).getLong("apk", 0) == stamp) return;
            if (ap != null) delete(r, tree, ap[0]);
            Uri u = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, dirId),
                    "application/vnd.android.package-archive", APK);
            if (u == null) return;
            InputStream in = new FileInputStream(src);
            boolean ok = false;
            try {
                OutputStream o = r.openOutputStream(u, "w");
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    ok = true;
                } finally {
                    o.close();
                }
            } finally {
                in.close();
                if (!ok) DocumentsContract.deleteDocument(r, u);      // never leave half an app behind
            }
            if (ok) prefs(c).edit().putLong("apk", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime).apply();
        } catch (Exception ignored) {
        }
    }

    static String readme() {
        return "MY MUSIC - how to get everything back on a new phone\n"
                + "\n"
                + "MY MUSIC makes this folder and updates it by itself when you close the\n"
                + "app. It lives inside your music folder so it travels with your songs.\n"
                + "\n"
                + "  backup.zip              play counts, hidden songs, playlist pictures,\n"
                + "                          which YouTube playlist goes where, songs gone\n"
                + "                          from YouTube, lyrics fixes, sort order - and\n"
                + "                          a list of every song and its YouTube video\n"
                + "  backup (previous).zip   the one before it, just in case\n"
                + "  MY MUSIC.apk            the app itself\n"
                + "\n"
                + "ON THE NEW PHONE\n"
                + "1. Copy the whole music folder to the new phone (for example\n"
                + "   Internal storage / Music / MY MUSIC).\n"
                + "2. In Files, open its MY MUSIC backup folder and tap MY MUSIC.apk\n"
                + "   (or download the newest one from the app's Releases page).\n"
                + "   If Android asks, allow installing from Files. Install.\n"
                + "3. Open MY MUSIC, allow music, tap Music folder and pick the folder\n"
                + "   you copied over.\n"
                + "4. On the home screen tap Backup, then Restore.\n"
                + "5. If the songs did not come over, tap Get songs back in the same\n"
                + "   place - MY MUSIC downloads them again from YouTube.\n";
    }

    /** this install of MY MUSIC (a new phone gets a new one) */
    static synchronized String install(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("install", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            p.edit().putString("install", id).apply();
        }
        return id;
    }

    /** backup.json of a backup zip, or null */
    static JSONObject meta(ContentResolver r, Uri u) {
        InputStream in = null;
        try {
            in = r.openInputStream(u);
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!"backup.json".equals(e.getName())) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0 && b.size() < 1 << 20) b.write(buf, 0, n);
                return new JSONObject(b.toString("UTF-8"));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** why the backup there must stay as it is, or null to go ahead */
    private static String holdBack(Context c, ContentResolver r, Uri tree, String[] curZip, int songsHere) {
        if (curZip == null) return null;
        JSONObject old = meta(r, DocumentsContract.buildDocumentUriUsingTree(tree, curZip[0]));
        if (old == null) return null;
        String why = holdReason(old, install(c), prefs(c).getString("adopted", ""), songsHere);
        if ("foreign".equals(why)) return "There is a backup from another phone in your music folder / MY MUSIC backup - "
                + "Restore it first. MY MUSIC won't replace it until you do.";
        if ("fewer".equals(why)) return "This phone has " + songsHere + " songs but the backup has " + old.optInt("songs")
                + " - not replacing it. Get songs back first, or tap Back up anyway.";
        return null;
    }

    /** "foreign" (another phone's, not restored here), "fewer" (far more songs than are here), or null */
    static String holdReason(JSONObject old, String mine, String adopted, int songsHere) {
        String oi = old.optString("install", "");
        if (!oi.isEmpty() && !oi.equals(mine) && !oi.equals(adopted)) return "foreign";
        int os = old.optInt("songs", 0);
        if (os >= 20 && songsHere * 2 < os) return "fewer";
        return null;
    }

    // ================================================================ what the page shows

    /** {tree, last, songs, playlists, found, restored, back: {songs, playlists, noId}} */
    static String info(Context c) {
        SharedPreferences p = prefs(c);
        StringBuilder b = new StringBuilder("{");
        Uri tree = Importer.tree(c);
        b.append("\"tree\":").append(tree != null);
        b.append(",\"last\":").append(p.getLong("last", 0));
        b.append(",\"songs\":").append(p.getInt("songs", 0));
        b.append(",\"playlists\":").append(p.getInt("playlists", 0));
        b.append(",\"restored\":").append(p.getLong("restored", 0));
        b.append(",\"running\":").append(running);
        long found = 0;
        String hold = null;
        if (tree != null) {
            String[] z = find(c, tree);
            if (z != null && z[1] != null) {
                try {
                    found = Long.parseLong(z[1]);
                } catch (Exception ignored) {
                }
            }
            if (z != null) {
                JSONObject old = meta(c.getContentResolver(), Uri.parse(z[0]));
                if (old != null) {
                    hold = holdReason(old, install(c), p.getString("adopted", ""), Library.get(c).paths.size());
                    if (old.optLong("made") > 0) found = old.optLong("made");
                    b.append(",\"foundSongs\":").append(old.optInt("songs"));
                }
            }
        }
        b.append(",\"found\":").append(found);
        if (hold != null) b.append(",\"hold\":").append(Library.q(hold));
        int[] back = getBack(c, false);
        if (back != null) b.append(",\"back\":{\"songs\":").append(back[0]).append(",\"playlists\":").append(back[1])
                .append(",\"noId\":").append(back[2]).append('}');
        return b.append('}').toString();
    }

    /** the backup in the music folder / MY MUSIC backup: {document uri, last modified, size}, or null */
    static String[] find(Context c, Uri tree) {
        ContentResolver r = c.getContentResolver();
        String root = DocumentsContract.getTreeDocumentId(tree);
        String dirId = null;
        for (Map.Entry<String, String[]> e : children(r, tree, root).entrySet()) {
            if (e.getKey().equals(DIR.toLowerCase(Locale.ROOT)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(e.getValue()[3])) dirId = e.getValue()[0];
        }
        if (dirId == null) return null;
        Map<String, String[]> have = children(r, tree, dirId);
        for (String n : new String[]{ZIP, NEW, PREV}) {
            String[] z = have.get(n.toLowerCase(Locale.ROOT));
            if (z != null) return new String[]{DocumentsContract.buildDocumentUriUsingTree(tree, z[0]).toString(), z[1], z[2]};
        }
        return null;
    }

    // ================================================================ restoring

    /**
     * puts a backup back. Nothing is lost by restoring: play counts keep the
     * higher number, hidden songs and gone songs are added to what is
     * there, pictures and lyrics fixes fill in only where there are none.
     * Returns {"ok": true, "msg": "...", "page": {...}} or {"ok": false, "msg": "..."}.
     */
    static String restore(Context c, InputStream in) {
        try {
            Map<String, byte[]> e = unzip(in);
            byte[] metaB = e.get("backup.json");
            if (metaB == null) return "{\"ok\":false,\"msg\":\"That file is not a MY MUSIC backup\"}";
            JSONObject meta = new JSONObject(new String(metaB, "UTF-8"));
            File dir = c.getFilesDir();
            List<String> said = new ArrayList<>();

            JSONObject lists = meta.optJSONObject("lists");
            if (lists != null) {
                SharedPreferences.Editor ed = c.getSharedPreferences("lists", Context.MODE_PRIVATE).edit();
                SharedPreferences lp = c.getSharedPreferences("lists", Context.MODE_PRIVATE);
                Iterator<String> it = lists.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!lp.contains(k)) ed.putString(k, lists.optString(k));
                }
                ed.apply();
            }
            int n;
            synchronized (Plays.class) {
                try {
                    n = mergePlays(new File(dir, "plays.json"), e.get("plays.json"));
                } finally {
                    Plays.reload();
                }
            }
            if (n > 0) said.add(n + (n == 1 ? " play count" : " play counts"));
            synchronized (Library.class) {
                n = mergeLines(new File(dir, "hidden.txt"), e.get("hidden.txt"));
            }
            if (n > 0) said.add(n + (n == 1 ? " hidden song" : " hidden songs"));
            synchronized (Missing.class) {
                try {
                    mergeLists(new File(dir, "missing.json"), e.get("missing.json"));
                } finally {
                    Missing.reload();
                }
            }
            synchronized (Dead.class) {
                try {
                    mergeDead(new File(dir, "dead.json"), e.get("dead.json"));
                } finally {
                    Dead.reload();
                }
            }
            synchronized (LyricsFix.class) {
                try {
                    mergeFix(new File(dir, "lyrics_fix.json"), e.get("lyrics_fix.json"));
                } finally {
                    LyricsFix.reload();
                }
            }
            String from = meta.optString("install", "");
            if (!from.isEmpty()) prefs(c).edit().putString("adopted", from).apply();     // its successor may replace it now
            int pics = 0;
            File cdir = new File(dir, "covers");
            for (Map.Entry<String, byte[]> x : e.entrySet()) {
                String name = x.getKey();
                if (!name.startsWith("covers/") || name.contains("..") || !name.endsWith(".jpg")) continue;
                File f = new File(cdir, name.substring(7));
                if (f.isFile()) continue;
                cdir.mkdirs();
                write(f, x.getValue());
                pics++;
            }
            if (pics > 0) said.add(pics + (pics == 1 ? " playlist picture" : " playlist pictures"));
            byte[] man = e.get("manifest.json");
            if (man != null) write(new File(dir, "restored_manifest.json"), man);
            prefs(c).edit().putLong("restored", System.currentTimeMillis()).apply();
            Library.load(c);
            int[] back = getBack(c, false);
            String msg = "Restored" + (said.isEmpty() ? " the settings" : ": " + join(said)) + ".";
            if (back != null && back[0] > 0) msg += " " + back[0] + (back[0] == 1 ? " song is" : " songs are")
                    + " not on this phone yet - Get songs back downloads them.";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("msg", msg);
            out.put("made", meta.optLong("made"));
            JSONObject page = meta.optJSONObject("page");
            out.put("page", page == null ? new JSONObject() : page);
            return out.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"msg\":" + Library.q("Could not read that backup: " + ex.getMessage()) + "}";
        }
    }

    private static Map<String, byte[]> unzip(InputStream in) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        ZipInputStream z = new ZipInputStream(in);
        long total = 0;
        try {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                int n;
                while ((n = z.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    total += n;
                    if (total > 64L * 1024 * 1024) throw new Exception("too big");
                }
                out.put(e.getName(), b.toByteArray());
            }
        } finally {
            z.close();
        }
        return out;
    }

    /** {"key": [times, last]}: the higher of each */
    static int mergePlays(File f, byte[] from) throws Exception {
        if (from == null) return 0;
        JSONObject in = new JSONObject(new String(from, "UTF-8")), mine = readJson(f);
        int n = 0;
        Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = in.optJSONArray(k), b = mine.optJSONArray(k);
            if (a == null) continue;
            long times = Math.max(a.optLong(0), b == null ? 0 : b.optLong(0));
            long last = Math.max(a.optLong(1), b == null ? 0 : b.optLong(1));
            if (b == null || times != b.optLong(0) || last != b.optLong(1)) n++;
            mine.put(k, new JSONArray().put(times).put(last));
        }
        write(f, mine.toString().getBytes("UTF-8"));
        return n;
    }

    /** one line each: lines not there yet are added */
    static int mergeLines(File f, byte[] from) throws Exception {
        if (from == null) return 0;
        Set<String> have = new java.util.LinkedHashSet<>();
        if (f.isFile()) for (String l : new String(read(f), "UTF-8").split("\n")) if (!l.trim().isEmpty()) have.add(l);
        int n = 0;
        for (String l : new String(from, "UTF-8").split("\n")) if (!l.trim().isEmpty() && have.add(l)) n++;
        StringBuilder b = new StringBuilder();
        for (String l : have) b.append(l).append('\n');
        write(f, b.toString().getBytes("UTF-8"));
        return n;
    }

    /** {"playlist": [{"id": ...}, ...]}: songs not listed yet are added */
    static void mergeLists(File f, byte[] from) throws Exception {
        if (from == null) return;
        JSONObject in = new JSONObject(new String(from, "UTF-8")), mine = readJson(f);
        Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = in.optJSONArray(k);
            if (a == null) continue;
            JSONArray b = mine.optJSONArray(k);
            if (b == null) mine.put(k, b = new JSONArray());
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < b.length(); i++) ids.add(b.optJSONObject(i) == null ? "" : b.optJSONObject(i).optString("id"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && ids.add(o.optString("id"))) b.put(o);
            }
        }
        write(f, mine.toString().getBytes("UTF-8"));
    }

    /** {"id": [times, last, why]}: the one with more tries (then the later one) */
    static void mergeDead(File f, byte[] from) throws Exception {
        if (from == null) return;
        JSONObject in = new JSONObject(new String(from, "UTF-8")), mine = readJson(f);
        Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = in.optJSONArray(k), b = mine.optJSONArray(k);
            if (a == null) continue;
            if (b == null || a.optInt(0) > b.optInt(0) || a.optInt(0) == b.optInt(0) && a.optLong(1) > b.optLong(1)) mine.put(k, a);
        }
        write(f, mine.toString().getBytes("UTF-8"));
    }

    /** {"song key": [...]}: only where there is none */
    static void mergeFix(File f, byte[] from) throws Exception {
        if (from == null) return;
        JSONObject in = new JSONObject(new String(from, "UTF-8")), mine = readJson(f);
        Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!mine.has(k)) mine.put(k, in.get(k));
        }
        write(f, mine.toString().getBytes("UTF-8"));
    }

    // ================================================================ getting the songs back

    /**
     * the songs the restored backup lists that are not on this phone.
     * start = also queue their downloads. Returns {songs, playlists, without a
     * YouTube video}, or null when no backup was restored.
     */
    static int[] getBack(Context c, boolean start) {
        File mf = new File(c.getFilesDir(), "restored_manifest.json");
        if (!mf.isFile()) return null;
        JSONObject man;
        try {
            man = readJson(mf);
        } catch (Exception e) {
            return null;
        }
        Library lib = Library.get(c);
        Set<String> here = new HashSet<>();
        String root = lib.root + "/";
        for (String p : lib.paths) {
            if (!p.startsWith(root)) continue;
            String rel = p.substring(root.length());
            here.add(rel.toLowerCase(Locale.ROOT));
            int sl = rel.indexOf('/');
            // the video too: a song fetched again may come back under a slightly different name
            String id = start ? Ids.of(c, p) : Ids.cached(c, p);
            if (sl > 0 && id != null && !id.isEmpty()) here.add((rel.substring(0, sl) + "/#" + id).toLowerCase(Locale.ROOT));
        }
        int songs = 0, pls = 0, noId = 0;
        Iterator<String> it = man.keys();
        List<String> folders = new ArrayList<>();
        while (it.hasNext()) folders.add(it.next());
        java.util.Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        for (String folder : folders) {
            JSONObject e = man.optJSONObject(folder);
            JSONArray a = e == null ? null : e.optJSONArray("songs");
            if (a == null) continue;
            JSONArray want = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONArray s = a.optJSONArray(i);
                if (s == null) continue;
                String file = s.optString(0), id = s.optString(1);
                if (here.contains((folder + "/" + file).toLowerCase(Locale.ROOT))
                        || !id.isEmpty() && here.contains((folder + "/#" + id).toLowerCase(Locale.ROOT))) continue;
                if (id.isEmpty()) {
                    noId++;
                    continue;
                }
                String[] ta = nameParts(file);
                want.put(new JSONArray().put(id).put(ta[0]).put(ta[1]).put(String.valueOf(s.optLong(2))).put(file));
            }
            if (want.length() == 0) continue;
            songs += want.length();
            pls++;
            if (start) {
                try {
                    File rf = restoreFile(c, folder);
                    rf.getParentFile().mkdirs();
                    write(rf, want.toString().getBytes("UTF-8"));
                    DownloadService.start(c, IDS + folder, folder);
                } catch (Exception ignored) {
                }
            }
        }
        return new int[]{songs, pls, noId};
    }

    /** "Artist - Song.mp3" -> {Song, Artist} */
    static String[] nameParts(String file) {
        String n = file.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
        int d = n.indexOf(" - ");
        if (d > 0) return new String[]{n.substring(d + 3).trim(), n.substring(0, d).trim()};
        return new String[]{n.trim(), ""};
    }

    private static File restoreFile(Context c, String folder) {
        return new File(new File(c.getFilesDir(), "restore"), Art.sha1(folder.toLowerCase(Locale.ROOT)) + ".json");
    }

    /** the songs a "Get songs back" job fetches for this playlist: {id, title, artist, seconds, file name} */
    static List<String[]> restoreItems(Context c, String folder) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(read(restoreFile(c, folder)), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray s = a.optJSONArray(i);
                if (s != null && s.length() >= 5) out.add(new String[]{s.optString(0), s.optString(1), s.optString(2), s.optString(3), s.optString(4)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** add songs to a playlist's "fetch these" list (kept with any already waiting); {id, title, artist, seconds, file} */
    static synchronized void addRestoreItems(Context c, String folder, List<String[]> items) throws Exception {
        File rf = restoreFile(c, folder);
        JSONArray a = new JSONArray();
        Set<String> have = new HashSet<>();
        if (rf.isFile()) {
            JSONArray old = new JSONArray(new String(read(rf), "UTF-8"));
            for (int i = 0; i < old.length(); i++) {
                JSONArray s = old.optJSONArray(i);
                if (s != null && have.add(s.optString(0))) a.put(s);
            }
        }
        for (String[] it : items) {
            if (!have.add(it[0])) continue;
            a.put(new JSONArray().put(it[0]).put(it[1]).put(it[2]).put(it[3]).put(it[4]));
        }
        rf.getParentFile().mkdirs();
        write(rf, a.toString().getBytes("UTF-8"));
    }

    /**
     * a fetch job finished: drop the songs that came in (or are gone for good),
     * count one more try for the ones it tried, give up on a song after 3 tries.
     * Returns {left, left that this job never tried}.
     */
    static synchronized int[] restoreKeep(Context c, String folder, Set<String> drop, Set<String> tried) {
        File rf = restoreFile(c, folder);
        int left = 0, untried = 0;
        try {
            if (!rf.isFile()) return new int[]{0, 0};
            JSONArray old = new JSONArray(new String(read(rf), "UTF-8")), keep = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONArray s = old.optJSONArray(i);
                if (s == null || drop.contains(s.optString(0))) continue;
                int tries = s.optInt(5, 0);
                if (tried.contains(s.optString(0))) tries++;
                else untried++;
                if (tries >= 3) continue;
                keep.put(new JSONArray().put(s.optString(0)).put(s.optString(1)).put(s.optString(2)).put(s.optString(3))
                        .put(s.optString(4)).put(tries));
            }
            left = keep.length();
            if (left == 0) rf.delete();
            else write(rf, keep.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
        return new int[]{left, untried};
    }

    static void restoreDone(Context c, String folder) {
        restoreFile(c, folder).delete();
    }

    // ================================================================ files

    /** children of a folder: lower-case name -> {document id, last modified, size, mime} */
    private static Map<String, String[]> children(ContentResolver r, Uri tree, String docId) {
        Map<String, String[]> out = new HashMap<>();
        Cursor c = null;
        try {
            c = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name != null) out.put(name.toLowerCase(Locale.ROOT), new String[]{c.getString(0), c.getString(2), c.getString(3), c.getString(4)});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static void delete(ContentResolver r, Uri tree, String docId) {
        try {
            DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, docId));
        } catch (Exception ignored) {
        }
    }

    private static void rename(ContentResolver r, Uri tree, String docId, String name) {
        try {
            DocumentsContract.renameDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, docId), name);
        } catch (Exception ignored) {
        }
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha1(Map<String, byte[]> files) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            d.update(e.getKey().getBytes("UTF-8"));
            d.update((byte) 0);
            d.update(e.getValue());
        }
        StringBuilder b = new StringBuilder();
        for (byte x : d.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
        return b.toString();
    }

    private static JSONObject readJson(File f) throws Exception {
        if (!f.isFile()) return new JSONObject();
        String s = new String(read(f), "UTF-8").trim();
        return s.isEmpty() ? new JSONObject() : new JSONObject(s);
    }

    static byte[] read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new Exception("could not write " + f.getName());
        }
    }

    private static String join(List<String> a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) b.append(i == a.size() - 1 ? " and " : ", ");
            b.append(a.get(i));
        }
        return b.toString();
    }
}
