package io.github.emordnilap67.mymusic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The play order. Plain Java on purpose (no Android parts), so it can be
 * tested anywhere. A song is named by its key: "Playlist/File.mp3".
 *
 * One shuffle never repeats a file, but three things brought
 * songs back long before the whole playlist had played:
 *   1. every tap on a song, Play / Shuffle, or shuffle off and on made a
 *      brand-new shuffle - everything heard so far went back in the draw;
 *   2. two files of one song (two uploads of it) both played in one pass;
 *   3. after a full round, the new round was not saved, so a restart went
 *      back to the old round's order.
 * Now shuffle remembers what has been heard (kept across new shuffles and
 * restarts): songs not heard yet come first, the heard ones only after all
 * of the playlist has played, and then a new pass starts. A second copy of
 * a song (same name and artist, length within 3 s) is skipped in a pass
 * once the first has played. Remixes and sped-up versions are not copies:
 * their length differs.
 *
 * *   1. once a list had been cut down (Android's media index read while busy
 *      gave only part of the library, and the player dropped every song it
 *      did not see), it stayed cut down - shuffle went round the same few
 *      songs. The player now keeps songs still on the phone (PlayerService),
 *      and a list that is a whole playlist (src) follows that playlist: songs
 *      it lacks - new downloads too - join it (grow).
 *   2. after shuffle was turned off and on, the songs already heard were put
 *      at the end of the new order and played again before the pass ended.
 *      With repeat on, a song already heard in the pass is now never played
 *      again in it: once only those are left, a new pass starts.
 */
final class Queue {
    /** the order the songs were picked in (the list as sorted on screen) */
    final List<String> base = new ArrayList<>();
    /** the order they play in: base, or base shuffled */
    final List<String> order = new ArrayList<>();
    int i = -1;
    /**
     * Play next / Add to queue: songs picked to play before the rest of the
     * list, first one first. They are not part of the list; when they are
     * done, the list carries on after the song that was on when they started.
     */
    final List<String> upNext = new ArrayList<>();
    /** a queued song playing now (not in the list); null = the list's own song is on */
    String extra;
    /** the next time round in shuffle (made early, so "up next" can show it before it starts) */
    private List<String> round2;
    boolean shuffle;
    String repeat = "off";          // off | all | one
    private final Random rnd = new Random();

    /** songs heard in the current shuffle pass (kept by the player across new shuffles and restarts) */
    final Set<String> heard = new HashSet<>();
    /** key -> "same song" tag (tidied name | first artist) and its length, for spotting two copies of one song */
    private final Map<String, String> tags = new java.util.concurrent.ConcurrentHashMap<>();     // filled in the background
    private final Map<String, Long> lens = new java.util.concurrent.ConcurrentHashMap<>();
    boolean knows(String key) {
        return lens.containsKey(key);
    }
    /** songs played since this order began (their copies are skipped) */
    private final List<String> played = new ArrayList<>();
    /** goes up each time a shuffle starts over after the whole list (the player saves the new order) */
    int rounds;
    /** the playlist this list is all of (it follows that playlist), or null (favourites, one song, ...) */
    String src;
    /** the furthest place Next has reached in this order - songs before it were played on purpose (Previous) */
    private int far = -1;

    /** a song moved to another playlist (its key changed) keeps its place everywhere in the list */
    void rename(String from, String to) {
        swap(base, from, to);
        swap(order, from, to);
        swap(upNext, from, to);
        swap(played, from, to);
        if (round2 != null) swap(round2, from, to);
        if (from.equals(extra)) extra = to;
        if (heard.remove(from)) heard.add(to);
        String t = tags.remove(from);
        if (t != null) tags.put(to, t);
        Long l = lens.remove(from);
        if (l != null) lens.put(to, l);
    }

    private static void swap(List<String> l, String a, String b) {
        for (int k = 0; k < l.size(); k++) if (a.equals(l.get(k))) l.set(k, b);
    }

    /** what a song is, for copies: its tidied name + artist, and its length */
    void info(String key, String tag, long ms) {
        if (tag != null && !tag.isEmpty()) tags.put(key, tag);
        lens.put(key, ms);
    }

