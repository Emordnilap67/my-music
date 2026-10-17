package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class Embed {
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(Low.THREADS);
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static final Object LOCK = new Object();
    private static volatile String writing;
    private static final long MAX_FILE = 80L * 1024 * 1024;
    private static final String ALL_DONE = "embedAll1";
    private static JSONObject done;          // song key -> sha1 of the lyrics in its file ("x:..." = cannot be written)

    private Embed() {}

    // ------------------------------------------------------------ asking

    /** lyrics were found or changed for this song: put them in its file when it is not playing */
    static void mark(Context c, String key) {
        synchronized (Embed.class) {
            Set<String> p = pending(c);
            p.add(key);
            savePending(c, p);
        }
        soon(c);
    }

    /** look for work in the background (start-up, the song changed, lyrics found) */
    static void soon(Context c) {
        final Context app = c.getApplicationContext();
        if (!QUEUED.compareAndSet(false, true)) return;
        BG.execute(new Runnable() {
            @Override
            public void run() {
                QUEUED.set(false);
                try {
                    pass(app);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** the player is about to open this file: wait (a second or two at most) if it is being written */
    static void waitIfWriting(String path) {
        if (path == null) return;
        synchronized (LOCK) {
            long until = System.currentTimeMillis() + 5000;
            while (path.equals(writing)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return;
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** are exactly these lyrics in this song's file now (for the page's note) */
    static synchronized boolean isIn(Context c, String key, String text) {
        if (key == null || text == null) return false;
        return Art.sha1(text).equals(done(c).optString(key, ""));
    }

    // ------------------------------------------------------------ the work

    private static void pass(Context c) throws Exception {
        recover(c);
        SharedPreferences prefs = c.getSharedPreferences("embed", Context.MODE_PRIVATE);
        boolean all = !prefs.getBoolean(ALL_DONE, false);
        Library lib = Library.get(c);
        List<Library.Song> list = new ArrayList<>();
        Set<String> want;
        synchronized (Embed.class) {
            want = new LinkedHashSet<>(pending(c));
        }
        Set<String> gone = new LinkedHashSet<>();
        for (String k : want) {
            Library.Song s = lib.byKey.get(k);
            if (s != null) list.add(s);
            else gone.add(k);                          // renamed, moved or hidden since
        }
        dropPending(c, gone);
        if (all) {
            for (Library.Pl p : lib.playlists) for (Library.Song s : p.songs) if (!want.contains(s.key)) list.add(s);
        } else {
            Set<String> seen = new java.util.HashSet<>(want);
            for (String k : Heard.keys(c)) {
                Library.Song s = lib.byKey.get(k);
                boolean has;
                synchronized (Embed.class) {
                    has = done(c).has(k);
                }
                if (s != null && !has && seen.add(k)) list.add(s);
            }
        }
        if (list.isEmpty()) {
            if (all) prefs.edit().putBoolean(ALL_DONE, true).apply();
            return;
        }
        Uri tree = Importer.tree(c);
        if (tree == null) return;                      // the folder was never allowed: keep the list for later
        Map<String, Map<String, String>> names = new HashMap<>();
        Set<String> settled = new LinkedHashSet<>(), later = new LinkedHashSet<>();
        List<String> scan = new ArrayList<>();
        int wrote = 0;
        for (Library.Song s : list) {
            String cur = c.getSharedPreferences("player", Context.MODE_PRIVATE).getString("cur", "");
            if (s.key.equals(cur)) {                   // open in the player: after it (the next song starts a pass)
                later.add(s.key);
                continue;
            }
            String text = Lyrics.forFile(c, s);
            if (text == null) {
                settled.add(s.key);
                continue;
            }
            String h = Art.sha1(text);
            String had;
            synchronized (Embed.class) {
                had = done(c).optString(s.key, "");
            }
            if (had.equals(h) || had.equals("x:" + h)) {
                settled.add(s.key);
                continue;
            }
            int r = write(c, tree, names, s, text);
            if (r == RETRY) {
                later.add(s.key);
                continue;
            }
            synchronized (Embed.class) {
                done(c).put(s.key, r == OK ? h : "x:" + h);
                if (++wrote % 10 == 0) saveDone(c);
            }
            settled.add(s.key);
            if (r == OK) scan.add(s.path);
            if (scan.size() >= 20) {                   // let Android see the new sizes, a batch at a time
                Importer.scan(c, scan);
                scan = new ArrayList<>();
            }
            Thread.sleep(150);                         // keep the phone easy
        }
        Importer.scan(c, scan);
        synchronized (Embed.class) {
            saveDone(c);
            Set<String> p = pending(c);
            p.removeAll(settled);
            p.addAll(later);
            savePending(c, p);
        }
        if (all) prefs.edit().putBoolean(ALL_DONE, true).apply();      // anything left over is on the pending list
    }

    private static final int OK = 0, SKIP = 1, RETRY = 2;

    private static int write(Context c, Uri tree, Map<String, Map<String, String>> names, Library.Song s, String text) {
        File f = new File(s.path);
        if (!s.file.toLowerCase(Locale.ROOT).endsWith(".mp3")) return SKIP;
        long len = f.length();
        if (len <= 0 || len > MAX_FILE) return SKIP;
        byte[] old;
        try {
            old = readAll(f);
        } catch (Exception e) {
            return RETRY;
        }
        if (old.length != len) return RETRY;
        String there = Tagger.lyricsIn(old);
        if (text.equals(there)) return OK;                     // already in it
        byte[] neu = Tagger.withLyrics(old, text);
        if (neu == null || !Tagger.verify(old, neu, text)) return SKIP;
        Map<String, String> files = names.get(s.pl);
        if (files == null) {
            String fid = Importer.folderId(c, tree, s.pl);
            if (fid == null) return RETRY;
            files = Importer.names(c, tree, fid);
            names.put(s.pl, files);
        }
        String doc = files.get(s.file.toLowerCase(Locale.ROOT));
        if (doc == null) return SKIP;
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
        ContentResolver r = c.getContentResolver();
        File safe = new File(c.getFilesDir(), "embed-safe.mp3");
        File meta = new File(c.getFilesDir(), "embed-safe.json");
        try {
            writeFile(safe, old);
            if (!Arrays.equals(readAll(safe), old)) return RETRY;
            writeFile(meta, new JSONObject().put("path", s.path).put("uri", u.toString())
                    .put("old", old.length).put("new", neu.length).toString().getBytes("UTF-8"));
        } catch (Exception e) {
            safe.delete();
            meta.delete();
            return RETRY;
        }
        boolean good = false;
        synchronized (LOCK) {
            writing = s.path;
        }
        try {
            put(r, u, neu);
            good = Arrays.equals(readAll(f), neu);
            if (!good) put(r, u, old);                         // put the old one back
        } catch (Exception e) {
            try {
                put(r, u, old);
            } catch (Exception ignored) {
            }
        } finally {
            synchronized (LOCK) {
                writing = null;
                LOCK.notifyAll();
            }
        }
        try {
            if (good || Arrays.equals(readAll(f), old)) {
                safe.delete();
                meta.delete();
            }
        } catch (Exception ignored) {
        }
        return good ? OK : SKIP;
    }

    /** a write was cut off (the app was closed mid-way): check the file, put the old one back if needed */
    private static void recover(Context c) {
        File safe = new File(c.getFilesDir(), "embed-safe.mp3");
        File meta = new File(c.getFilesDir(), "embed-safe.json");
        if (!meta.isFile()) {
            safe.delete();
            return;
        }
        try {
            JSONObject m = new JSONObject(new String(readAll(meta), "UTF-8"));
            byte[] old = readAll(safe);
            if (old.length != m.getLong("old")) return;        // the copy itself is not whole: leave everything as is
            File f = new File(m.getString("path"));
            byte[] now = readAll(f);
            boolean fine = Arrays.equals(now, old);
            if (!fine && now.length == m.getLong("new")) {
                int a = Tagger.audioStart(old), b = Tagger.audioStart(now);
                fine = a >= 0 && b > 0 && old.length - a == now.length - b
                        && Arrays.equals(Arrays.copyOfRange(old, a, old.length), Arrays.copyOfRange(now, b, now.length))
                        && Tagger.lyricsIn(now) != null;
            }
            if (!fine) {
                put(c.getContentResolver(), Uri.parse(m.getString("uri")), old);
                if (!Arrays.equals(readAll(f), old)) return;   // keep the copy: try again next time
                Importer.scan(c, Collections.singletonList(f.getPath()));
            }
            safe.delete();
            meta.delete();
        } catch (Exception ignored) {
        }
    }

    private static void put(ContentResolver r, Uri u, byte[] b) throws Exception {
        OutputStream o = r.openOutputStream(u, "wt");
        if (o == null) throw new Exception("could not open the file");
        try {
            o.write(b);
            o.flush();
        } finally {
            o.close();
        }
    }

    // ------------------------------------------------------------ small files

    private static JSONObject done(Context c) {
        if (done == null) {
            done = new JSONObject();
            try {
                File f = new File(c.getFilesDir(), "embedded.json");
                if (f.isFile()) done = new JSONObject(new String(readAll(f), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return done;
    }

    private static void saveDone(Context c) {
        try {
            File f = new File(c.getFilesDir(), "embedded.json"), t = new File(c.getFilesDir(), "embedded.json.tmp");
            writeFile(t, done(c).toString().getBytes("UTF-8"));
            if (!t.renameTo(f)) t.delete();
        } catch (Exception ignored) {
        }
    }

    private static Set<String> pending(Context c) {
        Set<String> out = new LinkedHashSet<>();
        try {
            File f = new File(c.getFilesDir(), "embed-pending.json");
            if (f.isFile()) {
                JSONArray a = new JSONArray(new String(readAll(f), "UTF-8"));
                for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void savePending(Context c, Set<String> p) {
        try {
            writeFile(new File(c.getFilesDir(), "embed-pending.json"), new JSONArray(p).toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static void dropPending(Context c, Set<String> gone) {
        if (gone.isEmpty()) return;
        synchronized (Embed.class) {
            Set<String> p = pending(c);
            if (p.removeAll(gone)) savePending(c, p);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(b);
            o.getFD().sync();
        } finally {
            o.close();
        }
    }
}
