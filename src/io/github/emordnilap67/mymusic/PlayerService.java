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
        if (mp != null) {
            Viz.release();
            audioSession = 0;
            mp.release();
            mp = null;
        }
        session.setActive(false);
        session.release();
        bg.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- commands

    void setQueue(List<String> keys, int index, boolean shuffle) {
        q.set(keys, index, shuffle);
        saveQueue();
        String k = q.current();
        if (k != null) load(k, 0, true);
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
        String k = q.next(auto);
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
        if (prepared) mp.seekTo(Math.max(0, ms));
        else seekOnPrepare = Math.max(0, ms);
        changed();
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
        if (q.order.isEmpty()) {
            restore();
            return;
        }
        String before = q.current();
        boolean was = isPlaying() || (wantPlay && preparing);
        q.keepOnly(Library.get(this).byKey.keySet(), before);
        saveQueue();
        String now = q.current();
        if (before != null && !before.equals(now)) {
            // the song that was on got hidden or deleted
            if (now != null) {
                load(now, 0, was);
            } else {
                wantPlay = false;
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
        wantPlay = play;
        if (s == null) {
            if (mp != null) mp.reset();
            prepared = false;
            preparing = false;
            note = "That song is not in the library any more";
            changed();
            return;
        }
        if (mp == null) {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            audioSession = mp.getAudioSessionId();
        }
        mp.reset();
        prepared = false;
        preparing = true;
        seekOnPrepare = at;
        countKey = key;                       // a new time through this song
        counted = false;
        prefs.edit().putString("cur", key).putInt("pos", at).apply();
        try {
            // by file handle, not by name: a ":" in a file name would confuse Android
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
        prepared = true;
        preparing = false;
        if (seekOnPrepare > 0) m.seekTo(seekOnPrepare);
        seekOnPrepare = -1;
        if (wantPlay) start();
        else changed();
    }

    private void start() {
        if (!getFocus()) {
            wantPlay = false;
            note = "Another app has the sound right now";
            changed();
            return;
        }
        mp.setVolume(1f, 1f);
        mp.start();
        wantPlay = true;
        everPlayed = true;
        fails = 0;
        changed();
        MAIN.removeCallbacks(saver);
        MAIN.postDelayed(saver, 5000);
    }

    @Override
    public void onCompletion(MediaPlayer m) {
        countPlay();                          // a short song that ended between checks
        next(true);
    }

    @Override
    public boolean onError(MediaPlayer m, int what, int extra) {
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
                if (isPlaying()) mp.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (prepared) mp.setVolume(1f, 1f);
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
        if (s != null) {
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
        refreshWidget();
        Listener l = listener;
        if (l != null) l.onState(stateJson());
        note = "";
    }

    /** the home-screen widget catches up */
    void refreshWidget() {
        Library.Song s = Library.get(this).byKey.get(q.current());
        if (s == null) {
            PlayerWidget.show(this, "MY MUSIC", "Tap play to start", false, null, q.shuffle, q.repeat, 0, 0);
            return;
        }
        Bitmap small = null;
        if (art != null && s.key.equals(artKey)) small = art;   // the widget rounds it and takes its colours
        PlayerWidget.show(this, s.title, s.artist.isEmpty() ? s.pl : s.artist,
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
        return new Notification.Builder(this, CH).setSmallIcon(small).setContentTitle("MY MUSIC")
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
                    + ",\"note\":" + Library.q(note) + "}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private MediaMetadata meta(Library.Song s) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
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
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = Art.bitmap(c, s, 480);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (instance != PlayerService.this) return;      // closed meanwhile
                        if (b != null && s.key.equals(q.current())) {
                            art = b;
                            artKey = s.key;
                            changed();
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
                .setContentTitle(s.title)
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
                .putBoolean("shuffle", q.shuffle)
                .putString("repeat", q.repeat)
                .apply();
    }

    private void saveQueue() {
        writeList("base.txt", q.base);
        writeList("order.txt", q.order);
        save();
    }

    private void restore() {
        q.setRepeat(prefs.getString("repeat", "off"));
        q.shuffle = prefs.getBoolean("shuffle", false);
        q.base.clear();
        q.order.clear();
        q.base.addAll(readList("base.txt"));
        q.order.addAll(readList("order.txt"));
        String cur = prefs.getString("cur", "");
        q.i = prefs.getInt("i", -1);
        q.keepOnly(Library.get(this).byKey.keySet(), cur);
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
