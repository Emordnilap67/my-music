package io.github.emordnilap67.mymusic;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.DocumentsContract;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
            if (!tmp.renameTo(f)) return false;
            share(c, pl, f);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void reset(Context c, String pl) {
        file(c, pl).delete();
        unshare(c, pl);
    }


    static final String SHARED = ".cover.jpg";

    static File shared(String pl) {
        return new File(Library.rootPath() + "/" + pl + "/" + SHARED);
    }

    /** the picture into the playlist's folder (through the music folder's grant); false if it cannot */
    static boolean share(Context c, String pl, File src) {
        Uri tree = Importer.tree(c);
        if (tree == null || src == null || !src.isFile()) return false;
        try {
            ContentResolver r = c.getContentResolver();
            String dir = Importer.names(c, tree, Importer.rootDoc(c)).get(pl.toLowerCase());
            if (dir == null) return false;
            String doc = Importer.names(c, tree, dir).get(SHARED);
            Uri target = doc != null ? DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                    : DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, dir), "image/jpeg", SHARED);
            if (target == null) return false;
            InputStream in = new FileInputStream(src);
            OutputStream o = r.openOutputStream(target, "wt");
            try {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally {
                in.close();
                o.close();
            }
            shared(pl).setLastModified(System.currentTimeMillis());   // Android can keep the old time - Syncthing needs the new one
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void unshare(Context c, String pl) {
        Uri tree = Importer.tree(c);
        if (tree == null) return;
        try {
            String dir = Importer.names(c, tree, Importer.rootDoc(c)).get(pl.toLowerCase());
            String doc = dir == null ? null : Importer.names(c, tree, dir).get(SHARED);
            if (doc != null) DocumentsContract.deleteDocument(c.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, doc));
        } catch (Exception ignored) {
        }
    }

    private static volatile boolean sharedAll;

    /** once a run: every picture picked on this device before 19.9.3 goes into its playlist's folder - unless one is there already
     *  (then that one wins here too). True when one was written. */
    static boolean shareAll(Context c, Library lib) {
        if (sharedAll || lib == null || Importer.tree(c) == null) return false;
        sharedAll = true;
        boolean any = false;
        for (Library.Pl p : lib.playlists) {
            File mine = file(c, p.name);
            if (mine.isFile() && !shared(p.name).isFile()) any |= share(c, p.name, mine);
        }
        return any;
    }

    /** a picture came or went in a playlist's folder (from the other device) since the library was read */
    static boolean changed(Library lib) {
        if (lib == null) return false;
        for (Library.Pl p : lib.playlists) {
            File s = shared(p.name);
            String now = s.isFile() && s.length() > 0 ? stamp(s) : "";
            boolean was = p.cover.startsWith("s");
            if (was ? !p.cover.equals(now) : !now.isEmpty()) return true;
        }
        return false;
    }


    static InputStream openShared(Context c, String pl, String v) throws Exception {
        File dir = new File(c.getApplicationContext().getFilesDir(), "covers-shared");
        String key = Art.sha1(pl), ver = v == null ? "" : v.replaceAll("[^A-Za-z0-9-]", "");
        File cache = new File(dir, key + "-" + ver + ".jpg");
        if (cache.isFile() && cache.length() > 0) return new FileInputStream(cache);
        dir.mkdirs();
        InputStream in = null;
        Uri tree = Importer.tree(c);
        if (tree != null) {
            String d = Importer.names(c, tree, Importer.rootDoc(c)).get(pl.toLowerCase());
            String doc = d == null ? null : Importer.names(c, tree, d).get(SHARED);
            if (doc != null) in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc));
        }
        if (in == null) in = new FileInputStream(shared(pl));    // no grant: try it directly (it may be allowed)
        File tmp = new File(dir, key + ".tmp");
        OutputStream o = new FileOutputStream(tmp);
        try {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            in.close();
            o.close();
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().startsWith(key + "-")) f.delete();   // the older versions of it
        if (!tmp.renameTo(cache)) throw new java.io.IOException("could not keep the picture");
        return new FileInputStream(cache);
    }

    static String stamp(File s) {
        return "s" + s.lastModified() + "-" + s.length();
    }
}
