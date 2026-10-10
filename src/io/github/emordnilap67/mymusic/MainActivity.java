package io.github.emordnilap67.mymusic;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The screen. The look (index.html) is a page inside the app; it asks the
 * player for everything through "Native". Nothing here goes on the internet:
 * https://appassets.androidplatform.net/ (a name Android keeps for exactly this) is answered by the app itself, never the internet.
 */
public class MainActivity extends Activity implements PlayerService.Listener {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://appassets.androidplatform.net/";

    private WebView web;
    private boolean granted;
    private Library.Song pendingDelete;
    private String pendingCover;
    static final int REQ_DELETE = 7, REQ_COVER = 8, REQ_TREE = 9, REQ_SONGS = 10, REQ_MIC = 11, REQ_RESTORE = 12;
    static final String MIC = "android.permission.RECORD_AUDIO";      // Android's name for "see your own sound"
    private String addKey;                  // "Add to playlist" waiting for the music folder
    private List<String> addFolders;
    private static volatile MainActivity current;
    private String sharedLink, importFolder, dlLink, dlFolder;
    /** what to do once the music folder is allowed ("backup" / "getback") */
    private String afterTree;
    private boolean anyway;
    /** one of our own pickers is open: the app is not really closed, no backup now */
    private boolean picking;

    @Override
    public void startActivityForResult(Intent i, int code) {
        picking = true;
        super.startActivityForResult(i, code);
    }

    /** downloads moved on: the page redraws its progress card */
    static void progress() {
        final MainActivity a = current;
        if (a == null) return;
        a.h.post(new Runnable() {
            @Override
            public void run() {
                a.js("window.onProgress&&onProgress(" + Progress.progressJson() + ")");
            }
        });
    }

    /** new songs are in the folder: the page reads the library again, quietly */
    static void songsArrived() {
        final MainActivity a = current;
        if (a == null) return;
        a.h.post(new Runnable() {
            @Override
            public void run() {
                a.js("window.onSongs&&onSongs()");
            }
        });
    }

    private static final Runnable PENDING_JS = new Runnable() {
        @Override
        public void run() {
            MainActivity a = current;
            if (a != null) a.js("window.onPending&&onPending()");
        }
    };

    /** the list of songs on their way changed (at most a couple of redraws a second) */
    static void pendingChanged() {
        MainActivity a = current;
        if (a == null) return;
        a.h.removeCallbacks(PENDING_JS);
        a.h.postDelayed(PENDING_JS, 400);
    }

    /** show a line on the screen if the app is open (downloads finishing) */
    static void tell(final String msg) {
        final MainActivity a = current;
        if (a == null) return;
        a.h.post(new Runnable() {
            @Override
            public void run() {
                a.js("window.onLibrary&&onLibrary(" + Library.q(msg) + ")");
            }
        });
    }
    private final Handler h = new Handler(Looper.getMainLooper());

