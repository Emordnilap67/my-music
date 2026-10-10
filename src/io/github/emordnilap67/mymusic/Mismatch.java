package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * "Wrong audio" check. A downloader that saves songs by title only (yt-dlp
 * with -o "%(title)s") goes wrong when two songs in a playlist have the
 * same title ("Free" by Third Party, "Free" by La Cream): it finds
 * "Free.mp3" already there, skips the download and still writes the
 * second song's name, artist, picture and link into the first song's
 * file. The app shows one song and plays the other.
 *
 * The check: every file's tags name a YouTube video; the YouTube playlist
 * says how long that video is; the file's own audio says how long it
 * really is. More than 3 seconds apart = the wrong audio. Usually the
 * audio is the other song with the same title - found by its length.
 * The fix moves those files out of the playlist (.mismatched in the music folder -
 * nothing is deleted) and downloads both songs again, each into its own
 * correctly named file.
 */
final class Mismatch {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    static final String AWAY = ".mismatched";
    /** a wrong file kept in its playlist (its real song unknown), hidden, under this name */
    static final String KEPT = "Wrong audio - ";

    private Mismatch() {}

    /** the YouTube playlist link this folder came from, or "" */
    static String linkFor(Context c, String folder) {
        Map<String, ?> all = c.getSharedPreferences("lists", Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() instanceof String && ((String) e.getValue()).equalsIgnoreCase(folder))
                return "https://www.youtube.com/playlist?list=" + e.getKey();
        }
        return "";
    }

    /** every video in a YouTube playlist: id -> {title, channel, seconds} (yt-dlp, no downloading) */
    static Map<String, String[]> listing(Context c, String link) throws Exception {
        if (!Ytdl.available(c)) throw new Exception("The downloader is missing from this install - get the APK from the Releases page");
        Ytdl.init(c);
        List<String> a = new ArrayList<>(Arrays.asList("--flat-playlist", "--no-warnings",
                "--print", "MMID\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s",
                "--extractor-retries", "10", "--retry-sleep", "extractor:exp=2:30",
                link.trim().replace("music.youtube.com", "www.youtube.com")));
        Process p = Ytdl.start(c, a);
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        Map<String, String[]> out = new LinkedHashMap<>();
        String line, err = "";
        while ((line = r.readLine()) != null) {
            if (line.startsWith("MMID\t")) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5 && ID.matcher(f[1].trim()).matches())
                    out.put(f[1].trim(), new String[]{na(f[2]), na(f[4]).replaceAll("\\s*-\\s*Topic$", ""), na(f[3])});
            } else if (line.startsWith("ERROR")) {
                err = line;
            }
        }
        p.waitFor();
        if (out.isEmpty()) {
            if (DownloadService.held(err)) throw new Exception("YouTube is pausing this phone right now - try again in an hour");
            throw new Exception(err.isEmpty() ? "Could not read that playlist" : err.replaceFirst("^ERROR:\\s*", ""));
        }
        return out;
    }

    private static String na(String s) {
        s = s == null ? "" : s.trim();
        return "NA".equals(s) ? "" : s;
    }

    private static double secs(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** a file: its key, name, the YouTube id in its tags and how long its audio really is */
    static final class Song {
        final String key, file, id, title, artist;
        final double seconds;

        Song(String key, String file, String id, String title, String artist, double seconds) {
            this.key = key;
            this.file = file;
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.seconds = seconds;
        }
    }

    /**
     * the wrong ones: {key, file, shown, tagId, realId ("" = unknown), real (its name), expected, actual}.
     * checked[0] = how many could be checked (their video is in the playlist).
     */
    static List<String[]> find(List<Song> songs, Map<String, String[]> listing, int[] checked) {
        List<String[]> out = new ArrayList<>();
        int n = 0;
        for (Song s : songs) {
            String[] e = s.id.isEmpty() ? null : listing.get(s.id);
            if (e == null) continue;
            double exp = secs(e[2]);
            if (exp <= 0 || s.seconds <= 0) continue;
            n++;
            if (Math.abs(s.seconds - exp) <= 3) continue;
            // whose audio is it? the other video with the same title and this length
            String realId = "", real = "";
            String k = LyricsQuery.key(e[0]);
            for (Map.Entry<String, String[]> o : listing.entrySet()) {
                if (o.getKey().equals(s.id)) continue;
                String[] v = o.getValue();
                if (Math.abs(secs(v[2]) - s.seconds) > 2.5) continue;
                if (!LyricsQuery.key(v[0]).equals(k)) continue;
                realId = o.getKey();
                real = v[0] + (v[1].isEmpty() ? "" : " - " + v[1]);
                break;
            }
            out.add(new String[]{s.key, s.file, s.title + (s.artist.isEmpty() ? "" : " - " + s.artist), s.id, realId, real,
                    String.valueOf(Math.round(exp)), String.valueOf(Math.round(s.seconds))});
        }
        checked[0] = n;
        return out;
    }

    // ------------------------------------------------------------------ on the phone

    private static File store(Context c) {
        return new File(c.getFilesDir(), "mismatch.json");
    }

    /** check one playlist (slow: reads the YouTube playlist and each file's tags once) */
    static String check(Context c, String folder, String link) {
        try {
            if (link == null || link.trim().isEmpty()) link = linkFor(c, folder);
            if (link.isEmpty()) return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true}";
            String listId = DownloadService.listId(link.trim());
            if (listId == null) return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true,\"error\":\"That is not a YouTube playlist link\"}";
            Map<String, String[]> listing = listing(c, link);
            List<Song> songs = new ArrayList<>();
            Library lib = Library.get(c);
            for (Library.Pl p : lib.playlists) {
                if (!p.name.equalsIgnoreCase(folder)) continue;
                for (Library.Song s : p.songs)
                    songs.add(new Song(s.key, s.file, Ids.of(c, s.path), s.title, s.artist, s.durMs / 1000.0));
            }
            Ids.save(c, null);
            int[] checked = new int[1];
            List<String[]> bad = find(songs, listing, checked);
            int withIds = 0;
            for (Song s : songs) if (!s.id.isEmpty()) withIds++;
            // only remember the link when it really is this playlist (a good share of its songs are in it)
            if (withIds > 0 && checked[0] * 10 < withIds)
                return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true,\"error\":"
                        + Library.q("Only " + checked[0] + " of this playlist's songs are in that YouTube playlist - is it the right link?") + "}";
            c.getSharedPreferences("lists", Context.MODE_PRIVATE).edit().putString(listId, folder).apply();
            JSONArray arr = new JSONArray();
            for (String[] b : bad) {
                arr.put(new JSONObject().put("key", b[0]).put("file", b[1]).put("shown", b[2]).put("tagId", b[3])
                        .put("realId", b[4]).put("real", b[5]).put("expected", Long.parseLong(b[6])).put("actual", Long.parseLong(b[7])));
            }
            JSONObject all = readAll(c);
            JSONObject mine = new JSONObject().put("at", System.currentTimeMillis()).put("bad", arr);
            // what YouTube calls each song, for the fix
            JSONObject names = new JSONObject();
            for (String[] b : bad) {
                for (String id : new String[]{b[3], b[4]}) {
                    String[] e = listing.get(id);
                    if (e != null) names.put(id, new JSONArray().put(e[0]).put(e[1]).put(e[2]));
                }
            }
            mine.put("names", names);
            all.put(folder, mine);
            Backup.write(store(c), all.toString().getBytes("UTF-8"));
            return new JSONObject().put("folder", folder).put("songs", songs.size()).put("checked", checked[0]).put("bad", arr).toString();
        } catch (Exception e) {
            return "{\"folder\":" + Library.q(folder) + ",\"error\":" + Library.q(e.getMessage() == null ? e.toString() : e.getMessage()) + "}";
        }
    }

    private static JSONObject readAll(Context c) {
        try {
            File f = store(c);
            return f.isFile() ? new JSONObject(new String(Backup.read(f), "UTF-8")) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** move the wrong files out of the playlist and fetch both songs again; returns a line for the page */
    static String fix(Context c, String folder) {
        try {
            JSONObject mine = readAll(c).optJSONObject(folder);
            JSONArray bad = mine == null ? null : mine.optJSONArray("bad");
            if (bad == null || bad.length() == 0) return "Nothing to fix in " + folder;
            JSONObject names = mine.optJSONObject("names");
            Uri tree = Importer.tree(c);
            if (tree == null) return "Pick your music folder first (Music folder, on the home screen)";
            ContentResolver r = c.getContentResolver();
            String fid = Importer.folderId(c, tree, folder);
            String awayId = Importer.folderId(c, tree, AWAY);
            String target = awayId == null ? null : childDir(r, tree, awayId, folder);
            Map<String, String> files = Importer.names(c, tree, fid);
            List<String> moved = new ArrayList<>(), hide = new ArrayList<>();
            List<String[]> fetch = new ArrayList<>();
            java.util.Set<String> queued = new java.util.HashSet<>();
            String root = Library.get(c).root + "/" + folder + "/";
            // what to fetch goes on the list before any file moves, so nothing is ever only half done
            List<JSONObject> todo = new ArrayList<>();
            for (int i = 0; i < bad.length(); i++) {
                JSONObject b = bad.getJSONObject(i);
                if (files.get(b.optString("file").toLowerCase(Locale.ROOT)) == null) continue;
                todo.add(b);
                for (String id : new String[]{b.optString("tagId"), b.optString("realId")}) {
                    if (id.isEmpty() || !queued.add(id)) continue;
                    JSONArray n = names == null ? null : names.optJSONArray(id);
                    String t = n == null ? "Song" : n.optString(0), a = n == null ? "" : n.optString(1);
                    fetch.add(new String[]{id, t, a, n == null ? "0" : n.optString(2), (a.isEmpty() ? t : a + " - " + t) + ".mp3"});
                }
            }
            if (todo.isEmpty()) return "Those files are not in " + folder + " any more - nothing changed";
            Backup.addRestoreItems(c, folder, fetch);
            for (JSONObject b : todo) {
                String file = b.optString("file");
                String doc = files.get(file.toLowerCase(Locale.ROOT));
                if (b.optString("realId").isEmpty()) {
                    // nobody knows whose audio this is - it may be the only copy of that song: keep it,
                    // renamed (its name is free for the right song) and hidden (Hidden songs, Show again)
                    String keepAs = KEPT + file;
                    try {
                        if (DocumentsContract.renameDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, doc), keepAs) != null) {
                            moved.add(root + file);
                            moved.add(root + keepAs);
                            hide.add(folder + "/" + keepAs);
                        }
                    } catch (Exception ignored) {
                    }
                } else if (moveAway(r, tree, fid, doc, target, file)) {
                    moved.add(root + file);          // its real song is downloaded again too
                }
            }
            if (moved.isEmpty()) return "Could not move the wrong files - nothing changed";
            Importer.scan(c, moved);                      // they leave (or change name in) the library
            if (!hide.isEmpty()) Library.hideAll(c, hide);
            else Library.load(c);
            JSONObject all = readAll(c);
            all.remove(folder);
            Backup.write(store(c), all.toString().getBytes("UTF-8"));
            DownloadService.start(c, Backup.IDS + folder, folder);
            int n = todo.size();
            return "Fixing " + n + (n == 1 ? " song" : " songs") + ": the wrong files went to " + AWAY + " in your music folder"
                    + (hide.isEmpty() ? "" : " (" + hide.size() + " whose real song is unknown stay, under Hidden songs)")
                    + " - nothing deleted. Downloading " + fetch.size() + (fetch.size() == 1 ? " song" : " songs")
                    + " again, each with its own audio";
        } catch (Exception e) {
            return "Fix failed: " + e.getMessage();
        }
    }

    /** into .mismatched/<playlist>; if moving is not possible, a dot in front of the name hides it in place */
    private static boolean moveAway(ContentResolver r, Uri tree, String fromDir, String doc, String targetDir, String file) {
        Uri src = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
        if (targetDir != null) {
            try {
                Uri out = DocumentsContract.moveDocument(r, src, DocumentsContract.buildDocumentUriUsingTree(tree, fromDir),
                        DocumentsContract.buildDocumentUriUsingTree(tree, targetDir));
                if (out != null) return true;
            } catch (Exception ignored) {
            }
        }
        try {
            return DocumentsContract.renameDocument(r, src, ".wrong audio - " + file) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String childDir(ContentResolver r, Uri tree, String parent, String name) {
        Cursor q = null;
        try {
            q = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (q != null && q.moveToNext()) {
                if (name.equalsIgnoreCase(q.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2)))
                    return q.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (q != null) q.close();
        }
        try {
            Uri made = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            return made == null ? null : DocumentsContract.getDocumentId(made);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ by itself

    private static final java.util.concurrent.ExecutorService AUTO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile boolean autoRunning;
    private static final long AUTO_GAP_MS = 6L * 3600 * 1000, RECHECK_MS = 7L * 24 * 3600 * 1000;

    /**
     * nobody has to tap anything. When the app opens, each
     * playlist with a known YouTube link is checked (at most once a week,
     * looked at every 6 hours, in the background) and any song playing the
     * wrong audio is fixed straight away - only those songs are fetched again.
     */
    static void auto(Context c) {
        final Context a = c.getApplicationContext();
        if (autoRunning || Importer.tree(a) == null || !Ytdl.available(a) || Library.get(a).playlists.isEmpty()) return;
        final android.content.SharedPreferences p = a.getSharedPreferences("mismatch", Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (now - p.getLong("tried", 0) < AUTO_GAP_MS) return;
        p.edit().putLong("tried", now).apply();
        autoRunning = true;
        AUTO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    java.util.Set<String> folders = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    for (Object v : a.getSharedPreferences("lists", Context.MODE_PRIVATE).getAll().values())
                        if (v instanceof String) folders.add((String) v);
                    java.util.Set<String> here = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    for (Library.Pl pl : Library.get(a).playlists) here.add(pl.name);
                    for (String folder : folders) {
                        if (!here.contains(folder)) continue;
                        String k = "ok:" + folder.toLowerCase(Locale.ROOT);
                        if (System.currentTimeMillis() - p.getLong(k, 0) < RECHECK_MS) continue;
                        JSONObject r = new JSONObject(check(a, folder, ""));
                        if (r.has("error") || r.optBoolean("needLink")) continue;      // YouTube busy / no link: next time
                        JSONArray bad = r.optJSONArray("bad");
                        if (bad != null && bad.length() > 0) {
                            String msg = fix(a, folder);
                            MainActivity.tell(msg);
                            Progress.announce(a, msg, null);
                        }
                        p.edit().putLong(k, System.currentTimeMillis()).apply();
                    }
                } catch (Exception ignored) {
                } finally {
                    autoRunning = false;
                }
            }
        });
    }

    /** the last check's wrong songs, for the page: {"Chill": {at, bad: [...]}} */
    static String json(Context c) {
        JSONObject all = readAll(c);
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            JSONObject o = all.optJSONObject(it.next());
            if (o != null) o.remove("names");
        }
        return all.toString();
    }
}
