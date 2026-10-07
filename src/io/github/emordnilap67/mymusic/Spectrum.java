package io.github.emordnilap67.mymusic;

/**
 * The moving ring's numbers: a slice of the song's own sound
 * (8-bit, from Android's Visualizer) -> how loud each of BANDS pitch bands
 * is, bass first. Plain Java, no Android, so it can be tested anywhere.
 */
final class Spectrum {
    static final int BANDS = 64;
    static final float LO_HZ = 38f, HI_HZ = 15000f;

    private final int n;
    private final float[] win, re, im;
    private final int[] k0, k1;
    private final float[] tilt, at;     // at >= 0: a band narrower than one bin, read between two bins
    private final float winGain;

    Spectrum(int n, int sampleHz) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("size must be a power of 2");
        if (sampleHz <= 0) sampleHz = 44100;
        this.n = n;
        win = new float[n];
        re = new float[n];
        im = new float[n];
        float sum = 0;
        for (int i = 0; i < n; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));    // Hann
            sum += win[i];
        }
        winGain = sum / 2f;                       // a full-scale sine reads 1.0
        k0 = new int[BANDS];
        k1 = new int[BANDS];
        tilt = new float[BANDS];
        at = new float[BANDS];
        float hzPerBin = sampleHz / (float) n;
        float hi = Math.min(HI_HZ, sampleHz * 0.45f);
        for (int b = 0; b < BANDS; b++) {
            double f0 = LO_HZ * Math.pow(hi / LO_HZ, b / (double) BANDS);
            double f1 = LO_HZ * Math.pow(hi / LO_HZ, (b + 1) / (double) BANDS);
            int a = (int) Math.round(f0 / hzPerBin), z = (int) Math.round(f1 / hzPerBin);
            a = Math.max(1, Math.min(n / 2 - 1, a));
            z = Math.max(a + 1, Math.min(n / 2, z));
            k0[b] = a;
            k1[b] = z;
            at[b] = (f1 - f0) < hzPerBin ? (float) (Math.sqrt(f0 * f1) / hzPerBin) : -1f;
            // music gets quieter as it goes higher; lift the top so the whole ring moves
            double mid = Math.sqrt(f0 * f1);
            tilt[b] = (float) (3.0 * Math.log(Math.max(mid, 150) / 150.0) / Math.log(2));
        }
    }

    /**
     * 8-bit unsigned sound (128 = silence) -> BANDS values, 0..100
     * (0 = 60 dB below full scale or quieter, 100 = full scale).
     */
    int[] bands(byte[] wave, int[] out) {
        for (int i = 0; i < n; i++) {
            re[i] = (((wave[i] & 0xff) - 128) / 128f) * win[i];
            im[i] = 0f;
        }
        fft(re, im);
        if (out == null || out.length < BANDS) out = new int[BANDS];
        for (int b = 0; b < BANDS; b++) {
            double amp;
            if (at[b] >= 0) {
                int k = Math.max(1, (int) at[b]);
                float f = Math.max(0f, Math.min(1f, at[b] - k));
                double a1 = Math.sqrt(re[k] * re[k] + im[k] * im[k]), a2 = Math.sqrt(re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]);
                amp = (a1 + (a2 - a1) * f) / winGain;
            } else {
                float m = 0;
                for (int k = k0[b]; k < k1[b]; k++) {
                    float v = re[k] * re[k] + im[k] * im[k];
                    if (v > m) m = v;
                }
                amp = Math.sqrt(m) / winGain;
            }
            double db = 20 * Math.log10(amp + 1e-9) + tilt[b];
            int v = (int) Math.round((db + 60) * (100 / 60.0));
            out[b] = v < 0 ? 0 : v > 100 ? 100 : v;
        }
        return out;
    }

    /** in-place radix-2 FFT */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
