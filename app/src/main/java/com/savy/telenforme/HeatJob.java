package com.savy.telenforme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tâche de fond légère, toutes les 30 minutes :
 * - note la température de la batterie et les applis actives (historique de chauffe sur 7 jours)
 * - envoie le rappel d'entretien hebdomadaire si l'utilisateur l'a activé
 */
public class HeatJob extends JobService {

    static final int JOB_ID = 7;
    static final String FILE = "chauffe.txt";
    static final String PREFS = "telenforme";
    static final String CHANNEL = "entretien";
    static final long MIN = 60L * 1000;
    static final long DAY = 24 * 60 * MIN;
    static final long PERIOD = 30 * MIN;
    static final int HOT_TENTHS = 380; // 38 °C

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, HeatJob.class))
                    .setPeriodic(PERIOD)
                    .setPersisted(true)
                    .build();
            js.schedule(ji);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                record(c, false);
                maybeRemind(c);
            } catch (Exception ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    // ------------------------------------------------------------------ activité des applis

    /** Temps à l'écran et en service de fond par appli, entre from et to. */
    static void activity(UsageStatsManager usm, long from, long to,
                         Map<String, Long> fgOut, Map<String, Long> fgsOut) {
        try {
            UsageEvents ev = usm.queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            Map<String, Long> fgStart = new HashMap<>();
            Map<String, Long> fgsStart = new HashMap<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                String p = e.getPackageName();
                long ts = e.getTimeStamp();
                switch (e.getEventType()) {
                    case 1: // au premier plan
                        if (!fgStart.containsKey(p)) fgStart.put(p, ts);
                        break;
                    case 2: { // en arrière-plan
                        Long s = fgStart.remove(p);
                        if (s != null) add(fgOut, p, ts - s);
                        break;
                    }
                    case 19: // service de fond démarré (Android 10+)
                        if (!fgsStart.containsKey(p)) fgsStart.put(p, ts);
                        break;
                    case 20: { // service de fond arrêté
                        Long s = fgsStart.remove(p);
                        if (s != null) add(fgsOut, p, ts - s);
                        break;
                    }
                    default:
                        break;
                }
            }
            for (Map.Entry<String, Long> en : fgStart.entrySet()) add(fgOut, en.getKey(), to - en.getValue());
            for (Map.Entry<String, Long> en : fgsStart.entrySet()) add(fgsOut, en.getKey(), to - en.getValue());
        } catch (Exception ignored) {
        }
    }

    static void add(Map<String, Long> m, String k, long v) {
        if (v <= 0) return;
        Long old = m.get(k);
        m.put(k, (old == null ? 0 : old) + v);
    }

    /** Les applis les plus actives (écran + fond), sans l'écran d'accueil ni l'interface système. */
    private static List<String> topApps(Context c, long from, long to, int max) {
        List<String> out = new ArrayList<>();
        try {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            if (usm == null) return out;
            Map<String, Long> fg = new HashMap<>();
            Map<String, Long> fgs = new HashMap<>();
            activity(usm, from, to, fg, fgs);
            Map<String, Long> total = new HashMap<>(fg);
            for (Map.Entry<String, Long> en : fgs.entrySet()) add(total, en.getKey(), en.getValue());

            Set<String> ignore = new HashSet<>();
            ignore.add(c.getPackageName());
            ignore.add("com.android.systemui");
            ignore.add("android");
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
                    ignore.add(ri.activityInfo.packageName);
                }
            } catch (Exception ignored) {
            }

            List<Map.Entry<String, Long>> list = new ArrayList<>(total.entrySet());
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> en : list) {
                if (out.size() >= max) break;
                if (ignore.contains(en.getKey()) || en.getValue() < 2 * MIN) continue;
                out.add(en.getKey());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ historique

    static final class Reading {
        long time;
        int tenths;      // température en dixièmes de degré
        boolean charging;
        boolean screenOn;
        List<String> apps = new ArrayList<>();
    }

    /** Note un relevé. Si onlyIfStale, ne fait rien quand le dernier relevé a moins de 10 min. */
    static synchronized void record(Context c, boolean onlyIfStale) {
        long now = System.currentTimeMillis();
        List<String> lines = readLines(c);
        if (onlyIfStale && !lines.isEmpty()) {
            Reading last = parse(lines.get(lines.size() - 1));
            if (last != null && now - last.time < 10 * MIN) return;
        }
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int tenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        boolean screen = false;
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            screen = pm != null && pm.isInteractive();
        } catch (Exception ignored) {
        }
        List<String> top = topApps(c, now - PERIOD, now, 3);
        String line = now + ";" + tenths + ";" + (charging ? 1 : 0) + ";" + (screen ? 1 : 0) + ";"
                + TextUtils.join(",", top);

        long cutoff = now - 7 * DAY;
        List<String> keep = new ArrayList<>();
        for (String l : lines) {
            Reading r = parse(l);
            if (r != null && r.time >= cutoff) keep.add(l);
        }
        keep.add(line);
        try (FileWriter w = new FileWriter(new File(c.getFilesDir(), FILE), false)) {
            for (String l : keep) {
                w.write(l);
                w.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    static synchronized List<Reading> history(Context c) {
        List<Reading> out = new ArrayList<>();
        for (String l : readLines(c)) {
            Reading r = parse(l);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static List<String> readLines(Context c) {
        List<String> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isEmpty()) out.add(l);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Reading parse(String line) {
        try {
            String[] p = line.split(";", -1);
            if (p.length < 5) return null;
            Reading r = new Reading();
            r.time = Long.parseLong(p[0]);
            r.tenths = Integer.parseInt(p[1]);
            r.charging = "1".equals(p[2]);
            r.screenOn = "1".equals(p[3]);
            if (!p[4].isEmpty()) {
                for (String a : p[4].split(",")) if (!a.isEmpty()) r.apps.add(a);
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ rappel hebdomadaire

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void maybeRemind(Context c) {
        SharedPreferences sp = prefs(c);
        if (!sp.getBoolean("remind", false)) return;
        long now = System.currentTimeMillis();
        long lastOpen = sp.getLong("lastOpen", now);
        long lastRemind = sp.getLong("lastRemind", 0);
        if (now - lastOpen < 7 * DAY || now - lastRemind < 7 * DAY) return;
        if (android.os.Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rappel d'entretien",
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("C'est l'heure du petit entretien")
                    .setContentText("Regarde les applis inutiles et gourmandes de ton téléphone.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1, n);
            sp.edit().putLong("lastRemind", now).apply();
        } catch (Exception ignored) {
        }
    }
}
