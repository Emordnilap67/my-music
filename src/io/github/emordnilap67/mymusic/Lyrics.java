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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lyrics from LRCLIB (lrclib.net), a free, open lyrics
 * library - most songs come with timed lines, so the page can follow along.
 * Each answer is kept on the phone: found lyrics for good, "none" for a
 * few days (then asked again, in case someone added them).
 */
final class Lyrics {
    interface Done {
        void got(String json);
    }

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final String API = "https://lrclib.net/api/";
    private static final String AGENT = "MY MUSIC/1.0 (open-source Android music player)";
    private static final long RETRY_NONE_MS = 3L * 24 * 3600 * 1000;
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
        latest = key;
        BG.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(latest)) return;
                String out;
                try {
                    out = find(c, key);
                } catch (Busy e) {
                    out = page(key, "busy", null, null);
                } catch (org.json.JSONException e) {
                    out = page(key, "busy", null, null);
                } catch (Exception e) {
                    out = page(key, "offline", null, null);
                }
                done.got(out);
            }
        });
    }

    private static String find(Context c, String key) throws Exception {
        Library.Song s = Library.get(c).byKey.get(key);
        if (s == null) return page(key, "none", null, null);
        double secs = s.durMs / 1000.0;
        LyricsQuery q = new LyricsQuery(s.title, s.artist);
        File dir = new File(c.getFilesDir(), "lyrics");
        dir.mkdirs();
        File f = new File(dir, Art.sha1((q.artist + "|" + q.track + "|" + Math.round(secs)).toLowerCase()) + ".json");

        JSONObject kept = read(f);
        if (kept != null) {
            String st = kept.optString("status");
            boolean fresh = !"none".equals(st) || System.currentTimeMillis() - kept.optLong("at") < RETRY_NONE_MS;
            if (fresh) return page(key, st, kept.optString("synced", null), kept.optString("plain", null));
        }

        JSONObject hit = lookup(q, secs);
        if (hit == null) {
            LyricsQuery flip = q.flipped();               // "Lucky Luke - Somebody" from a channel
            if (flip != null) hit = lookup(flip, secs);
        }
        String status, synced = null, plain = null;
        if (hit == null) {
            status = "none";
        } else if (hit.optBoolean("instrumental")) {
            status = "instrumental";
        } else {
            synced = text(hit, "syncedLyrics");
            plain = text(hit, "plainLyrics");
            status = synced != null || plain != null ? "ok" : "none";
        }
        JSONObject keep = new JSONObject();
        keep.put("status", status);
        keep.put("at", System.currentTimeMillis());
        if (synced != null) keep.put("synced", synced);
        if (plain != null) keep.put("plain", plain);
        write(f, keep);
        return page(key, status, synced, plain);
    }

    /** exact first (name, artist, length), then a search; null = not in the library */
    private static JSONObject lookup(LyricsQuery q, double secs) throws Exception {
        if (q.track.isEmpty()) return null;
        if (!q.artist.isEmpty()) {
            String u = API + "get?track_name=" + enc(q.track) + "&artist_name=" + enc(q.artist)
                    + (secs > 0 ? "&duration=" + Math.round(secs) : "");
            String body = http(u);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                if (text(o, "syncedLyrics") != null || text(o, "plainLyrics") != null || o.optBoolean("instrumental")) return o;
            }
        }
        // search: by name + artist, then plain words (simplest forms)
        List<String> urls = new ArrayList<>();
        if (!q.artist.isEmpty()) urls.add(API + "search?track_name=" + enc(q.track) + "&artist_name=" + enc(q.artist));
        String words = (q.simpleTrack + " " + q.simpleArtist).trim();
        urls.add(API + "search?q=" + enc(words));
        for (int i = 0; i < urls.size(); i++) {
            String body = http(urls.get(i));
            if (body == null) continue;
            JSONArray a = new JSONArray(body);
            List<LyricsQuery.Hit> hits = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                hits.add(new LyricsQuery.Hit(o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0),
                        text(o, "syncedLyrics") != null, text(o, "plainLyrics") != null, o.optBoolean("instrumental"), o));
            }
            LyricsQuery.Hit best = q.best(hits, secs, true);
            if (best == null && i == urls.size() - 1 && q.artist.isEmpty()) best = q.best(hits, secs, false);
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

    /** body of a 200 answer; null for 404; throws when offline / the site is down */
    private static String http(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setRequestProperty("User-Agent", AGENT);
        h.setRequestProperty("Accept", "application/json");
        try {
            int code = h.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new Busy("lyrics site answered " + code);
            InputStream in = h.getInputStream();
            try {
                return new String(readAll(in), "UTF-8");
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

    /** what the page gets */
    private static String page(String key, String status, String synced, String plain) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("status", status);
            if (synced != null) o.put("synced", synced);
            if (plain != null) o.put("plain", plain);
            // two line-break characters JavaScript text must not hold raw
            return o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            return "{\"status\":\"none\"}";
        }
    }
}
