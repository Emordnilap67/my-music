package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Every song in the music folder, read from Android's own media index.
 * Each folder directly inside the music folder is a playlist.
 */
final class Library {
    static final class Song {
        String key, pl, file, path, title, artist, album;
        long id, durMs, size, added;
    }

    static final class Pl {
        final String name;
        final List<Song> songs = new ArrayList<>();
        String cover = "";          // "" = none; otherwise a stamp that changes when the picture does
        boolean custom;             // picked in the app
        Pl(String n) { name = n; }
    }

    private static volatile Library current;

    final String root;
    final List<Pl> playlists = new ArrayList<>();
    final Map<String, Song> byKey = new HashMap<>();
    final Set<String> paths = new HashSet<>();          // every song file, hidden ones too
    final List<Song> hiddenSongs = new ArrayList<>();
    String made = "";
    String json = "{\"playlists\":[]}";

    private Library(String root) { this.root = root; }

    private static volatile String chosenRoot;

    /** the music folder (picked in the app); Music/MY MUSIC until one is picked */
    static String rootPath() {
        String r = chosenRoot;
        return r != null ? r : new File(Environment.getExternalStorageDirectory(), "Music/MY MUSIC").getAbsolutePath();
    }

    private static void useRoot(Context c) {
        String d = Importer.rootDoc(c);
        chosenRoot = d.isEmpty() ? null : Importer.pathOf(d);
    }

    static Library get(Context c) {
        Library l = current;
        return l != null ? l : load(c);
    }

    static synchronized Library load(Context c) {
        useRoot(c.getApplicationContext());
        Library l = new Library(rootPath());
        l.read(c.getApplicationContext());
        current = l;
        return l;
    }

