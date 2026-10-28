package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Sound settings and the effects on each player.
 * - EVEN OUT THE VOLUME (on unless turned off): each song is brought to about the same
 *   loudness (Loud.java measures it). A loud song is turned down with the player's own
 *   volume; a quiet one is turned up with Android's LoudnessEnhancer, which keeps the peaks
 *   from clipping.
 * - CROSSFADE (6 s unless changed, 0 = off): songs blend into each other; see PlayerService.
 * - EQUALIZER (off unless turned on): Android's own, with this phone's bands and presets.
 * - LOUDER ON BLUETOOTH (+8 dB unless changed, 0 = off): on a Bluetooth
 *   speaker, earbuds, the car, wired or USB headphones every song gets this much more, through the same
 *   LoudnessEnhancer (it holds the peaks back, so nothing clips). The phone's own speaker stays as it was.
 * - on Bluetooth a song is never turned down any more (a loud EDM master was cut 4-6 dB to
 *   even it out, and the boost only won that back), then the boost goes on top: 12 dB unless changed, up to 18.
 *   The Sound sheet says which speaker it is playing through.
 * Settings live in the player's preferences, so the player uses them with the app closed.
 */
final class Sound {

    private Sound() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    static boolean levelOn(Context c) {
        return prefs(c).getBoolean("level", true);
    }

    /** the crossfade in ms (0 = off) */
    static int xfadeMs(Context c) {
        return Math.max(0, Math.min(12, prefs(c).getInt("xfade", 6))) * 1000;
    }

    /** how many dB louder on Bluetooth and other outside speakers (0 = off) - 12 unless changed, up to 18 */
    static int boostDb(Context c) {
        return Math.max(0, Math.min(18, prefs(c).getInt("boost", 12)));
    }

    /** the music goes out to a Bluetooth / wired / USB device, not the phone's own speaker */
    static boolean outside(Context c) {
        return outDevice(c) != null;
    }

    /** the same, also asking the player where its sound really goes right now */
    static boolean outside(Context c, MediaPlayer m) {
        if (outDevice(c) != null) return true;
        if (m == null || Build.VERSION.SDK_INT < 28) return false;
        try {
            AudioDeviceInfo d = m.getRoutedDevice();
            return d != null && isOut(d.getType());
        } catch (Throwable e) {
            return false;
        }
    }

    /** the outside speaker's name, for the Sound sheet ("" = none) */
    static String outName(Context c) {
        AudioDeviceInfo d = outDevice(c);
        if (d == null) return "";
        CharSequence n = d.getProductName();
        return n == null ? "" : n.toString().trim();
    }

    private static AudioDeviceInfo outDevice(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (isOut(d.getType())) return d;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isOut(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_DOCK:
                return true;
            default:
                return false;
        }
    }

    static boolean eqOn(Context c) {
        return prefs(c).getBoolean("eqOn", false);
    }

    /** the equalizer's band levels (millibels), as saved */
    static int[] eqLevels(Context c) {
        String s = prefs(c).getString("eqLv", "");
        if (s.isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the effects on one player

    /** the equalizer and the quiet-song boost on one player's sound */
    static final class Fx {
        private Equalizer eq;
        private LoudnessEnhancer le;

        static Fx attach(Context c, MediaPlayer m) {
            Fx f = new Fx();
            int s = m.getAudioSessionId();
            try {
                f.le = new LoudnessEnhancer(s);
            } catch (Throwable ignored) {
            }
            try {
                f.eq = new Equalizer(0, s);
            } catch (Throwable ignored) {
            }
            f.equalize(c);
            return f;
        }

        /** turn the song up this many dB (0 or less = off) */
        void boost(float db) {
            if (le == null) return;
            try {
                if (db > 0.05f) {
                    le.setTargetGain(Math.round(db * 100));
                    le.setEnabled(true);
                } else {
                    le.setEnabled(false);
                }
            } catch (Throwable ignored) {
            }
        }

        /** the equalizer as the settings say */
        void equalize(Context c) {
            if (eq == null) return;
            try {
                if (!eqOn(c)) {
                    eq.setEnabled(false);
                    return;
                }
                int[] lv = eqLevels(c);
                short n = eq.getNumberOfBands();
                short[] r = eq.getBandLevelRange();
                for (short b = 0; b < n; b++) {
                    int v = b < lv.length ? lv[b] : 0;
                    eq.setBandLevel(b, (short) Math.max(r[0], Math.min(r[1], v)));
                }
                eq.setEnabled(true);
            } catch (Throwable ignored) {
            }
        }

        void release() {
            try {
                if (eq != null) eq.release();
            } catch (Throwable ignored) {
            }
            try {
                if (le != null) le.release();
            } catch (Throwable ignored) {
            }
            eq = null;
            le = null;
        }
    }

    // ------------------------------------------------------------------ the page's Sound sheet

    /** everything the Sound sheet shows, as JSON */
    static String info(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("level", levelOn(c));
            o.put("xfade", xfadeMs(c) / 1000);
            o.put("boost", boostDb(c));
            o.put("outside", outside(c));
            o.put("outName", outName(c));
            o.put("measured", Loud.count(c));
            o.put("total", Library.get(c).byKey.size());
            JSONObject e = new JSONObject();
            e.put("on", eqOn(c));
            Equalizer q = null;
            try {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                q = new Equalizer(0, am.generateAudioSessionId());
                short n = q.getNumberOfBands();
                short[] r = q.getBandLevelRange();
                e.put("min", r[0]);
                e.put("max", r[1]);
                int[] saved = eqLevels(c);
                JSONArray hz = new JSONArray(), lv = new JSONArray();
                for (short b = 0; b < n; b++) {
                    hz.put(q.getCenterFreq(b) / 1000);
                    lv.put(b < saved.length ? Math.max(r[0], Math.min(r[1], saved[b])) : 0);
                }
                e.put("hz", hz);
                e.put("lv", lv);
                JSONArray ps = new JSONArray();
                for (short p = 0; p < q.getNumberOfPresets(); p++) {
                    q.usePreset(p);
                    JSONObject one = new JSONObject();
                    one.put("n", q.getPresetName(p));
                    JSONArray pl = new JSONArray();
                    for (short b = 0; b < n; b++) pl.put(q.getBandLevel(b));
                    one.put("lv", pl);
                    ps.put(one);
                }
                e.put("presets", ps);
            } catch (Throwable t) {
                e.put("none", true);                      // this phone has no equalizer for apps
            } finally {
                if (q != null) q.release();
            }
            o.put("eq", e);
            return o.toString();
        } catch (Exception x) {
            return "{}";
        }
    }

    /** a change from the Sound sheet: any of level, xfade, boost, eqOn, eqLv */
    static void set(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = prefs(c).edit();
            if (o.has("level")) ed.putBoolean("level", o.getBoolean("level"));
            if (o.has("xfade")) ed.putInt("xfade", Math.max(0, Math.min(12, o.getInt("xfade"))));
            if (o.has("boost")) ed.putInt("boost", Math.max(0, Math.min(18, o.getInt("boost"))));
            if (o.has("eqOn")) ed.putBoolean("eqOn", o.getBoolean("eqOn"));
            if (o.has("eqLv")) {
                JSONArray a = o.getJSONArray("eqLv");
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    if (i > 0) b.append(',');
                    b.append(a.getInt(i));
                }
                ed.putString("eqLv", b.toString());
            }
            ed.apply();
        } catch (Exception ignored) {
        }
    }
}
