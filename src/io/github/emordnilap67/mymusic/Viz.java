package io.github.emordnilap67.mymusic;

import android.media.audiofx.Visualizer;

/**
 * The moving ring. Android's Visualizer hands MY MUSIC a
 * slice of its OWN sound (only the player's audio session - never the
 * microphone); Spectrum turns it into BANDS numbers for the page.
 * On only while the player screen is showing the ring.
 * Needs RECORD_AUDIO: that is the name Android gives this permission.
 */
final class Viz {
    private static Visualizer v;
    private static int session;
    private static byte[] wave;
    private static Spectrum spec;
    private static int[] out;
    private static long brokeAt;

    private Viz() {}

    /** "" = nothing yet (try again), "x" = not available, else BANDS numbers 0..100 with commas */
    static synchronized String read() {
        int s = PlayerService.audioSession;
        if (s == 0) return "";
        if (v == null || s != session) {
            release();
            if (System.currentTimeMillis() - brokeAt < 5000) return "x";
            try {
                Visualizer x = new Visualizer(s);
                try {
                    x.setEnabled(false);
                    int[] r = Visualizer.getCaptureSizeRange();
                    int n = Math.max(r[0], Math.min(1024, r[1]));
                    x.setCaptureSize(n);
                    x.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                    if (x.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException("not enabled");
                    n = x.getCaptureSize();
                    wave = new byte[n];
                    spec = new Spectrum(n, x.getSamplingRate() / 1000);
                    out = new int[Spectrum.BANDS];
                } catch (RuntimeException e) {
                    x.release();
                    throw e;
                }
                v = x;
                session = s;
            } catch (Throwable e) {
                brokeAt = System.currentTimeMillis();
                return "x";
            }
        }
        try {
            if (v.getWaveForm(wave) != Visualizer.SUCCESS) {
                release();                        // e.g. Android's sound system restarted: build it again soon
                brokeAt = System.currentTimeMillis();
                return "";
            }
        } catch (Throwable e) {
            release();
            brokeAt = System.currentTimeMillis();
            return "";
        }
        spec.bands(wave, out);
        StringBuilder b = new StringBuilder(Spectrum.BANDS * 3);
        for (int i = 0; i < out.length; i++) {
            if (i > 0) b.append(',');
            b.append(out[i]);
        }
        return b.toString();
    }

    static synchronized void release() {
        if (v != null) {
            try {
                v.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                v.release();
            } catch (Throwable ignored) {
            }
        }
        v = null;
        session = 0;
    }

    /** after the permission is given: try again at once */
    static synchronized void reset() {
        release();
        brokeAt = 0;
    }
}
