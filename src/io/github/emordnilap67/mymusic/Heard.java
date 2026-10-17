package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Heard {
    static final String DIR = ".lyrics";
    private static final long FRESH_MS = 60 * 1000;
    private static final int MAX = 256 * 1024;
    private static final Pattern LRC = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{2}([.:]\\d{1,3})?\\]");
    private static long at;
    private static Map<String, String> docs = Collections.emptyMap();     // "playlist/file.mp3" -> its .lrc document
    private static Map<String, String> lower = Collections.emptyMap();

    private Heard() {}

    /** every song that has phone-written lyrics waiting (looked at again at most once a minute) */
    static synchronized Set<String> keys(Context c) {
        refresh(c);
        return docs.keySet();
    }

    /** the phone-written lyrics of this song (timed, LRC), or null */
    static String get(Context c, Library.Song s) {
        if (s == null) return null;
        String doc;
        synchronized (Heard.class) {
            refresh(c);
            doc = docs.get(s.key);
            if (doc == null) doc = lower.get(s.key.toLowerCase(Locale.ROOT));
        }
        if (doc == null) return null;
        Uri tree = Importer.tree(c);
        if (tree == null) return null;
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc));
            if (in == null) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX) return null;
            }
            String t = new String(b.toByteArray(), "UTF-8").replace("\r\n", "\n").trim();
            if (t.startsWith("\ufeff")) t = t.substring(1);
            return t.isEmpty() || !LRC.matcher(t).find() ? null : t;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void refresh(Context c) {
        long now = System.currentTimeMillis();
        if (now - at < FRESH_MS && at > 0) return;
        at = now;
        Map<String, String> d = new HashMap<>(), l = new HashMap<>();
        Uri tree = Importer.tree(c);
        if (tree != null) {
            ContentResolver r = c.getContentResolver();
            String dir = null;
            for (String[] ch : children(r, tree, DocumentsContract.getTreeDocumentId(tree))) {
                if (DIR.equals(ch[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(ch[2])) dir = ch[0];
            }
            if (dir != null) {
                for (String[] pl : children(r, tree, dir)) {
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(pl[2])) continue;
                    for (String[] f : children(r, tree, pl[0])) {
                        String name = f[1];
                        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".lrc")) continue;
                        String key = pl[1] + "/" + name.substring(0, name.length() - 4);
                        d.put(key, f[0]);
                        l.put(key.toLowerCase(Locale.ROOT), f[0]);
                    }
                }
            }
        }
        docs = d;
        lower = l;
    }

    /** a folder's children: {document id, name, type} */
    private static List<String[]> children(ContentResolver r, Uri tree, String docId) {
        List<String[]> out = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (cur != null && cur.moveToNext()) out.add(new String[]{cur.getString(0), cur.getString(1), cur.getString(2)});
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }
}
