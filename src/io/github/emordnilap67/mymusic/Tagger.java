package io.github.emordnilap67.mymusic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * writes
 * lyrics into an MP3's own ID3 tag (a USLT frame), so they travel with the
 * file - a new phone, the tablet, any other music player. Timed lyrics go
 * in as LRC text ("[01:02.30] words"), which MY MUSIC and most players read.
 *
 * Everything else in the tag is copied byte for byte (picture, title,
 * artist, the YouTube link). The sound after the tag is not touched. A
 * tag this does not fully understand is left alone (null). Plain Java so it
 * can be tested.
 */
final class Tagger {
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16LE = Charset.forName("UTF-16LE");
    static final int PADDING = 1024;
    /** lyrics longer than this are not lyrics */
    static final int MAX_TEXT = 200 * 1024;

    private Tagger() {}

    /** where the sound starts (the end of the ID3v2 tag, or 0), or -1 for a tag this cannot handle */
    static int audioStart(byte[] f) {
        if (f.length < 10 || f[0] != 'I' || f[1] != 'D' || f[2] != '3') return 0;
        int ver = f[3] & 0xff, flags = f[5] & 0xff;
        if (ver != 3 && ver != 4) return -1;
        for (int i = 6; i < 10; i++) if ((f[i] & 0x80) != 0) return -1;
        long size = syncsafe(f, 6);
        long end = 10 + size + (ver == 4 && (flags & 0x10) != 0 ? 10 : 0);
        return end > f.length ? -1 : (int) end;
    }

    /**
     * the file with these lyrics in its tag (any lyrics it had are replaced),
     * or null when the tag is one this should not touch
     */
    static byte[] withLyrics(byte[] f, String text) {
        if (text == null) return null;
        text = text.replace("\u0000", "");
        if (text.trim().isEmpty() || text.length() > MAX_TEXT) return null;
        int start = audioStart(f);
        if (start < 0) return null;
        int ver = 4;
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (start > 0) {
            ver = f[3] & 0xff;
            int flags = f[5] & 0xff;
            if ((flags & 0x80) != 0) return null;              // whole-tag unsynchronisation: leave it
            int tagEnd = 10 + (int) syncsafe(f, 6);
            int pos = 10;
            if ((flags & 0x40) != 0) {                         // extended header: optional, dropped
                if (pos + 4 > tagEnd) return null;
                long es = ver == 4 ? syncsafe(f, pos) : int32(f, pos) + 4;
                if (es < 4 || pos + es > tagEnd) return null;
                pos += (int) es;
            }
            while (pos + 10 <= tagEnd) {
                if (f[pos] == 0) {                             // padding: must be all zeros to the end
                    for (int i = pos; i < tagEnd; i++) if (f[i] != 0) return null;
                    break;
                }
                for (int i = 0; i < 4; i++) {
                    int ch = f[pos + i] & 0xff;
                    if (!(ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9')) return null;
                }
                long fs = ver == 4 ? syncsafe(f, pos + 4) : int32(f, pos + 4);
                if (fs < 0 || pos + 10 + fs > tagEnd) return null;
                String id = new String(f, pos, 4, LATIN1);
                if (!id.equals("USLT")) frames.write(f, pos, 10 + (int) fs);
                pos += 10 + (int) fs;
            }
            if (pos < tagEnd && pos + 10 > tagEnd) {           // a few odd bytes before the end: zeros only
                for (int i = pos; i < tagEnd; i++) if (f[i] != 0) return null;
            }
        }
        byte[] lyr = uslt(text, ver);
        frames.write(lyr, 0, lyr.length);
        int body = frames.size() + PADDING;
        if (body >= (1 << 28)) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(body + 10 + f.length - start);
        out.write('I');
        out.write('D');
        out.write('3');
        out.write(ver);
        out.write(0);
        out.write(0);                                          // no flags: no extended header, no footer
        byte[] sz = syncsafe(body);
        out.write(sz, 0, 4);
        byte[] fr = frames.toByteArray();
        out.write(fr, 0, fr.length);
        out.write(new byte[PADDING], 0, PADDING);
        out.write(f, start, f.length - start);                 // the sound, untouched
        return out.toByteArray();
    }

    /** one USLT frame: v2.4 as UTF-8, v2.3 as UTF-16 (2.3 has no UTF-8) */
    private static byte[] uslt(String text, int ver) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (ver == 4) {
            b.write(3);
            b.write('e');
            b.write('n');
            b.write('g');
            b.write(0);                                        // empty description
            byte[] t = text.getBytes(UTF8);
            b.write(t, 0, t.length);
        } else {
            b.write(1);
            b.write('e');
            b.write('n');
            b.write('g');
            b.write(0xff);
            b.write(0xfe);
            b.write(0);
            b.write(0);                                        // empty description
            b.write(0xff);
            b.write(0xfe);
            byte[] t = text.getBytes(UTF16LE);
            b.write(t, 0, t.length);
        }
        byte[] data = b.toByteArray();
        ByteArrayOutputStream fr = new ByteArrayOutputStream(data.length + 10);
        fr.write('U');
        fr.write('S');
        fr.write('L');
        fr.write('T');
        byte[] sz = ver == 4 ? syncsafe(data.length) : new byte[]{
                (byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length};
        fr.write(sz, 0, 4);
        fr.write(0);
        fr.write(0);
        fr.write(data, 0, data.length);
        return fr.toByteArray();
    }

    /**
     * the new file is right: same sound byte for byte, the same YouTube link,
     * and reading its tag gives back exactly these lyrics
     */
    static boolean verify(byte[] old, byte[] neu, String text) {
        try {
            int a = audioStart(old), b = audioStart(neu);
            if (a < 0 || b <= 0) return false;
            if (old.length - a != neu.length - b) return false;
            if (!Arrays.equals(Arrays.copyOfRange(old, a, old.length), Arrays.copyOfRange(neu, b, neu.length))) return false;
            Id3.Tags t0 = new Id3.Tags(), t1 = new Id3.Tags();
            Id3.read(new ByteArrayInputStream(old), true, t0);
            Id3.read(new ByteArrayInputStream(neu), true, t1);
            if (!t0.id.equals(t1.id)) return false;
            return t1.lyrics != null && t1.lyrics.equals(text.replace("\u0000", ""));
        } catch (Exception e) {
            return false;
        }
    }

    /** the lyrics already in the file's tag, or null */
    static String lyricsIn(byte[] f) {
        try {
            Id3.Tags t = new Id3.Tags();
            Id3.read(new ByteArrayInputStream(f), true, t);
            return t.lyrics;
        } catch (Exception e) {
            return null;
        }
    }

    private static long syncsafe(byte[] b, int o) {
        return ((long) (b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f);
    }

    private static byte[] syncsafe(int n) {
        return new byte[]{(byte) ((n >>> 21) & 0x7f), (byte) ((n >>> 14) & 0x7f), (byte) ((n >>> 7) & 0x7f), (byte) (n & 0x7f)};
    }

    private static long int32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
}
