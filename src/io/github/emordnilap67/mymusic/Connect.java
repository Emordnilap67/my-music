package io.github.emordnilap67.mymusic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CONNECT - play on another device, like Spotify Connect.
 *
 * Every device with Carmen Musicum open listens on the Wi-Fi (port 47067) and makes itself known through Android's own
 * network discovery (NSD). Both devices hold the same songs in the same playlists (copied or synced between them), so
 * NOTHING IS STREAMED: "play on the tablet" hands the tablet the whole queue and the spot in the song, this device stops,
 * and it becomes the REMOTE - every button of the player goes to the tablet, and the player shows the tablet's song (its
 * picture, words and times come from this device's own copy; songs it lacks are left out). "This phone" brings it back
 * the same way. The volume keys turn the other device up and down while it is the one playing.
 *
 * Only Carmen Musicum signed with the same key is let in: every request carries a token made from the app's signing
 * certificate. Any device on the same Wi-Fi running the same build of the app can see this one while the app is open.
 */
final class Connect {
    static final int PORT = 47067;
    static final String TYPE = "_carmenmusicum._tcp";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    /** button presses for the other device go one at a time, in the order they were made */
    private static final ExecutorService CMD = Executors.newSingleThreadExecutor();

    static final class Peer {
        String id = "", name = "", host = "";
        int port;
    }

    private static boolean started;
    private static Context app;
    static String myId = "", myName = "Phone";
    private static String token = "";
    private static int myPort;
    private static NsdManager.RegistrationListener reg;

    /** the device this one is the remote of; null = this device plays the music itself */
    static volatile Peer remote;
    /** the other device's last answer to "what is playing?" (with "remote" added) */
    static volatile String remoteState = "{}";
    private static volatile int fails;
    private static volatile boolean asking;

    /** devices found (id -> where) */
    private static final Map<String, Peer> seen = new ConcurrentHashMap<>();
    private static volatile boolean scanning;
    private static final List<NsdServiceInfo> toResolve = new ArrayList<>();
    private static boolean resolving;

