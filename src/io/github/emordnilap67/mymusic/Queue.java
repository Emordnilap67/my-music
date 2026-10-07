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
    boolean shuffle;
    String repeat = "off";          // off | all | one
    private final Random rnd = new Random();

    String current() {
        return i >= 0 && i < order.size() ? order.get(i) : null;
    }

    int size() {
        return order.size();
    }

    /** a new list to play, starting at base[start] */
    void set(List<String> keys, int start, boolean shuf) {
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
        if (order.isEmpty()) return null;
        if (auto && "one".equals(repeat)) return current();
        if (i + 1 < order.size()) {
            i++;
            return current();
        }
        if (!"off".equals(repeat)) {
            if (shuffle) {
                List<String> a = shuffled(null);
                order.clear();
                order.addAll(a);
            }
            i = 0;
            return current();
        }
        return null;
    }

    String prev() {
        if (i > 0) i--;
        return current();
    }

    /** shuffle on/off without changing the song that is on now */
    void setShuffle(boolean on) {
        String cur = current();
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

    /** forget songs that are no longer in the library; keep the current one if it is still there */
    void keepOnly(Set<String> known, String cur) {
        base.retainAll(known);
        order.retainAll(known);
        i = cur == null ? -1 : order.indexOf(cur);
        if (i < 0) i = order.isEmpty() ? -1 : 0;
    }
}
