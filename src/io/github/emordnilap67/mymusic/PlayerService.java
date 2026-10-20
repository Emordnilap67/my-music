package io.github.emordnilap67.mymusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player itself. Lives apart from the screen, so the music keeps
 * going with the screen off or the app closed. Lock screen, notification
 * and headphone buttons all come here (MediaSession).
 */
public class PlayerService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    interface Listener {
        void onState(String json);
    }

    /** something to do with the player */
    interface Job {
        void run(PlayerService p);
    }

    static volatile PlayerService instance;
    /** the player's audio session (the moving ring listens to this one only); 0 = none */
    static volatile int audioSession;
    static volatile Listener listener;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final String CH = "playing";
    static final String A_TOGGLE = "io.github.emordnilap67.mymusic.TOGGLE";
    static final String A_NEXT = "io.github.emordnilap67.mymusic.NEXT";
    static final String A_PREV = "io.github.emordnilap67.mymusic.PREV";
    static final String A_STOP = "io.github.emordnilap67.mymusic.STOP";
    static final String A_SHUF = "io.github.emordnilap67.mymusic.SHUFFLE";
    static final String A_REP = "io.github.emordnilap67.mymusic.REPEAT";
    /** a tap on the widget's bar - "permille" = where, in thousandths of the song */
    static final String A_SEEK = "io.github.emordnilap67.mymusic.SEEK";
    static final int NID = 67;

    /** do something with the player on the main thread, starting it if needed */
    static void run(Context c, final Job job) {
        final Context app = c.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                PlayerService s = instance;
                if (s != null) {
                    job.run(s);
                    return;
                }
                synchronized (waiting) {
                    waiting.add(new Runnable() {
                        @Override
                        public void run() {
                            job.run(instance);
                        }
                    });
                }
                try {
                    app.startService(new Intent(app, PlayerService.class));
                } catch (IllegalStateException e) {
                    // Android does not allow it right now (app idle in the background)
                }
            }
        });
    }

    private final Queue q = new Queue();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private MediaPlayer mp;
    /* CROSSFADE: pre = the
       next song, opened about 15 s ahead and waiting at its first note, so it can start the
       moment the blend does; gone = the song fading out. The blend starts where the music of a
       song really ends (Loud.java finds it - a silent tail is skipped) and lasts the Sound
       setting (6 s unless changed); equal-power, so the two together stay as loud as one song.
       Next, Previous or a tap on another song blends too, quickly (QUICK_MS). Repeat one, and
       the last song of a list with repeat off, play out as before. Each player has its own
       effects (Sound.Fx: equalizer, quiet-song boost) and its own volume = level (evening out)
       x fade x duck. */
    private MediaPlayer pre, gone;
    private String preKey, goneKey;
    private boolean preReady;
    private final java.util.HashMap<MediaPlayer, Sound.Fx> fx = new java.util.HashMap<>();
    private final java.util.HashMap<MediaPlayer, float[]> vol = new java.util.HashMap<>();   // {level, fade}
    private float duck = 1f;
    private long fadeAt;
    private int fadeLen, nextFade, pendFade;
    private static final int QUICK_MS = 900;
    /** the songs whose files are open in a player now (the lyrics writer leaves them alone) */
    private static volatile String[] open = new String[0];
    private boolean prepared, preparing, wantPlay, resumeOnGain, foreground, everPlayed;
    private int seekOnPrepare = -1, fails;
    private long pausedAt;
    /** stay in the foreground this long after a pause, so the controls keep working */
    private static final long HOLD_MS = 10 * 60 * 1000;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focus;
    private AudioAttributes attrs;
    private SharedPreferences prefs;
    private Bitmap art;
    private String artKey, note = "";
    /* 5.2: changing songs on the widget flashed the default purple-cyan for a moment
       before the new song's colours: a new song's picture is read on the
       background thread, and the widget and Android's media player were redrawn before it
       was there - with no picture, so with the default colours. Now they keep showing the
       last song until the new picture is ready (a few hundredths of a second, 0.7 s at the
       most) and then change once: name, picture and colours together. */
    private String artWait;
    private final Runnable artLate = new Runnable() {
        @Override
        public void run() {
            if (artWait == null) return;
            artWait = null;
            changed();
        }
    };

    /** the current song's picture is still being read: hold the widget and the media player */
    private boolean holdArt(Library.Song s) {
        return s != null && artWait != null && s.key.equals(artWait);
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause();     // headphones pulled out
        }
    };

    private final Runnable skip = new Runnable() {
        @Override
        public void run() {
            next(false);
        }
    };

    private final Runnable release = new Runnable() {
        @Override
        public void run() {
            changed();          // the pause hold is over: let Android tidy up when it wants
        }
    };

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) {
                save();
                countPlay();
                MAIN.postDelayed(this, 5000);
            }
        }
    };

    // ------------------------------------------------ play counts ("Most played", "Recently played")
    private String countKey;               // the song loaded now
    private boolean counted;               // already counted this time through

    /** a song counts once it has played 30 seconds (half of it, if it is under a minute) */
    private void countPlay() {
        if (counted || countKey == null || mp == null) return;
        try {
            int pos = mp.getCurrentPosition(), dur = mp.getDuration();
            int need = dur > 0 ? Math.min(30000, dur / 2) : 30000;
            if (pos >= need) {
                counted = true;
                Plays.count(this, countKey);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        NotificationChannel ch = new NotificationChannel(CH, "Now playing", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm().createNotificationChannel(ch);
        session = new MediaSession(this, "MyMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onStop() { pause(); }
        });
        session.setSessionActivity(openApp());
        session.setActive(true);
        // 4 = RECEIVER_NOT_EXPORTED (Android 13+ wants it said; a system broadcast still arrives)
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), 4);
        instance = this;
        Loud.done = new Loud.Done() {               // a song just measured - the one on now (or next) takes its level
            @Override
            public void measured(final String key) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (instance != PlayerService.this) return;
                        if (key.equals(countKey) && mp != null) level(mp, key);
                        if (key.equals(preKey) && pre != null) level(pre, key);
                    }
                });
            }
        };
        MAIN.postDelayed(sweep, 20000);
        restore();
        List<Runnable> jobs;
        synchronized (waiting) {
            jobs = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (Runnable r : jobs) r.run();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        boolean fg = i != null && i.getBooleanExtra("fg", false);
        if (fg) {
            // started from the widget as a foreground service: must show a notification at once
            try {
                Library.Song s = Library.get(this).byKey.get(q.current());
                startForeground(NID, s != null ? notification(s, true) : placeholder());
                foreground = true;
            } catch (Exception ignored) {
            }
        }
        if (A_TOGGLE.equals(a)) toggle();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_PREV.equals(a)) prev();
        else if (A_SHUF.equals(a)) setShuffle(!q.shuffle);
        else if (A_REP.equals(a)) setRepeat("off".equals(q.repeat) ? "all" : "all".equals(q.repeat) ? "one" : "off");
        else if (A_SEEK.equals(a)) seekPart(i.getIntExtra("permille", 0));
        else if (A_STOP.equals(a)) {
            pause();
            stopForeground(true);
            foreground = false;
            stopSelf();
        }
        if (fg) changed();          // settles foreground / notification for what actually happened
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent root) {
        // swiped away from recent apps: keep playing if playing, otherwise close
        if (!isPlaying()) {
            save();
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(widgetTick);
        save();
        instance = null;
        MAIN.removeCallbacks(saver);
        MAIN.removeCallbacks(skip);
        MAIN.removeCallbacks(release);
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        if (focus != null) am.abandonAudioFocusRequest(focus);
        MAIN.removeCallbacks(fadeTick);
        MAIN.removeCallbacks(watch);
        MAIN.removeCallbacks(sweep);
        Loud.done = null;
        stopPre();
        if (gone != null) drop(gone);
        gone = null;
        if (mp != null) {
            Viz.release();
            audioSession = 0;
            drop(mp);
            mp = null;
        }
        open = new String[0];
        session.setActive(false);
        session.release();
        bg.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- commands

    void setQueue(List<String> keys, int index, boolean shuffle) {
        q.set(keys, index, shuffle);
        q.src = wholePlaylist(keys);          // all of one playlist - it follows that playlist
        describe();
        saveQueue();
        String k = q.current();
        if (k != null) load(k, 0, true);
    }

    /** Play next (first) / Add to queue; with nothing on, it just plays */
    void enqueue(String key, boolean first) {
        if (key == null || !Library.get(this).byKey.containsKey(key)) return;
        if (q.current() == null) {
            setQueue(java.util.Collections.singletonList(key), 0, false);
            return;
        }
        if (first) q.playNext(key);
        else q.addToQueue(key);
        saveQueue();
        changed();
    }

    /** take a queued song off (n = its place in Up next), or all of them (n < 0) */
    void unqueue(int n) {
        q.unqueue(n);
        saveQueue();
        changed();
    }

    void toggle() {
        if (isPlaying() || (wantPlay && preparing)) pause();
        else play();
    }

    void play() {
        String k = q.current();
        if (k == null) return;
        if (prepared) {
            start();
            return;
        }
        if (preparing) {
            wantPlay = true;
            changed();
            return;
        }
        load(k, k.equals(prefs.getString("cur", "")) ? prefs.getInt("pos", 0) : 0, true);
    }

    void pause() {
        resumeOnGain = false;
        wantPlay = false;
        MAIN.removeCallbacks(skip);
        endFade();                            // a blend going on ends at once
        if (prepared && mp.isPlaying()) mp.pause();
        held();
        save();
        changed();
    }

    /** start the 10-minute hold after a pause */
    private void held() {
        pausedAt = SystemClock.elapsedRealtime();
        MAIN.removeCallbacks(release);
        MAIN.postDelayed(release, HOLD_MS + 1000);
    }

    void next(boolean auto) {
        int r0 = q.rounds;
        String k = q.next(auto);
        if (q.rounds != r0) saveQueue();      // a new round of shuffle - saved, so a restart carries on with it
        if (k == null) {                       // the end of the list, repeat off
            wantPlay = false;
            if (prepared) {
                if (mp.isPlaying()) mp.pause();
                mp.seekTo(0);
            }
            held();
            save();
            changed();
            return;
        }
        load(k, 0, true);
    }

    void prev() {
        if (prepared && mp.getCurrentPosition() > 3000) {
            mp.seekTo(0);
            changed();
            return;
        }
        String k = q.prev();
        if (k != null) load(k, 0, true);
    }

    void seek(int ms) {
        endFade();
        if (prepared) mp.seekTo(Math.max(0, ms));
        else seekOnPrepare = Math.max(0, ms);
        changed();
    }

    /**
     * the widget's bar was tapped - jump to that part of the song,
     * playing or paused (it stays paused). If the player was closed, the
     * song is opened at that spot without starting it.
     */
    void seekPart(int permille) {
        String k = q.current();
        if (k == null) return;
        int dur = duration();
        if (dur <= 0) return;
        int ms = (int) Math.min(dur - 1000L, (long) dur * Math.max(0, Math.min(1000, permille)) / 1000L);
        ms = Math.max(0, ms);
        if (prepared || preparing) seek(ms);
        else load(k, ms, false);
    }

    void setShuffle(boolean on) {
        q.setShuffle(on);
        saveQueue();
        changed();
    }

    void setRepeat(String r) {
        q.setRepeat(r);
        save();
        changed();
    }

    /** after Update library, or once the app may read the music */
    void libraryChanged() {
        MAIN.removeCallbacks(sweep);          // new songs get measured too
        MAIN.postDelayed(sweep, 20000);
        if (q.order.isEmpty()) {
            restore();
            return;
        }
        String before = q.current();
        boolean was = isPlaying() || (wantPlay && preparing);
        Library lib = Library.get(this);
        q.keepOnly(keepable(lib), q.listCurrent());     // a song still on the phone stays in the list
        if (follow(lib) > 0) describe();
        saveQueue();
        String now = q.current();
        if (before != null && !before.equals(now)) {
            // the song that was on got hidden or deleted
            if (now != null) {
                load(now, 0, was);
            } else {
                wantPlay = false;
                stopPre();
                endFade();
                if (mp != null) mp.reset();
                prepared = false;
                preparing = false;
                changed();
            }
            return;
        }
        changed();
    }

    // ---------------------------------------------------------------- playing

    private void load(String key, int at, boolean play) {
        MAIN.removeCallbacks(skip);
        Library.Song s = Library.get(this).byKey.get(key);
        if (s == null) s = standIn(key);      // on the phone, but Android's index is busy right now
        if (s != null) {                      // heard in this shuffle pass
            q.markHeard(key);
            final List<String> h = new ArrayList<>(q.heard);
            try {
                bg.execute(new Runnable() {
                    @Override
                    public void run() {
                        writeList("heard.txt", h);
                    }
                });
            } catch (Exception ignored) {     // closing down
            }
        }
        // blend out of the song playing now - the crossfade's turn, or Next / Previous / a tap
        boolean blend = play && s != null && isPlaying() && Sound.xfadeMs(this) > 0;
        int fade = nextFade > 0 ? nextFade : QUICK_MS;
        nextFade = 0;
        MediaPlayer ready = s != null && preReady && key.equals(preKey) ? pre : null;
        if (ready != null) pre = null;        // the next song, already open and waiting
        stopPre();
        endFade();
        wantPlay = play;
        if (s == null) {
            if (mp != null) mp.reset();
            prepared = false;
            preparing = false;
            note = "That song is not in the library any more";
            changed();
            return;
        }
        if (blend) {
            gone = mp;                        // plays on until the new song starts, then fades
            goneKey = countKey;
            mp = null;
            if (at <= 0) at = inPoint(s);     // the new song starts on its first note
        } else if (ready != null && mp != null) {
            drop(mp);
            mp = null;
        }
        pendFade = blend ? fade : 0;
        List<String> up = q.upcoming(2);      // measured soon - this song first, then the next two
        for (int u = up.size() - 1; u >= 0; u--) Loud.soon(this, Library.get(this).byKey.get(up.get(u)));
        Loud.soon(this, s);
        countKey = key;                       // a new time through this song
        counted = false;
        prefs.edit().putString("cur", key).putInt("pos", at).apply();
        opened();
        Embed.soon(this);                         // the song before this one is free for its lyrics now
        if (ready != null) {
            mp = ready;
            if (gone == null) audioSession = mp.getAudioSessionId();
            prepared = true;
            preparing = false;
            seekOnPrepare = -1;
            level(mp, key);
            if (!blend) mp.seekTo(Math.max(0, at));   // (opened at its first note for a blend)
            loadArt(s);
            if (play) start();
            else changed();
            return;
        }
        if (mp == null) {
            mp = newPlayer();
            if (gone == null) audioSession = mp.getAudioSessionId();
        }
        mp.reset();
        prepared = false;
        preparing = true;
        seekOnPrepare = at;
        try {
            // by file handle, not by name: a ":" in a file name would confuse Android
            Embed.waitIfWriting(s.path);          // lyrics being written into it - a second at most
            FileInputStream in = new FileInputStream(s.path);
            try {
                mp.setDataSource(in.getFD());
            } finally {
                in.close();
            }
            mp.prepareAsync();
        } catch (Exception e) {
            onError(mp, 0, 0);
            return;
        }
        loadArt(s);
        changed();
    }

    @Override
    public void onPrepared(MediaPlayer m) {
        if (m == pre) {                       // the next song is open - it waits at its first note
            preReady = true;
            Library.Song s = Library.get(this).byKey.get(preKey);
            int in = inPoint(s);
            if (in > 0) m.seekTo(in);
            level(m, preKey);
            return;
        }
        if (m != mp) return;
        prepared = true;
        preparing = false;
        level(m, countKey);
        if (seekOnPrepare > 0) m.seekTo(seekOnPrepare);
        seekOnPrepare = -1;
        if (wantPlay) start();
        else changed();
    }

    private void start() {
        if (!getFocus()) {
            endFade();
            wantPlay = false;
            note = "Another app has the sound right now";
            changed();
            return;
        }
        boolean blendIn = gone != null && fadeLen == 0;     // the song before is still playing - fade across
        float[] v = vol.get(mp);
        if (v != null) v[1] = blendIn ? 0f : 1f;
        applyVol(mp);
        mp.start();
        if (blendIn) beginFade(pendFade > 0 ? pendFade : QUICK_MS);
        pendFade = 0;
        wantPlay = true;
        everPlayed = true;
        fails = 0;
        changed();
        MAIN.removeCallbacks(saver);
        MAIN.postDelayed(saver, 5000);
        MAIN.removeCallbacks(watch);
        MAIN.postDelayed(watch, 250);
    }

    @Override
    public void onCompletion(MediaPlayer m) {
        if (m == gone) {                      // the song fading out reached its end - the new one carries on
            drop(gone);
            gone = null;
            goneKey = null;
            opened();
            return;
        }
        if (m != mp) return;
        countPlay();                          // a short song that ended between checks
        next(true);
    }

    @Override
    public boolean onError(MediaPlayer m, int what, int extra) {
        if (m != mp && m != null) {           // the next song (opened ahead) or the one fading out
            if (m == pre) stopPre();
            else if (m == gone) {
                drop(gone);
                gone = null;
                goneKey = null;
                opened();
            }
            return true;
        }
        prepared = false;
        preparing = false;
        boolean go = wantPlay;
        Library.Song s = Library.get(this).byKey.get(q.current());
        if (++fails >= 5) {
            fails = 0;
            wantPlay = false;
            note = "5 songs in a row would not play";
            changed();
            return true;
        }
        note = "Could not play " + (s == null ? "that song" : s.title) + (go ? ". Skipping." : "");
        changed();
        if (go) MAIN.postDelayed(skip, 800);
        return true;
    }

    // ---------------------------------------------------------------- crossfade, evening out, effects

    private MediaPlayer newPlayer() {
        MediaPlayer m = new MediaPlayer();
        m.setAudioAttributes(attrs);
        m.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        m.setOnPreparedListener(this);
        m.setOnCompletionListener(this);
        m.setOnErrorListener(this);
        fx.put(m, Sound.Fx.attach(this, m));
        vol.put(m, new float[]{1f, 1f});
        return m;
    }

    /** a player is done with: its effects go with it */
    private void drop(MediaPlayer m) {
        if (m == null) return;
        Sound.Fx f = fx.remove(m);
        if (f != null) f.release();
        vol.remove(m);
        try {
            m.release();
        } catch (Exception ignored) {
        }
        if (m != mp) Embed.soon(this);        // its file is free for lyrics now
    }

    private void applyVol(MediaPlayer m) {
        float[] v = m == null ? null : vol.get(m);
        if (v == null) return;
        float g = Math.max(0f, Math.min(1f, v[0] * v[1] * duck));
        try {
            m.setVolume(g, g);
        } catch (Exception ignored) {
        }
    }

    /** evening out: turn this song down (the player's volume) or up (the boost) to the common loudness */
    private void level(MediaPlayer m, String key) {
        float[] v = m == null ? null : vol.get(m);
        if (v == null) return;
        float db = 0f;
        if (Sound.levelOn(this)) {
            Library.Song s = key == null ? null : Library.get(this).byKey.get(key);
            db = s == null ? Loud.UNKNOWN_DB : Loud.gainDb(this, key, s.size);
        }
        v[0] = db < 0 ? (float) Math.pow(10, db / 20.0) : 1f;
        Sound.Fx f = fx.get(m);
        if (f != null) f.boost(db);
        applyVol(m);
    }

    /** where this song's sound starts (ms) - used when blending into it */
    private int inPoint(Library.Song s) {
        if (s == null || Sound.xfadeMs(this) <= 0) return 0;
        Loud.Info i = Loud.get(this, s.key, s.size);
        return i == null ? 0 : i.in;
    }

    private void opened() {
        open = new String[]{countKey, preKey, goneKey};
    }

    /** is this song's file open in a player right now (the lyrics writer asks) */
    static boolean isOpen(String key) {
        if (key == null) return false;
        for (String k : open) if (key.equals(k)) return true;
        return false;
    }

    private void beginFade(int ms) {
        fadeAt = SystemClock.uptimeMillis();
        fadeLen = Math.max(200, ms);
        MAIN.removeCallbacks(fadeTick);
        MAIN.post(fadeTick);
    }

    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            if (fadeLen <= 0) return;
            float t = (SystemClock.uptimeMillis() - fadeAt) / (float) fadeLen;
            if (t >= 1f) {
                endFade();
                return;
            }
            // equal power: the two together stay about as loud as one song
            float[] vi = mp == null ? null : vol.get(mp), vo = gone == null ? null : vol.get(gone);
            if (vi != null) {
                vi[1] = (float) Math.sin(t * Math.PI / 2);
                applyVol(mp);
            }
            if (vo != null) {
                vo[1] = (float) Math.cos(t * Math.PI / 2);
                applyVol(gone);
            }
            if (t >= 0.5f && mp != null) audioSession = mp.getAudioSessionId();   // the ring follows the new song from halfway
            MAIN.postDelayed(this, 40);
        }
    };

    /** the blend is over (or cut short): the song before goes, the new one plays at full */
    private void endFade() {
        MAIN.removeCallbacks(fadeTick);
        fadeLen = 0;
        pendFade = 0;
        if (gone != null) {
            MediaPlayer g = gone;
            gone = null;
            goneKey = null;
            drop(g);
            opened();
        }
        if (mp != null) {
            float[] v = vol.get(mp);
            if (v != null) v[1] = 1f;
            applyVol(mp);
            audioSession = mp.getAudioSessionId();
        }
    }

    /** open the next song ahead, waiting at its first note */
    private void openPre(String k) {
        stopPre();
        Library.Song s = Library.get(this).byKey.get(k);
        if (s == null) s = standIn(k);
        if (s == null) return;
        Loud.soon(this, s);
        try {
            Embed.waitIfWriting(s.path);
            MediaPlayer m = newPlayer();
            pre = m;
            preKey = k;
            preReady = false;
            opened();
            vol.get(m)[1] = 0f;
            FileInputStream in = new FileInputStream(s.path);
            try {
                m.setDataSource(in.getFD());
            } finally {
                in.close();
            }
            m.prepareAsync();
        } catch (Exception e) {
            stopPre();
        }
    }

    private void stopPre() {
        if (pre != null) drop(pre);
        pre = null;
        preKey = null;
        preReady = false;
        opened();
    }

    /** four times a second while playing: time to open the next song, or to start the blend? */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            cross();
            MAIN.removeCallbacks(this);
            if (isPlaying()) MAIN.postDelayed(this, 250);
        }
    };

    private void cross() {
        int xf = Sound.xfadeMs(this);
        if (xf <= 0 || fadeLen > 0 || gone != null || !isPlaying() || "one".equals(q.repeat)) return;
        int pos, dur;
        try {
            pos = mp.getCurrentPosition();
            dur = mp.getDuration();
        } catch (Exception e) {
            return;
        }
        if (dur <= 0) return;
        Library.Song s = countKey == null ? null : Library.get(this).byKey.get(countKey);
        Loud.Info li = s == null ? null : Loud.get(this, s.key, s.size);
        int end = li != null && li.out > 0 ? Math.min(dur, li.out) : dur;   // where its music really ends
        int len = Math.min(xf, (end - 1000) / 3);                           // a short song gets a short blend
        if (len < 800) return;
        int at = end - len;
        if (pos < at - 15000) return;
        List<String> up = q.upcoming(1);
        if (up.isEmpty()) return;                                            // the last song plays out
        if (pre == null || !up.get(0).equals(preKey)) openPre(up.get(0));
        if (pos >= at && (preReady || pos >= at + 1500)) {
            nextFade = len;
            next(true);
        }
    }

    /** a song was moved to another playlist - the same file under a new key; it plays on */
    void moved(String from, String to) {
        q.rename(from, to);
        if (from.equals(countKey)) countKey = to;
        if (from.equals(preKey)) preKey = to;
        if (from.equals(goneKey)) goneKey = to;
        if (from.equals(artKey)) artKey = to;
        if (from.equals(artWait)) artWait = to;
        if (from.equals(prefs.getString("cur", ""))) prefs.edit().putString("cur", to).apply();
        opened();
        saveQueue();
    }

    /** the Sound settings changed: every player takes them now */
    void soundChanged() {
        for (java.util.Map.Entry<MediaPlayer, Sound.Fx> e : fx.entrySet()) e.getValue().equalize(this);
        level(mp, countKey);
        level(pre, preKey);
        if (Sound.xfadeMs(this) <= 0) stopPre();
    }

    /** measure the songs not measured yet, in the background */
    private final Runnable sweep = new Runnable() {
        @Override
        public void run() {
            try {
                bg.execute(new Runnable() {
                    @Override
                    public void run() {
                        Loud.sweep(PlayerService.this, Library.get(PlayerService.this));
                    }
                });
            } catch (Exception ignored) {     // closing down
            }
        }
    };

    boolean isPlaying() {
        try {
            return prepared && mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private int position() {
        try {
            if (prepared) return mp.getCurrentPosition();
        } catch (Exception ignored) {
        }
        return Math.max(0, seekOnPrepare);
    }

    private int duration() {
        try {
            if (prepared) return mp.getDuration();
        } catch (Exception ignored) {
        }
        Library.Song s = Library.get(this).byKey.get(q.current());
        return s == null ? 0 : (int) s.durMs;
    }

    private boolean getFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(this, MAIN)
                    .build();
        }
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:       // a call, a voice note
                if (isPlaying()) {
                    pause();
                    resumeOnGain = true;
                    changed();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                duck = 0.25f;
                applyVol(mp);
                applyVol(gone);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                duck = 1f;
                applyVol(mp);
                applyVol(gone);
                if (resumeOnGain && prepared) {
                    resumeOnGain = false;
                    start();
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- telling everyone

    /** lock screen, notification and the app screen all catch up */
    private void changed() {
        Library.Song s = Library.get(this).byKey.get(q.current());
        boolean playing = isPlaying();
        boolean loading = preparing && wantPlay;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING
                                : loading ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED,
                        position(), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        boolean held = holdArt(s) && foreground;          // the last song stays up until the picture is ready
        if (s != null && held) {
            // nothing to redraw yet - the media player, the notification and the widget wait for the picture
        } else if (s != null) {
            session.setMetadata(meta(s));
            boolean hold = everPlayed && (resumeOnGain
                    || SystemClock.elapsedRealtime() - pausedAt < HOLD_MS);
            boolean fg = false;
            if (playing || loading || hold) {
                try {
                    startForeground(NID, notification(s, playing || loading));
                    foreground = true;
                    fg = true;
                } catch (Exception e) {
                    // Android 12+ can refuse from the background: show it the plain way below
                }
            }
            if (fg) {
                // in the foreground: nothing more to do
            } else if (everPlayed || playing || loading) {
                if (foreground) {
                    stopForeground(false);
                    foreground = false;
                }
                nm().notify(NID, notification(s, playing || loading));
            }
        }
        if (s == null && foreground) {
            stopForeground(true);
            foreground = false;
        }
        if (!held) refreshWidget();
        Listener l = listener;
        if (l != null) l.onState(stateJson());
        note = "";
    }

    /** the home-screen widget catches up */
    void refreshWidget() {
        Library.Song s = Library.get(this).byKey.get(q.current());
        if (holdArt(s)) return;                          // drawn once the picture is ready (artLate at the latest)
        if (s == null) {
            PlayerWidget.show(this, "Repertorium", "Tap play to start", false, null, q.shuffle, q.repeat, 0, 0);
            return;
        }
        Bitmap small = null;
        if (art != null && s.key.equals(artKey)) small = art;   // the widget rounds it and takes its colours
        PlayerWidget.show(this, LyricsQuery.shownTitle(s.title, s.artist), s.artist.isEmpty() ? s.pl : s.artist,
                isPlaying() || (wantPlay && preparing), small, q.shuffle, q.repeat, position(), duration());
        MAIN.removeCallbacks(widgetTick);
        if (isPlaying()) MAIN.postDelayed(widgetTick, 1000);
    }

    /** the widget's progress bar and times move along once a second while playing and the screen is on */
    private final Runnable widgetTick = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying()) return;
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if ((pm == null || pm.isInteractive()) && PlayerWidget.any(PlayerService.this))
                    PlayerWidget.progress(PlayerService.this, position(), duration());
            } catch (Exception ignored) {
            }
            MAIN.postDelayed(this, 1000);
        }
    };

    private Notification placeholder() {
        int small = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        if (small == 0) small = android.R.drawable.ic_media_play;
        return new Notification.Builder(this, CH).setSmallIcon(small).setContentTitle("Repertorium")
                .setContentIntent(openApp()).build();
    }

    String stateJson() {
        try {
            String k = q.current();
            return "{\"key\":" + Library.q(k)
                    + ",\"pos\":" + position()
                    + ",\"dur\":" + duration()
                    + ",\"playing\":" + (isPlaying() || (wantPlay && preparing))
                    + ",\"shuffle\":" + q.shuffle
                    + ",\"repeat\":" + Library.q(q.repeat)
                    + ",\"qi\":" + q.i
                    + ",\"qn\":" + q.size()
                    + ",\"up\":" + keys(q.upNext)
                    + ",\"nx\":" + keys(q.upcoming(12))
                    + ",\"queued\":" + (q.extra != null)
                    + ",\"note\":" + Library.q(note) + "}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String keys(List<String> l) {
        StringBuilder b = new StringBuilder("[");
        for (String k : l) {
            if (b.length() > 1) b.append(',');
            b.append(Library.q(k));
        }
        return b.append(']').toString();
    }

    private MediaMetadata meta(Library.Song s) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, LyricsQuery.shownTitle(s.title, s.artist))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album.isEmpty() ? s.pl : s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (art != null && s.key.equals(artKey)) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        return b.build();
    }

    private void loadArt(final Library.Song s) {
        if (s.key.equals(artKey)) return;
        art = null;
        artKey = null;
        final Context c = this;
        if (bg.isShutdown()) return;
        artWait = s.key;
        MAIN.removeCallbacks(artLate);
        MAIN.postDelayed(artLate, 700);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = Art.bitmap(c, s, 480);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (instance != PlayerService.this) return;      // closed meanwhile
                        boolean waited = s.key.equals(artWait);
                        if (waited) {
                            artWait = null;
                            MAIN.removeCallbacks(artLate);
                        }
                        if (b != null && s.key.equals(q.current())) {
                            art = b;
                            artKey = s.key;
                            changed();
                        } else if (waited) {
                            changed();                                   // no picture in this song: show it as it is
                        }
                    }
                });
            }
        });
    }

    private Notification notification(Library.Song s, boolean playing) {
        int small = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        if (small == 0) small = android.R.drawable.ic_media_play;
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(small)
                .setContentTitle(LyricsQuery.shownTitle(s.title, s.artist))
                .setContentText(s.artist.isEmpty() ? s.pl : s.artist)
                .setContentIntent(openApp())
                .setDeleteIntent(action(A_STOP, 4))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                        "Previous", action(A_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play),
                        playing ? "Pause" : "Play", action(A_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_next),
                        "Next", action(A_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null && s.key.equals(artKey)) b.setLargeIcon(art);
        return b.build();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    // ---------------------------------------------------------------- remembering

    private void save() {
        String k = q.current();
        prefs.edit()
                .putString("cur", k == null ? "" : k)
                .putInt("pos", position())
                .putInt("i", q.i)
                .putString("lcur", q.listCurrent() == null ? "" : q.listCurrent())
                .putString("extra", q.extra == null ? "" : q.extra)
                .putBoolean("shuffle", q.shuffle)
                .putString("repeat", q.repeat)
                .putString("src", q.src == null ? "" : q.src)
                .apply();
    }

    // ---------------------------------------------------------------- the list keeps all of its playlist

    /*
     * The list
     * was cut down to whatever Android's media index gave back each time the library was read
     * (after downloads, Update library, lyrics saved into songs, ...). Read while the index is
     * busy, that can be only part of the library - and the songs it missed were dropped from
     * the list for good, so shuffle went round and round the ones left. Now a song stays in
     * the list while its file is still on the phone (unless it was hidden), and a list that is
     * a whole playlist takes back every song of that playlist it is missing - new downloads too.
     */

    /** the songs the list may keep: in the library now, or still on the phone and not hidden */
    private java.util.Set<String> keepable(Library lib) {
        java.util.Set<String> keep = new java.util.HashSet<>(lib.byKey.keySet());
        java.util.Set<String> all = new java.util.HashSet<>(q.base);
        all.addAll(q.order);
        all.addAll(q.upNext);
        all.addAll(q.heard);
        if (q.extra != null) all.add(q.extra);
        java.util.Set<String> hid = null;
        String root = Library.rootPath();
        for (String k : all) {
            if (keep.contains(k)) continue;
            if (hid == null) hid = Library.hidden(this);
            if (!hid.contains(k) && new File(root, k).isFile()) keep.add(k);
        }
        return keep;
    }

    /** the playlist these songs are all of (every song of it, nothing else), or null */
    private String wholePlaylist(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        String k0 = keys.get(0);
        int slash = k0.indexOf('/');
        if (slash <= 0) return null;
        String pl = k0.substring(0, slash), pre = pl + "/";
        for (String k : keys) if (!k.startsWith(pre)) return null;
        java.util.Set<String> mine = new java.util.HashSet<>(keys);
        for (Library.Pl p : Library.get(this).playlists) {
            if (!p.name.equals(pl)) continue;
            int miss = 0;                     // a song or two the screen had not shown yet still counts
            for (Library.Song s : p.songs) if (!mine.contains(s.key)) miss++;
            return miss <= Math.max(3, p.songs.size() / 50) ? pl : null;
        }
        return null;
    }

    /** a whole-playlist list takes back the songs of its playlist it is missing; how many came back */
    private int follow(Library lib) {
        if (q.src == null) return 0;
        List<String> all = new ArrayList<>();
        for (Library.Pl p : lib.playlists) {
            if (!p.name.equals(q.src)) continue;
            for (Library.Song s : p.songs) all.add(s.key);
            break;
        }
        return all.isEmpty() ? 0 : q.grow(all);
    }

    /** a song whose file is on the phone though Android's index does not list it right now */
    private Library.Song standIn(String key) {
        if (key == null) return null;
        int slash = key.indexOf('/');
        File f = new File(Library.rootPath(), key);
        if (slash <= 0 || !f.isFile()) return null;
        Library.Song s = new Library.Song();
        s.key = key;
        s.pl = key.substring(0, slash);
        s.file = key.substring(slash + 1);
        s.path = f.getAbsolutePath();
        int dot = s.file.lastIndexOf('.');
        s.title = dot > 0 ? s.file.substring(0, dot) : s.file;
        s.artist = "";
        s.album = "";
        s.size = f.length();
        s.added = f.lastModified() / 1000;
        s.id = -1;
        return s;
    }

    /**
     * each song's tidied name + artist and length, worked out in the
     * background (2,600 names take a moment), so shuffle can tell two
     * files of one song apart from two songs.
     */
    private void describe() {
        final List<String> keys = new ArrayList<>(q.base);
        final Library lib = Library.get(this);
        if (bg.isShutdown()) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                for (String k : keys) {
                    if (q.knows(k)) continue;
                    Library.Song s = lib.byKey.get(k);
                    if (s == null) continue;
                    try {
                        q.info(k, tag(s.title, s.artist), s.durMs);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** "the same song" - its tidied name and first artist */
    static String tag(String title, String artist) {
        LyricsQuery lq = LyricsQuery.forLyrics(title, artist);
        String t = LyricsQuery.key(lq.track);
        return t.isEmpty() ? "" : t + "|" + LyricsQuery.key(LyricsQuery.firstArtist(lq.artist));
    }

    private void saveQueue() {
        writeList("base.txt", q.base);
        writeList("order.txt", q.order);
        writeList("upnext.txt", q.upNext);
        save();
    }

    private void restore() {
        q.setRepeat(prefs.getString("repeat", "off"));
        q.shuffle = prefs.getBoolean("shuffle", false);
        q.base.clear();
        q.order.clear();
        q.base.addAll(readList("base.txt"));
        q.order.addAll(readList("order.txt"));
        q.upNext.clear();
        q.upNext.addAll(readList("upnext.txt"));
        q.heard.clear();
        q.heard.addAll(readList("heard.txt"));
        String cur = prefs.getString("cur", "");
        String ex = prefs.getString("extra", "");
        q.extra = ex.isEmpty() ? null : ex;
        q.i = prefs.getInt("i", -1);
        String src = prefs.getString("src", null);
        if (src == null) {                    // , once: an older list that is all from one playlist follows it
            String pl = null;
            for (String k : q.base) {
                int sl = k.indexOf('/');
                String p = sl > 0 ? k.substring(0, sl) : "";
                if (pl == null) pl = p;
                else if (!pl.equals(p)) { pl = ""; break; }
            }
            src = pl == null ? "" : pl;
        }
        q.src = src.isEmpty() ? null : src;
        Library lib = Library.get(this);
        q.keepOnly(keepable(lib), prefs.getString("lcur", cur));     // songs still on the phone stay
        if (follow(lib) > 0) saveQueue();
        describe();
        String k = q.current();
        if (k != null) load(k, k.equals(cur) ? prefs.getInt("pos", 0) : 0, false);
        else changed();
    }

    private void writeList(String name, List<String> list) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(), name)), "UTF-8");
            try {
                for (String s : list) w.write(s + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> readList(String name) {
        List<String> out = new ArrayList<>();
        File f = new File(getFilesDir(), name);
        if (!f.isFile()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) if (!line.isEmpty()) out.add(line);
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
