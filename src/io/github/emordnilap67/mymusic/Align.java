package io.github.emordnilap67.mymusic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Untimed lyrics get their times from a timed source.
 *
 * The lyrics library often has a song's words but no times (or times for a
 * shorter version than a lyric video), and untimed words can only sit there.
 * The video's own captions - or words written by listening to the song
 * - DO have times, but their words are often a little off. So the two are
 * laid side by side, word by word (the longest run of matching words, a
 * letter or so of slack per word), and each lyric line takes the moment its
 * first matched word is sung. Lines with nothing matched sit evenly between
 * their neighbours. Too few matches = not this song's words: nothing changes.
 *
 * shift(): library lyrics WITH times, for a longer cut (a lyric video's
 * intro): the same matching, then one offset for the whole song - the
 * library's own timing between lines is kept, only moved.
 */
final class Align {
    private Align() {}

    private static final Pattern LINE = Pattern.compile("^\\s*\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\](.*)$");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Set<String> SMALL = new HashSet<>(Arrays.asList(
            "a", "an", "the", "i", "im", "you", "me", "my", "we", "it", "its", "is", "are", "to", "of", "in", "on",
            "and", "or", "oh", "ooh", "yeah", "na", "la", "da", "so", "be", "do", "no", "up", "at", "for", "with",
            "that", "this", "your", "our", "but", "all", "can", "dont", "just", "like", "now", "ah", "hey", "uh"));
    /** a line's words before its first match: about this long each */
    private static final long WORD_MS = 330;

    /** one word: as written, compared form, its moment (ms, -1 = none), and its line */
    private static final class W {
        final String key;
        long t;
        final int line;

        W(String key, long t, int line) {
            this.key = key;
            this.t = t;
            this.line = line;
        }
    }

    // ------------------------------------------------------------ reading

    private static long ms(String m, String s, String f) {
        long v = Long.parseLong(m) * 60000 + Long.parseLong(s) * 1000;
        if (f != null && !f.isEmpty()) {
            String x = (f + "00").substring(0, 3);
            v += Long.parseLong(x);
        }
        return v;
    }

