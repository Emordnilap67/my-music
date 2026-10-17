package io.github.emordnilap67.mymusic;

import android.media.audiofx.Visualizer;
import android.os.Process;
import android.os.SystemClock;

final class Viz {
    static final int EVERY_MS = 25;            // 40 readings a second
    static final long IDLE_MS = 1500;          // nobody asking for this long: stop

    private static final Object LOCK = new Object();
    private static volatile Thread loop;       // the one capture thread (null = none)
    private static volatile String latest = "";
    private static volatile long askedAt;
    private static volatile long brokeAt;

    private Viz() {}

    /** "" = nothing yet (try again), "x" = not available, else BANDS numbers 0..100 with commas.
     *  Never waits: the newest reading the ring thread has taken. */
    static String read() {
        askedAt = SystemClock.uptimeMillis();
        if (loop == null) start();
        return latest;
    }

    private static void start() {
        synchronized (LOCK) {
            if (loop != null) return;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    capture();
                }
            }, "ring");
            t.setDaemon(true);
            loop = t;
            t.start();
        }
    }

    /** the ring thread: one reading every EVERY_MS until nobody asks or release() */
    private static void capture() {
        final Thread me = Thread.currentThread();
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        } catch (Throwable ignored) {
        }
        Visualizer v = null;
        int session = 0;
        byte[] wave = null;
        Spectrum spec = null;
        int[] out = new int[Spectrum.BANDS];
        StringBuilder b = new StringBuilder(Spectrum.BANDS * 3);
        long next = SystemClock.uptimeMillis();
        try {
            while (loop == me) {
                long now = SystemClock.uptimeMillis();
                if (now - askedAt > IDLE_MS) break;                 // the page stopped asking
                int s = PlayerService.audioSession;
                if (s == 0) {
                    latest = "";
                } else if (v == null || s != session) {
                    v = free(v);
                    session = 0;
                    if (now - brokeAt < 5000) {
                        latest = "x";
                    } else {
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
                            } catch (RuntimeException e) {
                                x.release();
                                throw e;
                            }
                            v = x;
                            session = s;
                        } catch (Throwable e) {
                            brokeAt = SystemClock.uptimeMillis();
                            latest = "x";
                        }
                    }
                }
                if (v != null) {
                    boolean ok;
                    try {
                        ok = v.getWaveForm(wave) == Visualizer.SUCCESS;
                    } catch (Throwable e) {
                        ok = false;
                    }
                    if (!ok) {                       // e.g. Android's sound system restarted: build it again soon
                        v = free(v);
                        session = 0;
                        brokeAt = SystemClock.uptimeMillis();
                        latest = "";
                    } else {
                        spec.bands(wave, out);
                        b.setLength(0);
                        for (int i = 0; i < out.length; i++) {
                            if (i > 0) b.append(',');
                            b.append(out[i]);
                        }
                        latest = b.toString();
                    }
                }
                // a steady beat: the next reading EVERY_MS after the last one was due
                next += EVERY_MS;
                long wait = next - SystemClock.uptimeMillis();
                if (wait < 0) {                      // fell behind (phone busy): don't try to catch up
                    next = SystemClock.uptimeMillis();
                    wait = v == null ? 100 : 0;
                }
                if (v == null && wait < 100) wait = 100;   // nothing to read: look again a little later
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } finally {
            free(v);
            synchronized (LOCK) {
                if (loop == me) loop = null;
            }
            latest = "";
        }
    }

    private static Visualizer free(Visualizer v) {
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
        return null;
    }

    /** stop the ring thread now (player closed, app in the background) */
    static void release() {
        Thread t;
        synchronized (LOCK) {
            t = loop;
            loop = null;
        }
        if (t != null) t.interrupt();          // it frees the Visualizer on its way out
        latest = "";
    }

    /** after the permission is given: try again at once */
    static void reset() {
        brokeAt = 0;
        release();
    }
}