    private void read(Context c) {
        Map<String, Pl> pls = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> hide = hidden(c);
        String[] cols = {"_data", "title", "artist", "album", "duration", "_size", "date_added", "_id"};
        String top = root + "/";
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cols, "_data LIKE ?", new String[]{top + "%"}, null);
            while (cur != null && cur.moveToNext()) {
                String path = cur.getString(0);
                if (path == null || !path.startsWith(top)) continue;
                String rel = path.substring(top.length());
                int slash = rel.indexOf('/');
                // only songs directly inside a playlist folder
                if (slash <= 0 || rel.indexOf('/', slash + 1) >= 0) continue;
                String pl = rel.substring(0, slash), file = rel.substring(slash + 1);
                if (pl.startsWith(".") || file.startsWith(".")) continue;
                Song s = new Song();
                s.pl = pl;
                s.file = file;
                s.path = path;
                s.key = pl + "/" + file;
                s.title = clean(cur.getString(1));
                s.artist = clean(cur.getString(2));
                s.album = clean(cur.getString(3));
                if (s.title.isEmpty()) {
                    int dot = file.lastIndexOf('.');
                    s.title = dot > 0 ? file.substring(0, dot) : file;
                }
                s.durMs = cur.getLong(4);
                s.size = cur.getLong(5);
                s.added = cur.getLong(6);
                s.id = cur.getLong(7);
                paths.add(path);
                if (hide.contains(s.key)) {
                    hiddenSongs.add(s);
                    continue;
                }
                Pl p = pls.get(pl);
                if (p == null) {
                    p = new Pl(pl);
                    pls.put(pl, p);
                }
                p.songs.add(s);
                byKey.put(s.key, s);
            }
        } catch (Exception e) {
            // no permission yet, or the index is busy: an empty library for now
        } finally {
            if (cur != null) cur.close();
        }
        for (Pl p : pls.values()) {
            Collections.sort(p.songs, new Comparator<Song>() {
                @Override
                public int compare(Song a, Song b) {
                    return a.file.compareToIgnoreCase(b.file);
                }
            });
            File picked = Covers.file(c, p.name);
            if (picked.isFile()) {
                p.cover = "u" + picked.lastModified();
                p.custom = true;
            } else if (Covers.hasAsset(c, p.name)) {
                p.cover = "a";
            } else {
                for (String cv : new String[]{"cover.jpg", "folder.jpg"}) {
                    if (new File(top + p.name + "/" + cv).isFile()) {
                        p.cover = "f";
                        break;
                    }
                }
            }
            playlists.add(p);
        }
        made = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        json = toJson();
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        return "<unknown>".equals(s) ? "" : s;
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".flac") || n.endsWith(".wav");
    }

    /**
     * Update library: index songs Android has not seen yet, drop ones that
     * are gone, read again. Runs off the main thread. Returns a short line.
     */
    static String rescan(Context c) {
        Library old = get(c);
        Set<String> onDisk = new HashSet<>();
        File[] dirs = new File(old.root).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.startsWith(".") && isAudio(n) && f.isFile()) onDisk.add(f.getAbsolutePath());
                }
            }
        }
        List<String> todo = new ArrayList<>();
        for (String p : onDisk) if (!old.paths.contains(p)) todo.add(p);
        int added = todo.size();
        // only treat songs as gone when the folder could be read at all
        if (!onDisk.isEmpty()) for (String p : old.paths) if (!onDisk.contains(p)) todo.add(p);
        int gone = todo.size() - added;
        if (!todo.isEmpty()) {
            final CountDownLatch done = new CountDownLatch(todo.size());
            MediaScannerConnection.scanFile(c.getApplicationContext(),
                    todo.toArray(new String[0]), null, new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            done.countDown();
                        }
                    });
            try {
                done.await(Math.max(30, todo.size() / 4), TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        Library l = load(c);
        int n = l.byKey.size();
        String msg = String.format(Locale.US, n == 1 ? "%,d song" : "%,d songs", n);
        if (added > 0) msg += ", " + added + " new";
        if (gone > 0) msg += ", " + gone + " removed";
        return msg;
    }

    private String toJson() {
        StringBuilder b = new StringBuilder(256 + byKey.size() * 180);
        b.append("{\"made\":").append(q(made)).append(",\"playlists\":[");
        for (int i = 0; i < playlists.size(); i++) {
            Pl p = playlists.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(q(p.name)).append(",\"cover\":").append(q(p.cover))
                    .append(",\"custom\":").append(p.custom).append(",\"songs\":[");
            for (int j = 0; j < p.songs.size(); j++) {
                Song s = p.songs.get(j);
                if (j > 0) b.append(',');
                b.append("{\"file\":").append(q(s.file))
                        .append(",\"title\":").append(q(s.title))
                        .append(",\"artist\":").append(q(s.artist))
                        .append(",\"album\":").append(q(s.album))
                        .append(",\"duration\":").append(Math.round(s.durMs / 1000.0))
                        .append(",\"mtime\":").append(s.added)
                        .append(",\"size\":").append(s.size)
                        .append('}');
            }
            b.append("]}");
        }
        b.append("],\"hidden\":[");
        for (int j = 0; j < hiddenSongs.size(); j++) {
            Song s = hiddenSongs.get(j);
            if (j > 0) b.append(',');
            b.append("{\"p\":").append(q(s.pl)).append(",\"file\":").append(q(s.file))
                    .append(",\"title\":").append(q(s.title)).append(",\"artist\":").append(q(s.artist)).append('}');
        }
        return b.append("]}").toString();
    }

    // ---------------------------------------------------------------- hidden songs

    private static File hiddenFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "hidden.txt");
    }

    static synchronized Set<String> hidden(Context c) {
        Set<String> out = new HashSet<>();
        File f = hiddenFile(c);
        if (!f.isFile()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) if (!line.isEmpty()) out.add(line);
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** hide these songs (keys "playlist/file"), one write, then read the library again */
    static synchronized void hideAll(Context c, java.util.Collection<String> keys) {
        Set<String> h = hidden(c);
        h.addAll(keys);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(hiddenFile(c)), "UTF-8");
            try {
                for (String k : h) w.write(k + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
        load(c);
    }

    /** hide (true) or show again (false); then read the library again */
    static synchronized void setHidden(Context c, String key, boolean hide) {
        Set<String> h = hidden(c);
        if (hide) h.add(key);
        else h.remove(key);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(hiddenFile(c)), "UTF-8");
            try {
                for (String k : h) w.write(k + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
        load(c);
    }

    /** a JSON string (also safe inside JavaScript) */
    static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == '<') {
                        b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}
