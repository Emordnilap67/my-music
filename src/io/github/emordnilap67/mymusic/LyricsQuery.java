package io.github.emordnilap67.mymusic;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class LyricsQuery {
    final String track, artist;          // tidied: what most lyrics sites call it
    final String simpleTrack, simpleArtist;   // barest form: no brackets, first artist only
    /** "Tony Z - On Your Own" uploaded by a lyrics channel: both sides, when the artist tag was neither */
    final String dashLeft, dashRight;

    private static final Pattern JUNK = Pattern.compile(
            "\\s*[(\\[{\\u3010\\u300c][^)\\]}\\u3011\\u300d]*\\b(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|explicit|clean|remaster(ed)?|music|full|color coded|letra|subtitulad[oa]|out now|free download|premiere|ncs|release|copyright free|no copyright|sped up|slowed|reverb|8d|bass boosted|tiktok|lyric|bpm|nightcore)\\b[^)\\]}\\u3011\\u300d]*[)\\]}\\u3011\\u300d]",
            Pattern.CASE_INSENSITIVE);
    /** "| Lyrics", "- Lyrics", "Lyrics" after the song name, without brackets */
    private static final Pattern BARE_JUNK = Pattern.compile(
            "\\s+([-|/~\\u2010-\\u2015]\\s*)?(lyrics?|lyric video|lyrics video|official (audio|video|lyric video|music video)|music video|visuali[sz]er|video oficial|letra)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEAT_BR = Pattern.compile("\\s*[(\\[]\\s*(feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEAT = Pattern.compile("\\s+(feat\\.?|ft\\.?|featuring)\\s.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL = Pattern.compile("\\s+(\\||//)\\s.*$");
    private static final Pattern DASH_JUNK = Pattern.compile(
            "\\s[-\u2010-\u2015]\\s[^-]*\\b(remaster(ed)?|radio edit|single version|album version|mono|stereo|official|lyrics?|audio|video)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[{][^)\\]}]*[)\\]}]");
    private static final Pattern DASH = Pattern.compile("\\s[-\u2010-\u2015]\\s");
    /** "Song - X Remix", "Song - Radio Edit", "Song - 134 BPM": the part after the dash names a version of the song */
    private static final Pattern VERSION_TAG = Pattern.compile(
            "\\b(remix|mix|edit|version|bootleg|flip|vip|rework|cover|live|acoustic|instrumental|extended|sped up|slowed|nightcore|bpm|remaster(ed)?|mashup)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPLIT_ARTISTS = Pattern.compile("\\s*(,|&|;|\\sx\\s|\\sX\\s|\\sand\\s|\\svs\\.?\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s)\\s*", Pattern.CASE_INSENSITIVE);

    /** "Song - X Remix" is one song name (lyrics only; the playlist matching keeps the older reading) */
    final boolean versions;

    LyricsQuery(String title, String artist) {
        this(title, artist, false);
    }

    /** what the lyrics lookup asks with */
    static LyricsQuery forLyrics(String title, String artist) {
        return new LyricsQuery(title, artist, true);
    }

    private LyricsQuery(String title, String artist, boolean versions) {
        this.versions = versions;
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        a = a.replaceAll("\\s*-\\s*Topic$", "").replaceAll("(?i)VEVO$", "").trim();
        if (a.equalsIgnoreCase("unknown artist") || a.equals("<unknown>")) a = "";
        t = TAIL.matcher(t).replaceAll("");
        t = JUNK.matcher(t).replaceAll("");
        t = BARE_JUNK.matcher(t).replaceAll("");
        t = DASH_JUNK.matcher(t).replaceAll("");
        t = FEAT_BR.matcher(t).replaceAll("");
        t = t.replaceAll("\\s{2,}", " ").trim();
        // "Artist - Song" in the title
        String l0 = "", r0 = "";
        java.util.regex.Matcher m = DASH.matcher(t);
        if (m.find()) {
            String left = t.substring(0, m.start()).trim(), right = t.substring(m.end()).trim();
            if (versions && isVersion(right) && !isVersion(left)) {
                // "Play - K-391 & Alan Walker Remix": the song, then its version - keep it whole
            } else if (a.isEmpty()) {
                a = left;
                t = right;
            } else if (same(left, a) || key(left).contains(key(firstArtist(a)))) {
                t = right;
            } else if (same(right, a)) {
                t = left;
            } else if (!left.isEmpty() && !right.isEmpty()) {
                l0 = left;                    // the artist tag is neither side: an uploader's channel, most likely
                r0 = right;
            }
        }
        this.dashLeft = l0;
        this.dashRight = r0;
        t = FEAT.matcher(t).replaceAll("").trim();
        t = t.replaceAll("^[\"'\u201c\u2018]+|[\"'\u201d\u2019]+$", "").trim();
        this.track = t;
        this.artist = a;
        String st = BRACKETS.matcher(t).replaceAll("");
        java.util.regex.Matcher d = DASH.matcher(st);
        if (d.find()) st = st.substring(0, d.start());      // "Song - Radio Edit" -> "Song"
        this.simpleTrack = st.trim().isEmpty() ? t : st.trim();
        this.simpleArtist = firstArtist(a);
    }

    private static boolean isVersion(String side) {
        return VERSION_TAG.matcher(BRACKETS.matcher(side).replaceAll("").trim()).find();
    }

    /**
     * what to ask for, best guess first. A lyric channel's upload ("Tony Z -
     * On Your Own", artist tag = the channel) is read as artist - song, then
     * song - artist, then as it stands.
     */
    java.util.List<LyricsQuery> candidates() {
        java.util.List<LyricsQuery> out = new java.util.ArrayList<>();
        if (!dashLeft.isEmpty()) {
            out.add(new LyricsQuery(dashRight, dashLeft, versions));
            out.add(new LyricsQuery(dashLeft, dashRight, versions));
        }
        out.add(this);
        LyricsQuery f = flipped();
        if (f != null && dashLeft.isEmpty()) out.add(f);
        return out;
    }

    /** the whole cleaned name as plain words, for a free-text search */
    String words() {
        String w = dashLeft.isEmpty() ? simpleTrack + " " + simpleArtist : dashLeft + " " + dashRight;
        return BRACKETS.matcher(w).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** "Lucky Luke - Somebody" uploaded by a channel: read it as artist Lucky Luke, song Somebody */
    LyricsQuery flipped() {
        java.util.regex.Matcher m = DASH.matcher(track);
        if (!m.find()) return null;
        String left = track.substring(0, m.start()).trim(), right = track.substring(m.end()).trim();
        if (left.isEmpty() || right.isEmpty()) return null;
        if (versions && isVersion(right)) return null;          // "Play - X Remix" is not "artist Play"
        return new LyricsQuery(right, left, versions);
    }

    /** each artist's key ("K-391, Alan Walker & Mangoo" -> k391, alanwalker, mangoo), plus the dash side read as an artist */
    java.util.List<String> artistKeys() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : SPLIT_ARTISTS.split(artist)) {
            String k = key(x);
            if (k.length() >= 3 && !out.contains(k)) out.add(k);
        }
        String k = key(dashLeft);
        if (k.length() >= 3 && !out.contains(k)) out.add(k);
        return out;
    }

    /** "I Don't Like Monday" <-> "I Don't Like Mondays" (the library matches whole words only); null when it does not apply */
    String plural() {
        String t = simpleTrack.trim();
        java.util.regex.Matcher m = Pattern.compile("(\\p{L}+)$").matcher(t);
        if (!m.find()) return null;
        String w = m.group(1);
        if (w.length() < 5) return null;
        String lw = w.toLowerCase(Locale.ROOT);
        if (lw.endsWith("ss")) return null;
        return lw.endsWith("s") ? t.substring(0, t.length() - 1) : t + "s";
    }

    /** a name long enough that a song of that name by anyone is most likely this song (a cover) */
    boolean distinctive() {
        String[] w = simpleTrack.trim().split("\\s+");
        return key(simpleTrack).length() >= 10 && w.length >= 3;
    }

    /** a library name without its brackets and " - version" part: "Play - Alan Walker Remix" -> "play" */
    static String baseKey(String name) {
        String b = BRACKETS.matcher(name == null ? "" : name).replaceAll("");
        java.util.regex.Matcher d = DASH.matcher(b);
        if (d.find()) b = b.substring(0, d.start());
        String k = key(b);
        return k.isEmpty() ? key(name) : k;
    }

    /**
     * Another version of this song (the original of a remix, a cover's
     * original), any length: its words are shown untimed. A hit by one of
     * this song's artists first; failing that, when anyAuthor is on and the
     * name is distinctive, the artist most of the hits agree on.
     */
    Hit other(List<Hit> hits, boolean anyAuthor) {
        String ks = key(simpleTrack);
        if (ks.length() < 2) return null;
        java.util.List<String> mine = artistKeys();
        Hit best = null;
        double bestScore = -1e9;
        java.util.Map<String, Integer> votes = new java.util.HashMap<>();
        java.util.Map<String, Hit> firstOf = new java.util.HashMap<>();
        for (Hit h : hits) {
            if (h.instrumental || !h.synced && !h.plain) continue;
            String hb = baseKey(h.track);
            boolean exact = hb.equals(ks);
            if (!exact && !hb.equals(ks + "s") && !ks.equals(hb + "s")) continue;
            String ha = key(h.artist);
            boolean ours = false;
            for (String a : mine) if (ha.contains(a)) ours = true;
            if (ours) {
                boolean orig = key(h.track).equals(hb);              // no "remix" / brackets of its own
                double score = (exact ? 20 : 0) + (orig ? 8 : 0) + (h.synced ? 2 : 0) + (h.plain ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = h;
                }
                continue;
            }
            String first = key(firstArtist(h.artist));
            if (first.isEmpty()) continue;
            votes.put(first, (votes.containsKey(first) ? votes.get(first) : 0) + (exact ? 2 : 1));
            if (!firstOf.containsKey(first)) firstOf.put(first, h);
        }
        if (best != null || !anyAuthor || !distinctive() || votes.isEmpty()) return best;
        String top = null;
        for (Hit h : hits) {                                           // most votes; ties go to the library's own order
            String f = key(firstArtist(h.artist));
            if (!votes.containsKey(f)) continue;
            if (top == null || votes.get(f) > votes.get(top)) top = f;
        }
        return top == null ? null : firstOf.get(top);
    }

    /**
     * the song name to show. "TwinBeats - Jou Eyes Se Alles" by
     * TwinBeats shows as "Jou Eyes Se Alles"; "Ganjaman - Alfons" by Alfons
     * as "Ganjaman". Anything else stays as it is.
     */
    static String shownTitle(String title, String artist) {
        String t = title == null ? "" : title.trim();
        if (artist == null || artist.trim().isEmpty()) return t;
        java.util.regex.Matcher m = DASH.matcher(t);
        if (!m.find()) return t;
        String left = t.substring(0, m.start()).trim(), right = t.substring(m.end()).trim();
        if (left.isEmpty() || right.isEmpty()) return t;
        java.util.Set<String> names = new java.util.HashSet<>();
        names.add(key(artist));
        for (String x : SPLIT_ARTISTS.split(artist.trim())) names.add(key(x));
        names.remove("");
        if (names.contains(key(left))) return right;
        if (names.contains(key(right))) return left;
        return t;
    }

    static String firstArtist(String a) {
        if (a == null) return "";
        String[] p = SPLIT_ARTISTS.split(a.trim(), 2);
        return p.length == 0 ? a.trim() : p[0].trim();
    }

    /** letters and digits only, lower case, accents off: "Beyonce" == "Beyonc\u00e9" */
    static String key(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static boolean same(String x, String y) {
        String a = key(x), b = key(y);
        return !a.isEmpty() && a.equals(b);
    }

    /** one result from the lyrics library */
    static final class Hit {
        final String track, artist;
        final double duration;
        final boolean synced, plain, instrumental;
        final Object raw;

        Hit(String track, String artist, double duration, boolean synced, boolean plain, boolean instrumental, Object raw) {
            this.track = track == null ? "" : track;
            this.artist = artist == null ? "" : artist;
            this.duration = duration;
            this.synced = synced;
            this.plain = plain;
            this.instrumental = instrumental;
            this.raw = raw;
        }
    }

    /**
     * the best of these for this song, or null. A hit must be the same
     * length (within a few seconds, when both are known) and the same song
     * name; the artist must match too unless strict is off.
     */
    Hit best(List<Hit> hits, double seconds, boolean strict) {
        return best(hits, seconds, strict, 6);
    }

    /** the same, with this much difference in length allowed (a lyric video's intro or outro) */
    Hit best(List<Hit> hits, double seconds, boolean strict, double slack) {
        Hit best = null;
        double bestScore = -1e9;
        String kt = key(track), ks = key(simpleTrack), ka = key(simpleArtist);
        for (Hit h : hits) {
            if (!h.synced && !h.plain && !h.instrumental) continue;
            double diff = seconds > 0 && h.duration > 0 ? Math.abs(h.duration - seconds) : 0;
            if (diff > slack) continue;
            String ht = key(h.track), ha = key(h.artist);
            boolean nameOk = !ht.isEmpty() && (ht.equals(kt) || ht.equals(ks) || ks.length() >= 3 && ht.startsWith(ks));
            if (!nameOk) continue;
            boolean artistOk = ka.isEmpty() || ha.contains(ka) || ka.contains(ha) && ha.length() >= 3;
            if (strict && !artistOk) continue;
            double score = (h.synced ? 30 : h.plain ? 15 : 5) - diff * 3 + (ht.equals(kt) ? 6 : 0) + (artistOk ? 10 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }
}
