package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Lyrics from the song's own YouTube video: its captions,
 * fetched with the built-in yt-dlp. They are timed to the very audio in
 * the file, so they follow along exactly. Typed-in captions first; YouTube's
 * automatic ones (words heard by a computer, often a little off) if that is
 * all there is.
 */
final class Captions {
    static final class Result {
        String lrc;          // "[mm:ss.xx]line" lines
        boolean auto;        // YouTube's automatic captions
    }

    /** YouTube held the request back (bot check / too many requests) */
    static final class Held extends Exception {
        Held(String m) {
            super(m);
        }
    }

    private Captions() {}

    static Result fetch(Context c, String id) throws Exception {
        if (id == null || id.isEmpty() || !Ytdl.available(c)) return null;
        Ytdl.init(c);
        File dir = new File(c.getCacheDir(), "cc");
        Ytdl.delete(dir);
        dir.mkdirs();
        List<String> a = new ArrayList<>(Arrays.asList("--skip-download", "--no-warnings", "--no-playlist",
                "--write-subs", "--write-auto-subs", "--sub-langs", "en.*,.*-orig", "--sub-format", "json3",
                "--extractor-retries", "3", "-o", new File(dir, "cc.%(ext)s").getAbsolutePath(),
                "https://www.youtube.com/watch?v=" + id));
        final Process p = Ytdl.start(c, a);
        // a stuck yt-dlp must not hold up every song's lyrics: stop it after 90 seconds
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!p.waitFor(90, TimeUnit.SECONDS)) p.destroy();
                } catch (InterruptedException ignored) {
                }
            }
        });
        watch.setDaemon(true);
        watch.start();
        StringBuilder out = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) if (out.length() < 20000) out.append(line).append('\n');
        } catch (Exception ignored) {
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
        watch.interrupt();
        String said = out.toString();
        Result best = null;
        int bestRank = -1;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".json3")) continue;
                Result res = parse(readText(f));
                if (res == null) continue;
                String lang = n.substring(3, n.length() - 6).toLowerCase(Locale.ROOT);     // cc.<lang>.json3
                int rank = (res.auto ? 0 : 10) + (lang.endsWith("-orig") ? 2 : 0) + (lang.startsWith("en") ? 1 : 0);
                if (rank > bestRank) {
                    bestRank = rank;
                    best = res;
                }
            }
        }
        Ytdl.delete(dir);
        if (best == null && (said.contains("not a bot") || said.contains("Sign in to confirm") || said.contains("429")
                || said.contains("Too Many Requests") || said.contains("try again later") || said.contains("rate-limit")))
            throw new Held("YouTube is pausing this phone right now");
        return best;
    }

    /** a json3 caption file as timed lines; null when it holds no words (only [Music] and the like) */
    static Result parse(String json) {
        try {
            JSONArray ev = new JSONObject(json).optJSONArray("events");
            if (ev == null) return null;
            Result res = new Result();
            List<long[]> times = new ArrayList<>();
            List<String> texts = new ArrayList<>();      // plain words, for doubles
            List<String> lines = new ArrayList<>();      // with word times (<mm:ss.xx>) when YouTube gives them
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                JSONArray segs = e == null ? null : e.optJSONArray("segs");
                if (segs == null) continue;
                if (e.optInt("aAppend", 0) == 1) {
                    res.auto = true;
                    continue;
                }
                long t = e.optLong("tStartMs", 0), d = e.optLong("dDurationMs", 0);
                StringBuilder b = new StringBuilder();
                List<long[]> wordAt = new ArrayList<>();          // {offset into b, ms}
                for (int k = 0; k < segs.length(); k++) {
                    JSONObject sg = segs.optJSONObject(k);
                    if (sg == null) continue;
                    if (sg.has("tOffsetMs")) res.auto = true;
                    wordAt.add(new long[]{b.length(), t + sg.optLong("tOffsetMs", 0)});
                    b.append(sg.optString("utf8", ""));
                }
                String all = b.toString();
                if (all.indexOf('\n') >= 0 || wordAt.size() < 2) {
                    // typed captions (or one piece): whole lines, two-line captions split in time
                    List<String> keep = new ArrayList<>();
                    for (String part : all.split("\n")) {
                        String x = clean(part);
                        if (!x.isEmpty()) keep.add(x);
                    }
                    for (int k = 0; k < keep.size(); k++) {
                        times.add(new long[]{t + (keep.size() > 1 ? k * d / keep.size() : 0)});
                        texts.add(keep.get(k));
                        lines.add(keep.get(k));
                    }
                    continue;
                }
                // automatic captions: each word with the moment it is sung - karaoke timing
                String plain = clean(all);
                if (plain.isEmpty()) continue;
                StringBuilder withTimes = new StringBuilder();
                for (int k = 0; k < wordAt.size(); k++) {
                    int from = (int) wordAt.get(k)[0], to = k + 1 < wordAt.size() ? (int) wordAt.get(k + 1)[0] : all.length();
                    String w = all.substring(from, to).replaceAll("[\u266a\u266b\u266c\u2669]", " ").replaceAll("\\s+", " ");
                    if (w.trim().isEmpty()) continue;
                    if (withTimes.length() == 0) w = w.replaceAll("^\\s+", "");
                    withTimes.append(stamp(wordAt.get(k)[1], '<', '>')).append(w);
                }
                times.add(new long[]{t});
                texts.add(plain);
                lines.add(withTimes.toString().trim());
            }
            StringBuilder lrc = new StringBuilder();
            String last = "";
            int n = 0;
            for (int i = 0; i < texts.size(); i++) {
                String x = texts.get(i);
                if (x.equalsIgnoreCase(last)) continue;
                last = x;
                lrc.append(stamp(times.get(i)[0], '[', ']')).append(lines.get(i)).append('\n');
                n++;
            }
            if (n < 4) return null;
            res.lrc = lrc.toString();
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stamp(long ms, char open, char close) {
        return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, close);
    }

    /** one caption line without music notes, sound labels ([Music], (applause)) or tags */
    static String clean(String s) {
        String x = s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("[\u266a\u266b\u266c\u2669]", " ").replaceAll("\\s+", " ").trim();
        if (x.matches("^[\\[(][^\\])]*[\\])]$")) return "";
        x = x.replaceAll("^-\\s*", "").trim();
        return x.matches(".*[\\p{L}\\p{N}].*") ? x : "";
    }

    private static String readText(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
