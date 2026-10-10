package io.github.emordnilap67.mymusic;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * the neon parts of the widget, drawn in the song's two
 * colours - the glowing edge
 * (nine pieces, so its corners keep their shape at any widget size), the
 * play / pause button in a glowing rounded square, previous / next in dark
 * circles, shuffle and repeat with their dots, the bar, and the rounded
 * picture.
 */
final class NeonArt {
    /** inside the card: nearly black, a touch see-through */
    static final int FILL = 0xF407050D;
    /** inside the play button */
    static final int PLAY_FILL = 0xFF0A0812;

    // the glow: wide soft halo, tight glow, then the bright tube
    static final float HALO_W = 3.4f, HALO_BLUR = 0.5f, TIGHT_W = 2.2f, TIGHT_BLUR = 1.5f, CORE = 0.2f;
    static final int HALO_A = 0xA6, TIGHT_A = 0xEE;
    /** the play button's body (the rest of its square is room for the glow) and the side buttons */
    static final float PLAY_BODY = 0.83f, SIDE_W = 0.62f, SIDE_D = 0.59f;

    private NeonArt() {}

    private static Bitmap make(int w, int h) {
        return Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
    }

    private static Paint paint(int color, Shader sh, float blur, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(color);
        if (sh != null) p.setShader(sh);
        p.setAlpha(alpha);
        if (blur > 0.3f) p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        return p;
    }

