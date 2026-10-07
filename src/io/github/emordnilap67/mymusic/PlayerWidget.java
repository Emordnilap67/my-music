package io.github.emordnilap67.mymusic;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/** The MY MUSIC home-screen widget: picture, song, previous / play / next. */
public class PlayerWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        PlayerService s = PlayerService.instance;
        if (s != null) {
            s.refreshWidget();
        } else {
            android.content.SharedPreferences p = c.getSharedPreferences("player", Context.MODE_PRIVATE);
            show(c, "MY MUSIC", "Tap play to start", false, null,
                    p.getBoolean("shuffle", false), p.getString("repeat", "off"));
        }
    }

    private static int id(Context c, String type, String name) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }

    static void show(Context c, String title, String artist, boolean playing, Bitmap art,
                     boolean shuffle, String repeat) {
        try {
            Resources r = c.getResources();
            String pkg = c.getPackageName();
            int layout = r.getIdentifier("widget", "layout", pkg);
            if (layout == 0) return;
            RemoteViews v = new RemoteViews(pkg, layout);
            v.setTextViewText(id(c, "id", "w_title"), title);
            v.setTextViewText(id(c, "id", "w_artist"), artist);
            v.setImageViewResource(id(c, "id", "w_play"), id(c, "drawable", playing ? "ic_w_pause" : "ic_w_play"));
            v.setImageViewResource(id(c, "id", "w_shuf"), id(c, "drawable", shuffle ? "ic_w_shuf_on" : "ic_w_shuf_off"));
            v.setImageViewResource(id(c, "id", "w_rep"), id(c, "drawable",
                    "one".equals(repeat) ? "ic_w_rep_one" : "all".equals(repeat) ? "ic_w_rep_all" : "ic_w_rep_off"));
            v.setOnClickPendingIntent(id(c, "id", "w_shuf"), button(c, PlayerService.A_SHUF, 16));
            v.setOnClickPendingIntent(id(c, "id", "w_rep"), button(c, PlayerService.A_REP, 17));
            if (art != null) v.setImageViewBitmap(id(c, "id", "w_art"), art);
            else v.setImageViewResource(id(c, "id", "w_art"), id(c, "mipmap", "ic_launcher_fg"));
            v.setOnClickPendingIntent(id(c, "id", "w_prev"), button(c, PlayerService.A_PREV, 11));
            v.setOnClickPendingIntent(id(c, "id", "w_play"), button(c, PlayerService.A_TOGGLE, 12));
            v.setOnClickPendingIntent(id(c, "id", "w_next"), button(c, PlayerService.A_NEXT, 13));
            PendingIntent open = PendingIntent.getActivity(c, 14, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(id(c, "id", "w_art"), open);
            v.setOnClickPendingIntent(id(c, "id", "w_text"), open);
            AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, PlayerWidget.class), v);
        } catch (Exception ignored) {
            // no widget on the home screen, or the launcher is busy: nothing to do
        }
    }

    /** widget buttons start the player as a foreground service (allowed for widget taps) */
    private static PendingIntent button(Context c, String action, int code) {
        Intent i = new Intent(c, PlayerService.class).setAction(action).putExtra("fg", true);
        return PendingIntent.getForegroundService(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
