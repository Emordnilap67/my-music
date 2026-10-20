package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.BatteryManager;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * How loud each song is,
 * measured once and kept, so the player can bring every song to about the same loudness.
 * Songs come from many different YouTube uploads and differ by 10 dB or more.
 *
 * Measured the way broadcast loudness is (ITU BS.1770: K-weighting, 400 ms blocks, the
 * quiet parts gated out), on ten 4-second slices spread through the song - within a dB or
 * so of the whole-song figure at a fraction of the work. Also the first 15 s and the last
 * 20 s in 50 ms steps: where the sound really starts and ends, so the crossfade begins at
 * the end of the music, not after a silent tail, and the next song starts on its first
 * note. About a second a song.
 *
 * Kept in files/loud.json: key -> [loudness x10 (LUFS), start ms, end ms, file size].
 * A song whose file size changed (new audio) is measured again.
 *
 * Asked-for songs (the one playing, the next few) go first. The rest of the library is
 * measured in the background, one song at a time, while music plays or the phone charges.
 */
final class Loud {

    static final class Info {
        float lufs;
        int in, out;
        long size;
    }

    interface Done {
        void measured(String key);
    }

    /** where every song is brought to (LUFS) - about as loud as the loud setting of the big apps */
    static final float TARGET = -11f;
    /** the most a song is turned down / up (dB) */
    static final float MAX_CUT = 15f, MAX_BOOST = 6f;
    /** a song not measured yet: a guess for a typical modern song */
    static final float UNKNOWN_DB = -3f;
    /** quieter than this (dBFS, 50 ms) counts as silence at the start and end */
    private static final double SILENT = -50;

    private static final Map<String, Info> MAP = new HashMap<>();
    private static File file;
    private static final LinkedList<String[]> SOON = new LinkedList<>();     // {key, path, size}
    private static final LinkedList<String[]> LATER = new LinkedList<>();
    private static Thread worker;
    private static int dirty;
    static volatile Done done;

    private Loud() {
    }

    // ------------------------------------------------------------------ the store

