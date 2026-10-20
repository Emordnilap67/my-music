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

    /** what the sheet is told while the check runs */
    interface Progress {
        void say(String line);
    }

    private static void say(Progress p, String line) {
        if (p != null) p.say(line);
    }

    /** a playlist read: id -> {title, channel, seconds}, the count the page states, the last error */
    static final class Read {
        final Map<String, String[]> got = new LinkedHashMap<>();
        int said = -1;
        String err = "";

        boolean held() {
            return got.isEmpty() && DownloadService.held(err);
        }
    }

    /** yt-dlp's lines from a playlist read (plain Java, for the tests) */
    static void parse(BufferedReader r, Read into, Progress p, String what) throws Exception {
        String line;
        int n0 = into.got.size();
        while ((line = r.readLine()) != null) {
            if (line.startsWith("MMID\t")) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5 && ID.matcher(f[1].trim()).matches()) {
                    into.got.put(f[1].trim(), new String[]{na(f[2]), na(f[4]).replaceAll("\\s*-\\s*Topic$", ""), na(f[3])});
                    int n = into.got.size();
                    if (n != n0 && n % 100 == 0) say(p, what + " - " + String.format(Locale.US, "%,d", n) + " songs so far");
                }
            } else if (line.startsWith("MMCOUNT\t")) {
                try {
                    into.said = Integer.parseInt(line.substring(8).trim());
                } catch (Exception ignored) {
                    // "NA": the page did not say
                }
            } else if (line.startsWith("ERROR")) {
                into.err = line;
            }
        }
    }

    /**
     * every video in a YouTube playlist (yt-dlp, no downloading). a read
     * short of the count the page states is read again, up to 3 times, and
     * what every read found is kept - YouTube sometimes sends a short list.
     */
    static Read listing(Context c, String link, Progress p) throws Exception {
        if (!Ytdl.available(c)) throw new Exception("The downloader is missing from this install - get the APK from the Releases page");
        Ytdl.init(c);
        Read all = new Read();
        for (int round = 1; round <= 3; round++) {
            say(p, round == 1 ? "Reading the YouTube playlist" : "Reading the YouTube playlist again to be sure (" + round + " of 3)");
            List<String> a = new ArrayList<>(Arrays.asList("--flat-playlist", "--no-warnings",
                    "--print", "MMID\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s",
                    "--print", "playlist:MMCOUNT\t%(playlist_count)s",
                    "--extractor-retries", "10", "--retry-sleep", "extractor:exp=2:30",
                    link.trim().replace("music.youtube.com", "www.youtube.com")));
            Process pr = Ytdl.start(c, a);
            Read one = new Read();
            parse(new BufferedReader(new InputStreamReader(pr.getInputStream(), "UTF-8")), one, p, "Reading the YouTube playlist");
            pr.waitFor();
            all.got.putAll(one.got);
            if (one.said > 0) all.said = one.said;
            if (!one.err.isEmpty()) all.err = one.err;
            if (one.held()) break;                                  // YouTube is pausing the phone: no point asking again now
            if (one.got.isEmpty() && round == 1) break;             // not a playlist it can read at all
            if (all.said <= 0 || all.got.size() >= all.said) break; // the whole list (or the page did not say)
            if (round < 3) Thread.sleep(15000);
        }
        if (!all.got.isEmpty()) Lengths.putAll(c, all.got);
        return all;
    }

    /**
     * the length of each video that is not in the playlist, one by one
     * (at most max this run). Returns true if YouTube paused the phone.
     */
    static boolean lookup(Context c, List<String> ids, int max, Progress p) throws Exception {
        if (ids.isEmpty() || max <= 0) return false;
        Ytdl.init(c);
        List<String> want = ids.size() > max ? ids.subList(0, max) : ids;
        List<String> a = new ArrayList<>(Arrays.asList("--skip-download", "--no-playlist", "--no-warnings",
                "--ignore-no-formats-error", "--ignore-errors", "--sleep-requests", "1", "--extractor-retries", "3",
                "--print", "MMID\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s"));
        for (String id : want) a.add("https://www.youtube.com/watch?v=" + id);
        Process pr = Ytdl.start(c, a);
        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), "UTF-8"));
        Map<String, String[]> got = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        boolean held = false;
        int done = 0;
        String line;
        try {
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MMID\t")) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 5 && ID.matcher(f[1].trim()).matches()) {
                        got.put(f[1].trim(), new String[]{na(f[2]), na(f[4]).replaceAll("\\s*-\\s*Topic$", ""), na(f[3])});
                        done++;
                        say(p, "Asking YouTube about songs that are not in the playlist - " + done + " of " + want.size());
                    }
                } else if (line.startsWith("ERROR")) {
                    if (DownloadService.held(line)) {
                        held = true;
                        pr.destroy();                               // the rest waits for the background check
                        break;
                    }
                    String id = errId(line);
                    if (id != null && gone(line)) {
                        Lengths.gone(c, id, now);
                        done++;
                    }
                }
            }
        } finally {
            try {
                pr.waitFor();
            } catch (Exception ignored) {
            }
            Lengths.putAll(c, got);
            Lengths.save(c);
        }
        return held;
    }

    private static final Pattern ERR_ID = Pattern.compile("\\[youtube[^\\]]*\\]\\s*([A-Za-z0-9_-]{11})\\s*:");

    /** the video an ERROR line is about, or null */
    static String errId(String line) {
        java.util.regex.Matcher m = ERR_ID.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** the video itself is gone (not YouTube being busy) */
    static boolean gone(String line) {
        if (DownloadService.held(line)) return false;
        String e = line.toLowerCase(Locale.ROOT);
        return e.contains("private") || e.contains("unavailable") || e.contains("removed") || e.contains("terminated")
                || e.contains("does not exist") || e.contains("copyright");
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

    /** songs looked up one by one per run: by hand / in the background */
    static final int SINGLE_HAND = 40, SINGLE_AUTO = 25;
    /** with no playlist link and more unknown songs than this, the link is asked for (one read beats hundreds of look-ups) */
    static final int ASK_LINK_OVER = 60;

    /** check one playlist by hand (shows its progress) */
    static String check(Context c, String folder, String link) {
        return check(c, folder, link, null, SINGLE_HAND);
    }

    /**
     * check one playlist. lengths already known are used as they are;
     * the YouTube playlist is read only when some of its songs are new to
     * the app, and songs that are not in it are looked up one by one (at
     * most single this run). The result says how many are left for later.
     */
    static String check(Context c, String folder, String link, Progress p, int single) {
        try {
            boolean given = link != null && !link.trim().isEmpty();
            if (!given) link = linkFor(c, folder);
            String listId = link.isEmpty() ? null : DownloadService.listId(link.trim());
            if (given && listId == null)
                return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true,\"error\":\"That is not a YouTube playlist link\"}";
            say(p, "Reading the songs' own tags");
            List<Song> songs = new ArrayList<>();
            Library lib = Library.get(c);
            for (Library.Pl pl : lib.playlists) {
                if (!pl.name.equalsIgnoreCase(folder)) continue;
                for (Library.Song s : pl.songs)
                    songs.add(new Song(s.key, s.file, Ids.of(c, s.path), s.title, s.artist, s.durMs / 1000.0));
            }
            Ids.save(c, null);
            long now = System.currentTimeMillis();
            List<String> need = unknownIds(c, songs, now);
            boolean held = false, readIt = false;
            int inList = -1;
            // 1. the playlist, in one read: when some songs are new to the app, or a link was just given
            if (listId != null && (given || !need.isEmpty())) {
                Read rd = listing(c, link, p);
                readIt = true;
                if (rd.got.isEmpty()) {
                    if (rd.held()) held = true;
                    else if (given) return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true,\"error\":"
                            + Library.q(rd.err.isEmpty() ? "Could not read that playlist" : rd.err.replaceFirst("^ERROR:\\s*", "")) + "}";
                } else {
                    inList = 0;
                    int withIds = 0;
                    for (Song s : songs) {
                        if (s.id.isEmpty()) continue;
                        withIds++;
                        if (rd.got.containsKey(s.id)) inList++;
                    }
                    // only remember the link when it really is this playlist (a good share of its songs are in it)
                    if (withIds > 0 && inList * 10 < withIds) {
                        if (given) return "{\"folder\":" + Library.q(folder) + ",\"needLink\":true,\"error\":"
                                + Library.q("Only " + inList + " of this playlist's songs are in that YouTube playlist - is it the right link?") + "}";
                    } else {
                        c.getSharedPreferences("lists", Context.MODE_PRIVATE).edit().putString(listId, folder).apply();
                    }
                }
                Lengths.save(c);
                need = unknownIds(c, songs, now);
            }
            // 2. no link and many songs new to the app: ask for the link (one read beats hundreds of look-ups)
            boolean askLink = listId == null && need.size() > ASK_LINK_OVER;
            // 3. the songs that are not in the playlist, one by one - a few this run, the rest later
            if (!held && !askLink && !need.isEmpty()) {
                if (!Ytdl.available(c)) throw new Exception("The downloader is missing from this install - get the APK from the Releases page");
                held = lookup(c, need, single, p);
                need = unknownIds(c, songs, now);
            }
            say(p, "Checking each song's length");
            Map<String, String[]> known = Lengths.known(c);
            int[] checked = new int[1];
            List<String[]> bad = find(songs, known, checked);
            int noId = 0, gone = 0;
            for (Song s : songs) {
                if (s.id.isEmpty()) noId++;
                else if (!known.containsKey(s.id) && !Lengths.unknown(c, s.id, now)) gone++;
            }
            JSONArray arr = new JSONArray();
            for (String[] b : bad) {
                arr.put(new JSONObject().put("key", b[0]).put("file", b[1]).put("shown", b[2]).put("tagId", b[3])
                        .put("realId", b[4]).put("real", b[5]).put("expected", Long.parseLong(b[6])).put("actual", Long.parseLong(b[7])));
            }
            JSONObject all = readAll(c);
            JSONObject mine = new JSONObject().put("at", now).put("bad", arr).put("left", need.size());
            // what YouTube calls each song, for the fix
            JSONObject names = new JSONObject();
            for (String[] b : bad) {
                for (String id : new String[]{b[3], b[4]}) {
                    String[] e = known.get(id);
                    if (e != null) names.put(id, new JSONArray().put(e[0]).put(e[1]).put(e[2]));
                }
            }
            mine.put("names", names);
            all.put(folder, mine);
            Backup.write(store(c), all.toString().getBytes("UTF-8"));
            JSONObject out = new JSONObject().put("folder", folder).put("songs", songs.size()).put("checked", checked[0])
                    .put("bad", arr).put("left", need.size()).put("noId", noId).put("gone", gone);
            if (held) out.put("held", true);
            if (askLink) out.put("needLink", true).put("many", need.size());
            if (readIt && inList >= 0) out.put("inList", inList);
            return out.toString();
        } catch (Exception e) {
            return "{\"folder\":" + Library.q(folder) + ",\"error\":" + Library.q(e.getMessage() == null ? e.toString() : e.getMessage()) + "}";
        }
    }

    /** the songs' videos the app does not know the length of yet (each once) */
    private static List<String> unknownIds(Context c, List<Song> songs, long now) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Song s : songs) if (!s.id.isEmpty() && Lengths.unknown(c, s.id, now)) seen.add(s.id);
        return new ArrayList<>(seen);
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
            return "Fixing " + n + (n == 1 ? " song" : " songs") + ": the wrong files went to your music folder / " + AWAY
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

    private static final java.util.concurrent.ExecutorService AUTO = java.util.concurrent.Executors.newSingleThreadExecutor(Low.THREADS);
    private static volatile boolean autoRunning;
    private static final long AUTO_GAP_MS = 6L * 3600 * 1000, RECHECK_MS = 7L * 24 * 3600 * 1000;

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
                    for (Library.Pl pl : Library.get(a).playlists) folders.add(pl.name);
                    for (String folder : folders) {
                        String k = "ok:" + folder.toLowerCase(Locale.ROOT);
                        if (System.currentTimeMillis() - p.getLong(k, 0) < RECHECK_MS) continue;
                        JSONObject r = new JSONObject(check(a, folder, "", null, SINGLE_AUTO));
                        if (r.has("error")) continue;                         // next time
                        JSONArray bad = r.optJSONArray("bad");
                        if (bad != null && bad.length() > 0) {
                            String msg = fix(a, folder);
                            MainActivity.tell(msg);
                            io.github.emordnilap67.mymusic.Progress.announce(a, msg, null);
                        }
                        // all of it known now (or nothing more to learn without a link): rest a week
                        if (r.optInt("left") == 0 || r.optBoolean("needLink")) p.edit().putLong(k, System.currentTimeMillis()).apply();
                        if (r.optBoolean("held")) break;                      // YouTube is pausing the phone: the rest next time
                    }
                } catch (Exception ignored) {
                } finally {
                    autoRunning = false;
                }
            }
        });
    }

    static String replace(Context c, Library.Song s, String newId, String t, String a, String secs) {
        try {
            Uri tree = Importer.tree(c);
            if (tree == null) return "Pick your music folder first (Music folder, on the home screen)";
            ContentResolver r = c.getContentResolver();
            String fid = Importer.folderId(c, tree, s.pl);
            if (fid == null) return "Could not open " + s.pl;
            Map<String, String> files = Importer.names(c, tree, fid);
            String doc = files.get(s.file.toLowerCase(Locale.ROOT));
            if (doc == null) return "Could not find " + s.file + " in " + s.pl;
            String keepAs = KEPT + s.file;
            for (int n = 2; files.containsKey(keepAs.toLowerCase(Locale.ROOT)); n++) keepAs = KEPT + n + " - " + s.file;
            if (DocumentsContract.renameDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, doc), keepAs) == null)
                return "Could not move the wrong file - nothing changed";
            String root = Library.get(c).root + "/" + s.pl + "/";
            List<String[]> fetch = new ArrayList<>();
            fetch.add(new String[]{newId, t == null || t.isEmpty() ? s.title : t, a == null ? "" : a, secs == null ? "0" : secs, s.file});
            Backup.addRestoreItems(c, s.pl, fetch);
            Importer.scan(c, Arrays.asList(root + s.file, root + keepAs));
            Library.hideAll(c, java.util.Collections.singletonList(s.pl + "/" + keepAs));
            DownloadService.start(c, Backup.IDS + s.pl, s.pl);
            return "Getting the right audio for " + s.title + " - the wrong file is kept under Hidden songs";
        } catch (Exception e) {
            return "Could not fix it: " + e.getMessage();
        }
    }

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
