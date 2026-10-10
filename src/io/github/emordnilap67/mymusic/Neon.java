package io.github.emordnilap67.mymusic;

/**
 * the widget's two neon colours come from the song's picture,
 * worked out exactly like the page's palette() / vivid() in index.html, so
 * the widget and the player screen glow in the same colours. Plain Java
 * (ARGB ints) so it can be tested.
 */
final class Neon {
    /** the colours of the picture he sent (no song, or a picture with no colour in it) */
    static final int PINK = 0xFFE040FB, BLUE = 0xFF3B9BFF;

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

    /** brighter and more saturated, like a neon tube (page: vivid) */
    static int vivid(double[] c) {
        double r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
        double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        double h = 0;
        if (d != 0) h = (mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4) / 6;
        if (h < 0) h += 1;
        double l = (mx + mn) / 2, s = d != 0 ? d / (1 - Math.abs(2 * l - 1)) : 0;
        double[] o = hsl2rgb(h, Math.max(s, .68), Math.min(.7, Math.max(.58, l)));
        return rgb(o[0], o[1], o[2]);
    }

    /**
     * two lively colours from the picture's pixels (ARGB, about 32 x 32):
     * {left colour, right colour}. A grey or dark picture gives MY MUSIC's
     * own pink and blue.
     */
    static int[] palette(int[] px) {
        final int B = 24;
        double[] w = new double[B];
        double[][] sum = new double[B][3];
        for (int p : px) {
            if (((p >>> 24) & 255) < 128) continue;                 // see-through: not part of the picture
            int R = r(p), G = g(p), Bl = b(p);
            double r = R / 255.0, g = G / 255.0, b = Bl / 255.0;
            double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
            double s = mx != 0 ? d / mx : 0;
            if (mx < .2 || s < .22) continue;                       // too dark or too grey to be a colour
            double h = (mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4) / 6;
            if (h < 0) h += 1;
            int k = ((int) Math.floor(h * B)) % B;
            double wt = s * s * mx;
            w[k] += wt;
            sum[k][0] += wt * R;
            sum[k][1] += wt * G;
            sum[k][2] += wt * Bl;
        }
        int first = -1, second = -1;
        for (int i = 0; i < B; i++) if (w[i] != 0 && (first < 0 || score(w, i) > score(w, first))) first = i;
        if (first < 0 || score(w, first) < 1.5) return new int[]{PINK, BLUE};
        for (int i = 0; i < B; i++) {
            int dist = Math.min(Math.abs(i - first), B - Math.abs(i - first));
            // a second colour: well apart in hue, and the further apart the better
            if (dist >= 3 && w[i] != 0 && score(w, i) >= .1 * score(w, first)) {
                if (second < 0) { second = i; continue; }
                int d2 = Math.min(Math.abs(second - first), B - Math.abs(second - first));
                if (score(w, i) * (.5 + dist / 12.0) > score(w, second) * (.5 + d2 / 12.0)) second = i;
            }
        }
        int a = colourOf(w, sum, first);
        if (second >= 0) return new int[]{a, colourOf(w, sum, second)};
        int l = mix(a, 0xFFFFFFFF, .35);                              // one-colour picture: a lighter partner
        return new int[]{a, vivid(new double[]{r(l), g(l), Math.min(255, b(l) + 40)})};
    }

    private static double score(double[] w, int i) {
        int B = w.length;
        return w[i] + .5 * (w[(i + B - 1) % B] + w[(i + 1) % B]);
    }

    private static int colourOf(double[] w, double[][] sum, int i) {
        int B = w.length;
        int[] z = {(i + B - 1) % B, i, (i + 1) % B};
        double W = 0;
        for (int j : z) W += w[j];
        double[] c = new double[3];
        for (int ch = 0; ch < 3; ch++) {
            double t = 0;
            for (int j : z) t += sum[j][ch];
            c[ch] = t / W;
        }
        return vivid(c);
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
     * the widget's two ends (left, right): the picture's two colours, but
     * when they are nearly the same (a one-colour picture) the right end is
     * turned 45 degrees round the wheel, so the edge still glows from one
     * colour into another (pink into blue).
     */
    static int[] ends(int[] p) {
        double d = Math.abs(hue(p[0]) - hue(p[1]));
        if (Math.min(d, 360 - d) >= 35) return p;
        return new int[]{p[0], turn(p[0], -45)};
    }

    /** towards white (the bright core of a neon line) */
    static int light(int c, double k) {
        return mix(c, 0xFFFFFFFF, k);
    }
}
