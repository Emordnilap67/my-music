package io.github.emordnilap67.mymusic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * a Spotify or Deezer link - a song, an album or a playlist - read into its songs' names. Their sound is
 * locked, so nothing is taken from them: DownloadService finds each song on YouTube (best() below, with
 * AutoCopy's rules - its name, its artist, its length within 4 s, not another version) and downloads it from
 * there, the same as a shared YouTube song. A song with no clear match goes under "Find another copy".
 * - Spotify: its public embed page (open.spotify.com/embed/...), which carries the name, artists and length of
 *   every song - the first 100 of a playlist, all Spotify shows an app with no account.
 * - Deezer: its open API (api.deezer.com), every song, a hundred at a time.
 * - Short share links (spotify.link, link.deezer.com, deezer.page.link) are followed to the real one.
 */
final class Sources {
    private static final Pattern SP = Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]{22})");
    private static final Pattern SP_URI = Pattern.compile("spotify:(track|album|playlist):([A-Za-z0-9]{22})");
    private static final Pattern DZ = Pattern.compile("deezer\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist)/(\\d+)");
    private static final Pattern SHORT = Pattern.compile(
            "^https?://(spotify\\.link|spoti\\.fi|link\\.deezer\\.com|deezer\\.page\\.link|dzr\\.page\\.link)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern COUNT = Pattern.compile("\"music:song_count\" content=\"(\\d+)\"");
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final String API = "https://api.deezer.com/";
    /** the most songs of one Deezer list read (a runaway list stops here) */
    private static final int MAX = 3000;

    private Sources() {}

    /** what a link held */
    static final class Got {
        String name = "";          // the album's or playlist's name ("" for one song)
        boolean list;              // an album or a playlist
        int total;                 // how many songs the list has (Spotify may show fewer)
        String from = "";          // "Spotify" or "Deezer"
        final List<String[]> songs = new ArrayList<>();   // {its own id, title, artists, seconds}
    }

    /** a Spotify or Deezer link (not YouTube) */
    static boolean is(String link) {
        String l = link == null ? "" : link.trim();
        return SP.matcher(l).find() || SP_URI.matcher(l).find() || DZ.matcher(l).find() || SHORT.matcher(l).find();
    }

    /** a short share link that has to be followed before it says what it is */
    static boolean isShort(String link) {
        return link != null && SHORT.matcher(link.trim()).find();
    }

    /** the songs behind the link */
    static Got read(String link) throws Exception {
        String l = link.trim();
        if (isShort(l)) {
            String r = resolve(l);
            if (r.isEmpty()) throw new Exception("could not follow that share link");
            l = r;
        }
        Matcher m = SP.matcher(l);
        if (m.find()) return spotify(m.group(1), m.group(2));
        m = SP_URI.matcher(l);
        if (m.find()) return spotify(m.group(1), m.group(2));
        m = DZ.matcher(l);
        if (m.find()) return deezer(m.group(1), m.group(2));
        throw new Exception("that is not a Spotify or Deezer song, album or playlist");
    }

    /** a short share link followed to the real one ("" when it leads nowhere known) */
    static String resolve(String link) {
        String u = link.trim();
        try {
            for (int hop = 0; hop < 8; hop++) {
                String known = canonical(u);
                if (!known.isEmpty()) return known;
                HttpURLConnection c = open(u);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                String to = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && to != null) {
                    c.disconnect();
                    u = new URL(new URL(u), to).toString();
                    continue;
                }
                String body = code == 200 ? body(c) : "";
                c.disconnect();
                return canonical(body.replace("\\/", "/"));       // a page that sends you on with a script
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** the plain link of the first Spotify or Deezer song, album or playlist named in s ("" = none) */
    static String canonical(String s) {
        Matcher m = SP.matcher(s);
        if (m.find()) return "https://open.spotify.com/" + m.group(1) + "/" + m.group(2);
        m = SP_URI.matcher(s);
        if (m.find()) return "https://open.spotify.com/" + m.group(1) + "/" + m.group(2);
        m = DZ.matcher(s);
        if (m.find()) return "https://www.deezer.com/" + m.group(1) + "/" + m.group(2);
        return "";
    }

    /** "playlist" for an album or playlist link, "song" for one song, "" when a short link hides it */
    static String kind(String link) {
        String c = canonical(link == null ? "" : link);
        if (c.isEmpty()) return "";
        return c.contains("/track/") ? "song" : "playlist";
    }

    // ---------------------------------------------------------------- Spotify

    private static Got spotify(String kind, String id) throws Exception {
        String html = text("https://open.spotify.com/embed/" + kind + "/" + id);
        Matcher m = NEXT.matcher(html);
        if (!m.find()) throw new Exception("Spotify did not show that " + kind);
        JSONObject e = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity");
        Got g = new Got();
        g.from = "Spotify";
        if ("track".equals(kind)) {
            JSONArray ar = e.optJSONArray("artists");
            List<String> who = new ArrayList<>();
            if (ar != null) for (int i = 0; i < ar.length(); i++) {
                JSONObject a = ar.optJSONObject(i);
                if (a != null && !a.optString("name").isEmpty()) who.add(clean(a.optString("name")));
            }
            g.songs.add(new String[]{"sp" + id, clean(e.optString("name", e.optString("title"))), join(who),
                    String.valueOf(Math.round(e.optLong("duration") / 1000.0))});
            g.total = 1;
            return g;
        }
        g.list = true;
        g.name = clean(e.optString("name", e.optString("title")));
        JSONArray t = e.optJSONArray("trackList");
        if (t != null) for (int i = 0; i < t.length(); i++) {
            JSONObject s = t.optJSONObject(i);
            if (s == null) continue;
            String uri = s.optString("uri"), sid = uri.substring(uri.lastIndexOf(':') + 1);
            String title = clean(s.optString("title"));
            if (title.isEmpty()) continue;
            g.songs.add(new String[]{"sp" + sid, title, clean(s.optString("subtitle")).replaceAll("\\s*,\\s*", ", "),
                    String.valueOf(Math.round(s.optLong("duration") / 1000.0))});
        }
        g.total = g.songs.size();
        if (g.songs.size() >= 100) {
            // the embed page stops at 100: the full page says how many the list really has
            try {
                Matcher c = COUNT.matcher(text("https://open.spotify.com/" + kind + "/" + id));
                if (c.find()) g.total = Math.max(g.total, Integer.parseInt(c.group(1)));
            } catch (Exception ignored) {
            }
        }
        return g;
    }

    // ---------------------------------------------------------------- Deezer

    private static Got deezer(String kind, String id) throws Exception {
        Got g = new Got();
        g.from = "Deezer";
        if ("track".equals(kind)) {
            g.songs.add(dz(json(API + "track/" + id)));
            g.total = 1;
            return g;
        }
        JSONObject o = json(API + kind + "/" + id);
        g.list = true;
        g.name = clean(o.optString("title"));
        g.total = o.optInt("nb_tracks");
        String next = API + kind + "/" + id + "/tracks?limit=100&index=0";
        while (next != null && !next.isEmpty() && g.songs.size() < MAX) {
            JSONObject p = json(next);
            JSONArray d = p.optJSONArray("data");
            if (d == null || d.length() == 0) break;
            for (int i = 0; i < d.length(); i++) {
                JSONObject s = d.optJSONObject(i);
                if (s != null && !s.optString("title").isEmpty()) g.songs.add(dz(s));
            }
            next = p.optString("next", "");
        }
        if (g.total < g.songs.size()) g.total = g.songs.size();
        return g;
    }

    /** one Deezer song: {id, title, artists, seconds} */
    private static String[] dz(JSONObject s) {
        List<String> who = new ArrayList<>();
        JSONArray c = s.optJSONArray("contributors");           // the song's own page names every artist
        if (c != null) for (int i = 0; i < c.length(); i++) {
            JSONObject a = c.optJSONObject(i);
            if (a != null && !a.optString("name").isEmpty() && !who.contains(clean(a.optString("name")))) who.add(clean(a.optString("name")));
        }
        JSONObject a = s.optJSONObject("artist");
        if (who.isEmpty() && a != null) who.add(clean(a.optString("name")));
        return new String[]{"dz" + s.optLong("id"), clean(s.optString("title")), join(who), String.valueOf(s.optLong("duration"))};
    }

    // ---------------------------------------------------------------- finding it on YouTube

    /** what to ask YouTube for: the first artist and the song's name */
    static String query(String[] song) {
        String who = song[2].split(",")[0].trim();
        return (who + " " + song[1]).replaceAll("[\"\\s]+", " ").trim();
    }

    /**
     * the YouTube upload that is clearly this song, or null. res = {id, title, channel, seconds} in YouTube's
     * order. The official audio (a "Topic" channel, "Audio" in the title) is tried first, then the closest
     * length; AutoCopy.pick says yes or no to each.
     */
    static String best(List<String[]> res, String[] song) {
        final long d = num(song[3]);
        List<String[]> r = new ArrayList<>();
        for (String[] x : res) r.add(new String[]{x[0], x[1], x[2], x[3], String.valueOf(r.size())});
        Collections.sort(r, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int c = rank(a) - rank(b);
                if (c != 0) return c;
                long x = Math.abs(num(a[3]) - d), y = Math.abs(num(b[3]) - d);
                if (x != y) return x < y ? -1 : 1;
                return Integer.parseInt(a[4]) - Integer.parseInt(b[4]);
            }
        });
        List<String[]> plain = new ArrayList<>();
        for (String[] x : r) plain.add(new String[]{x[0], x[1], x[2].replaceAll("\\s*-\\s*Topic$", ""), x[3]});
        return AutoCopy.pick(plain, "", "", song[1], song[2], d);
    }

    private static int rank(String[] x) {
        boolean topic = x[2].endsWith(" - Topic"), audio = x[1].toLowerCase(Locale.ROOT).contains("audio");
        return topic ? 0 : audio ? 1 : 2;
    }

    // ---------------------------------------------------------------- the web

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en");
        return c;
    }

    private static String text(String u) throws Exception {
        HttpURLConnection c = open(u);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception((u.contains("deezer") ? "Deezer" : "Spotify") + " answered " + code);
            return body(c);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject json(String u) throws Exception {
        JSONObject o = new JSONObject(text(u));
        JSONObject err = o.optJSONObject("error");
        if (err != null) throw new Exception("Deezer: " + err.optString("message", "no such song or list"));
        return o;
    }

    private static String body(HttpURLConnection c) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        return b.toString("UTF-8");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String join(List<String> who) {
        StringBuilder b = new StringBuilder();
        for (String w : who) {
            if (b.length() > 0) b.append(", ");
            b.append(w);
        }
        return b.toString();
    }

    private static long num(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return 0;
        }
    }
}