    /** started by the app and by the player, whichever comes first; runs while the app is alive */
    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        SharedPreferences pr = app.getSharedPreferences("connect", Context.MODE_PRIVATE);
        myId = pr.getString("id", "");
        if (myId.length() < 8) {
            myId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            pr.edit().putString("id", myId).apply();
        }
        myName = app.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? "Tablet" : "Phone";
        token = makeToken(app);
        try {
            JSONArray a = new JSONArray(pr.getString("peers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Peer p = new Peer();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.host = o.optString("host");
                p.port = o.optInt("port", PORT);
                if (!p.id.isEmpty() && !p.id.equals(myId)) seen.put(p.id, p);
            }
        } catch (Exception ignored) {
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        });
    }

    private static String makeToken(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("carmen-musicum-connect".getBytes("UTF-8"));
            for (Signature s : sigs) md.update(s.toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "none";
        }
    }

    // ---------------------------------------------------------------- this device, listening

    private static void serve() {
        ServerSocket ss = null;
        for (int p = PORT; p < PORT + 6 && ss == null; p++) {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                myPort = p;
            } catch (IOException e) {
                try {
                    if (ss != null) ss.close();
                } catch (IOException ignored) {
                }
                ss = null;
            }
        }
        if (ss == null) return;
        register();
        while (true) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return;
            }
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    handle(s);
                }
            });
        }
    }

    private static NsdManager nsd() {
        return (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    }

    private static void register() {
        try {
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("Carmen " + myName + " " + myId.substring(0, 4));
            si.setServiceType(TYPE);
            si.setPort(myPort);
            si.setAttribute("id", myId);
            si.setAttribute("name", myName);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            };
            nsd().registerService(si, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception ignored) {
        }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(6000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            String method = rl[0], path = rl.length > 1 ? rl[1] : "/";
            int len = 0;
            String tok = "", h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c < 0) continue;
                String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
                if (k.equals("content-length")) len = Integer.parseInt(v);
                else if (k.equals("x-carmen")) tok = v;
            }
            byte[] body = new byte[Math.max(0, Math.min(len, 4 << 20))];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            if (token.equals(tok) && path.startsWith("/vizstream")) {   // the ring's readings, as they come
                vizStream(s);
                return;
            }
            int code = 200;
            String out;
            if (!token.equals(tok)) {
                code = 403;
                out = "{\"ok\":false}";
            } else {
                out = route(method, path, new String(body, 0, got, "UTF-8"));
            }
            byte[] ob = out.getBytes("UTF-8");
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden")
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + ob.length
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(ob);
            os.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String route(String method, String path, String body) throws Exception {
        if (path.startsWith("/hello")) {
            return "{\"id\":" + Library.q(myId) + ",\"name\":" + Library.q(myName) + ",\"port\":" + myPort + "}";
        }
        if (path.startsWith("/state")) return localState();
        if (path.startsWith("/hand")) {                  // the other device takes the music: our queue + spot, and we stop
            final String[] out = {"{}"};
            onMain(new Runnable() {
                @Override
                public void run() {
                    PlayerService p = PlayerService.instance;
                    if (p != null) {
                        out[0] = p.handJson();
                        p.pause();
                    }
                }
            });
            return out[0];
        }
        if (path.startsWith("/take")) return "{\"ok\":" + takeHere(body) + "}";
        if (path.startsWith("/cmd")) return cmdHere(new JSONObject(body));
        return "{\"ok\":false}";
    }

    /**
     * The bars on the remote: it has no sound of its own to read, so this device sends it the ring's readings
     * (Viz - its own player's sound) as they come: one line every 30 ms, for as long as the remote keeps the line open.
     */
    private static void vizStream(Socket s) {
        try {
            OutputStream os = s.getOutputStream();
            os.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            boolean mic = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            long end = System.currentTimeMillis() + 3 * 3600 * 1000L;
            while (System.currentTimeMillis() < end) {
                os.write(((mic ? Viz.read() : "x") + "\n").getBytes("UTF-8"));
                os.flush();
                pause(30);
            }
        } catch (Exception ignored) {                   // the remote closed it
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** what this device is playing, with its name */
    private static String localState() {
        final String[] out = {"{}"};
        onMain(new Runnable() {
            @Override
            public void run() {
                PlayerService p = PlayerService.instance;
                if (p != null) out[0] = p.stateJson();
            }
        });
        String s = out[0];
        return s.length() > 2 ? "{\"dev\":" + Library.q(myName) + "," + s.substring(1) : "{\"dev\":" + Library.q(myName) + "}";
    }

    /** the music comes to this device: the queue + the spot another device handed over */
    private static boolean takeHere(final String json) {
        if (remote != null) {                            // it was the remote of another device - now it plays itself
            remote = null;
            remoteState = "{}";
            gone();
            info();
        }
        if (PlayerService.instance == null) {
            try {
                app.startService(new Intent(app, PlayerService.class));
            } catch (Exception e) {
                return false;                            // Android does not let it start now (app closed in the background)
            }
            for (int i = 0; i < 40 && PlayerService.instance == null; i++) pause(100);
            if (PlayerService.instance == null) return false;
        }
        final boolean[] ok = {false};
        onMain(new Runnable() {
            @Override
            public void run() {
                PlayerService p = PlayerService.instance;
                if (p != null) ok[0] = p.takeHand(json);
            }
        });
        return ok[0];
    }

    /** a button pressed on the remote */
    private static String cmdHere(final JSONObject o) {
        String c = o.optString("c");
        if (c.equals("fav")) {                          // hearted on the remote - hearted here too
            Favs.set(app, o.optString("key"), o.optBoolean("on"));
            MainActivity.page("window.loadFavs&&loadFavs()");
            return "{\"ok\":true}";
        }
        if (c.equals("vol")) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "{\"ok\":false}";
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    o.optInt("d") > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            return "{\"ok\":true,\"vol\":" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + ",\"max\":" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "}";
        }
        PlayerService.run(app, new PlayerService.Job() {
            @Override
            public void run(PlayerService p) {
                String c = o.optString("c");
                switch (c) {
                    case "toggle": p.toggle(); break;
                    case "pause": p.pause(); break;
                    case "resume": p.play(); break;
                    case "next": p.next(false); break;
                    case "prev": p.prev(); break;
                    case "seek": p.seek(o.optInt("ms")); break;
                    case "shuffle": p.setShuffle(o.optBoolean("on")); break;
                    case "repeat": p.setRepeat(o.optString("r", "off")); break;
                    case "unqueue": p.unqueue(o.optInt("n", -1)); break;
                    case "enqueue": p.enqueue(o.optString("key"), o.optBoolean("first")); break;
                    case "play": {
                        JSONArray a = o.optJSONArray("keys");
                        List<String> keys = new ArrayList<>();
                        if (a != null) for (int i = 0; i < a.length(); i++) keys.add(a.optString(i));
                        if (!keys.isEmpty()) p.setQueue(keys, o.optInt("i"), o.optBoolean("shuffle"));
                        break;
                    }
                    default: break;
                }
            }
        });
        return "{\"ok\":true}";
    }

    // ---------------------------------------------------------------- finding the other devices

    /** look for the other devices (the list sheet is open); answers come in through info() */
    static void scan() {
        if (app == null) return;
        if (scanning) {
            info();
            return;
        }
        scanning = true;
        info();
        for (final Peer p : new ArrayList<>(seen.values())) {     // the ones seen before: asked straight away
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    if (!probe(p.host, p.port)) {
                        Peer now = seen.get(p.id);
                        if (now != null && now.host.equals(p.host) && (remote == null || !remote.id.equals(p.id)))
                            seen.remove(p.id);
                        info();
                    }
                }
            });
        }
        POOL.execute(new Runnable() {             // and every address on this Wi-Fi, in case Android's discovery misses it
            @Override
            public void run() {
                sweep();
            }
        });
        final NsdManager m = nsd();
        final NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo si) { }
            @Override
            public void onServiceFound(NsdServiceInfo si) {
                if (si.getServiceName() != null && si.getServiceName().endsWith(" " + myId.substring(0, 4))) return;   // itself
                synchronized (toResolve) {
                    toResolve.add(si);
                }
                resolveNext();
            }
        };
        try {
            m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Exception ignored) {
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    m.stopServiceDiscovery(dl);
                } catch (Exception ignored) {
                }
                scanning = false;
                info();
            }
        }, 6000);
    }

    /**
     * the backup way to find the other device: knock on port 47067 of every address next to this one on the Wi-Fi
     * (x.x.x.1 - .254, 32 at a time, 0.4 s each - about 3 s); the ones that answer are asked who they are (/hello)
     */
    private static void sweep() {
        List<String> bases = new ArrayList<>();
        final List<String> mine = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    mine.add(ip);
                    String base = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!bases.contains(base)) bases.add(base);
                }
            }
        } catch (Exception ignored) {
        }
        ExecutorService knock = Executors.newFixedThreadPool(32);
        for (String base : bases) {
            for (int i = 1; i < 255; i++) {
                final String host = base + i;
                if (mine.contains(host)) continue;
                knock.execute(new Runnable() {
                    @Override
                    public void run() {
                        Socket s = new Socket();
                        boolean open = false;
                        try {
                            s.connect(new InetSocketAddress(host, PORT), 400);
                            open = true;
                        } catch (Exception ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (open) probe(host, PORT);
                    }
                });
            }
        }
        knock.shutdown();
    }

    /** Android resolves one found device at a time */
    private static void resolveNext() {
        final NsdServiceInfo si;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            resolving = true;
            si = toResolve.remove(0);
        }
        try {
            nsd().resolveService(si, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int e) {
                    done();
                }

                @Override
                public void onServiceResolved(final NsdServiceInfo i) {
                    final InetAddress a = i.getHost();
                    final int port = i.getPort();
                    if (a != null) POOL.execute(new Runnable() {
                        @Override
                        public void run() {
                            probe(a.getHostAddress(), port);
                        }
                    });
                    done();
                }

                private void done() {
                    synchronized (toResolve) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (toResolve) {
                resolving = false;
            }
        }
    }

    /** is Carmen Musicum (signed with the same key) answering there? */
    private static boolean probe(String host, int port) {
        if (host == null || host.isEmpty()) return false;
        Peer t = new Peer();
        t.host = host;
        t.port = port;
        String r = call(t, "GET", "/hello", null, 1500);
        if (r == null) return false;
        try {
            JSONObject o = new JSONObject(r);
            t.id = o.optString("id");
            t.name = o.optString("name", "Device");
            if (t.id.isEmpty() || t.id.equals(myId)) return false;
            seen.put(t.id, t);
            savePeers();
            info();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void savePeers() {
        try {
            JSONArray a = new JSONArray();
            for (Peer p : seen.values()) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("host", p.host);
                o.put("port", p.port);
                a.put(o);
            }
            app.getSharedPreferences("connect", Context.MODE_PRIVATE).edit().putString("peers", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** the list for the page: this device, the ones found, which one plays */
    static String infoJson() {
        StringBuilder b = new StringBuilder("{\"me\":").append(Library.q(myName))
                .append(",\"remote\":").append(Library.q(remote == null ? "" : remote.id))
                .append(",\"scanning\":").append(scanning).append(",\"devs\":[");
        boolean first = true;
        for (Peer p : seen.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(Library.q(p.id)).append(",\"name\":").append(Library.q(p.name)).append('}');
        }
        return b.append("]}").toString();
    }

    private static void info() {
        MainActivity.page("window.onConnect&&onConnect(" + infoJson() + ")");
    }

    private static void note(String text) {
        MainActivity.page("typeof toast==='function'&&toast(" + Library.q(text) + ")");
    }

    // ---------------------------------------------------------------- this device as the remote

    /** play on another device (id), or here again (id empty) */
    static void use(final String id) {
        if (app == null) return;
        CMD.execute(new Runnable() {
            @Override
            public void run() {
                if (id == null || id.isEmpty()) {
                    here();
                    return;
                }
                final Peer p = seen.get(id);
                if (p == null) {
                    note("Can't see that device now - open Carmen Musicum on it");
                    return;
                }
                Peer was = remote;
                if (was != null && was.id.equals(p.id)) {
                    info();
                    return;
                }
                String hand = was != null ? call(was, "GET", "/hand", null, 4000) : localHand();
                boolean give = hand != null && hasSong(hand);
                /* this device paused and the other one already playing - just become its remote (its music stays) */
                if (give && was == null && !playingIn(hand)) {
                    String there = call(p, "GET", "/state", null, 2500);
                    if (there != null && there.contains("\"playing\":true")) give = false;
                }
                if (give) {
                    String r = call(p, "POST", "/take", hand, 6000);
                    if (r == null || !r.contains("\"ok\":true")) {
                        note("The " + p.name.toLowerCase(Locale.ROOT) + " didn't take it - is Carmen Musicum open on it?");
                        if (was != null) call(was, "POST", "/cmd", "{\"c\":\"toggle\"}", 2500);   // the one that stopped plays on
                        return;
                    }
                    if (was == null) onMain(new Runnable() {     // it plays there now - this device goes quiet
                        @Override
                        public void run() {
                            PlayerService s = PlayerService.instance;
                            if (s != null) s.pause();
                        }
                    });
                }
                remote = p;
                fails = 0;
                remoteState = "{\"remote\":" + Library.q(p.name) + "}";
                info();
                fetch(p);
                poll(p);
                note("Playing on the " + p.name.toLowerCase(Locale.ROOT));
            }
        });
    }

    /** the music comes back to this device from the one it was the remote of */
    private static void here() {
        final Peer p = remote;
        if (p == null) {
            info();
            return;
        }
        String hand = call(p, "GET", "/hand", null, 4000);
        remote = null;
        remoteState = "{}";
        gone();
        if (hand != null && hasSong(hand)) takeHere(hand);
        pushLocal();
        info();
        note("Playing on this " + myName.toLowerCase(Locale.ROOT));
    }

    private static String localHand() {
        final String[] out = {null};
        onMain(new Runnable() {
            @Override
            public void run() {
                PlayerService p = PlayerService.instance;
                if (p != null) out[0] = p.handJson();
            }
        });
        return out[0];
    }

    private static boolean playingIn(String hand) {
        try {
            return new JSONObject(hand).optBoolean("playing");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasSong(String hand) {
        try {
            JSONObject o = new JSONObject(hand);
            JSONArray order = o.optJSONArray("order");
            return (order != null && order.length() > 0) || !o.optString("extra").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** the page asks "what is playing?" - on the remote, the other device answers (comes in through onNative) */
    static void ask() {
        final Peer p = remote;
        if (p == null || asking) return;
        asking = true;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    fetch(p);
                } finally {
                    asking = false;
                }
            }
        });
    }

    private static void fetch(Peer p) {
        String r = call(p, "GET", "/state", null, 2500);
        if (remote != p) return;
        if (r == null) {
            if (++fails >= 4) {                          // ~8 s without an answer: the music is lost - back to this device
                remote = null;
                remoteState = "{}";
                gone();
                note("Lost the " + p.name.toLowerCase(Locale.ROOT) + " - back on this " + myName.toLowerCase(Locale.ROOT));
                pushLocal();
                info();
            }
            return;
        }
        fails = 0;
        String s = r.trim();
        remoteState = "{\"remote\":" + Library.q(p.name) + (s.length() > 2 ? "," + s.substring(1) : "}");
        push(remoteState);
        final String st = s;
        MAIN.post(new Runnable() {                       // this device's notification, lock screen and widget show it
            @Override
            public void run() {
                PlayerService ps = PlayerService.instance;
                if (ps != null && remote != null) ps.remoteState(st);
            }
        });
    }

    /** back to this device's own song on its notification, lock screen and widget */
    private static void gone() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                PlayerService ps = PlayerService.instance;
                if (ps != null) ps.remoteGone();
            }
        });
    }

    /** while this device is the remote, it asks the other one what is playing every 2.5 s - with the app off screen too,
     *  so the widget and the lock screen keep up */
    private static void poll(final Peer p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (remote == p) {
                    pause(2500);
                    if (remote == p) fetch(p);
                }
            }
        }, "connect-poll");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- the ring's readings from the other device

    private static volatile String remoteViz = "";
    private static volatile long vizAskedAt;
    private static volatile Thread vizThread;

    /** the page's ring asks for a reading (on the remote): the newest from the other device; "" = none yet */
    static String viz() {
        vizAskedAt = System.currentTimeMillis();
        final Peer p = remote;
        if (p == null) return "x";
        if (vizThread == null) {
            synchronized (Connect.class) {
                if (vizThread == null) {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                vizRead(p);
                            } finally {
                                vizThread = null;
                            }
                        }
                    }, "connect-viz");
                    t.setDaemon(true);
                    vizThread = t;
                    t.start();
                }
            }
        }
        return remoteViz;
    }

    /** one open line to the other device's /vizstream while the ring is asking (stops 3 s after the last ask) */
    private static void vizRead(Peer p) {
        while (remote == p && System.currentTimeMillis() - vizAskedAt < 3000) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(p.host, p.port), 2500);
                s.setSoTimeout(4000);
                OutputStream os = s.getOutputStream();
                os.write(("GET /vizstream HTTP/1.1\r\nHost: " + p.host + "\r\nX-Carmen: " + token
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                os.flush();
                InputStream in = new BufferedInputStream(s.getInputStream());
                String status = readLine(in), h;
                if (status == null || !status.contains(" 200")) throw new IOException("refused");
                while ((h = readLine(in)) != null && !h.isEmpty()) { }
                String line;
                while ((line = readLine(in)) != null && remote == p && System.currentTimeMillis() - vizAskedAt < 3000) remoteViz = line;
            } catch (Exception e) {
                pause(1000);                             // lost for a moment - try again while still asked
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        remoteViz = "";
    }

    /** a button on this remote: sent to the device that plays, then its new state is shown (as JSON - every call goes
     *  through cmd(c, extras...), so a one-word button like "toggle" is never sent as a bare word) */
    private static void cmdJson(final String json) {
        final Peer p = remote;
        if (p == null) return;
        CMD.execute(new Runnable() {
            @Override
            public void run() {
                String r = call(p, "POST", "/cmd", json, 2500);
                if (r != null && json.contains("\"vol\"")) {
                    try {
                        JSONObject o = new JSONObject(r);
                        note(p.name + " volume " + o.optInt("vol") + " of " + o.optInt("max"));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                pause(180);                              // the player there catches up first
                fetch(p);
            }
        });
    }

    static void cmd(String c, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("c", c);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
            cmdJson(o.toString());
        } catch (Exception ignored) {
        }
    }

    private static void push(String json) {
        PlayerService.Listener l = PlayerService.listener;
        if (l != null) l.onState(json);
    }

    private static void pushLocal() {
        onMain(new Runnable() {
            @Override
            public void run() {
                PlayerService p = PlayerService.instance;
                push(p != null ? p.stateJson() : "{}");
            }
        });
    }

    // ---------------------------------------------------------------- plumbing

    private static String call(Peer p, String method, String path, String body, int ms) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(p.host, p.port), ms);
            s.setSoTimeout(ms + 2000);
            byte[] b = body == null ? new byte[0] : body.getBytes("UTF-8");
            OutputStream os = s.getOutputStream();
            os.write((method + " " + path + " HTTP/1.1\r\nHost: " + p.host + "\r\nX-Carmen: " + token
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(b);
            os.flush();
            InputStream in = new BufferedInputStream(s.getInputStream());
            String status = readLine(in);
            if (status == null || !status.contains(" 200")) return null;
            int len = -1;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((len < 0 || bo.size() < len) && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    /** run on the main thread (where the player lives) and wait for it, up to 3 s */
    private static void onMain(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