    /** two files of one song (same name and artist, length within 3 s) */
    boolean copies(String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        String ta = tags.get(a), tb = tags.get(b);
        if (ta == null || !ta.equals(tb)) return false;
        Long la = lens.get(a), lb = lens.get(b);
        return la != null && lb != null && la > 0 && lb > 0 && Math.abs(la - lb) <= 3000;
    }

    private boolean copyOfAny(String k, List<String> those) {
        for (String p : those) if (copies(k, p)) return true;
        return false;
    }

    /** this song started - it counts as heard in the pass */
    void markHeard(String key) {
        if (key == null) return;
        heard.add(key);
        if (!played.contains(key)) played.add(key);
    }

    String current() {
        return extra != null ? extra : listCurrent();
    }

    /** the list's song at the play position (the one the list carries on from) */
    String listCurrent() {
        return i >= 0 && i < order.size() ? order.get(i) : null;
    }

    /** "Play next": right after the song that is on now */
    void playNext(String key) {
        upNext.add(0, key);
    }

    /** "Add to queue": after the songs already queued */
    void addToQueue(String key) {
        upNext.add(key);
    }

    /** take one queued song off (n = its place), or all of them (n < 0) */
    void unqueue(int n) {
        if (n < 0) upNext.clear();
        else if (n < upNext.size()) upNext.remove(n);
    }

    int size() {
        return order.size();
    }

    /** a new list to play, starting at base[start] */
    void set(List<String> keys, int start, boolean shuf) {
        extra = null;                   // the queue itself stays, like Spotify
        round2 = null;
        played.clear();
        base.clear();
        base.addAll(keys);
        order.clear();
        shuffle = shuf;
        if (base.isEmpty()) {
            i = -1;
            return;
        }
        start = Math.max(0, Math.min(start, base.size() - 1));
        if (shuf) {
            order.addAll(shuffled(base.get(start)));
            i = 0;
        } else {
            order.addAll(base);
            i = start;
        }
        far = i;
    }

    /**
     * songs of the whole playlist (all) that the list lacks join it - in
     * shuffle at random places among the songs still to come, otherwise at the
     * end. Returns how many joined.
     */
    int grow(List<String> all) {
        Set<String> have = new HashSet<>(base);
        List<String> add = new ArrayList<>();
        for (String k : all) if (have.add(k)) add.add(k);
        if (add.isEmpty()) return 0;
        base.addAll(add);
        round2 = null;
        if (shuffle) {
            Collections.shuffle(add, rnd);
            int from = Math.max(0, Math.min(order.size(), i + 1));
            for (String k : add) order.add(from + rnd.nextInt(order.size() - from + 1), k);
        } else {
            order.addAll(add);
        }
        if (i < 0) i = 0;
        return add.size();
    }

    /** shuffle with repeat on - a song already heard in this pass is passed over (place j of order) */
    private boolean spent(int j, String k) {
        return shuffle && !"off".equals(repeat) && j > far && heard.contains(k);
    }

    /** base in random order; first (if given) stays at the front */
    private List<String> shuffled(String first) {
        return shuffled(first, false);
    }

    /**
     * base in random order, first (if given) at the front, then the
     * songs not heard yet in this pass, then the ones heard already (and
     * second copies of songs). newPass = the whole list is starting over:
     * nothing counts as heard (nothing is changed here - next() clears it).
     */
    private List<String> shuffled(String first, boolean newPass) {
        boolean fresh = newPass;
        if (!fresh) {                    // everything heard already: this is a new pass too
            fresh = true;
            for (String k : base) if (!k.equals(first) && !heard.contains(k)) { fresh = false; break; }
            if (fresh) heard.removeAll(base);
        }
        List<String> now = new ArrayList<>(), later = new ArrayList<>(), heardHere = new ArrayList<>();
        if (!fresh) for (String k : base) if (heard.contains(k)) heardHere.add(k);
        for (String k : base) {
            if (k.equals(first)) continue;
            if (!fresh && (heard.contains(k) || copyOfAny(k, heardHere))) later.add(k);
            else now.add(k);
        }
        Collections.shuffle(now, rnd);
        Collections.shuffle(later, rnd);
        // a second copy of a song already in "now" waits until later
        List<String> a = new ArrayList<>(), keep = new ArrayList<>();
        if (first != null && base.contains(first)) { a.add(first); keep.add(first); }
        List<String> second = new ArrayList<>();
        for (String k : now) {
            if (copyOfAny(k, keep)) second.add(k);
            else { a.add(k); keep.add(k); }
        }
        a.addAll(later);
        a.addAll(second);
        return a;
    }

