package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;

/**
 * Song pictures: pulled out of each MP3 the first time they are needed,
 * cropped square, kept as small JPEGs in the app's cache.
 */
final class Art {
    private static final Semaphore gate = new Semaphore(2);   // 2 at a time

    private Art() {}

    /** the cached picture file, or null when the song has none */
    static File file(Context c, Library.Song s, int px) {
        File dir = new File(c.getCacheDir(), "art");
        dir.mkdirs();
        if (!dir.isDirectory()) return null;
        String name = sha1(s.path + "|" + s.size + "|" + s.added + "|" + px);
        File out = new File(dir, name + ".jpg");
        File none = new File(dir, name + ".none");
        if (out.isFile()) return out;
        if (none.exists()) return null;
        gate.acquireUninterruptibly();
        try {
            if (out.isFile()) return out;
            Bitmap bm = decode(s.path, px);
            if (bm == null) {
                none.createNewFile();
                return null;
            }
            File tmp = new File(dir, name + "." + Thread.currentThread().getId() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                bm.compress(Bitmap.CompressFormat.JPEG, 86, fo);
            } finally {
                fo.close();
            }
            if (!tmp.renameTo(out)) tmp.delete();
            return out.isFile() ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            gate.release();
        }
    }

    static Bitmap bitmap(Context c, Library.Song s, int px) {
        File f = file(c, s, px);
        return f == null ? null : BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static Bitmap decode(String path, int px) {
        byte[] pic = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            FileInputStream in = new FileInputStream(path);
            try {
                r.setDataSource(in.getFD());
            } finally {
                in.close();
            }
            pic = r.getEmbeddedPicture();
        } catch (Exception e) {
            pic = null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (pic == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return null;
        int sample = 1;
        while (Math.min(w, h) / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        if (src == null) return null;
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        return side == px ? sq : Bitmap.createScaledBitmap(sq, px, px, true);
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
