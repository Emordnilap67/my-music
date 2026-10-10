package io.github.emordnilap67.mymusic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local first: is this YouTube song already in the playlist
 * folder? Songs on the phone always win - a song that is in the folder is
 * never called "gone from YouTube" and never downloaded again. Matches by
 * the song's name and artist the way the files and tags have them, so a
 * different upload of the same song counts too. Plain Java so it can be
 * tested.
 */
final class LocalMatch {
    /** song name key -> the artist keys it has in this folder ("" = no artist) */
    private final Map<String, Set<String>> byTitle = new HashMap<>();
    /** exact name keys (with what is in brackets), for the strict check */
    private final Map<String, Set<String>> exact = new HashMap<>();
    /** the YouTube videos the files came from */
    final Set<String> ids = new HashSet<>();

    LocalMatch() {}

    /** one song on the phone: its tags and its file name ("Artist - Song.mp3") */
    void add(String title, String artist, String file, String id) {
        if (file != null && file.startsWith(Mismatch.KEPT)) return;     // its tags name a song it is not
        if (id != null && !id.isEmpty()) ids.add(id);
        put(title, artist);
        if (file != null) {
            String stem = file.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
            int d = stem.indexOf(" - ");
            if (d > 0) put(stem.substring(d + 3), stem.substring(0, d));
            else put(stem, artist == null ? "" : artist);       // "Free.mp3": the artist its tags name
        }
    }

    private void put(String title, String artist) {
        LyricsQuery q = new LyricsQuery(title, artist);
        for (LyricsQuery k : q.candidates()) {
            String a = LyricsQuery.key(LyricsQuery.firstArtist(k.artist));
            add(byTitle, LyricsQuery.key(k.track), a);
            add(byTitle, LyricsQuery.key(k.simpleTrack), a);
        }
        add(exact, LyricsQuery.key(title), LyricsQuery.key(LyricsQuery.firstArtist(artist)));
    }

    private static void add(Map<String, Set<String>> m, String k, String a) {
        if (k.isEmpty()) return;
        Set<String> s = m.get(k);
        if (s == null) m.put(k, s = new HashSet<>());
        s.add(a);
    }

    /**
     * the playlist already has this song. loose = also when only the bare
     * name (no brackets) matches - fine for not calling a song "gone", too
     * loose for skipping a download (a remix would count as the original).
     */
    boolean has(String id, String ytTitle, String ytArtist, boolean loose) {
        if (id != null && ids.contains(id)) return true;
        if (!loose) {
            Set<String> as = exact.get(LyricsQuery.key(ytTitle));
            if (as == null) return false;
            String a = LyricsQuery.key(LyricsQuery.firstArtist(ytArtist));
            if (!a.isEmpty() && as.size() == 1 && as.contains("")) return false;   // nothing says who sings it: not proof
            return artistOk(as, a);
        }
        LyricsQuery q = new LyricsQuery(ytTitle, ytArtist);
        for (LyricsQuery k : q.candidates()) {
            String a = LyricsQuery.key(LyricsQuery.firstArtist(k.artist));
            for (String t : new String[]{LyricsQuery.key(k.track), LyricsQuery.key(k.simpleTrack)}) {
                Set<String> as = t.isEmpty() ? null : byTitle.get(t);
                if (as != null && artistOk(as, a)) return true;
            }
        }
        return false;
    }

    /** the artist agrees (or one side has none) */
    private static boolean artistOk(Set<String> local, String a) {
        if (a.isEmpty() || local.contains("")) return true;
        for (String l : local) {
            if (l.equals(a)) return true;
            if (l.length() >= 3 && a.length() >= 3 && (l.contains(a) || a.contains(l))) return true;
        }
        return false;
    }

    private static Library cachedFor;
    private static final Map<String, LocalMatch> CACHE = new HashMap<>();

    /** the same, kept until the library is read again (for the page, which asks often) */
    static synchronized LocalMatch cached(android.content.Context c, String folder) {
        Library lib = Library.get(c);
        if (lib != cachedFor) {
            CACHE.clear();
            cachedFor = lib;
        }
        String k = folder.toLowerCase(java.util.Locale.ROOT);
        LocalMatch m = CACHE.get(k);
        if (m == null) CACHE.put(k, m = of(c, folder, false));
        return m;
    }

    /** the songs of this playlist on the phone (hidden ones too), with their YouTube ids if known */
    static LocalMatch of(android.content.Context c, String folder, boolean readTags) {
        LocalMatch m = new LocalMatch();
        Library lib = Library.get(c);
        for (Library.Pl p : lib.playlists) {
            if (!p.name.equalsIgnoreCase(folder)) continue;
            for (Library.Song s : p.songs) m.add(s.title, s.artist, s.file, readTags ? Ids.of(c, s.path) : Ids.cached(c, s.path));
        }
        for (Library.Song s : lib.hiddenSongs) {
            if (folder.equalsIgnoreCase(s.pl)) m.add(s.title, s.artist, s.file, readTags ? Ids.of(c, s.path) : Ids.cached(c, s.path));
        }
        return m;
    }
}