    private static Paint stroke(float w, int color, Shader sh, float blur, int alpha) {
        Paint p = paint(color, sh, blur, alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Shader across(float x0, float x1, int a, int b) {
        return new LinearGradient(x0, 0, x1, 0, a, b, Shader.TileMode.CLAMP);
    }

    /** one glowing rounded rectangle (outer edge = outer): inside, a wide soft glow, a tight glow, the tube */
    static void neon(Canvas cv, RectF outer, float rad, float bw, float halo, int ca, int cb, float x0, float x1, int inside) {
        if (inside != 0) cv.drawRoundRect(outer, rad, rad, paint(inside, null, 0, inside >>> 24));
        RectF s = new RectF(outer);
        s.inset(bw / 2, bw / 2);
        float r = Math.max(0, rad - bw / 2);
        boolean two = ca != cb;
        cv.drawRoundRect(s, r, r, stroke(bw * HALO_W, ca, two ? across(x0, x1, ca, cb) : null, halo, HALO_A));
        cv.drawRoundRect(s, r, r, stroke(bw * TIGHT_W, ca, two ? across(x0, x1, ca, cb) : null, bw * TIGHT_BLUR, TIGHT_A));
        int la = Neon.light(ca, CORE), lb = Neon.light(cb, CORE);
        cv.drawRoundRect(s, r, r, stroke(bw, la, two ? across(x0, x1, la, lb) : null, 0, 255));
    }

    /**
     * the card's edge in nine pieces (px): glow room g, corner radius rad,
     * edge width bw, piece size k. {top left, top, top right, left, right,
     * bottom left, bottom, bottom right}; the middle is plain FILL.
     */
    static Bitmap[] frame(float g, float rad, float bw, int k, int c1, int c2) {
        int t = 96, e = 8;
        float big = k * 4f, halo = g * HALO_BLUR;
        return new Bitmap[]{
                piece(k, k, new RectF(g, g, g + big, g + big), rad, bw, halo, c1, c1),
                piece(t, k, new RectF(-big, g, t + big, g + big), rad, bw, halo, c1, c2),
                piece(k, k, new RectF(k - g - big, g, k - g, g + big), rad, bw, halo, c2, c2),
                piece(k, e, new RectF(g, -big, g + big, e + big), rad, bw, halo, c1, c1),
                piece(k, e, new RectF(k - g - big, -big, k - g, e + big), rad, bw, halo, c2, c2),
                piece(k, k, new RectF(g, k - g - big, g + big, k - g), rad, bw, halo, c1, c1),
                piece(t, k, new RectF(-big, k - g - big, t + big, k - g), rad, bw, halo, c1, c2),
                piece(k, k, new RectF(k - g - big, k - g - big, k - g, k - g), rad, bw, halo, c2, c2)};
    }

    private static Bitmap piece(int w, int h, RectF card, float rad, float bw, float halo, int ca, int cb) {
        Bitmap bm = make(w, h);
        neon(new Canvas(bm), card, rad, bw, halo, ca, cb, 0, w, FILL);
        return bm;
    }

    /** the play / pause button: a glowing rounded square (s x s px, the glow inside the square) */
    static Bitmap play(int s, boolean playing, int c1, int c2) {
        Bitmap bm = make(s, s);
        Canvas cv = new Canvas(bm);
        float body = s * PLAY_BODY, x = (s - body) / 2f, rad = body * 0.24f, bw = body * 0.045f;
        int right = Neon.mix(c1, c2, 0.6);
        neon(cv, new RectF(x, x, x + body, x + body), rad, bw, body * 0.07f, c1, right, x, x + body, PLAY_FILL);
        float cx = s / 2f, cy = s / 2f;
        Path p = new Path();
        if (playing) {
            float w = body * 0.13f, h = body * 0.40f, gap = body * 0.12f, r = w * 0.3f;
            p.addRoundRect(new RectF(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2), r, r, Path.Direction.CW);
            p.addRoundRect(new RectF(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2), r, r, Path.Direction.CW);
        } else {
            float w = body * 0.34f, h = body * 0.40f, x0 = cx - w * 0.40f;
            p.moveTo(x0, cy - h / 2);
            p.lineTo(x0 + w, cy);
            p.lineTo(x0, cy + h / 2);
            p.close();
        }
        Paint glow = paint(0xFFFFFFFF, null, body * 0.06f, 0x55);
        Paint white = paint(0xFFFFFFFF, null, 0, 255);
        if (!playing) {
            glow.setPathEffect(new CornerPathEffect(body * 0.05f));
            white.setPathEffect(new CornerPathEffect(body * 0.05f));
        }
        cv.drawPath(p, glow);
        cv.drawPath(p, white);
        return bm;
    }

    /** previous (circle at the left of the picture) or next (at the right): s px high */
    static Bitmap side(int s, boolean next, int c) {
        int w = Math.round(s * SIDE_W);
        Bitmap bm = make(w, s);
        Canvas cv = new Canvas(bm);
        float d = s * SIDE_D, m = s * 0.02f, cx = next ? w - m - d / 2 : m + d / 2, cy = s / 2f;
        cv.drawCircle(cx, cy, d / 2, paint(0xF0120F1B, null, 0, 0xF0));
        cv.drawCircle(cx, cy, d / 2 - Math.max(1f, d * 0.012f) / 2, stroke(Math.max(1f, d * 0.012f), 0xFFFFFFFF, null, 0, 0x22));
        float iw = d * 0.56f, ih = d * 0.38f, half = iw / 2;
        Path p = new Path();
        for (int i = 0; i < 2; i++) {
            float x0 = cx - half + i * half;
            if (next) {
                p.moveTo(x0, cy - ih / 2);
                p.lineTo(x0 + half, cy);
                p.lineTo(x0, cy + ih / 2);
            } else {
                p.moveTo(x0 + half, cy - ih / 2);
                p.lineTo(x0, cy);
                p.lineTo(x0 + half, cy + ih / 2);
            }
            p.close();
        }
        Paint glow = paint(c, null, ih * 0.35f, 0x99);
        Paint ic = paint(Neon.light(c, 0.12), null, 0, 255);
        glow.setPathEffect(new CornerPathEffect(ih * 0.1f));
        ic.setPathEffect(new CornerPathEffect(ih * 0.1f));
        cv.drawPath(p, glow);
        cv.drawPath(p, ic);
        return bm;
    }

    /** shuffle or repeat (h px high: the icon, then its dot when it is on). mode: "off" / "all" / "one" */
    static Bitmap toggle(int h, boolean shuffle, String mode, int c) {
        boolean on = !"off".equals(mode);
        float icon = h * 34f / 53f, dot = h * 8f / 53f, gap = h * 11f / 53f, sw = icon * 0.12f;
        float bw = icon * 1.2f;
        int w = Math.round(bw + sw * 2);
        Bitmap bm = make(w, h);
        Canvas cv = new Canvas(bm);
        int col = on ? Neon.light(c, 0.1) : Neon.mix(c, 0xFF77748A, 0.6);
        RectF box = new RectF(sw, sw / 2, sw + bw, icon - sw / 2);
        Path p = shuffle ? shufflePath(box) : repeatPath(box);
        if (on) cv.drawPath(p, stroke(sw * 1.8f, c, null, sw * 1.6f, 0x88));
        cv.drawPath(p, stroke(sw, col, null, 0, on ? 255 : 0xA0));
        if ("one".equals(mode)) {
            Paint t = paint(col, null, 0, 255);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(icon * 0.42f);
            Rect tb = new Rect();
            t.getTextBounds("1", 0, 1, tb);
            cv.drawText("1", box.centerX(), box.centerY() + tb.height() / 2f, t);
        }
        if (on) {
            float cy = icon + gap + dot / 2;
            cv.drawCircle(w / 2f, cy, dot * 0.9f, paint(c, null, dot * 0.8f, 0x99));
            cv.drawCircle(w / 2f, cy, dot / 2, paint(Neon.light(c, 0.15), null, 0, 255));
        }
        return bm;
    }

    /** two crossing lines with arrows on the right */
    static Path shufflePath(RectF b) {
        float W = b.width(), H = b.height(), x = b.left, y = b.top, a = H * 0.2f;
        Path p = new Path();
        p.moveTo(x, y + .2f * H);
        p.lineTo(x + .16f * W, y + .2f * H);
        p.cubicTo(x + .46f * W, y + .2f * H, x + .5f * W, y + .8f * H, x + .8f * W, y + .8f * H);
        p.lineTo(x + W, y + .8f * H);
        p.moveTo(x, y + .8f * H);
        p.lineTo(x + .16f * W, y + .8f * H);
        p.cubicTo(x + .46f * W, y + .8f * H, x + .5f * W, y + .2f * H, x + .8f * W, y + .2f * H);
        p.lineTo(x + W, y + .2f * H);
        for (float ay : new float[]{.2f, .8f}) {
            p.moveTo(x + W - a, y + ay * H - a);
            p.lineTo(x + W, y + ay * H);
            p.lineTo(x + W - a, y + ay * H + a);
        }
        return p;
    }

    /** a rounded loop: arrow along the top pointing right, along the bottom pointing left */
    static Path repeatPath(RectF b) {
        float W = b.width(), H = b.height(), x = b.left, y = b.top, a = H * 0.2f, r = H * 0.24f;
        float l = x + .06f * W, rt = x + .94f * W, top = y + .2f * H, bot = y + .8f * H;
        Path p = new Path();
        p.moveTo(l, y + .56f * H);
        p.lineTo(l, top + r);
        p.quadTo(l, top, l + r, top);
        p.lineTo(rt, top);
        p.moveTo(rt - a, top - a);
        p.lineTo(rt, top);
        p.lineTo(rt - a, top + a);
        p.moveTo(rt, y + .44f * H);
        p.lineTo(rt, bot - r);
        p.quadTo(rt, bot, rt - r, bot);
        p.lineTo(l, bot);
        p.moveTo(l + a, bot - a);
        p.lineTo(l, bot);
        p.lineTo(l + a, bot + a);
        return p;
    }

    /** the bar: the whole length pink to blue (the song's colours), glowing; stretched to the bar's width */
    static Bitmap track(int w, int h, float core, int c1, int c2) {
        Bitmap bm = make(w, h);
        Canvas cv = new Canvas(bm);
        float y = h / 2f, x0 = core / 2 + 1, x1 = w - core / 2 - 1;
        cv.drawLine(x0, y, x1, y, stroke(core * 2.2f, c1, across(0, w, c1, c2), core * 1.3f, 0x80));
        int la = Neon.light(c1, 0.12), lb = Neon.light(c2, 0.12);
        cv.drawLine(x0, y, x1, y, stroke(core, la, across(0, w, la, lb), 0, 255));
        return bm;
    }

    /** the song's picture: square (centre cut), rounded corners, a faint light edge */
    static Bitmap art(Bitmap src, int size) {
        Bitmap out = make(size, size);
        try {
            int sw = src.getWidth(), sh = src.getHeight(), side = Math.min(sw, sh);
            Bitmap sq = Bitmap.createBitmap(src, (sw - side) / 2, (sh - side) / 2, side, side);
            Bitmap sc = Bitmap.createScaledBitmap(sq, size, size, true);
            Canvas cv = new Canvas(out);
            Paint p = paint(0xFF000000, new BitmapShader(sc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), 0, 255);
            float r = size * 0.06f;
            cv.drawRoundRect(new RectF(0, 0, size, size), r, r, p);
            float e = Math.max(1f, size / 200f);
            cv.drawRoundRect(new RectF(e / 2, e / 2, size - e / 2, size - e / 2), r, r, stroke(e, 0xFFFFFFFF, null, 0, 0x2E));
        } catch (Exception ignored) {
            // a picture that will not decode: the card stays dark there
        }
        return out;
    }

    /** about 32 x 32 pixels of the picture, for its colours */
    static int[] pixels(Bitmap src) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(src, 32, 32, true);
            int[] px = new int[32 * 32];
            s.getPixels(px, 0, 32, 0, 0, 32, 32);
            return px;
        } catch (Exception e) {
            return new int[0];
        }
    }
}
