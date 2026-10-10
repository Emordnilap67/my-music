package io.github.emordnilap67.mymusic;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What to ask the lyrics library for, from a song's own title
 * and artist - which, for YouTube downloads, often carry extras like
 * "(Official Video)" or "Artist - Song". Plain Java so it can be tested.
 */
final class LyricsQuery {
    final String track, artist;          // tidied: what most lyrics sites call it
    final String simpleTrack, simpleArtist;   // barest form: no brackets, first artist only
    /** "Tony Z - On Your Own" uploaded by a lyrics channel: both sides, when the artist tag was neither */
    final String dashLeft, dashRight;

    private static final Pattern JUNK = Pattern.compile(
            "\\s*[(\\[{\\u3010\\u300c][^)\\]}\\u3011\\u300d]*\\b(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|explicit|clean|remaster(ed)?|music|full|color coded|letra|subtitulad[oa]|out now|free download|premiere|ncs|release|copyright free|no copyright|sped up|slowed|reverb|8d|bass boosted|tiktok|lyric)\\b[^)\\]}\\u3011\\u300d]*[)\\]}\\u3011\\u300d]",
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
    private static final Pattern SPLIT_ARTISTS = Pattern.compile("\\s*(,|&|;|\\sx\\s|\\sX\\s|\\sand\\s|\\svs\\.?\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s)\\s*", Pattern.CASE_INSENSITIVE);

    LyricsQuery(String title, String artist) {
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
            if (a.isEmpty()) {
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

    /**
     * what to ask for, best guess first. A lyric channel's upload ("Tony Z -
     * On Your Own", artist tag = the channel) is read as artist - song, then
     * song - artist, then as it stands.
     */
    java.util.List<LyricsQuery> candidates() {
        java.util.List<LyricsQuery> out = new java.util.ArrayList<>();
        if (!dashLeft.isEmpty()) {
            out.add(new LyricsQuery(dashRight, dashLeft));
            out.add(new LyricsQuery(dashLeft, dashRight));
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
        return new LyricsQuery(right, left);
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