    private static void load(Context c) {
        if (file != null) return;
        file = new File(c.getApplicationContext().getFilesDir(), "loud.json");
        if (!file.isFile()) return;
        try {
            byte[] b = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null || a.length() < 4) continue;
                Info i = new Info();
                i.lufs = a.optInt(0) / 10f;
                i.in = a.optInt(1);
                i.out = a.optInt(2);
                i.size = a.optLong(3);
                MAP.put(k, i);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            synchronized (Loud.class) {
                for (Map.Entry<String, Info> e : MAP.entrySet()) {
                    Info i = e.getValue();
                    JSONArray a = new JSONArray();
                    a.put(Math.round(i.lufs * 10));
                    a.put(i.in);
                    a.put(i.out);
                    a.put(i.size);
                    o.put(e.getKey(), a);
                }
                dirty = 0;
            }
            File tmp = new File(file.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                w.write(o.toString());
            } finally {
                w.close();
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /** what is known about this song (null = not measured, or its audio changed since) */
    static synchronized Info get(Context c, String key, long size) {
        if (key == null) return null;
        load(c);
        Info i = MAP.get(key);
        return i != null && (size <= 0 || i.size == size) ? i : null;
    }

    static synchronized int count(Context c) {
        load(c);
        return MAP.size();
    }

    /** how far to turn this song up (+) or down (-), in dB, to bring it to TARGET */
    static float gainDb(Context c, String key, long size) {
        Info i = get(c, key, size);
        if (i == null) return UNKNOWN_DB;
        return Math.max(-MAX_CUT, Math.min(MAX_BOOST, TARGET - i.lufs));
    }

    // ------------------------------------------------------------------ asking

    /** measure this song soon (before the background ones) */
    static void soon(Context c, Library.Song s) {
        if (s == null || s.path == null || get(c, s.key, s.size) != null) return;
        synchronized (Loud.class) {
            for (Iterator<String[]> it = SOON.iterator(); it.hasNext(); ) if (it.next()[0].equals(s.key)) it.remove();
            SOON.addFirst(new String[]{s.key, s.path, String.valueOf(s.size)});
            while (SOON.size() > 8) SOON.removeLast();
        }
        wake(c);
    }

    /** measure every song not measured yet, slowly, in the background */
    static void sweep(Context c, Library lib) {
        List<String[]> todo = new ArrayList<>();
        for (Library.Song s : lib.byKey.values())
            if (s.path != null && get(c, s.key, s.size) == null) todo.add(new String[]{s.key, s.path, String.valueOf(s.size)});
        synchronized (Loud.class) {
            LATER.clear();
            LATER.addAll(todo);
        }
        if (!todo.isEmpty()) wake(c);
    }

    private static synchronized void wake(Context c) {
        if (worker != null) return;
        final Context app = c.getApplicationContext();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                work(app);
            }
        }, "loudness");
        worker.start();
    }

    private static void work(Context c) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        try {
            while (true) {
                String[] job;
                boolean background;
                synchronized (Loud.class) {
                    job = SOON.pollFirst();
                    background = job == null;
                    if (background) {
                        if (LATER.isEmpty() || PlayerService.instance == null) {
                            worker = null;
                            break;
                        }
                    }
                }
                if (background) {
                    if (!busy(c)) {                     // not playing and not charging: wait and look again
                        Thread.sleep(30000);
                        continue;
                    }
                    synchronized (Loud.class) {
                        job = LATER.pollFirst();
                    }
                    if (job == null) continue;
                }
                long size = Long.parseLong(job[2]);
                if (get(c, job[0], size) != null) continue;
                Info i = null;
                try {
                    Embed.waitIfWriting(job[1]);        // lyrics being written into it: a moment
                    i = measure(job[1]);
                } catch (Throwable ignored) {
                }
                if (i == null) {                        // could not read it: remember it as plain so it is not tried forever
                    i = new Info();
                    i.lufs = TARGET - UNKNOWN_DB;
                }
                i.size = size;
                boolean write;
                synchronized (Loud.class) {
                    MAP.put(job[0], i);
                    write = ++dirty >= 10 || SOON.isEmpty() && LATER.isEmpty();
                }
                if (write) save();
                Done d = done;
                if (d != null) d.measured(job[0]);
                if (background) Thread.sleep(150);
            }
        } catch (InterruptedException e) {
            synchronized (Loud.class) {
                worker = null;
            }
        }
        save();
    }

    /** music playing, or the phone on charge */
    private static boolean busy(Context c) {
        PlayerService p = PlayerService.instance;
        if (p != null && p.isPlaying()) return true;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int st = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ measuring

    /** gets the decoded sound, as floats -1..1, frames interleaved */
    private interface Sink {
        void pcm(float[] x, int frames, int ch, int rate, long ptsUs);
    }

    /** K-weighted loudness in 400 ms blocks (BS.1770); the first block of each slice is skipped while the filters settle */
    private static final class KMeter implements Sink {
        final List<Double> blocks = new ArrayList<>();
        private int rate, ch, n, per;
        private double acc;
        private boolean first;
        private long from;
        private double[] s;                                  // 4 filter states per channel
        private double a1, a2, b0, b1, b2, c1, c2;           // stage 1 (shelf), stage 2 (high-pass) coefficients

        void slice(long fromUs) {
            from = fromUs;
            first = true;
            n = 0;
            acc = 0;
            if (s != null) java.util.Arrays.fill(s, 0);
        }

        private void setup(int r, int c) {
            rate = r;
            ch = c;
            s = new double[4 * c];
            per = Math.max(1, (int) (r * 0.4));
            double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
            double k = Math.tan(Math.PI * f0 / r), vh = Math.pow(10, g / 20), vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1 + k / q + k * k;
            b0 = (vh + vb * k / q + k * k) / a0;
            b1 = 2 * (k * k - vh) / a0;
            b2 = (vh - vb * k / q + k * k) / a0;
            a1 = 2 * (k * k - 1) / a0;
            a2 = (1 - k / q + k * k) / a0;
            f0 = 38.13547087602444;
            q = 0.5003270373238773;
            k = Math.tan(Math.PI * f0 / r);
            a0 = 1 + k / q + k * k;
            c1 = 2 * (k * k - 1) / a0;
            c2 = (1 - k / q + k * k) / a0;
        }

        @Override
        public void pcm(float[] x, int frames, int c, int r, long ptsUs) {
            if (r != rate || c != ch) setup(r, c);
            for (int f = 0; f < frames; f++) {
                if (ptsUs + f * 1000000L / r < from) continue;
                double e = 0;
                for (int j = 0; j < c; j++) {
                    int o = 4 * j;
                    double v = x[f * c + j];
                    double y = b0 * v + s[o];                     // stage 1, transposed direct form II
                    s[o] = b1 * v - a1 * y + s[o + 1];
                    s[o + 1] = b2 * v - a2 * y;
                    double z = y + s[o + 2];                      // stage 2: b = 1, -2, 1
                    s[o + 2] = -2 * y - c1 * z + s[o + 3];
                    s[o + 3] = y - c2 * z;
                    e += z * z;
                }
                acc += e;
                if (++n >= per) {
                    if (!first) blocks.add(acc / n);
                    first = false;
                    acc = 0;
                    n = 0;
                }
            }
        }

        /** the gated loudness (LUFS), or NaN with nothing to go on */
        double lufs() {
            double sum = 0;
            int m = 0;
            for (double z : blocks) {
                if (lk(z) > -70) {
                    sum += z;
                    m++;
                }
            }
            if (m == 0) return Double.NaN;
            double rel = lk(sum / m) - 10;
            sum = 0;
            m = 0;
            for (double z : blocks) {
                if (lk(z) > rel) {
                    sum += z;
                    m++;
                }
            }
            return m == 0 ? Double.NaN : lk(sum / m);
        }

        private static double lk(double z) {
            return -0.691 + 10 * Math.log10(Math.max(z, 1e-12));
        }
    }

    /** plain level in 50 ms steps: when (ms) each one starts, and how loud (dBFS) */
    private static final class Edges implements Sink {
        final List<long[]> steps = new ArrayList<>();     // {start ms, level dB x10}
        private long from, at = -1;
        private double acc;
        private int n;

        void slice(long fromUs) {
            from = fromUs;
            at = -1;
            acc = 0;
            n = 0;
        }

        @Override
        public void pcm(float[] x, int frames, int c, int r, long ptsUs) {
            int per = Math.max(1, r / 20);
            for (int f = 0; f < frames; f++) {
                long t = ptsUs + f * 1000000L / r;
                if (t < from) continue;
                if (at < 0) at = t;
                double e = 0;
                for (int j = 0; j < c; j++) {
                    double v = x[f * c + j];
                    e += v * v;
                }
                acc += e / c;
                if (++n >= per) {
                    steps.add(new long[]{at / 1000, Math.round(100 * Math.log10(Math.max(acc / n, 1e-12)))});
                    at = -1;
                    acc = 0;
                    n = 0;
                }
            }
        }
    }

    /** decodes one stretch of the song (fromUs to toUs) into the sink */
    private static final class Decoder {
        final MediaExtractor ex;
        final MediaCodec dec;
        int rate, ch;
        boolean floats;
        private float[] buf = new float[0];

        Decoder(MediaExtractor e, MediaFormat f) throws Exception {
            ex = e;
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, null, null, 0);
            dec.start();
        }

        void run(long fromUs, long toUs, Sink sink) {
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            dec.flush();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false;
            int idle = 0;
            while (true) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        long t = ex.getSampleTime();
                        if (n < 0 || t > toUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, t, 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(bi, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat o = dec.getOutputFormat();
                    if (o.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (o.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floats = o.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && o.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && bi.size > 0 && ch > 0 && bi.presentationTimeUs <= toUs) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.nativeOrder());
                        int count = floats ? bi.size / 4 : bi.size / 2;
                        if (buf.length < count) buf = new float[count];
                        if (floats) {
                            FloatBuffer fb = le.asFloatBuffer();
                            fb.get(buf, 0, count);
                        } else {
                            ShortBuffer sb = le.asShortBuffer();
                            for (int i = 0; i < count; i++) buf[i] = sb.get(i) / 32768f;
                        }
                        sink.pcm(buf, count / ch, ch, rate, bi.presentationTimeUs);
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    idle = 0;
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inDone && ++idle > 100) {
                    break;                                      // no end-of-stream mark came: done anyway
                }
            }
        }

        void close() {
            try {
                dec.stop();
            } catch (Exception ignored) {
            }
            try {
                dec.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** loudness, start and end of one song file (null if it cannot be read) */
    static Info measure(String path) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        Decoder d = null;
        try {
            FileInputStream fin = new FileInputStream(path);
            try {
                ex.setDataSource(fin.getFD());
            } finally {
                fin.close();
            }
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat g = ex.getTrackFormat(i);
                String m = g.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    ex.selectTrack(i);
                    f = g;
                    break;
                }
            }
            if (f == null) return null;
            long dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            d = new Decoder(ex, f);
            KMeter k = new KMeter();
            if (dur <= 0 || dur < 50000000L) {                     // short or unknown: the whole of it
                k.slice(0);
                d.run(0, dur > 0 ? dur : 600000000L, k);
            } else {
                for (int i = 0; i < 10; i++) {
                    long t = (long) (dur * (0.08 + 0.84 * i / 9.0)) - 2000000L;
                    k.slice(t);
                    d.run(t, t + 4000000L, k);
                }
            }
            double l = k.lufs();
            if (Double.isNaN(l)) return null;
            Info out = new Info();
            out.lufs = (float) l;
            if (dur > 0) {
                Edges e = new Edges();
                long head = Math.min(dur, 15000000L);
                e.slice(0);
                d.run(0, head, e);
                for (long[] st : e.steps) {
                    if (st[1] / 10.0 > SILENT) {
                        if (st[0] < 10000) out.in = (int) st[0];        // longer than 10 s quiet: an intro, played
                        break;
                    }
                }
                Edges t = new Edges();
                long from = Math.max(head, dur - 20000000L);
                t.slice(from);
                d.run(from, dur, t);
                out.out = (int) (dur / 1000);
                for (int i = t.steps.size() - 1; i >= 0; i--) {
                    long[] st = t.steps.get(i);
                    if (st[1] / 10.0 > SILENT) {
                        out.out = (int) Math.min(dur / 1000, st[0] + 50);
                        break;
                    }
                }
            }
            return out;
        } finally {
            if (d != null) d.close();
            ex.release();
        }
    }
}
