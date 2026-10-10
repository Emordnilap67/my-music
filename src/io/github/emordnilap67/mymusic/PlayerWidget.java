package io.github.emordnilap67.mymusic;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * The MY MUSIC home-screen widget: a neon card - a glowing edge, the big rounded
 * picture, a big title, the whole bar in colour with a white knob, a
 * glowing play / pause button between dark previous / next circles,
 * shuffle and repeat in the corners with a dot when on. The colours come
 * from the song's picture (Neon.palette, the same as the player screen).
 * Everything is laid out by weights from the widget's real size, so it
 * fills the widget the same on any launcher; a one-row widget is a strip.
 */
public class PlayerWidget extends AppWidgetProvider {
    // what every widget shows now (kept, so a resized widget can be drawn again)
    private static String title = "MY MUSIC", artist = "Tap play to start", repeat = "off";
    private static boolean playing, shuffle;
    private static Bitmap art;
    private static long posMs, durMs;

    // worked out from the picture, kept until the picture changes
    private static Bitmap artFrom, rounded;
    private static boolean artDone;
    private static int[] cols = {Neon.PINK, Neon.BLUE};
    // drawn parts, kept while nothing they show changes
    private static final Map<String, Bitmap[]> PARTS = new HashMap<>();

    /** the card's edge per size: {glow room, corner radius, edge width} dp and the piece size (as in res/layout) */
    static final float[] FULL_EDGE = {13f, 21f, 1.7f}, MID_EDGE = {9f, 16f, 1.5f};
    static final int FULL_PIECE = 37, MID_PIECE = 29;
    /** sizes (dp, upright): this big or more = the full card / the smaller card; less = a one-row strip */
    static final int FULL_W = 300, FULL_H = 160, MID_W = 200, MID_H = 96;
    /** drawn sizes (dp): the picture, the button row, the corner toggles, the bar */
    private static final int ART_DP = 160, BUTTONS_DP = 64, TOGGLE_DP = 22, BAR_W = 384;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        PlayerService s = PlayerService.instance;
        if (s != null) {
            s.refreshWidget();
        } else {
            loadPrefs(c);
            drawAll(c);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        if (PlayerService.instance == null) loadPrefs(c);
        draw(c, m, id);                                   // resized: maybe the other card
    }

    private static void loadPrefs(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        shuffle = p.getBoolean("shuffle", false);
        repeat = p.getString("repeat", "off");
    }

