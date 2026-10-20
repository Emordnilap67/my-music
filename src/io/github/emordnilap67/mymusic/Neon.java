package io.github.emordnilap67.mymusic;

/**
 * The widget's two colours come from the song's picture,
 * worked out exactly like the page's palette() / onBlack() in index.html, so
 * the widget and the player screen glow in the same colours. Plain Java
 * (ARGB ints) so it can be tested.
 */
final class Neon {
    /**
     * Carmen Musicum's own colours, left to right - purple, magenta, blue,
     * light blue, green (no song, or a picture with no colour in it). PINK / BLUE = the two
     * ends; anything drawn from one end to the other runs through all five (NeonArt.across).
     */
    static final int[] DEF5 = {0xFF9646FF, 0xFFE13CC8, 0xFF3C64FF, 0xFF3CBEFF, 0xFF32DC8C};
    static final int PINK = DEF5[0], BLUE = DEF5[4];

    /** where the colours sit along the gradient - the purple and the green equally wide */
    static final float[] POS = {0f, .06f, .27f, .48f, .66f, .84f, 1f};

    /** the default's colours at POS when a .. b is the default pair (or a lightened copy of it), else null */
    static int[] stops(int a, int b) {
        for (double k : new double[]{0, 0.12, (double) NeonArt.CORE, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5}) {
            if (a == light(PINK, k) && b == light(BLUE, k)) {
                int[] at = {0, 0, 1, 2, 3, 4, 4}, o = new int[at.length];
                for (int i = 0; i < o.length; i++) o[i] = light(DEF5[at[i]], k);
                return o;
            }
        }
        return null;
    }

    private Neon() {}