    static String key(String w) {
        String x = Normalizer.normalize(w, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return x.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** the timed source's words, each with its moment (word stamps when there are, else spread over the line) */
    private static List<W> timedWords(String lrc) {
        List<long[]> starts = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) continue;
            starts.add(new long[]{ms(m.group(1), m.group(2), m.group(3))});
            bodies.add(m.group(4));
        }
        List<W> out = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            long t0 = starts.get(i)[0];
            long t1 = i + 1 < starts.size() ? Math.min(starts.get(i + 1)[0], t0 + 7000) : t0 + 4000;
            String body = bodies.get(i);
            Matcher wm = WORD.matcher(body);
            if (wm.find()) {
                // automatic captions: <mm:ss.xx>word for every word
                int at = 0;
                long when = t0;
                wm.reset();
                List<Object[]> parts = new ArrayList<>();
                while (wm.find()) {
                    if (wm.start() > at) parts.add(new Object[]{when, body.substring(at, wm.start())});
                    when = ms(wm.group(1), wm.group(2), wm.group(3));
                    at = wm.end();
                }
                parts.add(new Object[]{when, body.substring(at)});
                for (Object[] p : parts)
                    for (String w : ((String) p[1]).trim().split("\\s+")) {
                        String k = key(w);
                        if (!k.isEmpty()) out.add(new W(k, (Long) p[0], i));
                    }
                continue;
            }
            String[] ws = body.trim().split("\\s+");
            List<String> keys = new ArrayList<>();
            for (String w : ws) {
                String k = key(w);
                if (!k.isEmpty()) keys.add(k);
            }
            for (int k = 0; k < keys.size(); k++)
                out.add(new W(keys.get(k), t0 + (t1 - t0) * k / Math.max(1, keys.size()), i));
        }
        return out;
    }

    /** "[mm:ss.xx]" or "<...>" stamps off, blank lines out: the lyric lines as shown */
    private static List<String> lines(String text, List<Long> times) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            Matcher m = LINE.matcher(raw);
            String body = raw;
            long t = -1;
            if (m.matches()) {
                t = ms(m.group(1), m.group(2), m.group(3));
                body = m.group(4);
            }
            body = WORD.matcher(body).replaceAll("").trim();
            if (body.isEmpty() || key(body).isEmpty()) continue;
            out.add(body);
            if (times != null) times.add(t);
        }
        return out;
    }

    // ----------------------------------------------------------- matching

    private static boolean same(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < 4 || b.length() < 4) return false;
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, miss = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++miss > 1) return false;
            if (a.length() > b.length()) i++;
            else if (b.length() > a.length()) j++;
            else {
                i++;
                j++;
            }
        }
        return miss + (a.length() - i) + (b.length() - j) <= 1;
    }

    private static double worth(String k) {
        return SMALL.contains(k) || k.length() < 3 ? 0.35 : 1.0;
    }

    /**
     * the best run of matching words (longest common sequence, small words
     * worth less): for each lyric word, the index of its timed word or -1
     */
    private static int[] match(List<W> a, List<W> b) {
        int n = a.size(), m = b.size();
        int[] pick = new int[n];
        Arrays.fill(pick, -1);
        if (n == 0 || m == 0 || (long) n * m > 4000000L) return pick;
        float[] dp = new float[(n + 1) * (m + 1)];
        int W1 = m + 1;
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                float best = Math.max(dp[(i + 1) * W1 + j], dp[i * W1 + j + 1]);
                if (same(a.get(i).key, b.get(j).key)) {
                    float d = dp[(i + 1) * W1 + j + 1] + (float) worth(a.get(i).key);
                    if (d > best) best = d;
                }
                dp[i * W1 + j] = best;
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (same(a.get(i).key, b.get(j).key)
                    && dp[i * W1 + j] == dp[(i + 1) * W1 + j + 1] + (float) worth(a.get(i).key)) {
                pick[i] = j;
                i++;
                j++;
            } else if (dp[(i + 1) * W1 + j] >= dp[i * W1 + j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        // a lone small word matched far from anything else is chance, not the song
        for (int k = 0; k < n; k++) {
            if (pick[k] < 0 || worth(a.get(k).key) >= 1) continue;
            boolean prev = k > 0 && pick[k - 1] >= 0 && pick[k - 1] == pick[k] - 1;
            boolean next = k + 1 < n && pick[k + 1] >= 0 && pick[k + 1] == pick[k] + 1;
            if (!prev && !next) pick[k] = -1;
        }
        return pick;
    }

    private static List<W> lyricWords(List<String> ls) {
        List<W> out = new ArrayList<>();
        for (int i = 0; i < ls.size(); i++)
            for (String w : ls.get(i).split("\\s+")) {
                String k = key(w);
                if (!k.isEmpty()) out.add(new W(k, -1, i));
            }
        return out;
    }

    /** each lyric line's moment from the matched words, or -1; null when too little matched */
    private static long[] anchor(List<String> ls, String timed) {
        List<W> lw = lyricWords(ls), tw = timedWords(timed);
        if (lw.size() < 8 || tw.size() < 8) return null;
        int[] pick = match(lw, tw);
        double want = 0, got = 0;
        for (int k = 0; k < lw.size(); k++) {
            double v = worth(lw.get(k).key);
            want += v;
            if (pick[k] >= 0) got += v;
        }
        long[] at = new long[ls.size()];
        Arrays.fill(at, -1);
        int[] firstIdx = new int[ls.size()];
        Arrays.fill(firstIdx, -1);
        int pos = 0;
        for (int k = 0; k < lw.size(); k++) {
            int ln = lw.get(k).line;
            if (k == 0 || lw.get(k - 1).line != ln) pos = 0;
            if (pick[k] >= 0 && at[ln] < 0 && worth(lw.get(k).key) >= 1 || pick[k] >= 0 && at[ln] < 0 && pos == 0) {
                at[ln] = Math.max(0, tw.get(pick[k]).t - pos * WORD_MS);
                firstIdx[ln] = pos;
            }
            pos++;
        }
        int anchored = 0;
        for (long t : at) if (t >= 0) anchored++;
        if (got / Math.max(1, want) < 0.35 || anchored < Math.max(3, (int) Math.ceil(ls.size() * 0.4))) return null;
        // a moment out of order (a repeated chorus matched the wrong time) is dropped, not trusted
        long last = -1;
        for (int i = 0; i < at.length; i++) {
            if (at[i] < 0) continue;
            if (at[i] < last) {
                at[i] = -1;
                continue;
            }
            last = at[i];
        }
        return at;
    }

    private static String stamp(long ms) {
        ms = Math.max(0, ms);
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10);
    }

    // -------------------------------------------------------------- answers

    /** untimed lyrics + a timed source of the same song -> timed lyrics (LRC), or null */
    static String time(String plain, String timed) {
        if (plain == null || timed == null) return null;
        try {
            List<String> ls = lines(plain, null);
            long[] at = anchor(ls, timed);
            if (at == null) return null;
            int n = ls.size();
            int[] words = new int[n];
            for (int i = 0; i < n; i++) words[i] = Math.max(1, ls.get(i).split("\\s+").length);
            // lines with no moment: evenly by their words between the moments around them
            int i = 0;
            while (i < n) {
                if (at[i] >= 0) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && at[j] < 0) j++;
                long from, to;
                if (i == 0) {
                    int w = 0;
                    for (int k = 0; k < j; k++) w += words[k];
                    to = j < n ? at[j] : 0;
                    from = Math.max(0, to - w * 450L);
                } else from = at[i - 1];
                if (j >= n) {
                    int w = 0;
                    for (int k = i - 1; k < n; k++) w += words[k];
                    to = from + w * 450L;
                } else to = at[j];
                int total = 0;
                for (int k = (i == 0 ? 0 : i - 1); k < j; k++) total += words[k];
                long acc = 0;
                for (int k = (i == 0 ? 0 : i - 1); k < j; k++) {
                    if (k >= i) at[k] = from + (to - from) * acc / Math.max(1, total);
                    acc += words[k];
                }
                i = j;
            }
            StringBuilder b = new StringBuilder();
            long last = -1;
            for (int k = 0; k < n; k++) {
                long t = Math.max(at[k], last + 100);
                b.append(stamp(t)).append(ls.get(k)).append('\n');
                last = t;
            }
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * how much later (ms) the timed source sings these timed lyrics - one offset for the whole
     * song - or null (not the same words, or no one offset fits)
     */
    static Long offset(String synced, String timed) {
        if (synced == null || timed == null) return null;
        try {
            List<Long> own = new ArrayList<>();
            List<String> ls = lines(synced, own);
            long[] at = anchor(ls, timed);
            if (at == null) return null;
            List<Long> d = new ArrayList<>();
            for (int i = 0; i < ls.size(); i++) if (at[i] >= 0 && own.get(i) >= 0) d.add(at[i] - own.get(i));
            if (d.size() < 4) return null;
            List<Long> s = new ArrayList<>(d);
            java.util.Collections.sort(s);
            long med = s.get(s.size() / 2);
            int near = 0;
            for (long x : d) if (Math.abs(x - med) <= 1500) near++;
            return near < d.size() * 0.6 ? null : med;          // no one offset fits: leave it
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * the library's timed lyrics against other uploads of the same song and length (closest
     * length first): the one to take instead - the closest of two or more that agree with each other
     * but sit 1.5 s+ away from these, with fewer than half as many siding with these - or -1 (these stand)
     */
    static int odd(String synced, List<String> others) {
        int with = 0;
        List<Long> away = new ArrayList<>();
        List<Integer> who = new ArrayList<>();
        for (int i = 0; i < others.size(); i++) {
            Long off = offset(synced, others.get(i));
            if (off == null) continue;                         // other words: another song or a remix
            if (Math.abs(off) <= 1200) with++;
            else if (Math.abs(off) >= 1500) {
                away.add(off);
                who.add(i);
            }
        }
        if (away.size() < 2) return -1;
        List<Long> s = new ArrayList<>(away);
        java.util.Collections.sort(s);
        long med = s.get(s.size() / 2);
        int agree = 0, pick = -1;
        for (int k = 0; k < away.size(); k++) {
            if (Math.abs(away.get(k) - med) > 1000) continue;
            agree++;
            if (pick < 0) pick = who.get(k);
        }
        return agree >= 2 && agree > 2 * with ? pick : -1;
    }

    /** library lyrics WITH times for another cut + a timed source -> the same lyrics moved by one offset, or null */
    static String shift(String synced, String timed) {
        Long med = offset(synced, timed);
        if (med == null) return null;
        try {
            List<Long> own = new ArrayList<>();
            List<String> ls = lines(synced, own);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < ls.size(); i++) {
                long t = own.get(i);
                if (t < 0) continue;
                b.append(stamp(t + med)).append(ls.get(i)).append('\n');
            }
            return b.length() == 0 ? null : b.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
