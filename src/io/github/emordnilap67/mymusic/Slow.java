package io.github.emordnilap67.mymusic;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.contentcapture.ContentCaptureManager;
import android.webkit.WebView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Every call the page makes to Android runs on one
 * thread (WebView's "JavaBridge") and the page waits for each one. This watches that thread and
 * Android's main thread four times a second while the app is in use: if either sits inside one of
 * Carmen Musicum's own calls for 1.5 s or more, where it was (its stack) is written down once it is
 * free - into Carmen Musicum backup/.freezes.txt, newest first, the last 12 - so the next freeze can
 * be traced to the line that held it. It only reads; it changes nothing.
 */
final class Slow {
    static final long EVERY_MS = 250, STUCK_MS = 1500, IDLE_MS = 8000;
    static final String FILE = ".freezes.txt";
    private static volatile Thread bridge, watcher;
    private static volatile long seenAt;           // the page's last call
    private static volatile Context app;

    private Slow() {}

    /** from the page's calls: remembers the page's thread and keeps the watch going */
    static void mark(Context c) {
        seenAt = SystemClock.uptimeMillis();
        if (bridge == null) bridge = Thread.currentThread();
        if (app == null) app = c.getApplicationContext();
        if (watcher == null) start();
    }

    private static synchronized void start() {
        if (watcher != null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                watch();
            }
        }, "slow-watch");
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    private static void watch() {
        Spell b = new Spell("page call (JavaBridge)"), m = new Spell("Android main thread");
        Thread main = Looper.getMainLooper().getThread();
        try {
            // the watch rests once the page has gone quiet - but never in the middle of a stuck spell
            while (SystemClock.uptimeMillis() - seenAt < IDLE_MS || b.on() || m.on()) {
                b.look(bridge);
                m.look(main);
                Thread.sleep(EVERY_MS);
            }
        } catch (Throwable ignored) {
        } finally {
            watcher = null;
        }
    }

    /** one thread: the call it is in (its outermost frame of ours), since when, and where it was */
    private static final class Spell {
        final String who;
        String call, first, last;
        long since;

        Spell(String w) { who = w; }

        boolean on() { return call != null; }

        void look(Thread t) {
            if (t == null) return;
            StackTraceElement[] st = t.getStackTrace();
            String ours = null;
            for (StackTraceElement e : st) if (e.getClassName().startsWith("io.github.emordnilap67.") && !e.getClassName().endsWith(".Slow")) ours = e.getClassName() + "." + e.getMethodName();
            long now = SystemClock.uptimeMillis();
            if (ours != null && ours.equals(call)) {
                if (now - since >= STUCK_MS) {
                    String s = text(t, st);
                    if (first == null) first = s;
                    last = s;
                }
                return;
            }
            if (first != null) write(who, now - since, first, last);
            call = ours;
            since = now;
            first = last = null;
        }
    }

    private static String text(Thread t, StackTraceElement[] st) {
        StringBuilder b = new StringBuilder("    (" + t.getState() + ")\n");
        for (int i = 0; i < st.length && i < 40; i++) b.append("    at ").append(st[i]).append('\n');
        return b.toString();
    }

    private static void write(String who, long ms, String first, String last) {
        Context c = app;
        if (c == null) return;
        String when = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date(System.currentTimeMillis() - ms));
        add(c, when + "  " + who + " held for " + String.format(Locale.US, "%.1f", ms / 1000.0) + " s  (app "
                + Backup.version(c) + ")\n  where it was:\n" + first + (last.equals(first) ? "" : "  where it was at the end:\n" + last));
    }

    /** the page's own account of a freeze (from its sampler: the function it was stuck in) - same file */
    static void note(Context c, final String text) {
        if (app == null) app = c.getApplicationContext();
        final Context a = app;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String when = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date());
                add(a, when + "  " + text + "  (app " + Backup.version(a) + ")\n  phone: " + phone(a) + "\n");
            }
        }, "slow-note").start();
    }

    private static synchronized void add(Context c, String entry) {
        SharedPreferences p = c.getSharedPreferences("slow", Context.MODE_PRIVATE);
        String[] old = p.getString("log", "").split("\n----\n");
        StringBuilder all = new StringBuilder(entry);
        for (int i = 0; i < old.length && i < 11; i++) if (!old[i].isEmpty()) all.append("\n----\n").append(old[i]);
        p.edit().putString("log", all.toString()).apply();
        save(c, all.toString());
    }

    /** Carmen Musicum backup/.freezes.txt, written over */
    private static void save(Context c, String text) {
        write(c, FILE, text);
    }

    private static void write(Context c, String name, String text) {
        OutputStream o = open(c, name, "text/plain");
        if (o == null) return;
        try {
            o.write(text.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            try {
                o.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** a file in Carmen Musicum backup/, opened to be written over (made if missing), or null - not on the main thread */
    static OutputStream open(Context c, String name, String mime) {
        Cursor cur = null;
        try {
            Uri tree = Importer.tree(c);
            if (tree == null) return null;
            String dirId = Importer.folderId(c, tree, Backup.DIR);
            if (dirId == null) return null;
            ContentResolver r = c.getContentResolver();
            String doc = null;
            cur = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            while (cur != null && cur.moveToNext()) if (name.equals(cur.getString(1))) doc = cur.getString(0);
            Uri u = doc != null ? DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                    : DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, dirId), mime, name);
            return u == null ? null : r.openOutputStream(u, "wt");
        } catch (Throwable e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /**
     * what on the phone makes Chrome inside the app do extra work for every row on the page - accessibility
     * services (they read the whole screen), content capture, autofill - and which WebView it is
     */
    static String phone(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            b.append("accessibility ").append(am != null && am.isEnabled() ? "ON" : "off");
            if (am != null && am.isEnabled()) {
                b.append(" (");
                int n = 0;
                for (AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (n++ > 0) b.append(", ");
                    b.append(i.getId());
                    if ((i.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0) b.append(" [reads the screen]");
                }
                if (n == 0) b.append("no service listed");
                b.append(")");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentCaptureManager cc = c.getSystemService(ContentCaptureManager.class);
                b.append(", content capture ").append(cc != null && cc.isContentCaptureEnabled() ? "ON" : "off");
            }
            AutofillManager af = c.getSystemService(AutofillManager.class);
            b.append(", autofill ").append(af != null && af.isEnabled() ? "ON" : "off");
            PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) b.append(", WebView ").append(wv.versionName);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            b.append(String.format(Locale.US, ", memory free %.1f of %.1f GB%s", mi.availMem / 1e9, mi.totalMem / 1e9, mi.lowMemory ? " (LOW)" : ""));
        } catch (Throwable e) {
            b.append(" (").append(e.getClass().getSimpleName()).append(")");
        }
        return b.toString();
    }
}