    static final PlayerService.Job LIBRARY_CHANGED = new PlayerService.Job() {
        @Override
        public void run(PlayerService p) {
            p.libraryChanged();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        // Android 15+ draws apps under the status and navigation bars:
        // keep the page between them, black behind
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in;
            }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());     // stay inside the app
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                recreate();                                     // the page crashed: build the screen again
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Back: Android 13+ way (Android 16 apps no longer get onBackPressed)
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            back();
                        }
                    });
        }

        PlayerService.listener = this;
        startPlayer();
        granted = hasPermission();
        if (!granted) askPermission();
        current = this;
        shared(getIntent());
        resumeTapped(getIntent());
        web.loadUrl(ORIGIN);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!picking) Backup.auto(this);      // the new-phone backup, refreshed when something changed
    }

    @Override
    protected void onPause() {
        Viz.release();                        // the ring only runs while it can be seen
        if (web != null) web.onPause();      // the page rests while the app is not on screen
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        PlayerService.listener = this;
        current = this;
        if (!granted && hasPermission()) onGranted();     // allowed in Settings meanwhile
        PlayerService p = PlayerService.instance;
        if (p != null) onState(p.stateJson());
        else startPlayer();
        Retry.check(this);                    // songs that were waiting for YouTube carry on
        Mismatch.auto(this);                  // wrong-audio songs found and fixed by themselves
    }

    /** "Tap to keep downloading" (when Android would not let the retry start by itself) */
    private void resumeTapped(Intent i) {
        if (i == null || !Retry.A_RESUME.equals(i.getAction())) return;
        try {
            DownloadService.resume(this);
        } catch (Exception ignored) {
        }
    }

    private void startPlayer() {
        try {
            startService(new Intent(this, PlayerService.class));
        } catch (IllegalStateException e) {
            // not allowed this moment; the next tap starts it
        }
    }

    @Override
    protected void onDestroy() {
        if (PlayerService.listener == this) PlayerService.listener = null;
        if (current == this) current = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);       // leave the app, keep the music going
    }

    @Override
    public void onState(final String json) {
        h.post(new Runnable() {
            @Override
            public void run() {
                js("window.onNative&&onNative(" + json + ")");
            }
        });
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    // ---------------------------------------------------------------- permission

    static final String AUDIO = "android.permission.READ_MEDIA_AUDIO";            // Android 13+
    static final String NOTIFY = "android.permission.POST_NOTIFICATIONS";         // Android 13+

    boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    void askPermission() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{AUDIO, NOTIFY}, 1);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_MIC) {
            boolean ok = checkSelfPermission(MIC) == PackageManager.PERMISSION_GRANTED;
            if (ok) Viz.reset();
            boolean forever = !ok && !shouldShowRequestPermissionRationale(MIC);    // Android will not ask again
            js("window.onMic&&onMic(" + ok + "," + forever + ")");
            return;
        }
        if (hasPermission()) {
            onGranted();
        } else {
            js("window.onNoPermission&&onNoPermission()");
        }
    }

    // ---------------------------------------------------------------- adding songs

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        resumeTapped(i);
        shared(i);
        if (sharedLink != null) js("window.onShared&&onShared(" + Library.q(sharedLink) + ")");
    }

    /** a link shared from YouTube / YouTube Music: "Share > MY MUSIC" */
    private void shared(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) sharedLink = m.group();
    }

    private void addNow(final String key, final List<String> folders) {
        final Uri t = Importer.tree(this);
        final Library.Song s = Library.get(this).byKey.get(key);
        if (t == null || s == null || folders == null || folders.isEmpty()) return;
        js("window.onBusy&&onBusy(" + Library.q("Copying " + s.title + "...") + ")");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg = Importer.addSong(MainActivity.this, t, s, folders);
                reread(msg, false);
            }
        }).start();
    }

    private void importNow(Uri tree) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_SONGS);
    }

    /** music may be read now: read the library and show it */
    private void onGranted() {
        granted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Library.load(MainActivity.this);
                PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (web != null) web.reload();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- hide / delete

    // ---------------------------------------------------------------- the new-phone kit

    private void askTree(String then) {
        afterTree = then;
        js("window.onLibrary&&onLibrary(" + Library.q("Pick a folder for your music (or make one, like MY MUSIC inside Music), then Use this folder, then Allow") + ")");
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, Importer.initialUri(this));
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            afterTree = null;
        }
    }

    private void backupNow() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg = Backup.make(MainActivity.this, true, anyway);
                anyway = false;
                final String info = Backup.info(MainActivity.this);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onBackup&&onBackup(" + Library.q(msg) + "," + info + ")");
                    }
                });
            }
        }).start();
    }

    private void getBackNow() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int[] n = Backup.getBack(MainActivity.this, true);
                final String msg = n == null ? "Restore a backup first" : n[0] == 0 ? "Every song in the backup is already here"
                        : "Getting " + n[0] + (n[0] == 1 ? " song" : " songs") + " back into " + n[1]
                        + (n[1] == 1 ? " playlist" : " playlists") + " - they show up as they come in"
                        + (n[2] > 0 ? " (" + n[2] + " not from YouTube can't be fetched)" : "");
                final String info = Backup.info(MainActivity.this);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onBackup&&onBackup(" + Library.q(msg) + "," + info + ")");
                    }
                });
            }
        }).start();
    }

    private void restoreFrom(final Uri u) {
        js("window.onBusy&&onBusy(" + Library.q("Restoring...") + ")");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(u);
                    try {
                        out = Backup.restore(MainActivity.this, in);
                    } finally {
                        if (in != null) in.close();
                    }
                } catch (Exception e) {
                    out = "{\"ok\":false,\"msg\":" + Library.q("Could not open that file") + "}";
                }
                PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                final String res = out, info = Backup.info(MainActivity.this);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onRestored&&onRestored(" + res + "," + info + ")");
                    }
                });
            }
        }).start();
    }

    /** read the library again, tell the player and the page */
    private void reread(final String msg, final boolean load) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (load) Library.load(MainActivity.this);
                PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onLibrary&&onLibrary(" + Library.q(msg) + ")");
                    }
                });
            }
        }).start();
    }

    private void askDelete(String key) {
        Library.Song s = Library.get(this).byKey.get(key);
        if (s == null) return;
        if (Build.VERSION.SDK_INT < 30) {
            js("window.onLibrary&&onLibrary(" + Library.q("Deleting needs Android 11 or newer") + ")");
            return;
        }
        try {
            Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id);
            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(u));
            pendingDelete = s;
            // Android shows its own "Allow MY MUSIC to delete this?" box
            picking = true;
            startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
        } catch (Exception e) {
            pendingDelete = null;
            js("window.onLibrary&&onLibrary(" + Library.q("Could not delete " + s.title) + ")");
        }
    }

    /** send the song file itself (Messages, Drive, Bluetooth, ...) */
    private void shareSong(String key) {
        Library.Song s = Library.get(this).byKey.get(key);
        if (s == null) return;
        String name = (s.artist == null || s.artist.isEmpty() ? "" : s.artist + " - ") + s.title;
        try {
            Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id);
            String type = null;
            try {
                type = getContentResolver().getType(u);
            } catch (Exception ignored) {
            }
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(type != null && type.startsWith("audio/") ? type : "audio/mpeg")
                    .putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_TITLE, name)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // the app you pick gets to read just this one song
            send.setClipData(android.content.ClipData.newUri(getContentResolver(), name, u));
            Intent pick = Intent.createChooser(send, "Share " + name);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(pick);
        } catch (Exception e) {
            js("window.onLibrary&&onLibrary(" + Library.q("Could not share " + s.title) + ")");
        }
    }

    private void pickCover(String pl) {
        pendingCover = pl;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_COVER);
        } catch (Exception e) {
            pendingCover = null;
            js("window.onLibrary&&onLibrary(" + Library.q("No picture app found") + ")");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        picking = false;
        if (req == REQ_TREE) {
            if (result != RESULT_OK || data == null || data.getData() == null) {
                importFolder = null;
                dlLink = null;
                addKey = null;
                afterTree = null;
                return;
            }
            Uri t = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(t,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            if (!Importer.choose(this, t) || Importer.tree(this) == null) {
                importFolder = null;
                dlLink = null;
                addKey = null;
                afterTree = null;
                js("window.onLibrary&&onLibrary(" + Library.q("Pick (or make) a folder for your music - not the whole phone") + ")");
                return;
            }
            if (addKey != null) {                      // the folder was allowed for "Add to playlist"
                addNow(addKey, addFolders);
                addKey = null;
                return;
            }
            if (afterTree != null) {                   // the folder was allowed for the backup
                final String what = afterTree;
                afterTree = null;
                if ("getback".equals(what)) getBackNow();
                else backupNow();
                return;
            }
            if (dlLink != null) {                      // the folder was allowed for a download
                DownloadService.start(this, dlLink, dlFolder);
                js("window.onLibrary&&onLibrary(" + Library.q(dlFolder.isEmpty()
                        ? "Adding this playlist to MY MUSIC - its songs show up as they come in"
                        : "Adding to " + dlFolder + " - songs show up as they come in") + ")");
                dlLink = null;
                return;
            }
            if (importFolder != null) {
                importNow(t);
                return;
            }
            // "Music folder": read what is in the folder now
            final String name = Importer.label(this);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String m = Library.rescan(MainActivity.this);
                    PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                    tell("Music folder: " + name + " - " + m);
                }
            }).start();
            return;
        }
        if (req == REQ_RESTORE) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            restoreFrom(data.getData());
            return;
        }
        if (req == REQ_SONGS) {
            final String folder = importFolder;
            importFolder = null;
            final Uri t = Importer.tree(this);
            if (folder == null || t == null || result != RESULT_OK || data == null) return;
            final List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) picked.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (picked.isEmpty()) return;
            js("window.onBusy&&onBusy(" + Library.q("Copying " + picked.size() + (picked.size() == 1 ? " song..." : " songs...")) + ")");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String msg = Importer.copy(MainActivity.this, t, picked, folder);
                    reread(msg, false);
                }
            }).start();
            return;
        }
        if (req == REQ_COVER) {
            final String pl = pendingCover;
            pendingCover = null;
            if (pl == null || result != RESULT_OK || data == null || data.getData() == null) return;
            final Uri pic = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = Covers.save(MainActivity.this, pl, pic);
                    reread(ok ? "New picture for " + pl : "Could not use that picture", true);
                }
            }).start();
            return;
        }
        if (req != REQ_DELETE) return;
        Library.Song s = pendingDelete;
        pendingDelete = null;
        if (s == null) return;
        if (result == RESULT_OK) reread("Deleted: " + s.title, true);
        else reread("Not deleted", false);
    }

    // ---------------------------------------------------------------- the page's files

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        try {
            if (p.equals("/") || p.equals("/index.html")) return asset("index.html");
            if (p.equals("/api/library")) {
                byte[] b = Library.get(this).json.getBytes("UTF-8");
                return ok("application/json", new ByteArrayInputStream(b), "no-store");
            }
            if (p.equals("/art")) {
                Library.Song s = Library.get(this).byKey.get(u.getQueryParameter("p") + "/" + u.getQueryParameter("f"));
                File f = s == null ? null : Art.file(this, s, "big".equals(u.getQueryParameter("s")) ? 480 : 160);
                return f == null ? missing() : ok("image/jpeg", new FileInputStream(f), "max-age=604800");
            }
            if (p.equals("/cover")) {
                String pl = u.getQueryParameter("p");
                if (pl != null && !pl.contains("/") && !pl.contains("..")) {
                    File picked = Covers.file(this, pl);
                    if (picked.isFile()) return ok("image/jpeg", new FileInputStream(picked), "max-age=604800");
                    try {
                        return ok("image/jpeg", getAssets().open(Covers.assetName(pl)), "max-age=604800");
                    } catch (Exception notBuiltIn) {
                        // try the folder's own picture
                    }
                    for (String c : new String[]{"cover.jpg", "folder.jpg"}) {
                        File f = new File(Library.rootPath() + "/" + pl + "/" + c);
                        if (f.isFile()) return ok("image/jpeg", new FileInputStream(f), "max-age=86400");
                    }
                }
                return missing();
            }
            String name = p.substring(1);
            if (!name.isEmpty() && !name.contains("..")) return asset(name);
        } catch (Exception e) {
            // falls through to "not found"
        }
        return missing();
    }

    private WebResourceResponse asset(String name) throws java.io.IOException {
        return ok(mime(name), getAssets().open(name), name.endsWith(".html") ? "no-cache" : "max-age=86400");
    }

    private static WebResourceResponse ok(String type, InputStream in, String cache) {
        Map<String, String> hd = new HashMap<>();
        hd.put("Cache-Control", cache);
        String enc = type.startsWith("text/") || type.endsWith("json") ? "utf-8" : null;
        return new WebResourceResponse(type, enc, 200, "OK", hd, in);
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String n) {
        n = n.toLowerCase(Locale.US);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- what the page can ask

    public final class Bridge {
        @JavascriptInterface
        public void importSongs(final String folder) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    importFolder = folder;
                    Uri t = Importer.tree(MainActivity.this);
                    if (t != null) {
                        importNow(t);
                        return;
                    }
                    // first time: point MY MUSIC at the music folder
                    js("window.onLibrary&&onLibrary(" + Library.q("Pick a folder for your music (or make one, like MY MUSIC inside Music), then Use this folder, then Allow") + ")");
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Importer.initialUri(MainActivity.this));
                    startActivityForResult(i, REQ_TREE);
                }
            });
        }

        /** download inside MY MUSIC */
        @JavascriptInterface
        public String download(final String link, final String folder) {
            if (!Ytdl.available(MainActivity.this))
                return "The downloader is missing from this install - get the APK from the Releases page";
            if (Importer.tree(MainActivity.this) == null) {
                dlLink = link;
                dlFolder = folder;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onLibrary&&onLibrary(" + Library.q("Pick a folder for your music (or make one, like MY MUSIC inside Music), then Use this folder, then Allow") + ")");
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Importer.initialUri(MainActivity.this));
                        startActivityForResult(i, REQ_TREE);
                    }
                });
                return "";
            }
            DownloadService.start(MainActivity.this, link, folder);
            return folder.isEmpty() ? "Adding this playlist to MY MUSIC - its songs show up as they come in"
                    : "Adding to " + folder + " - songs show up as they come in";
        }

        /** songs on their way into playlists (see Pending) */
        @JavascriptInterface
        public String pending() {
            return Pending.json(MainActivity.this);
        }

        @JavascriptInterface
        public boolean builtIn() {
            return Ytdl.available(MainActivity.this);
        }

        @JavascriptInterface
        public String progress() {
            return Progress.progressJson();
        }

        @JavascriptInterface
        public void stopFetch() {
            DownloadService.stop(MainActivity.this);
        }

        /** a link that came in through Share, once */
        @JavascriptInterface
        public String takeShared() {
            String s = sharedLink;
            sharedLink = null;
            return s == null ? "" : s;
        }

        /** a YouTube link on the clipboard, to fill the box */
        @JavascriptInterface
        public String clipboardLink() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return "";
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                String s = t == null ? "" : t.toString().trim();
                return s.contains("youtu") ? s : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String state() {
            // read on the main thread, where the player lives
            final String[] out = {"{}"};
            final CountDownLatch done = new CountDownLatch(1);
            h.post(new Runnable() {
                @Override
                public void run() {
                    PlayerService p = PlayerService.instance;
                    if (p != null) out[0] = p.stateJson();
                    done.countDown();
                }
            });
            try {
                done.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return out[0];
        }

        @JavascriptInterface
        public void play(String keysJson, final int index, final boolean shuffle) {
            final List<String> keys = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(keysJson);
                for (int i = 0; i < a.length(); i++) keys.add(a.getString(i));
            } catch (Exception e) {
                return;
            }
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.setQueue(keys, index, shuffle);
                }
            });
        }

        @JavascriptInterface
        public void toggle() {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.toggle();
                }
            });
        }

        @JavascriptInterface
        public void next() {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.next(false);
                }
            });
        }

        @JavascriptInterface
        public void prev() {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.prev();
                }
            });
        }

        @JavascriptInterface
        public void seek(final int ms) {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.seek(ms);
                }
            });
        }

        @JavascriptInterface
        public void setShuffle(final boolean on) {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.setShuffle(on);
                }
            });
        }

        @JavascriptInterface
        public void setRepeat(final String r) {
            PlayerService.run(MainActivity.this, new PlayerService.Job() {
                @Override
                public void run(PlayerService p) {
                    p.setRepeat(r);
                }
            });
        }

        @JavascriptInterface
        public void rescan() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String msg = Library.rescan(MainActivity.this);
                    PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onLibrary&&onLibrary(" + Library.q(msg) + ")");
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void hide(final String key) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Library.Song s = Library.get(MainActivity.this).byKey.get(key);
                    Library.setHidden(MainActivity.this, key, true);
                    reread("Hidden: " + (s == null ? "song" : s.title), false);
                }
            }).start();
        }

        @JavascriptInterface
        public void unhide(final String key) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Library.setHidden(MainActivity.this, key, false);
                    reread("Back in MY MUSIC", false);
                }
            }).start();
        }

        @JavascriptInterface
        public void pickCover(final String pl) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.pickCover(pl);
                }
            });
        }

        @JavascriptInterface
        public void resetCover(final String pl) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Covers.reset(MainActivity.this, pl);
                    reread("Original picture back for " + pl, true);
                }
            }).start();
        }

        @JavascriptInterface
        public void deleteSong(final String key) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    askDelete(key);
                }
            });
        }

        // ------------------------------------------------ add to playlist, lyrics, the moving ring

        /** copy this song into these playlists (a JSON list of names; new ones are made) */
        @JavascriptInterface
        public String addTo(final String key, String foldersJson) {
            final List<String> folders = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(foldersJson);
                for (int i = 0; i < a.length(); i++) {
                    String f = a.getString(i).trim();
                    if (!f.isEmpty() && !f.contains("/") && !f.startsWith(".") && !folders.contains(f)) folders.add(f);
                }
            } catch (Exception e) {
                return "";
            }
            if (folders.isEmpty()) return "Pick a playlist first";
            if (Importer.tree(MainActivity.this) == null) {
                addKey = key;
                addFolders = folders;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        js("window.onBusy&&onBusy(" + Library.q("Pick a folder for your music (or make one, like MY MUSIC inside Music), then Use this folder, then Allow") + ")");
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Importer.initialUri(MainActivity.this));
                        startActivityForResult(i, REQ_TREE);
                    }
                });
                return "";
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    addNow(key, folders);
                }
            });
            return "";
        }

        /** look up the lyrics; the answer comes back through onLyrics(...) */
        @JavascriptInterface
        public void lyrics(final String key) {
            Lyrics.get(MainActivity.this, key, new Lyrics.Done() {
                @Override
                public void got(final String json) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onLyrics&&onLyrics(" + json + ")");
                        }
                    });
                }
            });
        }

        /** "Search by name": look the lyrics up by this song name and artist from now on */
        @JavascriptInterface
        public void lyricsAs(final String key, final String track, final String artist) {
            LyricsFix.put(MainActivity.this, key, track, artist);
            Lyrics.get(MainActivity.this, key, true, new Lyrics.Done() {
                @Override
                public void got(final String json) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onLyrics&&onLyrics(" + json + ")");
                        }
                    });
                }
            });
        }

        // ------------------------------------------------ wrong audio

        @JavascriptInterface
        public String mismatches() {
            return Mismatch.json(MainActivity.this);
        }

        @JavascriptInterface
        public void checkAudio(final String folder, final String link) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String r = Mismatch.check(MainActivity.this, folder, link);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onAudioCheck&&onAudioCheck(" + r + ")");
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void fixAudio(final String folder) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String msg = Mismatch.fix(MainActivity.this, folder);
                    PlayerService.run(MainActivity.this, LIBRARY_CHANGED);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onAudioFixed&&onAudioFixed(" + Library.q(msg) + ")");
                            js("window.onSongs&&onSongs()");
                        }
                    });
                }
            }).start();
        }

        // ------------------------------------------------ backup / new phone

        @JavascriptInterface
        public String backupInfo() {
            return Backup.info(MainActivity.this);
        }

        @JavascriptInterface
        public void savePrefs(final String json) {
            Backup.pagePrefs(MainActivity.this, json);
        }

        @JavascriptInterface
        public void backupNow(final boolean replace) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    anyway = replace;
                    if (Importer.tree(MainActivity.this) == null) askTree("backup");
                    else MainActivity.this.backupNow();
                }
            });
        }

        /** found = the backup in the music folder / MY MUSIC backup; otherwise pick a file */
        @JavascriptInterface
        public void restoreBackup(final boolean found) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    Uri t = Importer.tree(MainActivity.this);
                    String[] z = found && t != null ? Backup.find(MainActivity.this, t) : null;
                    if (z != null) {
                        restoreFrom(Uri.parse(z[0]));
                        return;
                    }
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                                        "application/octet-stream"})
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(Importer.AUTH,
                                        Importer.rootDoc(MainActivity.this).isEmpty() ? "primary:Music"
                                                : Importer.rootDoc(MainActivity.this) + "/" + Backup.DIR));
                        startActivityForResult(i, REQ_RESTORE);
                    } catch (Exception e) {
                        js("window.onLibrary&&onLibrary(" + Library.q("No file picker found") + ")");
                    }
                }
            });
        }

        /** "Music folder": pick (or make) the folder MY MUSIC reads and fills */
        @JavascriptInterface
        public void chooseFolder() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Importer.initialUri(MainActivity.this)), REQ_TREE);
                    } catch (Exception e) {
                        js("window.onLibrary&&onLibrary(" + Library.q("This phone has no folder picker") + ")");
                    }
                }
            });
        }

        /** the music folder's name for the screen ("" until one is picked) */
        @JavascriptInterface
        public String folder() {
            return Importer.tree(MainActivity.this) == null ? "" : Importer.label(MainActivity.this);
        }

        @JavascriptInterface
        public String getSongsBack() {
            if (!Ytdl.available(MainActivity.this))
                return "The downloader is missing from this install - get the APK from the Releases page";
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (Importer.tree(MainActivity.this) == null) askTree("getback");
                    else getBackNow();
                }
            });
            return "";
        }

        @JavascriptInterface
        public void shareSong(final String key) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.shareSong(key);
                }
            });
        }

        /** how often and when each song was played: {"playlist/file": [times, last ms]} */
        @JavascriptInterface
        public String plays() {
            return Plays.json(MainActivity.this);
        }

        // ------------------------------------------------ songs gone from YouTube: find another copy

        @JavascriptInterface
        public String missing() {
            Missing.pruneLater(MainActivity.this);
            return Missing.json(MainActivity.this);
        }

        /** search YouTube for other uploads; the answer comes back to onCopies(tok, list) */
        @JavascriptInterface
        public void searchCopies(final String query, final String tok) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String res = Copies.search(MainActivity.this, query);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            js("window.onCopies&&onCopies(" + Library.q(tok) + "," + res + ")");
                        }
                    });
                }
            }).start();
        }

        /** use this upload instead of the gone one: download it into the playlist */
        @JavascriptInterface
        public String useCopy(final String folder, final String oldId, final String newId) {
            String r = download("https://www.youtube.com/watch?v=" + newId, folder);
            forget(folder, oldId);
            return r;
        }

        /** let a gone song go ("*" = all of this playlist's): not looked for again */
        @JavascriptInterface
        public void dropMissing(final String folder, final String oldId) {
            forget(folder, oldId);
        }

        private void forget(final String folder, final String oldId) {
            final List<String> ids = Missing.ids(MainActivity.this, folder, oldId);
            Missing.remove(MainActivity.this, folder, oldId);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // in the playlist's download history, so sharing the playlist again skips it
                    Uri t = Importer.tree(MainActivity.this);
                    if (t == null || ids.isEmpty()) return;
                    String fid = Importer.folderId(MainActivity.this, t, folder);
                    if (fid != null) Importer.appendArchive(MainActivity.this, t, fid, ids);
                }
            }).start();
        }

        @JavascriptInterface
        public boolean micAllowed() {
            return checkSelfPermission(MIC) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void askMic() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{MIC}, REQ_MIC);
                }
            });
        }

        /** the ring's numbers right now (see Viz.read) */
        @JavascriptInterface
        public String viz() {
            if (checkSelfPermission(MIC) != PackageManager.PERMISSION_GRANTED) return "x";
            return Viz.read();
        }

        @JavascriptInterface
        public void vizStop() {
            Viz.release();
        }

        @JavascriptInterface
        public boolean hasPermission() {
            return MainActivity.this.hasPermission();
        }

        @JavascriptInterface
        public void askPermission() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.askPermission();
                }
            });
        }

        @JavascriptInterface
        public void openSettings() {
            h.post(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            });
        }
    }
}
