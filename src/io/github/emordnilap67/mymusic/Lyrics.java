package io.github.emordnilap67.mymusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Lyrics, looked up in order: LRCLIB (lrclib.net, a free lyrics library - mostly timed lines), lyrics
 * already in the song file, LRCLIB again allowing a lyric video's longer cut, the words in the video's
 * description, another version of the song in LRCLIB, and the video's own captions. Each answer is kept
 * on the phone; lyrics found are also written into the song file (Embed).
 *
 * Words found without times get them from the video's captions (Align). Timed lyrics from LRCLIB are laid
 * beside the library's other uploads of the same song and length (Align.odd): when those agree with each
 * other and not with the answer - an upload timed to a music video's intro, say - the better-timed one is
 * used. Lyrics kept from before are checked once, the next time their song plays.
 */
final class Lyrics {
    interface Done {
        void got(String json);
    }

    private static final ExecutorService BG = Executors.newSingleThreadExecutor(Low.THREADS);
    private static final String API = "https://lrclib.net/api/";
    private static final String AGENT = "CarmenMusicum/5.1 (open-source Android music player)";
    private static final long RETRY_NONE_MS = 3L * 24 * 3600 * 1000;
    private static final long RETRY_SOON_MS = 6L * 3600 * 1000;
    /** captions found while the library was busy: look in the library again after this */
    private static final long RECHECK_MS = 2L * 60 * 1000;
    /** machine-made lyrics (the phone's own, YouTube's automatic captions): ask the library again after this */
    private static final long MACHINE_RECHECK_MS = 7L * 24 * 3600 * 1000;
    /** untimed lyrics: the video's captions are asked for their timing again after this */
    private static final long TIME_RETRY_MS = 7L * 24 * 3600 * 1000;
    /**
     * answers kept before this version are asked again: "none" from the
     * older, narrower search; YouTube's automatic captions get one look in
     * the library with the new search
     */
    private static final int V = 26;
    private static final Pattern LRC = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{2}([.:]\\d{1,3})?\\]");
    private static final Pattern NO_WORDS = Pattern.compile("(?i)\\b(instrumental|karaoke|backing track|no vocals)\\b");
    /** the song asked about last: older questions still waiting are dropped (skipping through songs) */
    private static volatile String latest;

    /** the lyrics site answered, but not with lyrics (busy, rate limit, error page) */
    private static final class Busy extends Exception {
        Busy(String m) {
            super(m);
        }
    }

    private Lyrics() {}

    static void get(final Context c, final String key, final Done done) {
        get(c, key, false, done);
    }

    /** fresh = ask again even if an answer is kept (after "Search by name") */
    static void get(final Context c, final String key, final boolean fresh, final Done done) {
        latest = key;
        BG.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(latest)) return;
                String out;
                try {
                    out = find(c, key, fresh, done);
                } catch (Exception e) {
                    out = page(key, "offline", null, null, null, null);
                }
                if (out != null) done.got(out);
            }
        });
    }

    /** the name this song's lyrics are looked up by (a fix by name wins) */
    private static LyricsQuery query(Context c, Library.Song s) {
        String[] fix = LyricsFix.get(c, s.key);
        return fix != null ? LyricsQuery.forLyrics(fix[0], fix[1]) : LyricsQuery.forLyrics(s.title, s.artist);
    }

    /** where this song's answer is kept on the phone */
    private static File cacheFile(Context c, Library.Song s, LyricsQuery q) {
        File dir = new File(c.getFilesDir(), "lyrics");
        dir.mkdirs();
        return new File(dir, Art.sha1((q.artist + "|" + q.track + "|" + Math.round(s.durMs / 1000.0)).toLowerCase()) + ".json");
    }

    /** a kept answer worth writing into the song file */
    private static String fileText(JSONObject k) {
        if (k == null || !"ok".equals(k.optString("status"))) return null;
        String src = k.optString("src", "lrclib");
        if ("file".equals(src)) return null;                         // already in the file (machine-made ones go in too)
        String sy = k.optString("synced", null), pl = k.optString("plain", null);
        String t = sy != null && !sy.trim().isEmpty() ? sy : pl;
        return t == null || t.trim().isEmpty() ? null : t;
    }

    /**
     * the lyrics to put inside this song's MP3 - the kept answer, timed
     * if there are timed ones - or null (none found, machine captions, or
     * they came from the file already)
     */
    static String forFile(Context c, Library.Song s) {
        JSONObject k = read(cacheFile(c, s, query(c, s)));
        String t = fileText(k);
        if (t != null || k != null && "ok".equals(k.optString("status")) && !"heard".equals(k.optString("src"))) return t;
        return Heard.get(c, s);                                      // never played yet, but the phone has written its words
    }

    private static boolean found(String status) {
        return "ok".equals(status) || "instrumental".equals(status);
    }

    private static String find(Context c, final String key, boolean fresh, Done done) throws Exception {
        Library.Song s = Library.get(c).byKey.get(key);
        if (s == null) return page(key, "none", null, null, null, null);
        double secs = s.durMs / 1000.0;
        LyricsQuery q = query(c, s);
        List<LyricsQuery> asks = q.candidates();
        LyricsQuery likely = asks.get(0);
        File f = cacheFile(c, s, q);
        long now = System.currentTimeMillis();

        JSONObject kept = fresh ? null : read(f);
        long ccAt = 0;
        if (kept != null) {
            String st = kept.optString("status");
            String ksrc = kept.optString("src", "lrclib");
            long age = now - kept.optLong("at");
            if ("busy".equals(st)) {
                ccAt = kept.optLong("ccAt");          // the library was busy last time: ask it again, YouTube not again so soon
            } else {
                boolean keep = !"none".equals(st)
                        || kept.optInt("v") >= V && age < (kept.optBoolean("soon") ? RETRY_SOON_MS : RETRY_NONE_MS);
                if (keep && "none".equals(st) && Heard.get(c, s) != null) keep = false;     // the phone has written some since
                boolean machine = "heard".equals(ksrc) || "auto".equals(ksrc);
                boolean recheck = "ok".equals(st)
                        && (kept.optBoolean("recheck") && age > RECHECK_MS || "auto".equals(ksrc) && kept.optInt("v") < V
                        || machine && age > MACHINE_RECHECK_MS);
                if (recheck) return upgrade(c, key, f, kept, asks, secs, likely, done);
                if (keep && "ok".equals(st) && kept.optString("synced", null) == null && kept.optString("plain", null) != null
                        && key.equals(latest)) {
                    String timed = timeKept(c, key, f, kept, s, likely, now, done);
                    if (timed != null) return timed;
                }
                if (keep && "ok".equals(st) && "lrclib".equals(ksrc) && kept.optString("synced", null) != null
                        && !kept.optBoolean("loose") && kept.optString("timedBy", "").isEmpty() && kept.optLong("agreed") == 0
                        && key.equals(latest)) {
                    return agreeKept(c, key, f, kept, likely, secs, done);       // once, as it plays
                }
                if (keep) {
                    String ft = fileText(kept);
                    if (ft != null && !Embed.isIn(c, key, ft)) Embed.mark(c, key);     // not in the file yet
                    return pageOf(c, key, kept, likely);
                }
            }
        }

        // 1 + 3: the lyrics library, strict on length first, then allowing a lyric video's extra seconds
        Map<String, String> memo = new HashMap<>();
        JSONObject hit = null;
        boolean loose = false, siteDown = false, busy = false;
        long agreedAt = 0;
        try {
            for (LyricsQuery k : asks) {
                hit = lookup(k, secs, memo, 6, true);
                if (hit == null) continue;
                try {
                    JSONObject better = agreed(k, secs, hit, memo);
                    if (better != null) hit = better;
                    agreedAt = now;
                } catch (Exception e) {
                    // the library stopped answering: these stand, laid beside the others when the song next plays
                }
                break;
            }
        } catch (Busy e) {
            busy = true;
        } catch (Exception e) {
            siteDown = true;
        }
        String status = "none", synced = null, plain = null, src = null, fromT = null, fromA = null;
        boolean soon = false;
        if (hit != null) {
            if (hit.optBoolean("instrumental")) {
                status = "instrumental";
            } else {
                synced = text(hit, "syncedLyrics");
                plain = text(hit, "plainLyrics");
                if (synced != null || plain != null) status = "ok";
            }
            src = "lrclib";
        }
        if (!found(status)) {
            String h = Heard.get(c, s);
            if (h != null) {
                synced = h;
                status = "ok";
                src = "heard";
            }
        }
        // 2: lyrics already in the file; 4: the video's description
        Id3.Tags tags = null;
        if (!found(status)) {
            tags = Id3.read(s.path == null ? null : new File(s.path), true);
            if (tags.lyrics != null) {
                if (LRC.matcher(tags.lyrics).find()) synced = tags.lyrics;
                else plain = tags.lyrics;
                status = "ok";
                src = "file";
            }
        }
        String looseSynced = null;                                       // its own times, for another cut
        if (!found(status) && !siteDown && !busy) {
            for (LyricsQuery k : asks) {
                JSONObject h = lookup(k, secs, memo, 30, false);         // only what the library already said
                String pl = untimed(h);
                if (pl != null) {
                    plain = pl;
                    status = "ok";
                    src = "lrclib";
                    loose = true;
                    looseSynced = text(h, "syncedLyrics");
                    break;
                }
            }
        }
        if (!found(status) && tags != null) {
            String d = DescLyrics.find(tags.description, likely.track, likely.artist);
            if (d != null) {
                plain = d;
                status = "ok";
                src = "desc";
            }
        }
        // 5: another version of the song in the library (untimed) - never for an instrumental or karaoke version
        if (!found(status) && !siteDown && !busy && !NO_WORDS.matcher(s.title + " " + s.file).find()) {
            try {
                JSONObject o = other(likely, memo);
                String pl = untimed(o);
                if (pl != null) {
                    plain = pl;
                    status = "ok";
                    src = "other";
                    fromT = o.optString("trackName", "");
                    fromA = o.optString("artistName", "");
                }
            } catch (Busy e) {
                busy = true;
            } catch (Exception e) {
                siteDown = true;
            }
        }
        // 6: the video's captions (slow: a few seconds to start yt-dlp) - not again within 6 hours while the library is busy
        boolean ccNone = false;
        if (!found(status) && tags != null && !tags.id.isEmpty() && Ytdl.available(c) && key.equals(latest)
                && (ccAt == 0 || now - ccAt > RETRY_SOON_MS)) {
            done.got(page(key, "loading", null, null, "cc", likely));
            try {
                Captions.Result r = Captions.fetch(c, tags.id, new Captions.Cancel() {
                    @Override
                    public boolean now() {
                        return !key.equals(latest);
                    }
                });
                if (r != null) {
                    synced = r.lrc;
                    status = "ok";
                    src = r.auto ? "auto" : "cc";
                } else {
                    ccNone = true;
                }
            } catch (Captions.Stopped e) {
                return null;                                   // another song now: this answer is not wanted
            } catch (Captions.Held e) {
                soon = true;
            } catch (Exception e) {
                soon = true;
            }
        }
        // words without times - timed from the phone's own listening or the video's captions
        String timedBy = null;
        boolean timeTried = false;
        if ("ok".equals(status) && synced == null && plain != null && key.equals(latest)) {
            String sid = tags != null ? tags.id : Id3.read(s.path == null ? null : new File(s.path), true).id;
            done.got(page(key, "ok", null, plain, src, likely));         // the words now, their times as soon as they are worked out
            String[] t = timeFor(c, s, key, plain, looseSynced, sid);
            timeTried = true;
            if (t == STOPPED) return null;
            if (t != null) {
                synced = t[0];
                timedBy = t[1];
            }
        }
        if (!found(status) && (siteDown || busy)) {
            // nothing kept as "none": the library did not really answer. Remember only that YouTube had nothing.
            long cc = ccNone ? now : ccAt;
            if (cc > 0) {
                JSONObject b = new JSONObject();
                b.put("v", V);
                b.put("status", "busy");
                b.put("at", now);
                b.put("ccAt", cc);
                write(f, b);
            }
            return page(key, siteDown ? "offline" : "busy", null, null, null, likely);
        }
        JSONObject keep = new JSONObject();
        keep.put("v", V);
        keep.put("status", status);
        keep.put("at", now);
        if (soon) keep.put("soon", true);
        if (src != null) keep.put("src", src);
        if (loose) keep.put("loose", true);
        if (synced != null) keep.put("synced", synced);
        if (plain != null) keep.put("plain", plain);
        if (agreedAt > 0 && synced != null && "lrclib".equals(src) && !loose) keep.put("agreed", agreedAt);
        if (timedBy != null) keep.put("timedBy", timedBy);
        else if (timeTried) keep.put("tt", now);
        if (timedBy == null && looseSynced != null) keep.put("ownTimes", looseSynced);    // for a timing found later
        if (fromT != null) keep.put("fromTrack", fromT);
        if (fromA != null) keep.put("fromArtist", fromA);
        if ((siteDown || busy) && "ok".equals(status)) keep.put("recheck", true);     // the library may have better ones
        write(f, keep);
        if (fileText(keep) != null) Embed.mark(c, key);              // into the song file too
        return pageOf(c, key, keep, likely);
    }

    /**
     * lyrics found while the library was busy (or YouTube's automatic
     * captions from before ): show them now, then look in the library
     * once more and send its lyrics if it has them for this very song.
     */
    private static String upgrade(Context c, String key, File f, JSONObject kept, List<LyricsQuery> asks, double secs,
                                  LyricsQuery likely, Done done) throws Exception {
        done.got(pageOf(c, key, kept, likely));
        String was = kept.optString("src", "");
        boolean captions = "auto".equals(was) || "cc".equals(was) || "heard".equals(was);
        Map<String, String> memo = new HashMap<>();
        JSONObject hit = null;
        boolean loose = false;
        try {
            for (LyricsQuery k : asks) {
                hit = lookup(k, secs, memo, 6, true);
                if (hit != null) break;
            }
            if (hit == null && captions) {
                for (LyricsQuery k : asks) {
                    JSONObject h = lookup(k, secs, memo, 30, false);
                    if (untimed(h) != null) {
                        hit = h;
                        loose = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return null;                    // still busy or offline: keep what there is, look again next time
        }
        String sy = hit == null || loose ? null : text(hit, "syncedLyrics");
        String pl = hit == null ? null : loose ? untimed(hit) : text(hit, "plainLyrics");
        String tb = null;
        String machine = captions ? kept.optString("synced", null) : null;
        if (sy == null && pl != null && machine != null) {
            // the library's words, the machine's timing (they were timed before - they stay timed)
            String t = loose ? Align.shift(text(hit, "syncedLyrics"), machine) : null;
            if (t == null) t = Align.time(pl, machine);
            if (t != null) {
                sy = t;
                tb = was;
            }
        }
        if (hit != null && !hit.optBoolean("instrumental") && (sy != null || pl != null)) {
            JSONObject keep = new JSONObject();
            keep.put("v", V);
            keep.put("status", "ok");
            keep.put("at", System.currentTimeMillis());
            keep.put("src", "lrclib");
            if (loose) keep.put("loose", true);
            if (sy != null) keep.put("synced", sy);
            if (pl != null) keep.put("plain", pl);
            if (tb != null) keep.put("timedBy", tb);
            write(f, keep);
            Embed.mark(c, key);                                      // the better lyrics replace the ones in the file
            return pageOf(c, key, keep, likely);
        }
        kept.put("v", V);
        kept.remove("recheck");
        kept.put("at", System.currentTimeMillis());          // machine-made ones are asked again a week from now
        write(f, kept);
        return null;
    }

    /**
     * the library's timed answer laid beside its other uploads of this song at this length (8 at
     * most, closest length first): the upload to take instead when the others agree it is off, else
     * null. Throws when the library does not answer.
     */
    private static JSONObject agreed(LyricsQuery q, double secs, JSONObject hit, Map<String, String> memo) throws Exception {
        String sy = text(hit, "syncedLyrics");
        if (sy == null || secs <= 0 || q.artist.isEmpty() || q.simpleTrack.trim().isEmpty()) return null;
        String firstArtist = q.simpleArtist.isEmpty() ? q.artist : q.simpleArtist;
        String body = http(API + "search?track_name=" + enc(q.simpleTrack) + "&artist_name=" + enc(firstArtist), memo, true);
        if (body == null) return null;
        List<LyricsQuery.Hit> hs = new ArrayList<>();
        hits(body, hs);
        final long id = hit.optLong("id", -1);
        List<JSONObject> near = new ArrayList<>();
        for (LyricsQuery.Hit h : hs) {
            JSONObject o = (JSONObject) h.raw;
            String s = text(o, "syncedLyrics");
            if (h.instrumental || s == null || Math.abs(h.duration - secs) > 3 || id >= 0 && o.optLong("id", -2) == id || s.equals(sy)) continue;
            near.add(o);
        }
        final double want = secs;
        java.util.Collections.sort(near, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(Math.abs(a.optDouble("duration", 0) - want), Math.abs(b.optDouble("duration", 0) - want));
            }
        });
        List<String> others = new ArrayList<>();
        for (int i = 0; i < near.size() && i < 8; i++) others.add(text(near.get(i), "syncedLyrics"));
        int pick = Align.odd(sy, others);
        return pick < 0 ? null : near.get(pick);
    }

    /**
     * timed library lyrics kept from before as their song plays: shown at once, then laid
     * beside the other uploads; better-timed ones replace them (and the ones in the song file).
     * Returns the new answer, or null (nothing more to send).
     */
    private static String agreeKept(Context c, String key, File f, JSONObject kept, LyricsQuery likely, double secs,
                                    Done done) throws Exception {
        done.got(pageOf(c, key, kept, likely));
        JSONObject was = new JSONObject();
        was.put("syncedLyrics", kept.optString("synced"));
        JSONObject better;
        try {
            better = agreed(likely, secs, was, new HashMap<String, String>());
        } catch (Exception e) {
            return null;                                             // busy or offline: the next time it plays
        }
        kept.put("agreed", System.currentTimeMillis());
        if (better != null) {
            kept.put("synced", text(better, "syncedLyrics"));
            String pl = text(better, "plainLyrics");
            if (pl != null) kept.put("plain", pl);
        }
        write(f, kept);
        if (better == null) return null;
        Embed.mark(c, key);                                          // the better-timed lyrics go into the song file too
        return pageOf(c, key, kept, likely);
    }

    /** timeFor() when the song changed meanwhile - the answer is not wanted */
    private static final String[] STOPPED = new String[0];

    /**
     * times for untimed words: {timed lyrics, "heard" / "cc" / "auto"},
     * null when no timed source fits, STOPPED when another song was asked for
     */
    private static String[] timeFor(Context c, Library.Song s, final String key, String plain, String ownTimes, String videoId) {
        String heard = Heard.get(c, s);
        if (heard != null) {
            String t = ownTimes != null ? Align.shift(ownTimes, heard) : null;
            if (t == null) t = Align.time(plain, heard);
            if (t != null) return new String[]{t, "heard"};
        }
        if (videoId == null || videoId.isEmpty() || !Ytdl.available(c) || !key.equals(latest)) return null;
        try {
            Captions.Result r = Captions.fetch(c, videoId, new Captions.Cancel() {
                @Override
                public boolean now() {
                    return !key.equals(latest);
                }
            });
            if (r == null || r.lrc == null) return null;
            String t = ownTimes != null ? Align.shift(ownTimes, r.lrc) : null;
            if (t == null) t = Align.time(plain, r.lrc);
            return t == null ? null : new String[]{t, r.auto ? "auto" : "cc"};
        } catch (Captions.Stopped e) {
            return STOPPED;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * a kept answer with words but no times, as its song plays: the
     * words go to the page at once; then the phone's own listening (always
     * looked at - it may have been written since) and, once a week at most,
     * the video's captions. Returns the timed answer, or null (nothing more
     * to send).
     */
    private static String timeKept(Context c, String key, File f, JSONObject kept, Library.Song s, LyricsQuery likely,
                                   long now, Done done) throws Exception {
        boolean captionsDue = now - kept.optLong("tt", 0) > TIME_RETRY_MS;
        if (!captionsDue && Heard.get(c, s) == null) return null;
        done.got(pageOf(c, key, kept, likely));
        String sid = captionsDue ? Id3.read(s.path == null ? null : new File(s.path), true).id : null;
        String[] t = timeFor(c, s, key, kept.optString("plain"), kept.optString("ownTimes", null), sid);
        if (t == STOPPED) return null;
        if (t == null) {
            if (captionsDue) {
                kept.put("tt", now);
                write(f, kept);
            }
            return null;
        }
        kept.put("synced", t[0]);
        kept.put("timedBy", t[1]);
        kept.remove("tt");
        kept.remove("ownTimes");
        write(f, kept);
        Embed.mark(c, key);                                          // the timed words go into the song file too
        return pageOf(c, key, kept, likely);
    }

    /** a library answer's words without times (for a lyric video's longer length, or another version); null if none */
    private static String untimed(JSONObject h) {
        if (h == null || h.optBoolean("instrumental")) return null;
        String sy = text(h, "syncedLyrics"), pl = text(h, "plainLyrics");
        if (pl != null) return pl;
        if (sy == null) return null;
        String x = LRC.matcher(sy).replaceAll("").replaceAll("<\\d+:\\d{1,2}(?:[.:]\\d{1,3})?>", "")
                .replaceAll("(?m)^\\s+", "").trim();
        return x.isEmpty() ? null : x;
    }

    /**
     * the library's answer for this song, or null. net = may ask the site;
     * otherwise only answers it already gave (memo) are looked through.
     */
    private static JSONObject lookup(LyricsQuery q, double secs, Map<String, String> memo, double slack, boolean net) throws Exception {
        if (q.track.isEmpty()) return null;
        String firstArtist = q.simpleArtist.isEmpty() ? q.artist : q.simpleArtist;
        if (!q.artist.isEmpty() && net) {
            // exact asks: as tagged, then the barest form ("I Don't like Monday" by Piccadilly Corner)
            String[][] exact = {{q.track, q.artist}, {q.simpleTrack, firstArtist}};
            for (int i = 0; i < exact.length; i++) {
                if (i == 1 && exact[1][0].equals(exact[0][0]) && exact[1][1].equals(exact[0][1])) break;
                String u = API + "get?track_name=" + enc(exact[i][0]) + "&artist_name=" + enc(exact[i][1])
                        + (secs > 0 ? "&duration=" + Math.round(secs) : "");
                String body = http(u, memo, true);
                if (body != null) {
                    JSONObject o = new JSONObject(body);
                    if (text(o, "syncedLyrics") != null || text(o, "plainLyrics") != null || o.optBoolean("instrumental")) return o;
                }
            }
        }
        // search: by name + first artist (the bare name - the library matches whole words in order), then plain words
        List<String> urls = new ArrayList<>();
        if (!q.artist.isEmpty()) urls.add(API + "search?track_name=" + enc(q.simpleTrack) + "&artist_name=" + enc(firstArtist));
        String words = q.words();
        if (!words.isEmpty()) urls.add(API + "search?q=" + enc(words));
        for (int i = 0; i < urls.size(); i++) {
            String body = http(urls.get(i), memo, net);
            if (body == null) continue;
            List<LyricsQuery.Hit> hits = new ArrayList<>();
            hits(body, hits);
            LyricsQuery.Hit best = q.best(hits, secs, true, slack);
            if (best == null && i == urls.size() - 1 && q.artist.isEmpty()) best = q.best(hits, secs, false, slack);
            if (best != null) return (JSONObject) best.raw;
        }
        return null;
    }

    /**
     * another version of the song in the library, any length - the
     * original of a remix (by one of the same artists), or for a cover with
     * a distinctive name, the version most entries agree on. Looks through
     * what the library already said first, then asks by the name alone and
     * by its plural twin ("Monday" / "Mondays").
     */
    private static JSONObject other(LyricsQuery q, Map<String, String> memo) throws Exception {
        if (q.simpleTrack.trim().isEmpty()) return null;
        List<LyricsQuery.Hit> seen = new ArrayList<>();
        for (Map.Entry<String, String> e : memo.entrySet()) {
            if (!e.getKey().startsWith(API + "search?") || NOT_FOUND.equals(e.getValue())) continue;
            hits(e.getValue(), seen);
        }
        LyricsQuery.Hit h = q.other(seen, false);
        if (h != null) return (JSONObject) h.raw;
        for (String name : new String[]{q.simpleTrack, q.distinctive() ? q.plural() : null}) {
            if (name == null || name.trim().isEmpty()) continue;
            String body = http(API + "search?track_name=" + enc(name.trim()), memo, true);
            if (body == null) continue;
            List<LyricsQuery.Hit> got = new ArrayList<>();
            hits(body, got);
            h = q.other(got, true);
            if (h != null) return (JSONObject) h.raw;
        }
        return null;
    }

    private static void hits(String body, List<LyricsQuery.Hit> into) {
        try {
            JSONArray a = new JSONArray(body);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                into.add(new LyricsQuery.Hit(o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0),
                        text(o, "syncedLyrics") != null, text(o, "plainLyrics") != null, o.optBoolean("instrumental"), o));
            }
        } catch (Exception ignored) {
            // not a list (an exact answer kept under a search address cannot happen; a broken body can)
        }
    }

    private static String text(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String s = o.optString(k, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static final String NOT_FOUND = "\u0000404";

    /**
     * body of a 200 answer; null for 404 (or not asked yet, when net is off);
     * throws when offline / the site is down. "busy" (503, 429) is asked
     * again first - the site says to retry in a second (three times,
     * waiting 1, 3 and 5 s).
     */
    private static String http(String u, Map<String, String> memo, boolean net) throws Exception {
        if (memo.containsKey(u)) {
            String m = memo.get(u);
            return NOT_FOUND.equals(m) ? null : m;
        }
        if (!net) return null;
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            h.setRequestProperty("User-Agent", AGENT);
            h.setRequestProperty("Accept", "application/json");
            try {
                int code = h.getResponseCode();
                if (code == 404) {
                    memo.put(u, NOT_FOUND);
                    return null;
                }
                if ((code == 503 || code == 429) && attempt < 3) {
                    int wait = Math.max(1, Math.min(4, h.getHeaderFieldInt("Retry-After", 1)));
                    Thread.sleep(wait * 1000L + attempt * 2000L);
                    continue;
                }
                if (code != 200) throw new Busy("lyrics site answered " + code);
                InputStream in = h.getInputStream();
                try {
                    String body = new String(readAll(in), "UTF-8");
                    memo.put(u, body);
                    return body;
                } finally {
                    in.close();
                }
            } finally {
                h.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static JSONObject read(File f) {
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                return new JSONObject(new String(readAll(in), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, JSONObject o) {
        File tmp = new File(f.getPath() + ".part");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            tmp.delete();
        }
    }

    /** a kept answer, as the page gets it */
    private static String pageOf(Context c, String key, JSONObject k, LyricsQuery q) {
        String p = page(key, k.optString("status", "none"), k.optString("synced", null), k.optString("plain", null),
                k.optString("src", "lrclib"), q);
        String ft = k.optString("fromTrack", ""), fa = k.optString("fromArtist", "");
        boolean again = k.optBoolean("recheck");
        String ft2 = fileText(k);
        boolean inFile = ft2 != null && Embed.isIn(c, key, ft2);
        String tb = k.optString("timedBy", "");
        if (ft.isEmpty() && fa.isEmpty() && !again && !inFile && tb.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(p);
            if (!tb.isEmpty()) o.put("timedBy", tb);
            if (inFile) o.put("inFile", true);                   // the page says it is saved in the song
            if (!ft.isEmpty() || !fa.isEmpty()) {
                o.put("fromTrack", ft);
                o.put("fromArtist", fa);
            }
            if (again) o.put("recheck", true);      // the page asks the library again in 2 minutes
            return o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            return p;
        }
    }

    /** what the page gets: the lyrics, where they came from, and the name they were looked up by */
    private static String page(String key, String status, String synced, String plain, String src, LyricsQuery q) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("status", status);
            if (synced != null) o.put("synced", synced);
            if (plain != null) o.put("plain", plain);
            if (src != null) o.put("src", src);
            if (q != null) {
                o.put("track", q.track);
                o.put("artist", q.artist);
            }
            // two line-break characters JavaScript text must not hold raw
            return o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            return "{\"status\":\"none\"}";
        }
    }
}
