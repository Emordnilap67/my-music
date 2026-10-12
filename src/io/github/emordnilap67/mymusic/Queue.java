package io.github.emordnilap67.mymusic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The play order. Plain Java on purpose (no Android parts), so it can be
 * tested anywhere. A song is named by its key: "Playlist/File.mp3".
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
    }

    /** base in random order; first (if given) stays at the front */
    private List<String> shuffled(String first) {
        List<String> a = new ArrayList<>(base);
        if (first != null) a.remove(first);
        Collections.shuffle(a, rnd);
        if (first != null) a.add(0, first);
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
        if (i + 1 < order.size()) {
            i++;
            return current();
        }
        if (!"off".equals(repeat)) {
            if (shuffle) {
                List<String> a = nextRound();
                round2 = null;
                order.clear();
                order.addAll(a);
            }
            i = 0;
            return current();
        }
        return null;
    }

    private List<String> nextRound() {
        if (round2 == null) round2 = shuffled(null);
        return round2;
    }

    /**
     * the songs Next would bring, in order, without moving anything: the
     * queue first, then the list after the song on now (round again when
     * repeat is on). Up to n of them.
     */
    List<String> upcoming(int n) {
        List<String> out = new ArrayList<>(upNext.subList(0, Math.min(n, upNext.size())));
        List<String> src = order;
        int j = i + 1;
        boolean wrapped = false;
        while (out.size() < n) {
            if (j >= 0 && j < src.size()) {
                out.add(src.get(j++));
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
        shuffle = on;
        if (order.isEmpty()) return;
        List<String> a = on ? shuffled(cur) : new ArrayList<>(base);
        order.clear();
        order.addAll(a);
        i = on ? 0 : Math.max(0, order.indexOf(cur));
    }

    void setRepeat(String r) {
        repeat = "all".equals(r) || "one".equals(r) ? r : "off";
    }

    /** forget songs that are no longer in the library; keep the list's current one (cur) if it is still there */
    void keepOnly(Set<String> known, String cur) {
        base.retainAll(known);
        order.retainAll(known);
        upNext.retainAll(known);
        round2 = null;
        if (extra != null && !known.contains(extra)) extra = null;
        i = cur == null ? -1 : order.indexOf(cur);
        if (i < 0) i = order.isEmpty() ? -1 : 0;
    }
}
