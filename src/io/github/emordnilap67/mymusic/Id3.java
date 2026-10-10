package io.github.emordnilap67.mymusic;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the few MP3 tags MY MUSIC needs that Android does not hand out
 *: the YouTube link yt-dlp writes into the comment (so a
 * song can be fetched again on a new phone, and its captions found), the
 * video's description (lyric channels often paste the words there) and
 * any lyrics already inside the file. Plain Java so it can be tested.
 */
final class Id3 {
    static final class Tags {
        String id = "";            // the YouTube video id, "" if none
        String description;        // the video's description, or null
        String lyrics;             // lyrics inside the file (USLT), or null
    }

    private static final Pattern YT = Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16 = Charset.forName("UTF-16");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    /** a description longer than this is not lyrics-sized: read no more of it */
    private static final int MAX_TEXT = 256 * 1024;

    private Id3() {}

    /** the YouTube id in a link or text, or "" */
    static String youtubeId(String s) {
        if (s == null) return "";
        Matcher m = YT.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /**
     * the tags of this file. full = also the description and lyrics (slower:
     * reads further into the file); otherwise it stops at the YouTube link.
     */
    static Tags read(File f, boolean full) {
        Tags t = new Tags();
        if (f == null || !f.isFile()) return t;
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f), 16384);
            read(in, full, t);
            if (!full) {
                t.description = null;                      // only the link was asked for
                t.lyrics = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return t;
    }

    static void read(InputStream in, boolean full, Tags t) throws IOException {
        byte[] h = new byte[10];
        if (!fill(in, h, 10) || h[0] != 'I' || h[1] != 'D' || h[2] != '3') return;
        int ver = h[3] & 0xff;
        if (ver != 3 && ver != 4) return;                  // 2.2 (three-letter frames) is too old to matter here
        int flags = h[5] & 0xff;
        long size = syncsafe(h, 6);
        long pos = 0;
        if ((flags & 0x40) != 0) {                         // extended header: skip it
            byte[] e = new byte[4];
            if (!fill(in, e, 4)) return;
            long es = ver == 4 ? syncsafe(e, 0) - 4 : int32(e, 0);
            if (es < 0 || !skip(in, es)) return;
            pos += 4 + es;
        }
        byte[] fh = new byte[10];
        while (pos + 10 <= size) {
            if (!fill(in, fh, 10)) return;
            pos += 10;
            if (fh[0] == 0) return;                        // padding: no more frames
            String id = new String(fh, 0, 4, LATIN1);
            long fs = ver == 4 ? syncsafe(fh, 4) : int32(fh, 4);
            if (fs < 0 || pos + fs > size + 10) return;
            int fflags = ((fh[8] & 0xff) << 8) | (fh[9] & 0xff);
            boolean odd = ver == 4 ? (fflags & 0x000c) != 0 : (fflags & 0x00c0) != 0;    // compressed / encrypted
            boolean lenInd = ver == 4 && (fflags & 0x0001) != 0;
            boolean want = !odd && (id.equals("COMM") || id.equals("TXXX") || id.equals("WXXX")
                    || (full && id.equals("USLT")));
            if (!want || fs > MAX_TEXT + 16) {
                if (!skip(in, fs)) return;
                pos += fs;
                continue;
            }
            byte[] b = new byte[(int) fs];
            if (!fill(in, b, b.length)) return;
            pos += fs;
            int off = lenInd ? 4 : 0;
            if (b.length <= off) continue;
            take(id, b, off, t);
            if (!full && !t.id.isEmpty()) return;          // all that was asked for
        }
    }

    private static void take(String id, byte[] b, int off, Tags t) {
        int enc = b[off] & 0xff;
        if (id.equals("TXXX") || id.equals("WXXX")) {
            int[] end = new int[1];
            String desc = text(b, off + 1, enc, true, end).trim().toLowerCase();
            // WXXX: the link itself is always Latin-1
            String val = id.equals("WXXX") ? new String(b, end[0], b.length - end[0], LATIN1) : text(b, end[0], enc, false, null);
            val = strip(val);
            if (desc.equals("description") || desc.equals("synopsis")) {
                if (t.description == null || val.length() > t.description.length()) t.description = val;
            } else if (t.id.isEmpty() && (desc.equals("purl") || desc.equals("comment") || desc.contains("url") || desc.isEmpty())) {
                t.id = youtubeId(val);
            }
        } else if (id.equals("COMM") || id.equals("USLT")) {
            if (b.length < off + 4) return;
            int[] end = new int[1];
            text(b, off + 4, enc, true, end);              // short description: not needed
            String val = strip(text(b, end[0], enc, false, null));
            if (id.equals("COMM")) {
                if (t.id.isEmpty()) t.id = youtubeId(val);
            } else if (!val.trim().isEmpty() && (t.lyrics == null || val.length() > t.lyrics.length())) {
                t.lyrics = val;
            }
        }
    }

    /** text in this encoding from off; terminated = stop at the end marker (end[0] = just after it) */
    private static String text(byte[] b, int off, int enc, boolean terminated, int[] end) {
        if (off >= b.length) {
            if (end != null) end[0] = b.length;
            return "";
        }
        boolean wide = enc == 1 || enc == 2;
        int stop = b.length, next = b.length;
        if (terminated) {
            if (wide) {
                for (int i = off; i + 1 < b.length; i += 2) {
                    if (b[i] == 0 && b[i + 1] == 0) {
                        stop = i;
                        next = i + 2;
                        break;
                    }
                }
            } else {
                for (int i = off; i < b.length; i++) {
                    if (b[i] == 0) {
                        stop = i;
                        next = i + 1;
                        break;
                    }
                }
            }
        }
        if (end != null) end[0] = next;
        Charset cs = enc == 1 ? UTF16 : enc == 2 ? UTF16BE : enc == 3 ? UTF8 : LATIN1;
        if (enc == 1 && stop - off < 2) return "";
        return new String(b, off, Math.max(0, stop - off), cs);
    }

    /** trailing end markers and a stray byte-order mark */
    private static String strip(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == 0) e--;
        s = s.substring(0, e);
        if (s.startsWith("\ufeff")) s = s.substring(1);
        return s;
    }

    private static long syncsafe(byte[] b, int o) {
        return ((long) (b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f);
    }

    private static long int32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static boolean fill(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int k = in.read(b, got, n - got);
            if (k < 0) return false;
            got += k;
        }
        return true;
    }

    private static boolean skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) {
                if (in.read() < 0) return false;
                k = 1;
            }
            n -= k;
        }
        return true;
    }
}
