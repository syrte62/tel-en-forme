package com.savy.telenforme;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Point de restauration : une photo de l'état du téléphone (applis installées, désactivées,
 * réglages batterie, applis gardées) pour pouvoir revenir en arrière après une modification.
 */
final class Snapshot {

    static final String DIR = "restauration";
    static final int MAX = 12;

    static final class Entry {
        String pkg;
        String label;
        String installer;
        boolean system;
        boolean installed;
        boolean enabled;
        boolean unrestricted;
    }

    long time;
    String reason;
    final Map<String, Entry> apps = new LinkedHashMap<>();
    final Set<String> kept = new HashSet<>();

    // ------------------------------------------------------------------ prise de la photo

    @SuppressWarnings("deprecation")
    static Snapshot capture(Context c, String reason, Set<String> kept) {
        Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        s.reason = reason;
        if (kept != null) s.kept.addAll(kept);
        PackageManager pm = c.getPackageManager();
        PowerManager power = null;
        try {
            power = c.getSystemService(PowerManager.class);
        } catch (Exception ignored) {
        }
        List<ApplicationInfo> list;
        try {
            list = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        } catch (Exception e) {
            list = Collections.emptyList();
        }
        for (ApplicationInfo ai : list) {
            Entry e = new Entry();
            e.pkg = ai.packageName;
            e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.installed = (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            e.enabled = ai.enabled;
            boolean interesting = !e.system || !e.enabled || !e.installed
                    || Bloat.known(ai.packageName) != null
                    || pm.getLaunchIntentForPackage(ai.packageName) != null;
            if (interesting) {
                try {
                    e.label = ai.loadLabel(pm).toString();
                } catch (Exception ex) {
                    e.label = ai.packageName;
                }
            } else {
                e.label = ai.packageName;
            }
            if (!e.system) {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        e.installer = pm.getInstallSourceInfo(ai.packageName).getInstallingPackageName();
                    } else {
                        e.installer = pm.getInstallerPackageName(ai.packageName);
                    }
                } catch (Exception ignored) {
                }
            }
            if (power != null) {
                try {
                    e.unrestricted = power.isIgnoringBatteryOptimizations(ai.packageName);
                } catch (Exception ignored) {
                }
            }
            s.apps.put(e.pkg, e);
        }
        return s;
    }

    // ------------------------------------------------------------------ enregistrement

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static synchronized void save(Context c, Snapshot s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir(c), s.time + ".json")),
                StandardCharsets.UTF_8)) {
            w.write(s.toJson());
        } catch (Exception ignored) {
        }
        // On garde le point de départ (le plus ancien) et les plus récents
        List<File> files = files(c);
        while (files.size() > MAX) {
            File victim = files.get(files.size() - 2); // le plus ancien après le point de départ
            victim.delete();
            files.remove(files.size() - 2);
        }
    }

    static synchronized void delete(Context c, long time) {
        new File(dir(c), time + ".json").delete();
    }

    /** Fichiers triés du plus récent au plus ancien. */
    private static List<File> files(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        List<File> out = new ArrayList<>();
        if (fs != null) Collections.addAll(out, fs);
        out.sort((a, b) -> Long.compare(timeOf(b), timeOf(a)));
        return out;
    }

    private static long timeOf(File f) {
        try {
            return Long.parseLong(f.getName().replace(".json", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    static synchronized List<Snapshot> list(Context c) {
        List<Snapshot> out = new ArrayList<>();
        for (File f : files(c)) {
            Snapshot s = read(f);
            if (s != null) out.add(s);
        }
        return out;
    }

    static synchronized long lastTime(Context c) {
        List<File> fs = files(c);
        return fs.isEmpty() ? 0 : timeOf(fs.get(0));
    }

    private static Snapshot read(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return fromJson(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Crée le point de départ au tout premier lancement. */
    static void ensureBaseline(Context c, Set<String> kept) {
        if (lastTime(c) == 0) save(c, capture(c, "Point de départ (premier lancement)", kept));
    }

    /** Crée un point si le dernier date de plus de maxAgeMs. Renvoie true si un point a été créé. */
    static boolean createIfStale(Context c, String reason, Set<String> kept, long maxAgeMs) {
        if (System.currentTimeMillis() - lastTime(c) < maxAgeMs) return false;
        save(c, capture(c, reason, kept));
        return true;
    }

    // ------------------------------------------------------------------ format fichier

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("format", "tel-en-forme-restauration-1");
            o.put("time", time);
            o.put("reason", reason == null ? "" : reason);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            JSONArray k = new JSONArray();
            for (String p : kept) k.put(p);
            o.put("kept", k);
            JSONArray a = new JSONArray();
            for (Entry e : apps.values()) {
                JSONObject j = new JSONObject();
                j.put("p", e.pkg);
                j.put("l", e.label);
                if (e.installer != null) j.put("i", e.installer);
                j.put("s", e.system);
                j.put("n", e.installed);
                j.put("e", e.enabled);
                j.put("u", e.unrestricted);
                a.put(j);
            }
            o.put("apps", a);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static Snapshot fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("apps")) return null;
            Snapshot s = new Snapshot();
            s.time = o.optLong("time", System.currentTimeMillis());
            s.reason = o.optString("reason", "");
            JSONArray k = o.optJSONArray("kept");
            if (k != null) for (int i = 0; i < k.length(); i++) s.kept.add(k.getString(i));
            JSONArray a = o.getJSONArray("apps");
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Entry e = new Entry();
                e.pkg = j.getString("p");
                e.label = j.optString("l", e.pkg);
                e.installer = j.has("i") ? j.optString("i", null) : null;
                e.system = j.optBoolean("s");
                e.installed = j.optBoolean("n", true);
                e.enabled = j.optBoolean("e", true);
                e.unrestricted = j.optBoolean("u");
                s.apps.put(e.pkg, e);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ comparaison

    static final int CH_REMOVED_USER = 0;    // appli supprimée : à réinstaller
    static final int CH_REMOVED_SYSTEM = 1;  // préinstallée retirée par commande PC
    static final int CH_DISABLED = 2;        // préinstallée désactivée depuis
    static final int CH_ENABLED = 3;         // réactivée depuis
    static final int CH_BATTERY_LOST = 4;    // n'est plus « Sans restriction »
    static final int CH_BATTERY_GAINED = 5;  // est passée « Sans restriction »
    static final int CH_ADDED = 6;           // installée depuis

    static final class Change {
        int type;
        Entry then;
        Entry now;
    }

    /** Ce qui a changé entre ce point et maintenant. */
    List<Change> diff(Snapshot now) {
        List<Change> out = new ArrayList<>();
        for (Entry t : apps.values()) {
            Entry n = now.apps.get(t.pkg);
            boolean wasThere = t.installed;
            boolean isThere = n != null && n.installed;
            if (wasThere && !isThere) {
                out.add(change(t.system ? CH_REMOVED_SYSTEM : CH_REMOVED_USER, t, n));
                continue;
            }
            if (!wasThere || n == null) continue;
            if (t.enabled && !n.enabled) out.add(change(CH_DISABLED, t, n));
            else if (!t.enabled && n.enabled) out.add(change(CH_ENABLED, t, n));
            if (t.unrestricted && !n.unrestricted) out.add(change(CH_BATTERY_LOST, t, n));
            else if (!t.unrestricted && n.unrestricted) out.add(change(CH_BATTERY_GAINED, t, n));
        }
        for (Entry n : now.apps.values()) {
            if (n.system || !n.installed) continue;
            Entry t = apps.get(n.pkg);
            if (t == null || !t.installed) out.add(change(CH_ADDED, t, n));
        }
        out.sort((a, b) -> {
            if (a.type != b.type) return Integer.compare(a.type, b.type);
            return String.CASE_INSENSITIVE_ORDER.compare(label(a), label(b));
        });
        return out;
    }

    static String label(Change ch) {
        Entry e = ch.then != null ? ch.then : ch.now;
        return e == null ? "?" : (e.label == null ? e.pkg : e.label);
    }

    static String pkg(Change ch) {
        return ch.then != null ? ch.then.pkg : ch.now.pkg;
    }

    private static Change change(int type, Entry then, Entry now) {
        Change c = new Change();
        c.type = type;
        c.then = then;
        c.now = now;
        return c;
    }
}
