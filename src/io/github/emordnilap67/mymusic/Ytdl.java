package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The downloader built into MY MUSIC: Python 3.11 + ffmpeg made for Android
 * phones (from the youtubedl-android project, put in by build.sh as
 * lib/arm64-v8a/lib*.so) and yt-dlp itself (assets/yt-dlp, kept current
 * from yt-dlp's GitHub once a day).
 */
final class Ytdl {
    private Ytdl() {}

    static File base(Context c) {
        return new File(c.getNoBackupFilesDir(), "ytdl");
    }

    private static String lib(Context c) {
        return c.getApplicationInfo().nativeLibraryDir;
    }

    static boolean available(Context c) {
        return new File(lib(c), "libpython.so").isFile() && new File(lib(c), "libffmpeg.so").isFile();
    }

    /** unpack Python and ffmpeg (first time, and after an app update changes them) */
    static synchronized void init(Context c) throws Exception {
        File b = base(c);
        b.mkdirs();
        unpack(c, new File(lib(c), "libpython.zip.so"), new File(b, "python"), "py");
        unpack(c, new File(lib(c), "libffmpeg.zip.so"), new File(b, "ffmpeg"), "ff");
        File y = new File(b, "yt-dlp");
        if (!y.isFile()) {
            File part = new File(b, "yt-dlp.part");             // never leave a half copy behind
            InputStream in = c.getAssets().open("yt-dlp");
            try {
                copy(in, part);
            } finally {
                in.close();
            }
            if (!part.renameTo(y)) throw new java.io.IOException("could not set up yt-dlp");
        }
    }

    static boolean needsSetup(Context c) {
        SharedPreferences p = c.getSharedPreferences("ytdl", Context.MODE_PRIVATE);
        File py = new File(lib(c), "libpython.zip.so"), ff = new File(lib(c), "libffmpeg.zip.so");
        return !(py.length() + ":" + py.lastModified()).equals(p.getString("py", ""))
                || !(ff.length() + ":" + ff.lastModified()).equals(p.getString("ff", ""));
    }

    private static void unpack(Context c, File zip, File dir, String key) throws Exception {
        SharedPreferences p = c.getSharedPreferences("ytdl", Context.MODE_PRIVATE);
        String stamp = zip.length() + ":" + zip.lastModified();
        if (dir.isDirectory() && stamp.equals(p.getString(key, ""))) return;
        delete(dir);
        dir.mkdirs();
        Set<String> links = symlinks(zip);
        ZipFile z = new ZipFile(zip);
        try {
            String root = dir.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith("__MACOSX") || name.endsWith(".DS_Store")) continue;
                File out = new File(dir, name);
                if (!out.getCanonicalPath().startsWith(root) && !out.getCanonicalPath().equals(dir.getCanonicalPath())) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                InputStream in = z.getInputStream(e);
                try {
                    if (links.contains(name)) {
                        java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int n;
                        while ((n = in.read(buf)) > 0) t.write(buf, 0, n);
                        out.delete();
                        Os.symlink(new String(t.toByteArray(), "UTF-8"), out.getPath());
                    } else {
                        copy(in, out);
                    }
                } finally {
                    in.close();
                }
            }
        } finally {
            z.close();
        }
        p.edit().putString(key, stamp).apply();
    }

    /** names of the zip entries that are symbolic links (from the zip's own directory) */
    private static Set<String> symlinks(File zip) throws Exception {
        Set<String> out = new HashSet<>();
        RandomAccessFile f = new RandomAccessFile(zip, "r");
        try {
            long len = f.length();
            int scan = (int) Math.min(len, 65557);
            byte[] tail = new byte[scan];
            f.seek(len - scan);
            f.readFully(tail);
            int eocd = -1;
            for (int i = scan - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return out;
            int count = u16(tail, eocd + 10);
            long size = u32(tail, eocd + 12), off = u32(tail, eocd + 16);
            byte[] cd = new byte[(int) size];
            f.seek(off);
            f.readFully(cd);
            int p = 0;
            for (int k = 0; k < count && p + 46 <= cd.length; k++) {
                int nl = u16(cd, p + 28), el = u16(cd, p + 30), cl = u16(cd, p + 32);
                long ext = u32(cd, p + 38);
                String name = new String(cd, p + 46, nl, "UTF-8");
                if (((ext >>> 16) & 0170000) == 0120000) out.add(name);
                p += 46 + nl + el + cl;
            }
        } finally {
            f.close();
        }
        return out;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int i) {
        return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL;
    }

    static void copy(InputStream in, File out) throws Exception {
        File tmp = new File(out.getPath() + ".part");
        OutputStream o = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            o.close();
        }
        if (!tmp.renameTo(out)) throw new Exception("could not save " + out.getName());
    }

    static void delete(File f) {
        if (!java.nio.file.Files.isSymbolicLink(f.toPath())) {       // never follow a link out
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
        }
        f.delete();
    }

    /** keep yt-dlp current (YouTube changes often): once a day, quietly */
    static void maybeUpdate(Context c) {
        SharedPreferences p = c.getSharedPreferences("ytdl", Context.MODE_PRIVATE);
        long last = p.getLong("updated", 0);
        if (System.currentTimeMillis() - last < 20L * 3600 * 1000) return;
        File dst = new File(base(c), "yt-dlp");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(
                    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp").openConnection();
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            if (h.getResponseCode() != 200) return;
            File tmp = new File(base(c), "yt-dlp.new");
            InputStream in = h.getInputStream();
            try {
                copy(in, tmp);
            } finally {
                in.close();
            }
            // only switch if the new one really runs on our Python
            if (tmp.length() > 1000000 && runs(c, tmp)) {
                if (tmp.renameTo(dst)) p.edit().putLong("updated", System.currentTimeMillis()).apply();
            } else {
                tmp.delete();
                p.edit().putLong("updated", System.currentTimeMillis()).apply();   // try again tomorrow
            }
        } catch (Exception ignored) {
            // no internet or GitHub busy: the copy we have is used
        }
    }

    private static boolean runs(Context c, File ytdlp) {
        try {
            List<String> v = new ArrayList<>();
            v.add("--version");
            Process p = start(c, v, ytdlp);
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) { /* drain */ }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** yt-dlp with these arguments (stdout and stderr together) */
    static Process start(Context c, List<String> args) throws Exception {
        return start(c, args, new File(base(c), "yt-dlp"));
    }

    private static Process start(Context c, List<String> args, File ytdlp) throws Exception {
        File b = base(c);
        String py = new File(b, "python").getAbsolutePath(), ff = new File(b, "ffmpeg").getAbsolutePath();
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(lib(c), "libpython.so").getAbsolutePath());
        cmd.add(ytdlp.getAbsolutePath());
        cmd.addAll(args);
        if (!args.contains("--version")) {
            cmd.add("--ffmpeg-location");
            cmd.add(new File(lib(c), "libffmpeg.so").getAbsolutePath());
            cmd.add("--no-cache-dir");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", py + "/usr/lib:" + ff + "/usr/lib");
        env.put("SSL_CERT_FILE", py + "/usr/etc/tls/cert.pem");
        env.put("PATH", System.getenv("PATH") + ":" + lib(c));
        env.put("PYTHONHOME", py + "/usr");
        env.put("HOME", py + "/usr");
        env.put("TMPDIR", c.getCacheDir().getAbsolutePath());
        env.put("PYTHONIOENCODING", "utf-8");
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
