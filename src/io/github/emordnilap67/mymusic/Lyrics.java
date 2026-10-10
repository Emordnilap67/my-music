package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Lyrics. In order:
 *   1. LRCLIB (lrclib.net), a free lyrics library - mostly timed lines.
 *      A lyric channel's upload ("Tony Z - On Your Own" by the channel)
 *      is read as artist - song first.
 *   2. lyrics already inside the song file
 *   3. LRCLIB again, allowing a lyric video's longer intro/outro (shown
 *      untimed, since the timing would be off)
 *   4. the words pasted in the video's description (the file keeps it)
 *   5. the video's own captions, through the built-in yt-dlp
 * Each answer is kept on the phone: found lyrics for good, "none" for a
 * few days (then asked again, in case someone added them).
 */
final class Lyrics {
    interface Done {
        void got(String json);
    }

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final String API = "https://lrclib.net/api/";
    private static final String AGENT = "MY MUSIC/3.0 (open-source Android music player)";
    private static final long RETRY_NONE_MS = 3L * 24 * 3600 * 1000;
    private static final long RETRY_SOON_MS = 6L * 3600 * 1000;
    /** answers kept before this version are asked again ("none" from the old, narrower search) */
    private static final int V = 17;
    private static final Pattern LRC = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{2}([.:]\\d{1,3})?\\]");
    /** the song asked about last: older questions still waiting are dropped (skipping through songs) */
    private static volatile String latest;

    /** the lyrics site answered, but not with lyrics (busy, rate limit, error page) */
    private static final class Busy extends Exception {
        Busy(String m) {
            super(m);
        }
    }

    private Lyrics() {}

    static void get(final Context c, final String key, final Done done) {
        get(c, key, false, done);
    }

    /** fresh = ask again even if an answer is kept (after "Search by name") */
    static void get(final Context c, final String key, final boolean fresh, final Done done) {
        latest = key;
        BG.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(latest)) return;
                String out;
                try {
                    out = find(c, key, fresh, done);
                } catch (Exception e) {
                    out = page(key, "offline", null, null, null, null);
                }
                done.got(out);
            }
        });
    }

    private static String find(Context c, String key, boolean fresh, Done done) throws Exception {
        Library.Song s = Library.get(c).byKey.get(key);
        if (s == null) return page(key, "none", null, null, null, null);
        double secs = s.durMs / 1000.0;
        String[] fix = LyricsFix.get(c, key);
        LyricsQuery q = fix != null ? new LyricsQuery(fix[0], fix[1]) : new LyricsQuery(s.title, s.artist);
        List<LyricsQuery> asks = q.candidates();
        LyricsQuery likely = asks.get(0);
        File dir = new File(c.getFilesDir(), "lyrics");
        dir.mkdirs();
        File f = new File(dir, Art.sha1((q.artist + "|" + q.track + "|" + Math.round(secs)).toLowerCase()) + ".json");

        JSONObject kept = fresh ? null : read(f);
        if (kept != null) {
            String st = kept.optString("status");
            long age = System.currentTimeMillis() - kept.optLong("at");
            boolean keep = !"none".equals(st)
                    || kept.optInt("v") >= V && age < (kept.optBoolean("soon") ? RETRY_SOON_MS : RETRY_NONE_MS);
            if (keep) return page(key, st, kept.optString("synced", null), kept.optString("plain", null),
                    kept.optString("src", "lrclib"), likely);
        }

        // 1 + 3: the lyrics library, strict on length first, then allowing a lyric video's extra seconds
        Map<String, String> memo = new HashMap<>();
        JSONObject hit = null;
        boolean loose = false, siteDown = false, busy = false;
        try {
            for (LyricsQuery k : asks) {
                hit = lookup(k, secs, memo, 6, true);
                if (hit != null) break;
            }
        } catch (Busy e) {
            busy = true;
        } catch (Exception e) {
            siteDown = true;
        }
        String status = "none", synced = null, plain = null, src = null;
        boolean soon = false;
        if (hit != null) {
            if (hit.optBoolean("instrumental")) {
                status = "instrumental";
            } else {
                synced = text(hit, "syncedLyrics");
                plain = text(hit, "plainLyrics");
                if (synced != null || plain != null) status = "ok";
            }
            src = "lrclib";
        }
        // 2: lyrics already in the file; 4: the video's description
        Id3.Tags tags = null;
        if (!"ok".equals(status) && !"instrumental".equals(status)) {
            tags = Id3.read(s.path == null ? null : new File(s.path), true);
            if (tags.lyrics != null) {
                if (LRC.matcher(tags.lyrics).find()) synced = tags.lyrics;
                else plain = tags.lyrics;
                status = "ok";
                src = "file";
            }
        }
        if (!"ok".equals(status) && !"instrumental".equals(status) && !siteDown && !busy) {
            for (LyricsQuery k : asks) {
                JSONObject h = lookup(k, secs, memo, 30, false);         // only what the library already said
                if (h != null && !h.optBoolean("instrumental")) {
                    String sy = text(h, "syncedLyrics"), pl = text(h, "plainLyrics");
                    plain = pl != null ? pl : sy == null ? null : LRC.matcher(sy).replaceAll("").replaceAll("(?m)^\\s+", "").trim();
                    if (plain != null) {
                        status = "ok";
                        src = "lrclib";
                        loose = true;
                        break;
                    }
                }
            }
        }
        if (!"ok".equals(status) && !"instrumental".equals(status) && tags != null) {
            String d = DescLyrics.find(tags.description, likely.track, likely.artist);
            if (d != null) {
                plain = d;
                status = "ok";
                src = "desc";
            }
        }
        // 5: the video's captions (slow: a few seconds to start yt-dlp)
        if (!"ok".equals(status) && !"instrumental".equals(status) && tags != null && !tags.id.isEmpty()
                && Ytdl.available(c) && key.equals(latest)) {
            done.got(page(key, "loading", null, null, "cc", likely));
            try {
                Captions.Result r = Captions.fetch(c, tags.id);
                if (r != null) {
                    synced = r.lrc;
                    status = "ok";
                    src = r.auto ? "auto" : "cc";
                }
            } catch (Captions.Held e) {
                soon = true;
            } catch (Exception e) {
                soon = true;
            }
        }
        if (!"ok".equals(status) && !"instrumental".equals(status)) {
            if (siteDown) return page(key, "offline", null, null, null, likely);
            if (busy) return page(key, "busy", null, null, null, likely);
        }
        JSONObject keep = new JSONObject();
        keep.put("v", V);
        keep.put("status", status);
        keep.put("at", System.currentTimeMillis());
        if (soon) keep.put("soon", true);
        if (src != null) keep.put("src", src);
        if (loose) keep.put("loose", true);
        if (synced != null) keep.put("synced", synced);
        if (plain != null) keep.put("plain", plain);
        write(f, keep);
        return page(key, status, synced, plain, src, likely);
    }

    /**
     * the library's answer for this song, or null. net = may ask the site;
     * otherwise only answers it already gave (memo) are looked through.
     */
    private static JSONObject lookup(LyricsQuery q, double secs, Map<String, String> memo, double slack, boolean net) throws Exception {
        if (q.track.isEmpty()) return null;
        if (!q.artist.isEmpty() && net) {
            String u = API + "get?track_name=" + enc(q.track) + "&artist_name=" + enc(q.artist)
                    + (secs > 0 ? "&duration=" + Math.round(secs) : "");
            String body = http(u, memo, true);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                if (text(o, "syncedLyrics") != null || text(o, "plainLyrics") != null || o.optBoolean("instrumental")) return o;
            }
        }
        // search: by name + artist, then plain words
        List<String> urls = new ArrayList<>();
        if (!q.artist.isEmpty()) urls.add(API + "search?track_name=" + enc(q.track) + "&artist_name=" + enc(q.artist));
        String words = q.words();
        if (!words.isEmpty()) urls.add(API + "search?q=" + enc(words));
        for (int i = 0; i < urls.size(); i++) {
            String body = http(urls.get(i), memo, net);
            if (body == null) continue;
            JSONArray a = new JSONArray(body);
            List<LyricsQuery.Hit> hits = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                hits.add(new LyricsQuery.Hit(o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0),
                        text(o, "syncedLyrics") != null, text(o, "plainLyrics") != null, o.optBoolean("instrumental"), o));
            }
            LyricsQuery.Hit best = q.best(hits, secs, true, slack);
            if (best == null && i == urls.size() - 1 && q.artist.isEmpty()) best = q.best(hits, secs, false, slack);
            if (best != null) return (JSONObject) best.raw;
        }
        return null;
    }

    private static String text(JSONObject o, String k) {
        if (o.isNull(k)) return null;
        String s = o.optString(k, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static final String NOT_FOUND = "\u0000404";

    /** body of a 200 answer; null for 404 (or not asked yet, when net is off); throws when offline / the site is down */
    private static String http(String u, Map<String, String> memo, boolean net) throws Exception {
        if (memo.containsKey(u)) {
            String m = memo.get(u);
            return NOT_FOUND.equals(m) ? null : m;
        }
        if (!net) return null;
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setRequestProperty("User-Agent", AGENT);
        h.setRequestProperty("Accept", "application/json");
        try {
            int code = h.getResponseCode();
            if (code == 404) {
                memo.put(u, NOT_FOUND);
                return null;
            }
            if (code != 200) throw new Busy("lyrics site answered " + code);
            InputStream in = h.getInputStream();
            try {
                String body = new String(readAll(in), "UTF-8");
                memo.put(u, body);
                return body;
            } finally {
                in.close();
            }
        } finally {
            h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static JSONObject read(File f) {
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                return new JSONObject(new String(readAll(in), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, JSONObject o) {
        File tmp = new File(f.getPath() + ".part");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            tmp.delete();
        }
    }

    /** what the page gets: the lyrics, where they came from, and the name they were looked up by */
    private static String page(String key, String status, String synced, String plain, String src, LyricsQuery q) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("status", status);
            if (synced != null) o.put("synced", synced);
            if (plain != null) o.put("plain", plain);
            if (src != null) o.put("src", src);
            if (q != null) {
                o.put("track", q.track);
                o.put("artist", q.artist);
            }
            // two line-break characters JavaScript text must not hold raw
            return o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            return "{\"status\":\"none\"}";
        }
    }
}
