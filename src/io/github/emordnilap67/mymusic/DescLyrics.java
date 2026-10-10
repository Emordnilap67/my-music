package io.github.emordnilap67.mymusic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lyrics from a YouTube video's description. Lyric-video
 * channels usually paste the words under the links, hashtags and credits;
 * this finds that block and leaves the rest. Plain Java so it can be tested.
 */
final class DescLyrics {
    private DescLyrics() {}

    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|ly|gg|me|co|be|fm|to|link|lnk)(/|\\b))");
    private static final Pattern PLATFORM = Pattern.compile(
            "(?i)\\b(spotify|apple ?music|itunes|soundcloud|instagram|insta|tiktok|twitter|facebook|youtube|deezer|bandcamp|beatport|discord|twitch|patreon|snapchat|threads|tidal|amazon music)\\b");
    private static final Pattern PROMO = Pattern.compile(
            "(?i)^\\W*(follow|stream|download|free download|listen|subscribe|submit|support|contact|business|e-?mail|booking|bookings|inquir|enquir|"
                    + "artist|track|title|song|genre|label|release[ds]?|out now|produced|prod\\.?|producer|mixed|mastered|artwork|cover art|art by|"
                    + "photo|image|background|thumbnail|wallpaper|picture|edit by|edited by|video by|lyric video|lyrics video|lyrics by|"
                    + "written by|vocals?|singer|instagram|turn on|join|merch|shop|tickets?|tour|playlist|channel|promo|promotion)\\b.*$");
    private static final Pattern LABEL = Pattern.compile("^[^:]{1,32}:\\s*\\S.*$");           // "Artist: Name"
    private static final Pattern COPY = Pattern.compile(
            "(?i)(\u00a9|\u2117|\\(c\\)|all rights|copyright|fair use|no infringement|infringement intended|owner of|please contact|"
                    + "if you (are|own)|will be removed|take ?down|disclaimer|non-profit|educational purposes)");
    private static final Pattern TIMES = Pattern.compile("^\\W*\\(?\\d{1,2}:\\d{2}(:\\d{2})?\\)?\\s");      // chapters: "0:00 Intro"
    private static final Pattern MARKER = Pattern.compile(
            "(?i)^\\W*(lyrics?|letras?|paroles|songtext|lirik|testo)\\W*(\\(.*\\))?\\W*$");
    private static final Pattern HEADER = Pattern.compile(
            "(?i)^[\\[(]?\\s*(intro|verse|pre-?chorus|chorus|post-?chorus|hook|bridge|outro|refrain|interlude|breakdown|drop|instrumental)\\b[^\\])]{0,24}[\\])]?\\s*:?$");
    private static final Pattern EMOJI_LEAD = Pattern.compile("^[\\p{So}\\p{Sk}\\u2190-\\u21ff\\u25a0-\\u27bf\\u2b00-\\u2bff\\ufe0f\\u200d>|=*\\u2022~-]+\\s*");
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,3}[.)]\\s");                 // a track list: "1. Song"

    /** the lyrics in this description, or null when it has none */
    static String find(String desc, String track, String artist) {
        if (desc == null) return null;
        String[] raw = desc.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (String r : raw) lines.add(r.replace('\u00a0', ' ').trim());

        // "Lyrics:" on its own line: the words start right after it
        int startAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (MARKER.matcher(lines.get(i)).matches()) {
                startAt = i + 1;
                break;
            }
        }
        String best = null;
        if (startAt >= 0) best = pick(lines, startAt, track, artist, 4);
        if (best == null) best = pick(lines, 0, track, artist, 8);
        return best;
    }

    /** the biggest run of lyric-like lines from here (runs end at a link, a hashtag, credits ...) */
    private static String pick(List<String> lines, int from, String track, String artist, int need) {
        String best = null;
        int bestN = 0;
        int i = from;
        while (i < lines.size()) {
            while (i < lines.size() && (lines.get(i).isEmpty() || junk(lines.get(i)))) i++;
            int s = i;
            while (i < lines.size() && (lines.get(i).isEmpty() || !junk(lines.get(i)))) i++;
            List<String> run = new ArrayList<>(lines.subList(s, i));
            dropTitle(run, track, artist);
            int n = 0, words = 0, longOnes = 0, dashes = 0;
            for (String l : run) {
                if (l.isEmpty() || HEADER.matcher(l).matches()) continue;
                n++;
                words += l.split("\\s+").length;
                if (l.length() > 70) longOnes++;
                if (l.contains(" - ") || NUMBERED.matcher(l).find()) dashes++;
            }
            if (n >= need && n > bestN && longOnes * 4 <= n && words >= n * 2 && words <= n * 14 && dashes * 2 < n) {
                bestN = n;
                best = join(run);
            }
            if (from > 0 && best != null) break;             // after "Lyrics:" the first good run is the one
        }
        return best;
    }

    static boolean junk(String l) {
        if (l.length() > 110) return true;
        if (URL.matcher(l).find() || PLATFORM.matcher(l).find() || COPY.matcher(l).find() || TIMES.matcher(l).find()) return true;
        if (l.startsWith("#") || l.startsWith("@") || l.matches(".*(#\\S+\\s*){2,}.*")) return true;
        String bare = EMOJI_LEAD.matcher(l).replaceFirst("");
        if (PROMO.matcher(bare).matches() && (bare.contains(":") || bare.contains(" - ") || bare.contains("|") || bare.split("\\s+").length <= 3
                || !bare.equals(l))) return true;
        if (LABEL.matcher(bare).matches() && !HEADER.matcher(bare).matches() && bare.indexOf(':') <= 24
                && bare.substring(0, bare.indexOf(':')).split("\\s+").length <= 3 && !bare.equals(l)) return true;
        if (bare.endsWith(":") && bare.length() <= 32 && !HEADER.matcher(bare).matches()) return true;
        return !bare.equals(l) && bare.length() < 4;         // an emoji line on its own
    }

    /** the video's title repeated at the top ("Tony Z - On Your Own") is not part of the words */
    private static void dropTitle(List<String> run, String track, String artist) {
        String kt = LyricsQuery.key(track), ka = LyricsQuery.key(artist);
        while (!run.isEmpty()) {
            String first = run.get(0);
            if (first.isEmpty()) {
                run.remove(0);
                continue;
            }
            String k = LyricsQuery.key(first);
            boolean title = !kt.isEmpty() && k.contains(kt) && (ka.isEmpty() || k.contains(ka) || first.contains(" - "));
            if (!title) break;
            run.remove(0);
        }
    }

    private static String join(List<String> run) {
        StringBuilder b = new StringBuilder();
        boolean gap = false;
        for (String l : run) {
            if (l.isEmpty()) {
                gap = b.length() > 0;
                continue;
            }
            if (gap) b.append('\n');
            if (b.length() > 0) b.append('\n');
            b.append(l);
            gap = false;
        }
        String s = b.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
