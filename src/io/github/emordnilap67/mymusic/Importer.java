package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The music folder and writing into it. Each folder inside the music
 * folder is a playlist. Android lets an app write there only after you
 * point it at the folder once (the folder picker, "Use this folder" >
 * "Allow"); that access is kept.
 */
final class Importer {
    static final String AUTH = "com.android.externalstorage.documents";
    private static final String PREFS = "folder";

    private Importer() {}

    /** the music folder's document id ("primary:Music/Carmen Musicum"), "" until one is picked */
    static String rootDoc(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("doc", "");
    }

    /** where that is on the phone: "/storage/emulated/0/Music/Carmen Musicum" */
    static String pathOf(String doc) {
        int k = doc.indexOf(':');
        if (k < 0) return null;
        String vol = doc.substring(0, k), rel = doc.substring(k + 1);
        String base = "primary".equals(vol) ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    /** a short name for the music folder, for the screen ("Music/Carmen Musicum") */
    static String label(Context c) {
        String d = rootDoc(c);
        int k = d.indexOf(':');
        return k < 0 ? "" : d.substring(k + 1);
    }

    /** the kept access to the music folder, or null */
    static Uri tree(Context c) {
        String want = rootDoc(c);
        if (want.isEmpty()) return null;
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            Uri u = p.getUri();
            try {
                if (p.isWritePermission() && want.equals(DocumentsContract.getTreeDocumentId(u))) return u;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** the folder just picked becomes the music folder (not the whole phone or a whole card) */
    static boolean choose(Context c, Uri t) {
        String doc;
        try {
            doc = DocumentsContract.getTreeDocumentId(t);
        } catch (Exception e) {
            return false;
        }
        if (doc == null || doc.endsWith(":") || !AUTH.equals(t.getAuthority()) || pathOf(doc) == null) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("doc", doc).apply();
        // let go of any older folder
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            try {
                if (!doc.equals(DocumentsContract.getTreeDocumentId(p.getUri())))
                    c.getContentResolver().releasePersistableUriPermission(p.getUri(),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    /** where the folder picker opens: the music folder, or the phone's Music folder */
    static Uri initialUri(Context c) {
        String d = rootDoc(c);
        return DocumentsContract.buildDocumentUri(AUTH, d.isEmpty() ? "primary:Music" : d);
    }

    private static String name(ContentResolver r, Uri u) {
        Cursor c = null;
        try {
            c = r.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = u.getLastPathSegment();
        return last == null ? "song.mp3" : last.substring(last.lastIndexOf('/') + 1);
    }

    /** children of a folder: display name -> document id */
    private static List<String[]> children(ContentResolver r, Uri tree, String docId) {
        List<String[]> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (c != null && c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** the playlist folder's document id inside the kept tree (made if missing), or null */
    static String folderId(Context c, Uri tree, String folder) {
        ContentResolver r = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        for (String[] ch : children(r, tree, rootId)) {
            if (folder.equalsIgnoreCase(ch[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) return ch[0];
        }
        try {
            Uri made = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                    DocumentsContract.Document.MIME_TYPE_DIR, folder);
            return made == null ? null : DocumentsContract.getDocumentId(made);
        } catch (Exception e) {
            return null;
        }
    }

    /** the playlist folders in the music folder: {document id, name} */
    static List<String[]> folders(Context c, Uri tree) {
        List<String[]> out = new ArrayList<>();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            for (String[] ch : children(c.getContentResolver(), tree, rootId)) {
                if (ch[1] != null && !ch[1].startsWith(".") && !ch[1].equalsIgnoreCase(Backup.DIR) && !ch[1].equalsIgnoreCase(Backup.OLD_DIR)
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2]))
                    out.add(new String[]{ch[0], ch[1]});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** file names already in the folder (lower case) -> document id */
    static java.util.Map<String, String> names(Context c, Uri tree, String folderId) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String[] ch : children(c.getContentResolver(), tree, folderId)) {
            if (ch[1] != null) out.put(ch[1].toLowerCase(), ch[0]);
        }
        return out;
    }

    /** the yt-dlp archive (".playlist_archive.txt"): the video ids already done in this folder */
    static Set<String> readArchive(Context c, Uri tree, String folderId) {
        Set<String> ids = new HashSet<>();
        String doc = names(c, tree, folderId).get(".playlist_archive.txt");
        if (doc == null) return ids;
        try {
            InputStream in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc));
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) ids.add(p[1]);
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    /** add these ids to the folder's download history, so they are not fetched again */
    static void appendArchive(Context c, Uri tree, String folderId, List<String> ids) {
        if (ids.isEmpty()) return;
        ContentResolver r = c.getContentResolver();
        try {
            String doc = names(c, tree, folderId).get(".playlist_archive.txt");
            StringBuilder add = new StringBuilder();
            Uri target;
            if (doc != null) {
                target = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
                if (!endsWithNewline(r, target)) add.append('\n');
            } else {
                target = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, folderId),
                        "text/plain", ".playlist_archive.txt");
            }
            if (target == null) return;
            for (String id : ids) add.append("youtube ").append(id).append('\n');
            // append only (never rewrite): safe if anything stops halfway
            OutputStream o = r.openOutputStream(target, "wa");
            try {
                o.write(add.toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean endsWithNewline(ContentResolver r, Uri u) {
        try {
            InputStream in = r.openInputStream(u);
            int last = -1;
            byte[] buf = new byte[65536];
            int n;
            try {
                while ((n = in.read(buf)) > 0) last = buf[n - 1];
            } finally {
                in.close();
            }
            return last == -1 || last == '\n';
        } catch (Exception e) {
            return true;
        }
    }

    /** copy a finished download into the playlist folder; returns the real path, or null */
    static String put(Context c, Uri tree, String folderId, java.io.File src, String name) {
        try {
            return write(c.getContentResolver(), tree, folderId, new java.io.FileInputStream(src), name, "audio/mpeg");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * one song into the folder: made as a new file, filled, closed. Returns
     * the real path, or null - and then nothing half-copied is left behind.
     * Always closes in.
     */
    private static String write(ContentResolver r, Uri tree, String folderId, InputStream in, String name, String type) {
        Uri dst = null;
        try {
            dst = DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), type, name);
            if (dst == null) return null;
            OutputStream o = r.openOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            } finally {
                o.close();
            }
            // "primary:Music/Carmen Musicum/Chill/Artist - Song.mp3" -> the real path
            String id = DocumentsContract.getDocumentId(dst);
            int colon = id.indexOf(':');
            return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + id.substring(colon + 1);
        } catch (Exception e) {
            if (dst != null) {
                try {
                    DocumentsContract.deleteDocument(r, dst);
                } catch (Exception ignored) {
                }
            }
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Android swaps these for "_" when it makes a file: check both spellings for "already there" */
    private static boolean has(java.util.Map<String, String> names, String n) {
        String l = n.toLowerCase();
        return names.containsKey(l) || names.containsKey(l.replaceAll("[:?*\"<>|\\\\]", "_"));
    }

    /** let Android (and so the library) see new files, waiting up to a minute */
    static void scan(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        final CountDownLatch done = new CountDownLatch(paths.size());
        MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        done.countDown();
                    }
                });
        try {
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** copy the picked songs into a playlist folder; returns a short line */
    static String copy(Context c, Uri tree, List<Uri> picked, String folder) {
        ContentResolver r = c.getContentResolver();
        String folderId = folderId(c, tree, folder);
        if (folderId == null) return "Could not make the playlist " + folder;
        java.util.Map<String, String> have = names(c, tree, folderId);
        List<String> paths = new ArrayList<>();
        int added = 0, skipped = 0, failed = 0;
        for (Uri src : picked) {
            String n = name(r, src);
            if (has(have, n)) {
                skipped++;
                continue;
            }
            String type = r.getType(src);
            if (type == null || !type.startsWith("audio/")) type = "audio/mpeg";
            String path = null;
            try {
                InputStream in = r.openInputStream(src);
                if (in != null) path = write(r, tree, folderId, in, n, type);
            } catch (Exception ignored) {
            }
            if (path == null) {
                failed++;
                continue;
            }
            have.put(n.toLowerCase(), "");
            paths.add(path);
            added++;
        }
        scan(c, paths);
        Library.load(c);
        String msg = "Added " + added + (added == 1 ? " song to " : " songs to ") + folder;
        if (skipped > 0) msg += ", " + skipped + " already there";
        if (failed > 0) msg += ", " + failed + " could not be copied";
        return msg;
    }

    /** "Add to playlist": copy one song from the library into other playlist folders */
    static String addSong(Context c, Uri tree, Library.Song s, List<String> folders) {
        List<String> added = new ArrayList<>(), already = new ArrayList<>(), failed = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (String folder : folders) {
            String fid = folderId(c, tree, folder);
            if (fid == null) {
                failed.add(folder);
                continue;
            }
            if (has(names(c, tree, fid), s.file)) {
                already.add(folder);
                continue;
            }
            String path = null;
            try {
                path = write(c.getContentResolver(), tree, fid, new java.io.FileInputStream(s.path), s.file,
                        s.file.toLowerCase().endsWith(".mp3") ? "audio/mpeg" : "audio/*");
            } catch (Exception ignored) {
            }
            if (path == null) failed.add(folder);
            else {
                paths.add(path);
                added.add(folder);
            }
        }
        scan(c, paths);
        if (!added.isEmpty()) Library.load(c);
        StringBuilder m = new StringBuilder();
        if (!added.isEmpty()) m.append("Added ").append(s.title).append(" to ").append(join(added));
        if (!already.isEmpty()) m.append(m.length() > 0 ? ". " : "").append("Already in ").append(join(already));
        if (!failed.isEmpty()) m.append(m.length() > 0 ? ". " : "").append("Could not copy it to ").append(join(failed));
        return m.toString();
    }


    /** a playlist folder's document id, only if it is there (never made) */
    private static String findFolder(Context c, Uri tree, String folder) {
        for (String[] ch : children(c.getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree))) {
            if (folder.equalsIgnoreCase(ch[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) return ch[0];
        }
        return null;
    }

    /**
     * The song's own file goes into the other playlist (Android moves it -
     * nothing is copied, nothing asks), and its plays and heart go with it. Returns {message, new key or null}.
     */
    static String[] moveSong(Context c, Uri tree, Library.Song s, String folder) {
        ContentResolver r = c.getContentResolver();
        if (folder.equalsIgnoreCase(s.pl)) return new String[]{"It is already in " + folder, null};
        String from = findFolder(c, tree, s.pl), to = folderId(c, tree, folder);
        String doc = from == null ? null : names(c, tree, from).get(s.file.toLowerCase());
        if (to == null || doc == null) return new String[]{"Could not move " + s.title, null};
        String oldKey = s.pl + "/" + s.file, newKey = folder + "/" + s.file;
        List<String> paths = new ArrayList<>();
        paths.add(s.path);
        if (has(names(c, tree, to), s.file)) {
            // the same song is in that playlist already: this copy just leaves this one
            try {
                if (!DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, doc)))
                    return new String[]{"Could not move " + s.title, null};
            } catch (Exception e) {
                return new String[]{"Could not move " + s.title, null};
            }
            scan(c, paths);
            Plays.move(c, oldKey, newKey);
            if (Favs.has(c, oldKey)) {
                Favs.set(c, oldKey, false);
                Favs.set(c, newKey, true);
            }
            Library.load(c);
            return new String[]{s.title + " was in " + folder + " already - taken out of " + s.pl, newKey};
        }
        Uri moved = null;
        try {
            moved = DocumentsContract.moveDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, doc),
                    DocumentsContract.buildDocumentUriUsingTree(tree, from), DocumentsContract.buildDocumentUriUsingTree(tree, to));
        } catch (Exception ignored) {
        }
        String now;
        if (moved != null) {
            now = pathOf(DocumentsContract.getDocumentId(moved));
            newKey = folder + "/" + now.substring(now.lastIndexOf('/') + 1);
        } else {
            // this phone will not move it: copy, then take the old one away
            try {
                now = write(r, tree, to, new java.io.FileInputStream(s.path), s.file,
                        s.file.toLowerCase().endsWith(".mp3") ? "audio/mpeg" : "audio/*");
            } catch (Exception e) {
                now = null;
            }
            if (now == null) return new String[]{"Could not move " + s.title, null};
            newKey = folder + "/" + now.substring(now.lastIndexOf('/') + 1);
            try {
                DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, doc));
            } catch (Exception ignored) {
            }
        }
        paths.add(now);
        scan(c, paths);
        Plays.move(c, oldKey, newKey);
        if (Favs.has(c, oldKey)) {
            Favs.set(c, oldKey, false);
            Favs.set(c, newKey, true);
        }
        Library.load(c);
        return new String[]{"Moved " + s.title + " to " + folder, newKey};
    }

    /**
     * * the playlist folder and the songs still in it go. Songs moved or copied elsewhere are not in it any more.
     */
    static String deletePlaylist(Context c, Uri tree, String folder) {
        if (folder == null || folder.trim().isEmpty() || folder.contains("/") || folder.startsWith(".")
                || folder.equalsIgnoreCase(Backup.DIR)) return "That is not a playlist";
        String id = findFolder(c, tree, folder);
        if (id == null) return "Could not find " + folder;
        List<String> paths = new ArrayList<>();
        Library lib = Library.get(c);
        for (Library.Pl p : lib.playlists) if (p.name.equalsIgnoreCase(folder)) for (Library.Song s : p.songs) paths.add(s.path);
        for (Library.Song s : lib.hiddenSongs) if (folder.equalsIgnoreCase(s.pl)) paths.add(s.path);
        boolean ok;
        try {
            ok = DocumentsContract.deleteDocument(c.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, id));
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) return "Could not delete " + folder;
        scan(c, paths);                                   // Android forgets the files
        Sync.forget(c, folder);
        Missing.remove(c, folder, "*");
        Covers.reset(c, folder);
        Library.load(c);
        return "Deleted " + folder + (paths.isEmpty() ? "" : " and its " + paths.size() + (paths.size() == 1 ? " song" : " songs"));
    }

    /**
     * Rename playlist: the playlist folder gets its new name,
     * its by-ear lyrics folder (.lyrics/Name) too, and everything the app keeps under the old name follows it
     * (Rename.stores). Android's song list normally follows a folder rename by itself; if it has not, the songs
     * are looked at again. Returns {message, the name it has now or null, the name it had}.
     */
    static String[] renamePlaylist(Context c, Uri tree, String from, String want) {
        String to = want == null ? "" : want.trim().replaceAll("\\s+", " ");
        if (from == null || from.trim().isEmpty() || from.contains("/") || from.startsWith(".")
                || from.equalsIgnoreCase(Backup.DIR)) return new String[]{"That is not a playlist", null, null};
        if (to.isEmpty()) return new String[]{"Type the new name first", null, null};
        if (to.contains("/") || to.contains("\\")) return new String[]{"A playlist name cannot have / in it", null, null};
        if (to.startsWith(".")) return new String[]{"A playlist name cannot start with a dot", null, null};
        if (to.equalsIgnoreCase(Backup.DIR)) return new String[]{"That name is the backup folder's", null, null};
        if (to.length() > 100) return new String[]{"That name is too long", null, null};
        if (to.equals(from)) return new String[]{"It is called " + from + " already", null, null};
        if (DownloadService.alive) return new String[]{"Songs are coming in right now - rename it when they are done", null, null};
        ContentResolver r = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree), id = null, had = null;
        for (String[] ch : children(r, tree, rootId)) {
            if (ch[1] == null) continue;
            if (ch[1].equalsIgnoreCase(from) && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) {
                if (id == null || ch[1].equals(from)) {
                    id = ch[0];
                    had = ch[1];
                }
            } else if (ch[1].equalsIgnoreCase(to)) {
                return new String[]{"There is a playlist called " + ch[1] + " already", null, null};
            }
        }
        if (id == null) return new String[]{"Could not find " + from, null, null};
        List<String> was = new ArrayList<>();                 // its songs (hidden ones too), to check Android's list after
        Library lib = Library.get(c);
        for (Library.Pl p : lib.playlists) if (p.name.equalsIgnoreCase(had)) for (Library.Song s : p.songs) was.add(s.path);
        for (Library.Song s : lib.hiddenSongs) if (had.equalsIgnoreCase(s.pl)) was.add(s.path);
        Uri done = renameDoc(r, tree, id, had, to);
        if (done == null) return new String[]{"Could not rename " + had, null, null};
        String nid = DocumentsContract.getDocumentId(done), name = nid.substring(nid.lastIndexOf('/') + 1);
        // lyrics the phone heard are kept per playlist in .lyrics
        for (String[] ch : children(r, tree, rootId)) {
            if (Heard.DIR.equals(ch[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) heardFolder(c, r, tree, ch[0], had, name);
        }
        String top = Library.rootPath() + "/";
        int n = 0;
        for (int i = 0; i < 6 && !was.isEmpty() && (n = songsUnder(c, top + name + "/")) < was.size(); i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
        if (!was.isEmpty() && n < was.size()) {
            List<String> paths = new ArrayList<>(was);
            for (String p : was) if (p.startsWith(top)) paths.add(top + name + p.substring(top.length() + had.length()));
            scan(c, paths);
        }
        Rename.stores(c, had, name);
        Library.load(c);
        return new String[]{"Renamed " + had + " to " + name, name, had};
    }

    /** a folder's new name; only the capitals changing goes through a spare name (Android sees the same name) */
    private static Uri renameDoc(ContentResolver r, Uri tree, String id, String from, String to) {
        try {
            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            if (!to.equalsIgnoreCase(from)) return DocumentsContract.renameDocument(r, u, to);
            Uri mid = DocumentsContract.renameDocument(r, u, to + " (renaming)");
            if (mid == null) return null;
            Uri done = null;
            try {
                done = DocumentsContract.renameDocument(r, mid, to);
            } catch (Exception ignored) {
            }
            if (done == null) {
                try {
                    DocumentsContract.renameDocument(r, mid, from);       // back as it was
                } catch (Exception ignored) {
                }
            }
            return done;
        } catch (Exception e) {
            return null;
        }
    }

    /** .lyrics/Old becomes .lyrics/New; if both are there, the old one's files go in (any already there stay) */
    private static void heardFolder(Context c, ContentResolver r, Uri tree, String dir, String from, String to) {
        String old = null, oldName = null, there = null;
        for (String[] ch : children(r, tree, dir)) {
            if (ch[1] == null || !DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) continue;
            if (ch[1].equalsIgnoreCase(from)) {
                old = ch[0];
                oldName = ch[1];
            } else if (ch[1].equalsIgnoreCase(to)) there = ch[0];
        }
        if (old == null) return;
        if (there == null) {
            renameDoc(r, tree, old, oldName, to);
            return;
        }
        java.util.Map<String, String> have = names(c, tree, there);
        for (String[] f : children(r, tree, old)) {
            if (f[1] == null || have.containsKey(f[1].toLowerCase())) continue;
            try {
                DocumentsContract.moveDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, f[0]),
                        DocumentsContract.buildDocumentUriUsingTree(tree, old), DocumentsContract.buildDocumentUriUsingTree(tree, there));
            } catch (Exception ignored) {
            }
        }
        if (children(r, tree, old).isEmpty()) {
            try {
                DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, old));
            } catch (Exception ignored) {
            }
        }
    }

    /** how many songs Android's list has in this folder */
    private static int songsUnder(Context c, String dir) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{"_id"}, "_data LIKE ?", new String[]{dir + "%"}, null);
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) {
            return 0;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /**
     * Delete all songs: every song file in the playlist goes (hidden ones too) and the playlist itself stays - its picture, its
     * YouTube link and its archive (the files starting with "."), so a followed playlist does not fetch them again.
     */
    static String emptyPlaylist(Context c, Uri tree, String folder) {
        if (folder == null || folder.trim().isEmpty() || folder.contains("/") || folder.startsWith(".")
                || folder.equalsIgnoreCase(Backup.DIR)) return "That is not a playlist";
        String id = findFolder(c, tree, folder);
        if (id == null) return "Could not find " + folder;
        ContentResolver r = c.getContentResolver();
        List<String> paths = new ArrayList<>();
        int left = 0;
        for (String[] ch : children(r, tree, id)) {
            if (ch[1] == null || ch[1].startsWith(".") || ch[2] == null || !ch[2].startsWith("audio/")) continue;   // songs only
            boolean ok;
            try {
                ok = DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, ch[0]));
            } catch (Exception e) {
                ok = false;
            }
            if (ok) paths.add(pathOf(ch[0]));
            else left++;
        }
        scan(c, paths);                                   // Android forgets the files
        Missing.remove(c, folder, "*");
        Library.load(c);
        int n = paths.size();
        String msg = n == 0 ? "No songs deleted from " + folder
                : "Deleted " + n + (n == 1 ? " song" : " songs") + " from " + folder + " - the playlist stays";
        return left == 0 ? msg : msg + " (" + left + " could not be deleted)";
    }

    private static String join(List<String> a) {
        if (a.size() == 1) return a.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) b.append(i == a.size() - 1 ? " and " : ", ");
            b.append(a.get(i));
        }
        return b.toString();
    }
}