    static int rgb(double r, double g, double b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static int r(int c) { return (c >> 16) & 255; }
    static int g(int c) { return (c >> 8) & 255; }
    static int b(int c) { return c & 255; }

    /** a + (b - a) * k, per channel (opaque) */
    static int mix(int a, int b, double k) {
        return rgb(r(a) + (r(b) - r(a)) * k, g(a) + (g(b) - g(a)) * k, b(a) + (b(b) - b(a)) * k);
    }

    /** the same colour with this alpha (0-255) */
    static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static double[] hsl2rgb(double h, double s, double l) {
        double a = s * Math.min(l, 1 - l);
        double[] out = new double[3];
        int[] ns = {0, 8, 4};
        for (int i = 0; i < 3; i++) {
            double k = (ns[i] + h * 12) % 12;
            out[i] = 255 * (l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1)));
        }
        return out;
    }

    /** {hue 0..1, saturation, lightness} (page: hslOf) */
    static double[] hslOf(double[] c) {
        double r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
        double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        double h = 0;
        if (d != 0) h = (mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4) / 6;
        if (h < 0) h += 1;
        double l = (mx + mn) / 2, s = d != 0 ? d / (1 - Math.abs(2 * l - 1)) : 0;
        return new double[]{h, Math.min(1, s), l};
    }

    static final double LMIN = .48, LMAX = .82;

    /**
     * the picture's own colour, only lifted (or dimmed) as far as it must be
     * to show on black, and a very strong colour eased a little (page: onBlack)
     */
    static double[] shown(double[] c) {
        double[] q = hslOf(c);
        double s = q[1] > .85 ? .85 + (q[1] - .85) * .5 : q[1];
        return hsl2rgb(q[0], s, Math.min(LMAX, Math.max(LMIN, q[2])));
    }

    /** a shade of the same colour: lighter if it is darkish, darker if it is light (page: shade) */
    static double[] shade(double[] c, double k) {
        double[] q = hslOf(c);
        return hsl2rgb(q[0], q[1], Math.min(.9, Math.max(.4, q[2] < .65 ? q[2] + k : q[2] - k)));
    }

    /** how different two colours look (page: cdist) */
    static double cdist(double[] a, double[] b) {
        double rm = (a[0] + b[0]) / 2, dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db);
    }

    private static int[] near(int k) {
        int r = k >> 6, g = (k >> 3) & 7, b = k & 7, n = 0;
        int[] o = new int[6];
        if (r > 0) o[n++] = k - 64;
        if (r < 7) o[n++] = k + 64;
        if (g > 0) o[n++] = k - 8;
        if (g < 7) o[n++] = k + 8;
        if (b > 0) o[n++] = k - 1;
        if (b < 7) o[n++] = k + 1;
        return java.util.Arrays.copyOf(o, n);
    }

    /**
     * The song's colours from the round
     * part of its picture that shows on the disc, as they really are - every colour
     * counts (only near-black is skipped), grouped in 512 groups and averaged from
     * the real pixels. {left, right, up to three more}. Same as the page's palette().
     * px = ARGB, square (about 32 x 32).
     */
    static double[][] colours(int[] px) {
        int n = (int) Math.round(Math.sqrt(px.length));
        final double[] W = new double[512];
        double[] S = new double[1536];
        double total = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = (x + .5) / n - .5, dy = (y + .5) / n - .5;
            if (dx * dx + dy * dy > .25) continue;                   // outside the disc's circle
            int p = px[y * n + x];
            if (((p >>> 24) & 255) < 128) continue;                 // see-through
            int R = r(p), G = g(p), B = b(p), mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B));
            if (mx < 26) continue;                                  // near black
            double sat = (double) (mx - mn) / mx, wt = (.12 + 1.6 * sat * sat) * (.4 + .6 * mx / 255.0);
            int k = ((R >> 5) << 6) | ((G >> 5) << 3) | (B >> 5);
            W[k] += wt; S[k * 3] += wt * R; S[k * 3 + 1] += wt * G; S[k * 3 + 2] += wt * B; total += wt;
        }
        if (total == 0) return fill(new double[][]{{150, 150, 158}, {205, 205, 212}});
        final double[] sc = new double[512];
        java.util.List<Integer> ks = new java.util.ArrayList<>();
        for (int k = 0; k < 512; k++) if (W[k] != 0) {
            double t = W[k];
            for (int j : near(k)) t += .5 * W[j];
            sc[k] = t;
            ks.add(k);
        }
        // no lambda: the phone's build (android-33.jar) has no LambdaMetafactory
        java.util.Collections.sort(ks, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer p, Integer q) {
                return sc[q] != sc[p] ? Double.compare(sc[q], sc[p]) : p - q;
            }
        });
        java.util.List<double[]> cand = new java.util.ArrayList<>();
        for (int i = 0; i < ks.size(); i++) {
            int k = ks.get(i);
            if (i > 0 && sc[k] < .03 * total) continue;
            double w = W[k];
            double[] c = {S[k * 3], S[k * 3 + 1], S[k * 3 + 2]};
            for (int j : near(k)) { w += .5 * W[j]; c[0] += .5 * S[j * 3]; c[1] += .5 * S[j * 3 + 1]; c[2] += .5 * S[j * 3 + 2]; }
            cand.add(shown(new double[]{c[0] / w, c[1] / w, c[2] / w}));
        }
        java.util.List<double[]> out = new java.util.ArrayList<>();
        out.add(cand.get(0));
        double[] two = null;
        for (double[] c : cand) if (cdist(c, out.get(0)) >= 110) { two = c; break; }
        out.add(two != null ? two : shade(out.get(0), .18));
        for (double[] c : cand) {
            if (out.size() >= 5) break;
            boolean far = true;
            for (double[] o : out) if (cdist(o, c) < 75) { far = false; break; }
            if (far) out.add(c);
        }
        return fill(out.toArray(new double[0][]));
    }

    private static double[][] fill(double[][] cols) {
        java.util.List<double[]> out = new java.util.ArrayList<>(java.util.Arrays.asList(cols));
        if (out.size() < 3) out.add(shade(out.get(0), .12));
        if (out.size() < 4) out.add(shade(out.get(1), .12));
        return out.toArray(new double[0][]);
    }

    /** the widget's two colours {left, right} */
    static int[] palette(int[] px) {
        if (px == null || px.length == 0) return new int[]{PINK, BLUE};
        double[][] c = colours(px);
        return new int[]{rgb(c[0][0], c[0][1], c[0][2]), rgb(c[1][0], c[1][1], c[1][2])};
    }

    /** hue in degrees (0-360) */
    static double hue(int c) {
        double r = r(c) / 255.0, g = g(c) / 255.0, b = b(c) / 255.0;
        double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d == 0) return 0;
        double h = (mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4) * 60;
        return h < 0 ? h + 360 : h;
    }

    /** the same colour turned round the colour wheel (degrees), as bright and as strong */
    static int turn(int c, double deg) {
        double r = r(c) / 255.0, g = g(c) / 255.0, b = b(c) / 255.0;
        double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        double l = (mx + mn) / 2, s = d != 0 ? d / (1 - Math.abs(2 * l - 1)) : 0;
        double h = ((hue(c) + deg) % 360 + 360) % 360;
        double[] o = hsl2rgb(h / 360, s, l);
        return rgb(o[0], o[1], o[2]);
    }

    /**
     * the widget's two ends (left, right): the picture's own two colours. a
     * one-colour picture keeps its own colour and a shade of it - no colour that
     * is not in the picture is made up any more.
     */
    static int[] ends(int[] p) {
        return p;
    }

    /** towards white (the bright core of a neon line) */
    static int light(int c, double k) {
        return mix(c, 0xFFFFFFFF, k);
    }
}