    private static int id(Context c, String type, String name) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }

    static void show(Context c, String t, String a, boolean isPlaying, Bitmap picture,
                     boolean shuf, String rep) {
        show(c, t, a, isPlaying, picture, shuf, rep, posMs, durMs);
    }

    static void show(Context c, String t, String a, boolean isPlaying, Bitmap picture,
                     boolean shuf, String rep, long pos, long dur) {
        title = t;
        artist = a;
        playing = isPlaying;
        art = picture;
        shuffle = shuf;
        repeat = rep == null ? "off" : rep;
        posMs = pos;
        durMs = dur;
        drawAll(c);
    }

    /** any widget on the home screen? */
    static boolean any(Context c) {
        try {
            return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, PlayerWidget.class)).length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** the song moved on: only the knob and the times (a small update, once a second while playing) */
    static void progress(Context c, long pos, long dur) {
        posMs = pos;
        durMs = dur;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int wid : m.getAppWidgetIds(new ComponentName(c, PlayerWidget.class))) {
                Bundle o = m.getAppWidgetOptions(wid);
                RemoteViews v = new RemoteViews(c.getPackageName(), layoutFor(c, size(o)));
                times(c, v, size(o));
                m.partiallyUpdateAppWidget(wid, v);
            }
        } catch (Exception ignored) {
        }
    }

    private static void drawAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int wid : m.getAppWidgetIds(new ComponentName(c, PlayerWidget.class))) draw(c, m, wid);
        } catch (Exception ignored) {
            // no widget on the home screen, or the launcher is busy: nothing to do
        }
    }

    /** 2 = full card, 1 = smaller card, 0 = one-row strip (sizes upright, dp) */
    static int size(int w, int h) {
        if (h == 0 || w == 0) return 2;                  // the launcher did not say: the full card fits itself
        if (w >= FULL_W && h >= FULL_H) return 2;
        if (w >= MID_W && h >= MID_H) return 1;
        return 0;
    }

    private static int size(Bundle o) {
        if (o == null) return 2;
        return size(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),     // its width upright
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0));       // its height upright
    }

    private static int layoutFor(Context c, int z) {
        int l = id(c, "layout", z == 2 ? "widget" : z == 1 ? "widget_mid" : "widget_small");
        return l != 0 ? l : id(c, "layout", "widget");
    }

    private static void draw(Context c, AppWidgetManager m, int wid) {
        int z = 2;
        try {
            z = size(m.getAppWidgetOptions(wid));
            m.updateAppWidget(wid, build(c, z, true));
        } catch (Exception e) {
            try {                                        // too much at once for this phone: the plain card
                m.updateAppWidget(wid, build(c, z, false));
            } catch (Exception ignored) {
            }
        }
    }

    /** the picture and its colours, worked out once per picture */
    private static void prepareArt(Context c) {
        if (artDone && art == artFrom) return;
        artFrom = art;
        artDone = true;
        float dp = c.getResources().getDisplayMetrics().density;
        int px = Math.max(200, Math.min(440, Math.round(ART_DP * dp)));
        Bitmap src = art;
        if (src == null) {
            try {
                src = BitmapFactory.decodeResource(c.getResources(), id(c, "mipmap", "ic_launcher_fg"));
            } catch (Exception ignored) {
                src = null;
            }
        }
        rounded = src != null ? NeonArt.art(src, px) : null;
        cols = art != null ? Neon.ends(Neon.palette(NeonArt.pixels(art))) : new int[]{Neon.PINK, Neon.BLUE};
        PARTS.clear();
    }

    /** a drawn part, kept */
    private static Bitmap[] part(String key) {
        return PARTS.get(key);
    }

    private static Bitmap[] keep(String key, Bitmap... b) {
        if (PARTS.size() > 24) PARTS.clear();
        PARTS.put(key, b);
        return b;
    }

    private static RemoteViews build(Context c, int z, boolean rich) {
        prepareArt(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), layoutFor(c, z));
        v.setTextViewText(id(c, "id", "w_title"), title);
        v.setTextViewText(id(c, "id", "w_artist"), artist);
        v.setOnClickPendingIntent(id(c, "id", "w_shuf"), button(c, PlayerService.A_SHUF, 16));
        v.setOnClickPendingIntent(id(c, "id", "w_rep"), button(c, PlayerService.A_REP, 17));
        v.setOnClickPendingIntent(id(c, "id", "w_prev"), button(c, PlayerService.A_PREV, 11));
        v.setOnClickPendingIntent(id(c, "id", "w_play"), button(c, PlayerService.A_TOGGLE, 12));
        v.setOnClickPendingIntent(id(c, "id", "w_next"), button(c, PlayerService.A_NEXT, 13));
        PendingIntent open = PendingIntent.getActivity(c, 14, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(id(c, "id", "w_art"), open);
        v.setOnClickPendingIntent(id(c, "id", "w_text"), open);
        if (z == 0) {
            strip(c, v);
        } else {
            card(c, v, z, rich);
        }
        times(c, v, z);
        return v;
    }

    /** the one-row strip */
    private static void strip(Context c, RemoteViews v) {
        v.setImageViewResource(id(c, "id", "w_play"), id(c, "drawable", playing ? "ic_wn_pause" : "ic_wn_play"));
        v.setImageViewResource(id(c, "id", "w_shuf"), id(c, "drawable", shuffle ? "ic_wn_shuf_on" : "ic_wn_shuf_off"));
        v.setImageViewResource(id(c, "id", "w_rep"), id(c, "drawable",
                "one".equals(repeat) ? "ic_wn_rep_one" : "all".equals(repeat) ? "ic_wn_rep_on" : "ic_wn_rep_off"));
        if (rounded != null) v.setImageViewBitmap(id(c, "id", "w_art"), Bitmap.createScaledBitmap(rounded, 120, 120, true));
    }

    /** the neon card (full or smaller) */
    private static void card(Context c, RemoteViews v, int z, boolean rich) {
        float dp = c.getResources().getDisplayMetrics().density;
        int c1 = cols[0], c2 = cols[1];
        String rep = repeat == null ? "off" : repeat;
        if (rich) {
            float[] e = z == 2 ? FULL_EDGE : MID_EDGE;
            int k = Math.max(8, Math.round((z == 2 ? FULL_PIECE : MID_PIECE) * dp));
            String fk = "frame" + z + "/" + c1 + "/" + c2;
            Bitmap[] f = part(fk);
            if (f == null) f = keep(fk, NeonArt.frame(e[0] * dp, e[1] * dp, e[2] * dp, k, c1, c2));
            String[] ids = {"w_f_tl", "w_f_t", "w_f_tr", "w_f_l", "w_f_r", "w_f_bl", "w_f_b", "w_f_br"};
            for (int i = 0; i < ids.length; i++) v.setImageViewBitmap(id(c, "id", ids[i]), f[i]);
            v.setViewVisibility(id(c, "id", "w_plain"), View.GONE);
            if (rounded != null) v.setImageViewBitmap(id(c, "id", "w_art"), rounded);
        } else {
            v.setViewVisibility(id(c, "id", "w_plain"), View.VISIBLE);
            if (rounded != null) v.setImageViewBitmap(id(c, "id", "w_art"), Bitmap.createScaledBitmap(rounded, 160, 160, true));
        }
        int s = Math.max(64, Math.min(220, Math.round(BUTTONS_DP * dp)));
        String bk = "buttons/" + s + "/" + playing + "/" + c1 + "/" + c2;
        Bitmap[] b = part(bk);
        if (b == null) b = keep(bk, NeonArt.side(s, false, c1), NeonArt.play(s, playing, c1, c2), NeonArt.side(s, true, c2));
        v.setImageViewBitmap(id(c, "id", "w_prev"), b[0]);
        v.setImageViewBitmap(id(c, "id", "w_play"), b[1]);
        v.setImageViewBitmap(id(c, "id", "w_next"), b[2]);
        int th = Math.max(24, Math.round(TOGGLE_DP * dp));
        String tk = "toggles/" + th + "/" + shuffle + "/" + rep + "/" + c1 + "/" + c2;
        Bitmap[] t = part(tk);
        if (t == null) t = keep(tk, NeonArt.toggle(th, true, shuffle ? "all" : "off", c1), NeonArt.toggle(th, false, rep, c2));
        v.setImageViewBitmap(id(c, "id", "w_shuf"), t[0]);
        v.setImageViewBitmap(id(c, "id", "w_rep"), t[1]);
        String rk = "bar/" + c1 + "/" + c2;
        Bitmap[] r = part(rk);
        if (r == null) r = keep(rk, NeonArt.track(BAR_W, Math.round(10 * dp), 3f * dp, c1, c2));
        v.setImageViewBitmap(id(c, "id", "w_track"), r[0]);
    }

    /** where the knob sits (the bar's progress, 1 - 10000: 0 would hide the knob) */
    static int knob(long pos, long dur) {
        double frac = dur > 0 ? Math.max(0, Math.min(1, pos / (double) dur)) : 0;
        return (int) Math.max(1, Math.round(frac * 10000));
    }

    /** the knob on the bar and the two times */
    private static void times(Context c, RemoteViews v, int z) {
        if (z == 0) {
            double frac = durMs > 0 ? Math.max(0, Math.min(1, posMs / (double) durMs)) : 0;
            v.setProgressBar(id(c, "id", "w_prog"), 1000, (int) Math.round(frac * 1000), false);
            return;
        }
        v.setProgressBar(id(c, "id", "w_prog"), 10000, knob(posMs, durMs), false);
        v.setTextViewText(id(c, "id", "w_pos"), clock(posMs));
        v.setTextViewText(id(c, "id", "w_dur"), clock(durMs));
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** widget buttons start the player as a foreground service (allowed for widget taps) */
    private static PendingIntent button(Context c, String action, int code) {
        Intent i = new Intent(c, PlayerService.class).setAction(action).putExtra("fg", true);
        return PendingIntent.getForegroundService(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
