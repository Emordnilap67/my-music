package io.github.emordnilap67.mymusic;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Find another copy": asks YouTube (through the built-in yt-dlp) for the
 * top uploads matching a song that is gone, so one can be picked instead.
 */
final class Copies {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private Copies() {}

    /** up to 6 matches as JSON: [{"id","t","a","d"}], or {"error": "..."} */
    static String search(Context c, String query) {
        try {
            if (!Ytdl.available(c)) return "{\"error\":\"The downloader is missing from this install - get the APK from the Releases page\"}";
            Ytdl.init(c);
            String q = query.replaceAll("\\s+", " ").trim();
            if (q.isEmpty()) return "{\"error\":\"No name to search for\"}";
            List<String> a = new ArrayList<>(Arrays.asList("--flat-playlist", "--no-warnings",
                    "--print", "MMID\t%(id)s\t%(title)s\t%(duration)s\t%(channel,uploader|)s",
                    "ytsearch6:" + q));
            Process p = Ytdl.start(c, a);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder("[");
            String line, err = "";
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MMID\t")) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 5 || !ID.matcher(f[1].trim()).matches()) continue;
                    long d = 0;
                    try {
                        d = Math.round(Double.parseDouble(f[3].trim()));
                    } catch (Exception ignored) {
                    }
                    if (n++ > 0) out.append(',');
                    out.append("{\"id\":").append(Library.q(f[1].trim()))
                            .append(",\"t\":").append(Library.q(na(f[2])))
                            .append(",\"a\":").append(Library.q(na(f[4]).replaceAll("\\s*-\\s*Topic$", "")))
                            .append(",\"d\":").append(d).append('}');
                } else if (line.startsWith("ERROR")) {
                    err = line.replaceFirst("^ERROR:\\s*", "");
                }
            }
            p.waitFor();
            if (n == 0) {
                if (err.contains("not a bot") || err.contains("429"))
                    return "{\"error\":\"YouTube is pausing this phone right now - try again in a while\"}";
                return "{\"error\":" + Library.q(err.isEmpty() ? "Nothing found" : (err.length() > 90 ? err.substring(0, 90) + "..." : err)) + "}";
            }
            return out.append(']').toString();
        } catch (Exception e) {
            return "{\"error\":" + Library.q("Could not search YouTube: " + e.getMessage()) + "}";
        }
    }

    private static String na(String s) {
        s = s == null ? "" : s.trim();
        return "NA".equals(s) ? "" : s;
    }
}
