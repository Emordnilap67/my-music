package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Playlist pictures. In order: one picked in the app (kept in the app's
 * files), then the one built in (assets/covers/NAME.jpg), then the first song's.
 */
final class Covers {
    private Covers() {}

    static File file(Context c, String pl) {
        return new File(new File(c.getApplicationContext().getFilesDir(), "covers"), Art.sha1(pl) + ".jpg");
    }

    static String assetName(String pl) {
        return "covers/" + pl + ".jpg";
    }

    static boolean hasAsset(Context c, String pl) {
        try {
            c.getAssets().open(assetName(pl)).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** the picture he picked: cropped square, 800 px, saved for this playlist */
    static boolean save(Context c, String pl, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(u);
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 800) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap src;
            in = c.getContentResolver().openInputStream(u);
            try {
                src = BitmapFactory.decodeStream(in, null, o2);
            } finally {
                in.close();
            }
            if (src == null) return false;
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap out = side > 800 ? Bitmap.createScaledBitmap(sq, 800, 800, true) : sq;
            File f = file(c, pl);
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                out.compress(Bitmap.CompressFormat.JPEG, 88, fo);
            } finally {
                fo.close();
            }
            return tmp.renameTo(f);
        } catch (Exception e) {
            return false;
        }
    }

    static void reset(Context c, String pl) {
        file(c, pl).delete();
    }
}