    /**
     * The song to play next, or null = the end (repeat off).
     * auto = the last song ended by itself (repeat one only applies then).
     */
    String next(boolean auto) {
        if (auto && "one".equals(repeat) && current() != null) return current();
        if (!upNext.isEmpty()) {        // a queued song goes first
            extra = upNext.remove(0);
            return extra;
        }
        extra = null;
        if (order.isEmpty()) return null;
        while (i + 1 < order.size()) {
            i++;
            // a second copy of a song already played in this pass is passed over (in shuffle)
            if (shuffle && copyOfAny(current(), played)) continue;
            if (spent(i, current())) continue;                   // heard already in this pass
            far = Math.max(far, i);
            return current();
        }
        if (!"off".equals(repeat)) {
            if (shuffle) {
                List<String> a = nextRound();
                round2 = null;
                order.clear();
                order.addAll(a);
                heard.removeAll(base);       // the whole list played - a new pass
                played.clear();
                rounds++;
            }
            i = 0;
            far = 0;
            return current();
        }
        return null;
    }

    private List<String> nextRound() {
        if (round2 == null) {
            round2 = shuffled(null, true);
            // the song that ended the round does not open the next one
            String last = order.isEmpty() ? null : order.get(order.size() - 1);
            if (round2.size() > 1 && round2.get(0).equals(last)) round2.add(round2.remove(0));
        }
        return round2;
    }

    /**
     * the songs Next would bring, in order, without moving anything: the
     * queue first, then the list after the song on now (round again when
     * repeat is on). Up to n of them.
     */
    List<String> upcoming(int n) {
        List<String> out = new ArrayList<>(upNext.subList(0, Math.min(n, upNext.size())));
        List<String> src = this.order;
        int j = i + 1;
        boolean wrapped = false;
        List<String> seen = new ArrayList<>(played);
        while (out.size() < n) {
            if (j >= 0 && j < src.size()) {
                String k = src.get(j++);
                if (shuffle && !wrapped && copyOfAny(k, seen)) continue;     // next() passes it over too
                if (!wrapped && spent(j - 1, k)) continue;                    // so does this
                out.add(k);
                seen.add(k);
                continue;
            }
            if ("off".equals(repeat) || wrapped || order.isEmpty()) break;
            src = shuffle ? nextRound() : order;
            j = 0;
            wrapped = true;
        }
        return out;
    }

    String prev() {
        if (extra != null) {            // back to the list's song; the queued one waits at the front again
            upNext.add(0, extra);
            extra = null;
            return listCurrent();
        }
        if (i > 0) i--;
        return current();
    }

    /** shuffle on/off without changing the song that is on now */
    void setShuffle(boolean on) {
        String cur = listCurrent();
        round2 = null;
        played.clear();
        if (cur != null) played.add(cur);
        shuffle = on;
        if (order.isEmpty()) return;
        List<String> a = on ? shuffled(cur) : new ArrayList<>(base);
        order.clear();
        order.addAll(a);
        i = on ? 0 : Math.max(0, order.indexOf(cur));
        far = i;
    }

    void setRepeat(String r) {
        repeat = "all".equals(r) || "one".equals(r) ? r : "off";
    }

    /**
     * forget songs that are no longer in the library; keep the list's current one (cur) if it is still there.
     * when cur itself went, the place moves to the song that came after it - the one
     * Up next was showing - instead of back to the first song of the order.
     */
    void keepOnly(Set<String> known, String cur) {
        int at = cur == null ? -1 : order.indexOf(cur), after = 0;     // where cur sat, and how many before it stay
        for (int k = 0; k < at; k++) if (known.contains(order.get(k))) after++;
        base.retainAll(known);
        order.retainAll(known);
        upNext.retainAll(known);
        heard.retainAll(known);
        played.retainAll(known);
        round2 = null;
        if (extra != null && !known.contains(extra)) extra = null;
        i = cur == null ? -1 : order.indexOf(cur);
        if (i < 0 && at >= 0) i = Math.min(after, order.size() - 1);  // the song after it (the last one if it was last)
        if (i < 0) i = order.isEmpty() ? -1 : 0;
        far = i;
    }
}
