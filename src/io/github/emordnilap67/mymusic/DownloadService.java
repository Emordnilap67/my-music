package io.github.emordnilap67.mymusic;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService extends Service {
    static final String A_START = "io.github.emordnilap67.mymusic.DL_START";
    static final String A_STOP = "io.github.emordnilap67.mymusic.DL_STOP";
    static final String A_RESUME = "io.github.emordnilap67.mymusic.DL_RESUME";
    /** Sync's quiet check of a linked YouTube playlist for new songs */
    static final String A_SYNC = "io.github.emordnilap67.mymusic.DL_SYNC";
    /** the card's name while a new playlist is being read (its real name comes from YouTube) */
    static final String READING = "New playlist";

    static final int FG_ID = 198;                  // one fixed id for the running notification
    private static final long WAIT_MS = 60L * 60 * 1000;           // YouTube's pause: try again in an hour
    private final LinkedList<String[]> jobs = new LinkedList<>();
    private int lastStartId;
    private volatile String limitNote;
    /*
     * Up to AT_ONCE links download side by side, each in a
     * worker of its own with its own state (Now) and its own work folder. Never two into the same playlist at
     * once - its archive and its waiting list would cross - so a second link for a playlist waits for the first.
     * One quiet YouTube check (Sync) at a time, so the background checks don't add to YouTube's load.
     */
    static final int AT_ONCE = 3;
    private int workers;                                     // worker threads alive (guarded by jobs)
    private final List<Now> busy = new ArrayList<>();        // the downloads in hand (guarded by jobs)
    private final ThreadLocal<Now> mine = new ThreadLocal<>();
    private final Map<String, int[]> counts = new LinkedHashMap<>();   // folder -> {done, total}: the one notification

    /** one download in hand */
    private static final class Now {
        String[] job;
        boolean quiet;                             // a Sync check - says something only when songs came in
        volatile Process proc;
        volatile boolean stopping;
        String lastFolder;                         // where the job really went
        String lastPend;                           // and what its songs wait under (see pendKey)
        String sourceNote;                         // what a Spotify / Deezer link could not give (said at the end)
        String hold;                               // the playlist it writes into - no other download may meanwhile
        int slot;                                  // its own work folder: dl, dl1, dl2
    }

    static void start(Context c, String link, String folder) {
        Intent i = new Intent(c, DownloadService.class).setAction(A_START).putExtra("link", link).putExtra("folder", folder);
        c.startForegroundService(i);
    }

    /** check a playlist's YouTube link for new songs, quietly (Sync) */
    static void sync(Context c, String link, String folder) {
        Intent i = new Intent(c, DownloadService.class).setAction(A_SYNC).putExtra("link", link).putExtra("folder", folder);
        c.startForegroundService(i);
    }

    static void resume(Context c) {
        c.startForegroundService(new Intent(c, DownloadService.class).setAction(A_RESUME));
    }

    static volatile boolean alive;

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
    }

    @Override
    public void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    /** Stop: ends what is running and forgets what was waiting */
    static void stop(Context c) {
        Pending.clear(c);
        Retry.cancel(c);
        if (!alive) return;                        // nothing running: don't wake it just to stop
        try {
            c.startService(new Intent(c, DownloadService.class).setAction(A_STOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        String folder = i == null ? null : i.getStringExtra("folder");
        String link = i == null ? null : i.getStringExtra("link");
        List<String[]> add = new ArrayList<>();
        if (A_RESUME.equals(a)) {
            add.addAll(Pending.toResume(this));       // what YouTube held back last time
        } else if (A_SYNC.equals(a) && link != null && folder != null) {
            add.add(new String[]{link, folder, "sync"});
        } else if (link != null && folder != null) {
            add.add(new String[]{link, folder});
        }
        // one lock around all of it, so a finishing worker and a new link can never cross
        synchronized (jobs) {
            lastStartId = startId;
            if (!A_STOP.equals(a)) {
                try {
                    // Android wants the notification at once
                    startForeground(FG_ID, Progress.progressNote(this, label(add.isEmpty() ? "" : add.get(0)[1]),
                            "reading", 0, 0, A_SYNC.equals(a) ? "Checking YouTube for new songs" : "Getting ready"));
                } catch (Exception ignored) {
                }
            }
            if (A_STOP.equals(a)) {
                jobs.clear();
                stopAll();
            }
            for (String[] j : add) {
                if (queued(j[0])) continue;            // the same link twice: once is enough
                Progress.update(label(j[1]), "reading", 0, 0, "Starting");
                jobs.add(j);
            }
            if (jobs.isEmpty() && workers == 0) {
                stopForeground(true);
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            if (workers == 0) limitNote = null;
            spawn();
        }
        MainActivity.progress();
        return START_NOT_STICKY;
    }

    private boolean queued(String link) {
        String l = same(link);
        for (Now n : busy) if (same(n.job[0]).equals(l) && !n.stopping) return true;
        for (String[] j : jobs) if (same(j[0]).equals(l)) return true;
        return false;
    }

    /** every download in hand stops (Stop, Android's daily limit) - called holding jobs */
    private void stopAll() {
        for (Now n : busy) {
            n.stopping = true;
            Process p = n.proc;
            if (p != null) p.destroy();
        }
    }

    /** how many waiting links may start beside the ones in hand (other playlists) - called holding jobs */
    private int startable() {
        Set<String> using = using();
        boolean quiet = quietBusy();
        int n = 0;
        for (String[] j : jobs) {
            if (!free(j, using, quiet)) continue;
            n++;
            if (!j[1].isEmpty()) using.add(j[1].toLowerCase(Locale.ROOT));
            if (isSync(j)) quiet = true;
        }
        return n;
    }

    /** the first waiting link that may start now (take = also off the list) - called holding jobs */
    private String[] pick(boolean take) {
        Set<String> using = using();
        boolean quiet = quietBusy();
        for (java.util.Iterator<String[]> it = jobs.iterator(); it.hasNext(); ) {
            String[] j = it.next();
            if (!free(j, using, quiet)) continue;
            if (take) it.remove();
            return j;
        }
        return null;
    }

    private Set<String> using() {
        Set<String> u = new java.util.HashSet<>();
        for (Now n : busy) {
            if (n.hold != null) u.add(n.hold.toLowerCase(Locale.ROOT));
            if (!n.job[1].isEmpty()) u.add(n.job[1].toLowerCase(Locale.ROOT));
        }
        return u;
    }

    private boolean quietBusy() {
        for (Now n : busy) if (n.quiet) return true;
        return false;
    }

    private static boolean isSync(String[] j) {
        return j.length > 2 && "sync".equals(j[2]);
    }

    private static boolean free(String[] j, Set<String> using, boolean quietBusy) {
        if (isSync(j) && quietBusy) return false;
        return j[1].isEmpty() || !using.contains(j[1].toLowerCase(Locale.ROOT));
    }

    /** more workers while links can start beside the ones running (AT_ONCE at most) - called holding jobs */
    private void spawn() {
        while (workers < AT_ONCE && workers - busy.size() < startable()) {
            workers++;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }).start();
        }
    }

    /** the lowest work folder no download in hand uses */
    private int freeSlot() {
        for (int k = 0; ; k++) {
            boolean used = false;
            for (Now n : busy) if (n.slot == k) used = true;
            if (!used) return k;
        }
    }

    /**
     * a link that found its playlist only now (a new YouTube playlist, or the one it went to before) waits
     * while another download is writing into that playlist, then holds it. False: stopped meanwhile.
     */
    private boolean hold(Now me, String folder, String card) throws InterruptedException {
        boolean said = false;
        while (true) {
            synchronized (jobs) {
                if (me.stopping) return false;
                boolean other = false;
                for (Now n : busy)
                    if (n != me && (folder.equalsIgnoreCase(n.hold) || folder.equalsIgnoreCase(n.job[1]))) other = true;
                if (!other) {
                    me.hold = folder;
                    return true;
                }
                if (said) {
                    jobs.wait(2000);
                    continue;
                }
            }
            said = true;
            progress(card, "reading", 0, 0, "Waiting - another download is going into " + folder);
        }
    }

    /** a download's card and its line in the notification go */
    private void ended(String folder) {
        Progress.update(folder, null, 0, 0, null);
        synchronized (counts) {
            counts.remove(folder);
        }
    }

    /** one form for the same link (YouTube Music and YouTube, with or without the share tag) */
    private static String same(String link) {
        return link.trim().replace("music.youtube.com", "www.youtube.com").replaceAll("[?&]si=[^&]*", "");
    }

    static String label(String folder) {
        return folder == null || folder.isEmpty() ? READING : folder;
    }

    /** Android 15+: data downloads may run 6 hours a day; past that it asks the app to stop */
    public void onTimeout(int startId) {
        limitNote = "Android's 6-hour download time for today ran out";
        synchronized (jobs) {
            jobs.clear();
            stopAll();
        }
        stopForeground(true);
        stopSelf();
    }

    public void onTimeout(int startId, int type) {
        onTimeout(startId);
    }

    private void work() {
        android.os.PowerManager.WakeLock wake = null;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MyMusic:download");
            wake.acquire(6L * 3600 * 1000);          // keeps going with the screen off
        } catch (Exception ignored) {
        }
        try {
            loop();
        } finally {
            try {
                if (wake != null && wake.isHeld()) wake.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void loop() {
        while (true) {
            String[] job;
            final Now me = new Now();
            synchronized (jobs) {
                // Stop empties the list, so anything still in it was shared after the Stop
                job = pick(true);
                if (job != null) {
                    if (busy.isEmpty()) limitNote = null;
                    me.job = job;
                    me.quiet = isSync(job);
                    me.hold = job[1].isEmpty() ? null : job[1];
                    me.slot = freeSlot();
                    busy.add(me);
                    spawn();                               // the next link starts beside this one
                } else {
                    workers--;                             // what is left waits for a playlist in use - its worker takes it
                    if (workers == 0) {
                        stopForeground(true);
                        stopSelfResult(lastStartId);       // false if a newer link is on its way
                    }
                    break;
                }
            }
            mine.set(me);
            String msg;
            me.lastFolder = job[1];
            try {
                msg = run(job[0], job[1]);
                if (me.sourceNote != null) msg += " " + me.sourceNote;
            } catch (Exception e) {
                msg = "Download failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                if (!me.stopping) Pending.finish(this, me.lastPend != null ? me.lastPend : me.lastFolder);
            } finally {
                synchronized (jobs) {
                    busy.remove(me);
                    jobs.notifyAll();                      // a link waiting for this playlist goes on
                }
            }
            String where = me.lastFolder == null || me.lastFolder.isEmpty() ? READING : me.lastFolder;
            ended(label(job[1]));
            ended(where);
            MainActivity.progress();
            boolean cameIn = !me.quiet || msg.startsWith("Added ") || msg.contains(" new song");
            if (cameIn) PlayerService.run(this, MainActivity.LIBRARY_CHANGED);   // a check that found nothing changes nothing
            if (me.quiet) {
                // a Sync check speaks only when new songs came in
                Matcher n = Pattern.compile("^Added (\\d+) song").matcher(msg);
                if (n.find()) {
                    int k = Integer.parseInt(n.group(1));
                    msg = "Added " + k + (k == 1 ? " new song to " : " new songs to ") + where + " from your YouTube playlist";
                    MainActivity.tell(msg);
                    Progress.announce(this, msg, where);
                } else {
                    try {
                        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(Progress.noteId(where));
                    } catch (Exception ignored) {
                    }
                }
                Sync.schedule(this);
            } else {
                MainActivity.tell(msg);
                Progress.announce(this, msg, where);
                Sync.schedule(this);          // a playlist shared for the first time is checked from now on
            }
        }
    }

    private void progress(String folder, String phase, int done, int total, String text) {
        Progress.update(folder, phase, done, total, text);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            android.app.Notification n;
            synchronized (counts) {
                counts.put(folder, new int[]{done, "reading".equals(phase) ? 0 : total});
                // several at once - one notification, a line for each
                n = counts.size() < 2 ? Progress.progressNote(this, folder, phase, done, total, text) : Progress.manyNote(this, counts);
            }
            nm.notify(FG_ID, n);
        } catch (Exception ignored) {
        }
        MainActivity.progress();
    }

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern LIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern ERR = Pattern.compile("^ERROR: \\[youtube[^\\]]*\\] ([A-Za-z0-9_-]{11}): (.*)$");

    /** the playlist id of a whole-playlist link (a song link that also names a playlist is just the song) */
    static String listId(String link) {
        if (link.contains("watch?v=") || link.contains("youtu.be/") || link.contains("/shorts/")) return null;
        Matcher m = LIST.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    private static String na(String s) {
        s = s == null ? "" : s.trim();
        return "NA".equals(s) ? "" : s;
    }

    /** a YouTube playlist title as a folder name */
    static String folderName(String title) {
        String s = title == null ? "" : title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ")
                .replaceAll("\\s+", " ").trim().replaceAll("^\\.+", "").trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }

    /**
     * what a job's songs wait
     * under in Pending - the playlist's name, or, for a YouTube playlist poured into a playlist that follows
     * another one, the name plus that list, so the playlist's own check never wipes the songs still to come
     */
    private String pendKey(String folder, String listId) {
        if (listId == null || folder.isEmpty()) return folder;
        if (folder.equalsIgnoreCase(getSharedPreferences("lists", Context.MODE_PRIVATE).getString(listId, ""))) return folder;
        return Sync.followsOther(this, folder, listId) ? folder + Pending.SEP + listId : folder;
    }

    /** where a whole YouTube playlist goes when nobody picked a playlist for it */
    private String pickFolder(Uri tree, String listId, Set<String> ids, String title) {
        SharedPreferences lp = getSharedPreferences("lists", Context.MODE_PRIVATE);
        if (listId != null) {
            String known = lp.getString(listId, "");
            if (!known.isEmpty()) return known;                // shared before
        }
        // a playlist on the phone that already has a good part of these songs (its download history)
        if (!ids.isEmpty()) {
            String best = null;
            int bestN = 0;
            for (String[] d : Importer.folders(this, tree)) {
                Set<String> arc = Importer.readArchive(this, tree, d[0]);
                int n = 0;
                for (String id : ids) if (arc.contains(id)) n++;
                if (n > bestN) {
                    bestN = n;
                    best = d[1];
                }
            }
            if (best != null && (bestN >= 25 || bestN * 4 >= ids.size())) return best;
        }
        String name = folderName(title);
        return name.isEmpty() ? "From YouTube" : name;
    }

    /** how many songs one search run looks up, and how many uploads it hears about for each */
    private static final int BATCH = 10, HITS = 5;

    /**
     * a Spotify / Deezer link read (Sources) and each song found on YouTube - into meta (id -> {title,
     * artist, seconds}, as from a YouTube playlist), the rest into unmatched ({id, title, artist, seconds, why},
     * for "Find another copy"). Ten songs to a search run. {the list's name, YouTube's last error}
     */
    private String[] fromSource(String link, String card, Map<String, String[]> meta, List<String[]> unmatched) throws Exception {
        final Now me = mine.get();
        Sources.Got g = Sources.read(link);
        if (g.songs.isEmpty()) throw new Exception(g.from + " showed no songs for that link");
        int n = g.songs.size(), found = 0;
        if (g.total > n)
            me.sourceNote = g.from + " shows only the first " + n + " of this playlist's " + g.total + " songs to an app without an account - the other "
                    + (g.total - n) + " were not added.";
        String err = "";
        for (int i = 0; i < n && !me.stopping; i += BATCH) {
            List<String[]> part = g.songs.subList(i, Math.min(n, i + BATCH));
            progress(card, "reading", 0, 0, "Finding the " + g.from + " songs on YouTube: " + i + " of " + n);
            List<String> a = new ArrayList<>(Arrays.asList("--flat-playlist", "--no-warnings",
                    "--print", "MMID\t%(playlist_title|)s\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s",
                    "--extractor-retries", "3"));
            for (String[] s : part) a.add("ytsearch" + HITS + ":" + Sources.query(s));
            Map<String, List<String[]>> hits = new LinkedHashMap<>();
            Process p = me.proc = Ytdl.start(this, a);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            try {
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("MMID\t")) {
                        String[] f = line.split("\t", -1);
                        if (f.length < 6 || !ID.matcher(f[2].trim()).matches()) continue;
                        List<String[]> l = hits.get(f[1].trim());
                        if (l == null) hits.put(f[1].trim(), l = new ArrayList<>());
                        l.add(new String[]{f[2].trim(), na(f[3]), na(f[5]), na(f[4])});
                    } else if (line.trim().startsWith("ERROR")) {
                        err = line.trim();
                    }
                    if (me.stopping) break;
                }
            } catch (java.io.IOException e) {
                if (!me.stopping) throw e;
            }
            if (me.stopping) p.destroy();
            p.waitFor();
            me.proc = null;
            if (me.stopping) break;
            boolean none = true;
            for (String[] s : part) {
                List<String[]> l = hits.get(Sources.query(s));
                if (l != null) none = false;
                String id = l == null ? null : Sources.best(l, s);
                if (id == null) {
                    unmatched.add(new String[]{s[0], s[1], s[2], s[3], "No clear match on YouTube yet"});
                    continue;
                }
                for (String[] x : l) {
                    if (!x[0].equals(id)) continue;
                    if (!meta.containsKey(id)) meta.put(id, new String[]{x[1], x[2].replaceAll("\\s*-\\s*Topic$", ""), x[3]});
                    found++;
                    break;
                }
            }
            if (none && held(err)) {
                // YouTube is pausing this phone: what was not looked up yet waits under "Find another copy"
                for (String[] s : g.songs.subList(Math.min(n, i + BATCH), n))
                    unmatched.add(new String[]{s[0], s[1], s[2], s[3], "YouTube paused - not looked up yet"});
                break;
            }
            Thread.sleep(1500);                                      // gently, between search runs
        }
        return new String[]{g.name.isEmpty() ? "From " + g.from : g.name, found == 0 ? err : ""};
    }

    /** YouTube holding this phone back (bot check, too many requests) - not the song's fault */
    static boolean held(String line) {
        if (line.contains("confirm your age")) return false;          // age-restricted: the song's own problem
        return line.contains("not a bot") || line.contains("Sign in to confirm you") || line.contains("429")
                || line.contains("Too Many Requests") || line.contains("try again later") || line.contains("rate-limit")
                || line.contains("rate limit") || line.contains("content isn't available") || line.contains("content isn\u2019t available")
                || line.contains("content is not available");
    }

    private static String why(String err) {
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("private")) return "Private video";
        if (e.contains("premium") || e.contains("members")) return "Needs YouTube Premium";
        if (e.contains("age-restricted") || e.contains("confirm your age") || e.contains("inappropriate")) return "Age-restricted";
        if (e.contains("unavailable") || e.contains("removed") || e.contains("terminated")) return "Removed from YouTube";
        if (e.contains("copyright")) return "Blocked for copyright";
        String s = err.trim();
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private String run(String link, String wanted) throws Exception {
        final Now me = mine.get();
        String card = label(wanted);
        if (Ytdl.needsSetup(this)) progress(card, "reading", 0, 0, "Setting up the downloader (first time, about a minute)");
        Ytdl.init(this);
        boolean alone;
        synchronized (jobs) {
            alone = busy.size() <= 1;
        }
        if (alone) {                         // a new yt-dlp only while no other download is using it
            progress(card, "reading", 0, 0, "Checking for a newer yt-dlp");
            Ytdl.maybeUpdate(this);
        }
        Uri tree = Importer.tree(this);
        if (tree == null) return "Carmen Musicum needs a music folder first - tap Music folder on the home screen";

        link = link.trim().replace("music.youtube.com", "www.youtube.com");
        boolean restore = link.startsWith(Backup.IDS);           // "Get the songs back" after a new phone
        boolean source = !restore && Sources.is(link);           // a Spotify / Deezer link - found on YouTube
        String listId = restore || source ? null : listId(link);
        boolean playlist = listId != null || source;

        // 1. every song in the link: id, title, length, artist (+ the playlist's own title)
        progress(card, "reading", 0, 0, me.quiet ? "Checking YouTube for new songs" : source ? "Reading the link" : playlist ? "Reading the playlist" : "");
        List<String> a = new ArrayList<>(Arrays.asList("--flat-playlist", "--no-warnings",
                "--print", "MMID\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s",
                "--extractor-retries", "10", "--retry-sleep", "extractor:exp=2:30"));
        if (playlist) {
            a.add("--print");
            a.add("playlist:MMPL\t%(title)s");
        } else {
            a.add("--no-playlist");
        }
        a.add(link);
        Map<String, String[]> meta = new LinkedHashMap<>();       // id -> {title, artist, seconds}
        // what it waits under until it has a name (a new playlist is named after the YouTube one)
        String pkey = !wanted.isEmpty() ? pendKey(wanted, listId) : "?" + (listId != null ? listId : Integer.toHexString(link.hashCode()));
        me.lastPend = pkey;
        String plTitle = "", lastErr = "", lastLine = "";
        if (me.stopping) return limitNote != null ? limitNote : "Stopped before anything came in";
        Map<String, String> restoreNames = new LinkedHashMap<>();   // id -> the file name it had
        Map<String, String> restoreAs = new LinkedHashMap<>();      // the same, as written (it comes back under it)
        List<String[]> unmatched = new ArrayList<>();               // no clear match on YouTube
        Process p = null;
        BufferedReader r = null;
        String line;
        if (restore) {
            for (String[] it : Backup.restoreItems(this, wanted)) {
                meta.put(it[0], new String[]{it[1], it[2], it[3]});
                restoreNames.put(it[0], it[4].toLowerCase(Locale.ROOT));
                restoreAs.put(it[0], it[4]);
            }
            plTitle = wanted;
            if (meta.isEmpty()) return "Nothing to get back for " + wanted;
        } else if (source) {
            String[] got = fromSource(link, card, meta, unmatched);
            plTitle = got[0];
            lastErr = got[1];
            if (meta.isEmpty() && !unmatched.isEmpty() && !held(lastErr))
                return "No clear match on YouTube for " + (unmatched.size() == 1 ? "this song" : "any of these " + unmatched.size() + " songs");
        } else {
        p = me.proc = Ytdl.start(this, a);
        r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        try {
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MMID\t")) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 2 && ID.matcher(f[1].trim()).matches()) {
                        meta.put(f[1].trim(), new String[]{na(f.length > 2 ? f[2] : ""),
                                na(f.length > 4 ? f[4] : "").replaceAll("\\s*-\\s*Topic$", ""), na(f.length > 3 ? f[3] : "")});
                    }
                } else if (line.startsWith("MMPL\t")) {
                    plTitle = na(line.substring(5));
                } else {
                    String t = line.trim();
                    if (t.startsWith("ERROR")) lastErr = t;
                    else if (!t.isEmpty()) lastLine = t;
                }
                if (me.stopping) break;
            }
        } catch (java.io.IOException e) {
            if (!me.stopping) throw e;
        }
        if (me.stopping) p.destroy();
        p.waitFor();
        me.proc = null;
        }
        if (lastErr.isEmpty()) lastErr = lastLine;
        if (me.stopping) return limitNote != null ? limitNote : "Stopped before anything came in";
        if (meta.isEmpty()) {
            if (me.quiet) return "Nothing new checked for " + wanted;      // offline or held - the next check tries again
            if (held(lastErr)) {
                // YouTube is pausing this phone right now: keep the link and try again by itself
                if (playlist || !wanted.isEmpty()) {
                    long at = System.currentTimeMillis() + WAIT_MS;
                    Pending.keep(this, pkey, link, wanted);
                    Pending.waitFor(this, pkey, at, "YouTube paused downloads");
                    Retry.schedule(this, at);
                    return "YouTube paused downloads for now - Carmen Musicum tries this link again at " + clock(at) + " by itself";
                }
            }
            return "Could not read that link" + (lastErr.isEmpty() ? "" : ": " + shorten(lastErr));
        }

        // every video's length, kept - the wrong-audio check then needs no YouTube for these songs
        try {
            Lengths.putAll(this, meta);
            Lengths.save(this);
        } catch (Exception ignored) {
        }

        // where it goes: the playlist picked, the one it went to before, the one that already has
        // most of these songs, or a new playlist named after the YouTube one
        String folder = wanted.isEmpty() ? pickFolder(tree, listId, meta.keySet(), plTitle) : wanted;
        if (!hold(me, folder, card)) return limitNote != null ? limitNote : "Stopped before anything came in";
        final String pf = pendKey(folder, listId);
        if (!folder.equals(card)) ended(card);
        if (!pf.equals(pkey)) Pending.finish(this, pkey);           // it waited under a temporary name
        me.lastFolder = folder;
        me.lastPend = pf;
        // poured into a playlist that follows another YouTube playlist (its own), it comes in
        // once and is not followed - that playlist keeps following its own list
        if (listId != null && pf.equals(folder)) getSharedPreferences("lists", Context.MODE_PRIVATE).edit().putString(listId, folder).apply();
        String fid = Importer.folderId(this, tree, folder);
        if (fid == null) return "Could not open the folder " + folder;
        Set<String> archive = Importer.readArchive(this, tree, fid);
        Map<String, String> have = Importer.names(this, tree, fid);
        Set<String> idsHere = new java.util.HashSet<>();
        if (restore) {
            // a song fetched again may come back under another name: its video id says it is there
            Library lib = Library.get(this);
            for (Library.Pl pl : lib.playlists) {
                if (!pl.name.equalsIgnoreCase(folder)) continue;
                for (Library.Song s : pl.songs) if (!s.file.startsWith(Mismatch.KEPT)) idsHere.add(Ids.of(this, s.path));
            }
            for (Library.Song s : lib.hiddenSongs)
                if (folder.equalsIgnoreCase(s.pl) && !s.file.startsWith(Mismatch.KEPT)) idsHere.add(Ids.of(this, s.path));
            Ids.save(this, null);
        }

        List<String> todo = new ArrayList<>();
        List<String[]> items = new ArrayList<>();
        List<String[]> deadOnes = new ArrayList<>();         // gone before: listed under "Find another copy", not asked for
        int gone = 0, local = 0;
        java.util.Set<String> goneIds = new java.util.HashSet<>();       // gone for good (a fetch job drops them)
        // local first: a song already in the folder (any upload of it) is not fetched again
        LocalMatch here = LocalMatch.of(this, folder, false);
        List<String> alreadyHere = new ArrayList<>();
        long now0 = System.currentTimeMillis();
        Dead.tidy(this, now0);
        for (Map.Entry<String, String[]> e : meta.entrySet()) {
            String id = e.getKey();
            String[] v = e.getValue();
            if (restore ? have.containsKey(restoreNames.get(id)) || idsHere.contains(id) : archive.contains(id)) continue;
            if (!restore && here.has(id, v[0], v[1], false)) {
                local++;
                alreadyHere.add(id);
                continue;
            }
            if (Dead.placeholder(v[0])) {                    // "[Private video]": nothing to ask YouTube for
                gone++;
                goneIds.add(id);
                continue;
            }
            if (Dead.skip(this, id, now0)) {
                gone++;
                goneIds.add(id);
                if (!here.has(id, v[0], v[1], true)) deadOnes.add(new String[]{id, v[0], v[1], v[2], "Gone from YouTube"});
                continue;
            }
            todo.add(id);
            items.add(new String[]{id, v[0], v[1], v[2]});
        }
        if (!deadOnes.isEmpty()) Missing.add(this, folder, deadOnes);
        if (!unmatched.isEmpty()) {
            Missing.add(this, folder, unmatched);                    // picked by hand (or by AutoCopy later)
            me.sourceNote = (me.sourceNote == null ? "" : me.sourceNote + " ") + unmatched.size()
                    + (unmatched.size() == 1 ? " song has" : " songs have") + " no clear match on YouTube yet - open the playlist to pick one under Find another copy.";
        }
        if (!alreadyHere.isEmpty()) Importer.appendArchive(this, tree, fid, alreadyHere);    // checked once, not again
        if (me.quiet && listId != null) {
            // songs new since the last check first, then from the end of the playlist; at most Sync.MAX -
            // the rest come at the next check
            List<Integer> pick = Sync.order(todo, Sync.seen(this, listId), Sync.MAX);
            List<String> t2 = new ArrayList<>();
            List<String[]> i2 = new ArrayList<>();
            for (int k : pick) {
                t2.add(todo.get(k));
                i2.add(items.get(k));
            }
            todo = t2;
            items = i2;
            Sync.saveSeen(this, listId, meta.keySet());
        }
        int total = todo.size();
        if (total == 0) {
            Pending.finish(this, pf);
            if (restore) Backup.restoreDone(this, folder);
            if (gone > 0) return finishText(folder, 0, local, 0, gone, 0, false, null, 0, 0);
            return "Nothing new for " + folder + " - all " + meta.size() + " already there";
        }
        // the whole list shows in the app at once; each song fills in as it arrives
        Pending.start(this, pf, link, wanted, plTitle, items);

        // 2. fetch them, one after another, at the same polite pace as playlist.sh
        File work = new File(getCacheDir(), me.slot == 0 ? "dl" : "dl" + me.slot);      // one each
        Ytdl.delete(work);
        work.mkdirs();
        File batch = new File(work, "todo.txt");
        Writer w = new OutputStreamWriter(new FileOutputStream(batch), "UTF-8");
        try {
            for (String id : todo) w.write("https://www.youtube.com/watch?v=" + id + "\n");
        } finally {
            w.close();
        }
        String alb = folder.replaceAll("[%():|\\\\]", "");
        List<String> d = new ArrayList<>(Arrays.asList("-i", "--no-warnings",
                "-f", "bestaudio[ext=m4a]/bestaudio", "-x", "--audio-format", "mp3", "--audio-quality", "192K",
                "--embed-thumbnail", "--convert-thumbnails", "jpg",
                "--ppa", "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"",
                "--embed-metadata",
                "--parse-metadata", "%(artist,creator,uploader|)s:%(meta_artist)s",
                "--replace-in-metadata", "meta_artist", " - Topic$", "",
                "--parse-metadata", "%(album|" + alb + ")s:%(meta_album)s",
                "--extractor-retries", "10", "--retry-sleep", "extractor:exp=2:30",
                "--sleep-requests", "2", "--sleep-interval", "5", "--max-sleep-interval", "12",
                "--no-simulate", "--print", "after_move:MMDONE\t%(id)s\t%(filepath)s",
                // how far the song in hand is - a 3-hour video sat at "0 of 1" for ten minutes
                "--progress", "--newline", "--progress-template", "download:MMPCT %(progress._percent_str)s",
                "--progress-template", "postprocess:MMPP %(progress.postprocessor)s %(progress.status)s",
                "-o", work.getAbsolutePath() + "/%(title)s [%(id)s].%(ext)s",
                "-a", batch.getAbsolutePath()));
        int done = 0, added = 0, dupes = 0, blocked = 0, failed = 0, goneNow = 0, notSaved = 0, inARow = 0;
        boolean paused = false;
        List<String> newIds = new ArrayList<>();
        final AtomicInteger scanning = new AtomicInteger();
        long shownAt = System.currentTimeMillis(), pctAt = 0;
        int shownAdded = 0;
        progress(folder, "downloading", 0, total, "");
        if (me.stopping) {
            if (limitNote == null) Pending.finish(this, pf);
            return limitNote != null ? limitNote : "Stopped before anything came in";
        }
        p = me.proc = Ytdl.start(this, d);
        r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        try {
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MMDONE\t")) {
                    String[] f = line.split("\t", 3);
                    if (f.length < 3) continue;
                    inARow = 0;
                    File mp3 = new File(f[2]);
                    String as = restore ? restoreAs.get(f[1]) : null;
                    // a song fetched back keeps the name it had (its plays, heart and queue spot go with the name)
                    String name = as != null && as.toLowerCase(Locale.ROOT).endsWith(".mp3") && as.length() > 4
                            && !as.matches(".*[/\\\\:*?\"<>|].*") ? as : finalName(mp3, f[1]);
                    boolean inFolder = false;
                    if (have.containsKey(name.toLowerCase(Locale.ROOT))) {
                        dupes++;
                        inFolder = true;
                    } else {
                        String path = Importer.put(this, tree, fid, mp3, name);
                        if (path != null) {
                            Ids.put(this, path, f[1], mp3.length());
                            have.put(name.toLowerCase(Locale.ROOT), "");
                            added++;
                            inFolder = true;
                            // let Android index it now, so it shows in the app straight away
                            scanning.incrementAndGet();
                            MediaScannerConnection.scanFile(getApplicationContext(), new String[]{path}, null,
                                    new MediaScannerConnection.OnScanCompletedListener() {
                                        @Override
                                        public void onScanCompleted(String path, Uri uri) {
                                            scanning.decrementAndGet();
                                        }
                                    });
                        } else {
                            notSaved++;                    // not archived: tried again next time
                            Pending.failed(this, pf, f[1], "Could not save it to the folder");
                        }
                    }
                    mp3.delete();
                    done++;
                    if (inFolder) {
                        Pending.done(this, pf, f[1]);
                        newIds.add(f[1]);
                        if (newIds.size() % 10 == 0)
                            Importer.appendArchive(this, tree, fid, newIds.subList(newIds.size() - 10, newIds.size()));
                    }
                    progress(folder, "downloading", done, total, blocked > 0 ? "YouTube is slowing things down" : "");
                    // new songs appear in the app every few songs (or 20 seconds), not only at the end
                    long now = System.currentTimeMillis();
                    if (added > shownAdded && (added - shownAdded >= 5 || now - shownAt > 20000 || added == 1)) {
                        waitScans(scanning, 3000);
                        Library.load(this);
                        MainActivity.songsArrived();
                        shownAdded = added;
                        shownAt = now;
                    }
                } else if (line.startsWith("MMPCT ") || line.startsWith("MMPP ExtractAudio started")) {
                    // this song's own progress, once a second at most
                    long now = System.currentTimeMillis();
                    boolean conv = line.startsWith("MMPP");
                    if (conv || now - pctAt >= 1000) {
                        pctAt = now;
                        String what = conv ? "making the MP3 (a long video takes a few minutes)"
                                : (total > 1 ? "this song " : "") + line.substring(6).trim();
                        progress(folder, "downloading", done, total, (blocked > 0 ? "YouTube is slowing things down - " : "") + what);
                    }
                } else if (line.startsWith("ERROR")) {
                    Matcher m = ERR.matcher(line.trim());
                    boolean bot = held(line);
                    if (bot) {
                        blocked++;
                        inARow++;
                        progress(folder, "downloading", done, total, "YouTube is slowing things down");
                        if (inARow >= 3) {             // YouTube has paused this phone: stop asking for now
                            paused = true;
                            p.destroy();
                            break;
                        }
                    } else {
                        done++;
                        String fid1 = m.matches() ? m.group(1) : null, whyNot = m.matches() ? why(m.group(2)) : "";
                        if (fid1 != null && Dead.gone(whyNot)) {
                            goneNow++;
                            goneIds.add(fid1);
                            Dead.fail(this, fid1, whyNot, System.currentTimeMillis());
                            String[] v = meta.get(fid1);
                            if (v != null && !here.has(fid1, v[0], v[1], true)) {
                                List<String[]> one = new ArrayList<>();
                                one.add(new String[]{fid1, v[0], v[1], v[2], whyNot});
                                Missing.add(this, folder, one);     // in the playlist under "Find another copy" now
                            }
                        } else {
                            failed++;
                        }
                        if (fid1 != null) Pending.failed(this, pf, fid1, whyNot);
                        progress(folder, "downloading", done, total, "");
                    }
                }
                if (me.stopping) break;
            }
        } catch (java.io.IOException e) {
            if (!me.stopping && !paused) throw e;
        }
        if (me.stopping || paused) p.destroy();
        p.waitFor();
        me.proc = null;
        int tail = newIds.size() % 10;
        if (tail > 0) Importer.appendArchive(this, tree, fid, newIds.subList(newIds.size() - tail, newIds.size()));
        Ytdl.delete(work);

        // 3. the last ones in, then the library once more
        waitScans(scanning, 30000);
        Library.load(this);

        int left = Math.max(0, total - done);
        gone += goneNow;
        if (me.stopping && limitNote == null) {
            Pending.finish(this, pf);              // Stop: forget the rest
            return finishText(folder, added, dupes + local, notSaved, gone, failed, true, null, 0, 0);
        }
        if (paused || limitNote != null || (blocked > 0 && left > 0)) {
            // YouTube (or Android's daily limit) paused it: the rest waits and Carmen Musicum tries again by itself
            long at = System.currentTimeMillis() + (limitNote != null ? 12 * WAIT_MS : WAIT_MS);
            Pending.waitFor(this, pf, at, limitNote != null ? limitNote : "YouTube paused downloads");
            Retry.schedule(this, at);
            return finishText(folder, added, dupes + local, notSaved, gone, failed, false,
                    limitNote != null ? limitNote : "YouTube paused downloads", left, at);
        }
        // songs gone from YouTube stay listed in the playlist, with "Find another copy"
        List<String[]> goneItems = new ArrayList<>();
        for (String[] it : Pending.failedItems(this, pf)) if (Dead.gone(it[4]) && !here.has(it[0], it[1], it[2], true)) goneItems.add(it);
        Missing.add(this, folder, goneItems);
        Pending.finish(this, pf);
        if (restore) {
            // what did not come in stays on the list and is tried again by itself (3 tries at most)
            java.util.Set<String> drop = new java.util.HashSet<>(newIds);
            drop.addAll(goneIds);
            int[] rest = Backup.restoreKeep(this, folder, drop, new java.util.HashSet<>(todo));
            if (rest[0] > 0) {
                long at = System.currentTimeMillis() + (rest[1] > 0 ? 60000 : WAIT_MS);
                Pending.keep(this, pf, link, wanted);
                Pending.waitFor(this, pf, at, "Trying the rest again");
                Retry.schedule(this, at);
            }
        }
        return finishText(folder, added, dupes + local, notSaved, gone, failed, false, null, 0, 0);
    }

    /**
     * what a finished (or paused) download says, most important first - the
     * notification shows the start of it, the rest when pulled down. * never "Added 0 songs".
     */
    static String finishText(String folder, int added, int dupes, int notSaved, int gone, int failed,
                             boolean stopped, String pausedWhy, int left, long at) {
        StringBuilder b = new StringBuilder();
        if (stopped) b.append("Stopped. ");
        if (added > 0) b.append("Added ").append(added).append(added == 1 ? " song to " : " songs to ").append(folder);
        else if (pausedWhy != null) b.append("Nothing new in ").append(folder).append(" yet");
        else b.append("Nothing new for ").append(folder);
        if (dupes > 0) b.append(", ").append(dupes).append(" already there");
        b.append('.');
        if (pausedWhy != null) b.append(' ').append(pausedWhy).append(" - the other ").append(left)
                .append(left == 1 ? " comes in after " : " come in after ").append(clock(at)).append(", by itself.");
        if (gone > 0) b.append(' ').append(gone).append(gone == 1 ? " song is" : " songs are")
                .append(" gone from YouTube (removed or private) - open the playlist to find other copies.");
        if (failed > 0) b.append(' ').append(failed).append(failed == 1 ? " song" : " songs").append(" could not be downloaded - tried again next time.");
        if (notSaved > 0) b.append(' ').append(notSaved).append(notSaved == 1 ? " song" : " songs").append(" could not be saved to the folder (phone full?).");
        return b.toString();
    }

    private static void waitScans(AtomicInteger scanning, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (scanning.get() > 0 && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static String clock(long at) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
    }

    private static String shorten(String s) {
        s = s.replaceFirst("^ERROR:\\s*(\\[[^]]*\\]\\s*)?([A-Za-z0-9_-]{11}:\\s*)?", "");
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }

    // ---------------------------------------------------------------- "Artist - Song.mp3" (like songnames)

    private static final Pattern DASH = Pattern.compile("\\s[-\u2010-\u2015]\\s");
    private static final Pattern EXTRA = Pattern.compile(
            "\\b(remix|mix|edit|version|live|cover|extended|radio|vip|bootleg|flip|rework|instrumental|acoustic|lyrics?|official|video|audio|prod)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]");

    private String finalName(File mp3, String id) {
        String stem = mp3.getName();
        if (stem.toLowerCase(Locale.ROOT).endsWith(".mp3")) stem = stem.substring(0, stem.length() - 4);
        String tag = " [" + id + "]";
        if (stem.endsWith(tag)) stem = stem.substring(0, stem.length() - tag.length());
        String artist = "";
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            FileInputStream in = new FileInputStream(mp3);
            try {
                m.setDataSource(in.getFD());
            } finally {
                in.close();
            }
            String a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (a != null) artist = a.replaceAll("\\s*-\\s*Topic$", "").trim();
        } catch (Exception ignored) {
        } finally {
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
        String name = songName(stem, artist) + ".mp3";
        while (name.getBytes().length > 240 && stem.length() > 20) {
            stem = stem.substring(0, stem.length() - 5);
            name = songName(stem, artist) + ".mp3";
        }
        return name;
    }

    static String songName(String stem, String artist) {
        if (artist == null || artist.isEmpty()) return stem;
        String a = safe(artist);
        Matcher m = DASH.matcher(stem);
        if (!m.find()) return isArtist(stem, artist) ? stem : a + " - " + stem;
        String x = stem.substring(0, m.start()).trim(), y = stem.substring(m.end()).trim();
        String yb = BRACKETS.matcher(y).replaceAll("").trim();
        StringBuilder tail = new StringBuilder();
        Matcher bm = BRACKETS.matcher(y);
        while (bm.find()) tail.append(' ').append(bm.group().trim());
        if (!yb.isEmpty() && !isArtist(x, artist) && isArtist(yb, artist) && !EXTRA.matcher(yb).find()) {
            return yb + " - " + x + tail;
        }
        return stem;
    }

    private static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '/': b.append('\u29f8'); break;
                case '\\': b.append('\u29f9'); break;
                case '"': case '*': case ':': case '<': case '>': case '?': case '|':
                    b.append((char) (c + 0xfee0)); break;
                default: b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean isArtist(String text, String artist) {
        String t = norm(text);
        if (t.isEmpty()) return false;
        List<String> names = new ArrayList<>();
        names.add(norm(artist));
        for (String part : artist.split(",|&| x | X |\\bfeat\\.?|\\bft\\.?|\\band\\b")) names.add(norm(part));
        for (String n : names) {
            if (n.length() < 3) continue;
            if (t.equals(n) || t.startsWith(n)) return true;
            if (n.startsWith(t) && t.length() >= 4) return true;
        }
        return false;
    }
}
