package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A song gone from its
 * YouTube playlist - removed, private, or age-restricted (YouTube gives those only to a signed-in
 * account, so they can never be downloaded) - now gets another copy by itself. YouTube is searched
 * for the same song and an upload is taken only when it is clearly the same recording:
 *   - its title has the song's name, and the artist is in its title or channel;
 *   - it is not a remix / sped up / slowed / live / cover ... version, unless the gone one was;
 *   - it is as long as the gone one to within SLACK seconds.
 * That copy is downloaded into the playlist and the gone video is skipped from then on, the same as
 * picking it under "Find another copy". Each gone song gets at most TRIES searches; one with no
 * sure match stays under "Find another copy" for a person to pick.
 */
final class AutoCopy {
    static final int TRIES = 2, SLACK = 4;
    /** words that mean a different version of a song */
    private static final String[] VERSIONS = {"remix", "sped", "slowed", "nightcore", "reverb", "8d", "live", "cover",
            "karaoke", "instrumental", "extended", "acoustic", "mashup", "bass boosted", "1 hour", "loop"};
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(Low.THREADS);
    private static final AtomicBoolean QUEUED = new AtomicBoolean();

    private AutoCopy() {}

    /** look over the gone songs soon, in the background */
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

    private static void pass(Context c) throws Exception {
        if (!Ytdl.available(c) || Importer.tree(c) == null) return;
        SharedPreferences tried = c.getSharedPreferences("autocopy", Context.MODE_PRIVATE);
        for (String[] e : Missing.entries(c)) {              // {folder, id, title, artist, seconds}
            String folder = e[0], id = e[1];
            int n = tried.getInt(id, 0);
            if (n >= TRIES) continue;
            long d = num(e[4]);
            if (d <= 0) {                                    // no length to check a copy against: a person picks
                tried.edit().putInt(id, TRIES).apply();
                continue;
            }
            String res = Copies.search(c, (e[3].isEmpty() ? "" : e[3] + " ") + e[2]);
            if (!res.startsWith("[")) {
                if (res.contains("pausing")) return;         // YouTube is pausing this phone: next time
                tried.edit().putInt(id, n + 1).apply();
                continue;
            }
            String used = tried.getString(id + ".ids", "");
            String pick = pick(found(res), id, used, e[2], e[3], d);
            if (pick != null) {
                try {
                    DownloadService.start(c, "https://www.youtube.com/watch?v=" + pick, folder);
                } catch (Exception x) {
                    return;                                  // Android would not let it start now: next time
                }
                tried.edit().putInt(id, n + 1).putString(id + ".ids", used + pick + ",").apply();
                letGo(c, folder, id);
            } else {
                tried.edit().putInt(id, n + 1).apply();
            }
            Thread.sleep(3000);                              // gently: a search every few seconds
        }
    }

    /** Copies.search's answer as {id, title, channel, seconds} */
    private static List<String[]> found(String json) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new String[]{o.optString("id"), o.optString("t"), o.optString("a"), String.valueOf(o.optLong("d"))});
        }
        return out;
    }

    /** the first upload that is clearly the same song (YouTube's order), or null. Plain Java, so it can be tested. */
    static String pick(List<String[]> res, String oldId, String used, String title, String artist, long d) {
        LyricsQuery q = new LyricsQuery(title, artist);
        String song = LyricsQuery.key(q.simpleTrack), who = LyricsQuery.key(LyricsQuery.firstArtist(q.artist));
        if (song.length() < 2) return null;
        String was = title.toLowerCase(Locale.ROOT);
        for (String[] r : res) {
            String id = r[0], t = r[1], a = r[2];
            long cd = num(r[3]);
            if (id.isEmpty() || id.equals(oldId) || used.contains(id + ",")) continue;
            if (cd <= 0 || Math.abs(cd - d) > SLACK) continue;
            if (!LyricsQuery.key(t).contains(song)) continue;
            if (!who.isEmpty() && !LyricsQuery.key(t + " " + a).contains(who)) continue;
            String low = t.toLowerCase(Locale.ROOT);
            boolean other = false;
            for (String v : VERSIONS) {
                if (low.contains(v) && !was.contains(v)) {
                    other = true;
                    break;
                }
            }
            if (!other) return id;
        }
        return null;
    }

    /** off the gone list, and the gone video skipped from now on (as "Find another copy" does) */
    private static void letGo(Context c, String folder, String id) {
        List<String> ids = Missing.ids(c, folder, id);
        Missing.remove(c, folder, id);
        Uri t = Importer.tree(c);
        if (t == null || ids.isEmpty()) return;
        String fid = Importer.folderId(c, t, folder);
        if (fid != null) Importer.appendArchive(c, t, fid, ids);
    }

    private static long num(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return 0;
        }
    }
}
