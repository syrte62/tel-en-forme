package com.savy.telenforme;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tél en forme : un petit coach d'entretien pour Android.
 * - Note de santé du téléphone (stockage, mémoire, batterie, applis inutilisées)
 * - Liste des applis installées, avec leur poids et leur dernière utilisation
 * - Désinstallation propre (par la fenêtre officielle d'Android), une ou plusieurs à la fois
 * - Raccourcis vers les réglages d'entretien
 */
public class MainActivity extends Activity {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int UNUSED_DAYS = 30;
    private static final int REQ_UNINSTALL = 42;

    private static final int FILTER_UNUSED = 0;
    private static final int FILTER_HEAVY = 1;
    private static final int FILTER_ALL = 2;

    static class AppItem {
        String pkg;
        String label;
        Drawable icon;
        long size = -1;
        long cache = -1;
        long lastUsed = 0;
        long installed = 0;
    }

    // Couleurs
    private int cBg, cCard, cText, cSub, cAccent, cLine;
    private final int cWarn = Color.parseColor("#E0A100");
    private final int cDanger = Color.parseColor("#E5484D");

    private float dp;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView scoreView, scoreLabel, storageVal, storageSub, ramVal, ramSub, batVal, batSub;
    private TextView appsSummary;
    private LinearLayout tipsBox, appList, permCard, chipsRow;
    private Button uninstallBtn;
    private final TextView[] chips = new TextView[3];

    private List<AppItem> apps = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean usageGranted = false;
    private boolean appsLoaded = false;
    private int filter = -1;

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean uninstalling = false;
    private int removedCount = 0;

    // Mesures pour la note
    private int storageUsedPct = 0;
    private int ramFreePct = 100;
    private int batHealth = BatteryManager.BATTERY_HEALTH_GOOD;
    private float batTemp = 0;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        setupColors();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!uninstalling) refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL) {
            if (resultCode == RESULT_OK) removedCount++;
            launchNextUninstall();
        }
    }

    // ------------------------------------------------------------------ apparence

    private void setupColors() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            cBg = Color.parseColor("#121417");
            cCard = Color.parseColor("#1E2126");
            cText = Color.parseColor("#F2F3F5");
            cSub = Color.parseColor("#A0A6AE");
            cAccent = Color.parseColor("#4CC38A");
            cLine = Color.parseColor("#2C3036");
        } else {
            cBg = Color.parseColor("#F4F6F8");
            cCard = Color.WHITE;
            cText = Color.parseColor("#15181C");
            cSub = Color.parseColor("#5F6670");
            cAccent = Color.parseColor("#1F9D63");
            cLine = Color.parseColor("#E3E6EA");
        }
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cBg);
        if (!night) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(cCard, 18));
        c.setPadding(px(16), px(16), px(16), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : cAccent);
        GradientDrawable g = rounded(primary ? cAccent : Color.TRANSPARENT, 14);
        if (!primary) g.setStroke(px(1.5f), cAccent);
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(px(16), px(12), px(16), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, cText, true);
        t.setPadding(px(4), px(16), 0, px(8));
        return t;
    }

    // ------------------------------------------------------------------ construction de l'écran

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(20), px(16), px(32));
        sv.addView(root);

        root.addView(text("Tél en forme", 28, cText, true));
        TextView device = text(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE,
                14, cSub, false);
        device.setPadding(0, px(2), 0, px(16));
        root.addView(device);

        // --- Carte note
        LinearLayout scoreCard = card();
        LinearLayout scoreRow = new LinearLayout(this);
        scoreRow.setGravity(Gravity.CENTER_VERTICAL);
        scoreView = text("–", 52, cAccent, true);
        scoreRow.addView(scoreView);
        LinearLayout scoreTexts = new LinearLayout(this);
        scoreTexts.setOrientation(LinearLayout.VERTICAL);
        scoreTexts.setPadding(px(14), 0, 0, 0);
        scoreTexts.addView(text("Note de santé sur 100", 13, cSub, false));
        scoreLabel = text("Analyse en cours…", 18, cText, true);
        scoreTexts.addView(scoreLabel);
        scoreRow.addView(scoreTexts);
        scoreCard.addView(scoreRow);
        tipsBox = new LinearLayout(this);
        tipsBox.setOrientation(LinearLayout.VERTICAL);
        tipsBox.setPadding(0, px(8), 0, 0);
        scoreCard.addView(tipsBox);
        root.addView(scoreCard);

        // --- Trois petites cartes
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.bottomMargin = px(12);
        stats.setLayoutParams(statsLp);

        TextView[] s1 = statCard(stats, "Stockage", true);
        storageVal = s1[0];
        storageSub = s1[1];
        TextView[] s2 = statCard(stats, "Mémoire", true);
        ramVal = s2[0];
        ramSub = s2[1];
        TextView[] s3 = statCard(stats, "Batterie", false);
        batVal = s3[0];
        batSub = s3[1];
        root.addView(stats);

        // --- Carte autorisation
        permCard = card();
        permCard.addView(text("Une autorisation à donner", 16, cText, true));
        TextView pt = text("Pour savoir quelles applis tu n'utilises plus et combien elles pèsent, "
                + "active « Tél en forme » dans la liste qui va s'ouvrir, puis reviens ici.", 14, cSub, false);
        pt.setPadding(0, px(6), 0, 0);
        permCard.addView(pt);
        Button permBtn = button("Autoriser l'accès", true);
        permBtn.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permCard.addView(permBtn);
        permCard.setVisibility(View.GONE);
        root.addView(permCard);

        // --- Applications
        root.addView(sectionTitle("Applications"));
        LinearLayout appsCard = card();
        appsSummary = text("Analyse des applis…", 14, cSub, false);
        appsCard.addView(appsSummary);

        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, px(12), 0, px(4));
        String[] names = {"Inutilisées", "Les plus lourdes", "Toutes"};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView c = text(names[i], 14, cText, true);
            c.setPadding(px(14), px(8), px(14), px(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(8);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> {
                filter = f;
                styleChips();
                renderApps();
            });
            chips[i] = c;
            chipsRow.addView(c);
        }
        appsCard.addView(chipsRow);

        TextView hint = text("Coche les applis à supprimer. « Infos » ouvre la fiche de l'appli "
                + "(pour vider son cache ou la désactiver).", 12, cSub, false);
        hint.setPadding(0, px(6), 0, 0);
        appsCard.addView(hint);

        uninstallBtn = button("Désinstaller la sélection", true);
        uninstallBtn.setOnClickListener(v -> confirmUninstall());
        appsCard.addView(uninstallBtn);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, px(8), 0, 0);
        appsCard.addView(appList);
        root.addView(appsCard);

        // --- Entretien rapide
        root.addView(sectionTitle("Entretien rapide"));
        LinearLayout maint = card();
        addAction(maint, "Stockage", "Voir ce qui prend de la place (photos, fichiers, cache)",
                new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        addAction(maint, "Batterie", "Repérer les applis qui vident la batterie",
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
        Intent store = getPackageManager().getLaunchIntentForPackage("com.android.vending");
        addAction(maint, "Mettre à jour les applis", "Ouvre le Play Store → profil → Gérer les applis",
                store != null ? store : new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")));
        addAction(maint, "Mise à jour d'Android", "Vérifier les mises à jour de sécurité",
                new Intent("android.settings.SYSTEM_UPDATE_SETTINGS"));
        addAction(maint, "Applis préinstallées", "Désactiver celles qu'on ne peut pas supprimer",
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        root.addView(maint);

        // --- Bons réflexes
        root.addView(sectionTitle("Les bons réflexes"));
        LinearLayout tips = card();
        String[] advice = {
                "Redémarre ton téléphone une fois par semaine : ça libère la mémoire proprement.",
                "Garde au moins 15 % de stockage libre, sinon tout ralentit.",
                "Sauvegarde tes photos (Google Photos, ordinateur) puis supprime-les du téléphone.",
                "Évite les applis « booster » ou « nettoyeur » : elles tournent en fond et ralentissent plus qu'elles n'aident.",
                "Installe les mises à jour d'Android et des applis : elles corrigent des failles de sécurité.",
                "Ne force pas la fermeture des applis en permanence : Android gère bien la mémoire tout seul."
        };
        for (String a : advice) {
            TextView t = text("•  " + a, 14, cText, false);
            t.setPadding(0, px(4), 0, px(4));
            tips.addView(t);
        }
        root.addView(tips);

        setContentView(sv);
    }

    private TextView[] statCard(LinearLayout parent, String title, boolean withGap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(cCard, 16));
        c.setPadding(px(12), px(12), px(12), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (withGap) lp.rightMargin = px(8);
        c.setLayoutParams(lp);
        c.addView(text(title, 12, cSub, false));
        TextView val = text("–", 20, cText, true);
        c.addView(val);
        TextView sub = text("", 11, cSub, false);
        c.addView(sub);
        parent.addView(c);
        return new TextView[]{val, sub};
    }

    private void addAction(LinearLayout parent, String title, String desc, Intent intent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(10), 0, px(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(text(title, 16, cText, true));
        texts.addView(text(desc, 13, cSub, false));
        row.addView(texts);
        row.addView(text("›", 26, cAccent, true));
        row.setOnClickListener(v -> openSettings(intent));
        if (parent.getChildCount() > 0) parent.addView(divider());
        parent.addView(row);
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(cLine);
        d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        return d;
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Impossible d'ouvrir ce réglage sur ce téléphone.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void styleChips() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == filter;
            GradientDrawable g = rounded(on ? cAccent : Color.TRANSPARENT, 50);
            if (!on) g.setStroke(px(1), cLine);
            chips[i].setBackground(g);
            chips[i].setTextColor(on ? Color.WHITE : cText);
        }
        boolean showUnused = usageGranted;
        chips[FILTER_UNUSED].setVisibility(showUnused ? View.VISIBLE : View.GONE);
        chips[FILTER_HEAVY].setVisibility(showUnused ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ analyse

    private void refresh() {
        usageGranted = hasUsageAccess();
        permCard.setVisibility(usageGranted ? View.GONE : View.VISIBLE);
        if (filter == -1 || (!usageGranted && filter != FILTER_ALL)) {
            filter = usageGranted ? FILTER_UNUSED : FILTER_ALL;
        }
        styleChips();
        updateDeviceStats();
        updateScore();

        if (!appsLoaded) appsSummary.setText("Analyse des applis…");
        final boolean granted = usageGranted;
        exec.submit(() -> {
            List<AppItem> result = loadApps(granted);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps = result;
                appsLoaded = true;
                Set<String> present = new HashSet<>();
                for (AppItem a : apps) present.add(a.pkg);
                selected.retainAll(present);
                renderApps();
                updateScore();
            });
        });
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = getSystemService(AppOpsManager.class);
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private List<AppItem> loadApps(boolean granted) {
        PackageManager pm = getPackageManager();
        List<AppItem> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        Map<String, UsageStats> usage = new HashMap<>();
        StorageStatsManager ssm = null;
        if (granted) {
            try {
                UsageStatsManager usm = getSystemService(UsageStatsManager.class);
                Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 400 * DAY, now);
                if (m != null) usage = m;
            } catch (Exception ignored) {
            }
            try {
                ssm = getSystemService(StorageStatsManager.class);
            } catch (Exception ignored) {
            }
        }

        List<ApplicationInfo> list;
        try {
            list = pm.getInstalledApplications(0);
        } catch (Exception e) {
            list = Collections.emptyList();
        }

        for (ApplicationInfo ai : list) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue; // applis système : non supprimables
            if (ai.packageName.equals(getPackageName())) continue;

            AppItem it = new AppItem();
            it.pkg = ai.packageName;
            try {
                it.label = ai.loadLabel(pm).toString();
            } catch (Exception e) {
                it.label = ai.packageName;
            }
            try {
                it.icon = ai.loadIcon(pm);
            } catch (Exception ignored) {
            }
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                it.installed = pi.firstInstallTime;
            } catch (Exception ignored) {
            }
            UsageStats us = usage.get(ai.packageName);
            if (us != null && us.getLastTimeUsed() > 1262304000000L) { // après 2010 = valeur fiable
                it.lastUsed = us.getLastTimeUsed();
            }
            if (ssm != null) {
                try {
                    StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName,
                            Process.myUserHandle());
                    it.size = st.getAppBytes() + st.getDataBytes();
                    it.cache = st.getCacheBytes();
                } catch (Exception ignored) {
                }
            }
            out.add(it);
        }
        return out;
    }

    private boolean isUnused(AppItem a, long now) {
        if (!usageGranted) return false;
        if (a.installed > 0 && now - a.installed < UNUSED_DAYS * DAY) return false; // installée récemment
        return a.lastUsed <= 0 || now - a.lastUsed > UNUSED_DAYS * DAY;
    }

    private void updateDeviceStats() {
        // Stockage
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            storageUsedPct = total > 0 ? (int) Math.round(100.0 * (total - free) / total) : 0;
            storageVal.setText(storageUsedPct + " %");
            storageVal.setTextColor(storageUsedPct >= 90 ? cDanger : storageUsedPct >= 80 ? cWarn : cText);
            storageSub.setText(Formatter.formatShortFileSize(this, free) + " libres");
        } catch (Exception e) {
            storageVal.setText("?");
        }

        // Mémoire vive
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramFreePct = mi.totalMem > 0 ? (int) Math.round(100.0 * mi.availMem / mi.totalMem) : 100;
            ramVal.setText(ramFreePct + " %");
            ramVal.setTextColor(ramFreePct < 15 ? cWarn : cText);
            ramSub.setText("libre sur " + Formatter.formatShortFileSize(this, mi.totalMem));
        } catch (Exception e) {
            ramVal.setText("?");
        }

        // Batterie
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                batHealth = b.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
                batTemp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                int pct = scale > 0 ? Math.round(100f * level / scale) : level;
                batVal.setText(pct + " %");
                boolean badHealth = isBadBattery();
                batVal.setTextColor(badHealth ? cDanger : cText);
                batSub.setText(healthText(batHealth) + " · " + Math.round(batTemp) + " °C");
            }
        } catch (Exception e) {
            batVal.setText("?");
        }
    }

    private boolean isBadBattery() {
        return batHealth == BatteryManager.BATTERY_HEALTH_DEAD
                || batHealth == BatteryManager.BATTERY_HEALTH_OVERHEAT
                || batHealth == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE
                || batHealth == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE
                || batHealth == BatteryManager.BATTERY_HEALTH_COLD;
    }

    private String healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "bon état";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "surchauffe";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "usée";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "surtension";
            case BatteryManager.BATTERY_HEALTH_COLD: return "trop froide";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "défaut";
            default: return "état inconnu";
        }
    }

    private void updateScore() {
        long now = System.currentTimeMillis();
        int score = 100;
        List<String> tips = new ArrayList<>();

        if (storageUsedPct >= 90) {
            score -= 25;
            tips.add("Stockage presque plein : supprime des applis ou sauvegarde tes photos et vidéos.");
        } else if (storageUsedPct >= 80) {
            score -= 12;
            tips.add("Stockage bien rempli : fais un peu de place pour garder un téléphone fluide.");
        }

        if (ramFreePct < 15) {
            score -= 8;
            tips.add("Mémoire vive très occupée : un redémarrage fera du bien.");
        }

        if (isBadBattery()) {
            score -= 15;
            tips.add("La batterie signale un problème (" + healthText(batHealth) + ") : fais-la vérifier.");
        } else if (batTemp >= 40) {
            score -= 5;
            tips.add("Le téléphone est chaud (" + Math.round(batTemp) + " °C) : laisse-le refroidir, évite de le charger au soleil.");
        }

        if (appsLoaded && usageGranted) {
            int unused = 0;
            long unusedSize = 0;
            for (AppItem a : apps) {
                if (isUnused(a, now)) {
                    unused++;
                    if (a.size > 0) unusedSize += a.size;
                }
            }
            score -= Math.min(20, unused * 2);
            if (unused > 0) {
                tips.add(unused + " appli" + (unused > 1 ? "s" : "") + " non utilisée"
                        + (unused > 1 ? "s" : "") + " depuis plus de " + UNUSED_DAYS + " jours"
                        + (unusedSize > 0 ? " (" + Formatter.formatShortFileSize(this, unusedSize) + " à récupérer)" : "")
                        + ".");
            }
        } else if (!usageGranted) {
            tips.add("Donne l'autorisation ci-dessous pour une analyse complète des applis.");
        }

        score = Math.max(0, score);
        scoreView.setText(String.valueOf(score));
        int color = score >= 80 ? cAccent : score >= 60 ? cWarn : cDanger;
        scoreView.setTextColor(color);
        scoreLabel.setText(score >= 90 ? "Excellente forme" : score >= 80 ? "En bonne forme"
                : score >= 60 ? "Un peu d'entretien conseillé" : "Besoin d'un bon ménage");

        tipsBox.removeAllViews();
        if (tips.isEmpty()) tips.add("Rien à signaler. Continue comme ça !");
        for (String t : tips) {
            TextView tv = text("•  " + t, 14, cText, false);
            tv.setPadding(0, px(4), 0, 0);
            tipsBox.addView(tv);
        }
    }

    // ------------------------------------------------------------------ liste des applis

    private void renderApps() {
        appList.removeAllViews();
        final long now = System.currentTimeMillis();

        List<AppItem> shown = new ArrayList<>();
        int unusedCount = 0;
        long unusedSize = 0;
        for (AppItem a : apps) {
            boolean un = isUnused(a, now);
            if (un) {
                unusedCount++;
                if (a.size > 0) unusedSize += a.size;
            }
            if (filter == FILTER_UNUSED && !un) continue;
            shown.add(a);
        }

        if (filter == FILTER_HEAVY) {
            shown.sort((x, y) -> Long.compare(y.size, x.size));
            if (shown.size() > 25) shown = new ArrayList<>(shown.subList(0, 25));
        } else if (filter == FILTER_UNUSED) {
            shown.sort((x, y) -> Long.compare(y.size, x.size));
        } else {
            shown.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label));
        }

        String summary = apps.size() + " applis installées par toi";
        if (usageGranted) {
            summary += " · " + unusedCount + " inutilisée" + (unusedCount > 1 ? "s" : "");
            if (unusedSize > 0) summary += " (" + Formatter.formatShortFileSize(this, unusedSize) + ")";
        }
        appsSummary.setText(summary);

        if (shown.isEmpty()) {
            TextView empty = text(filter == FILTER_UNUSED
                    ? "Aucune appli oubliée. Bravo, ton téléphone est bien rangé !"
                    : "Aucune appli à afficher.", 14, cSub, false);
            empty.setPadding(0, px(12), 0, px(4));
            appList.addView(empty);
        }

        for (AppItem a : shown) {
            appList.addView(appRow(a, isUnused(a, now), now));
        }
        updateUninstallButton();
    }

    private View appRow(AppItem a, boolean unused, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(8), 0, px(8));

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(a.pkg));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(cAccent));
        cb.setOnCheckedChangeListener((b, checked) -> {
            if (checked) selected.add(a.pkg);
            else selected.remove(a.pkg);
            updateUninstallButton();
        });
        row.addView(cb);

        ImageView icon = new ImageView(this);
        if (a.icon != null) icon.setImageDrawable(a.icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(40), px(40));
        ilp.rightMargin = px(12);
        ilp.leftMargin = px(4);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = text(a.label, 15, cText, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        StringBuilder d = new StringBuilder();
        if (a.size > 0) d.append(Formatter.formatShortFileSize(this, a.size));
        if (usageGranted) {
            if (d.length() > 0) d.append(" · ");
            d.append(lastUsedText(a, now));
        }
        if (d.length() > 0) {
            TextView det = text(d.toString(), 12, unused ? cWarn : cSub, unused);
            texts.addView(det);
        }
        row.addView(texts);

        TextView info = text("Infos", 13, cAccent, true);
        info.setPadding(px(10), px(8), px(4), px(8));
        info.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg))));
        row.addView(info);

        row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (appList.getChildCount() > 0) wrap.addView(divider());
        wrap.addView(row);
        return wrap;
    }

    private String lastUsedText(AppItem a, long now) {
        if (a.lastUsed <= 0) return "pas d'utilisation enregistrée";
        long days = (now - a.lastUsed) / DAY;
        if (days <= 0) return "utilisée aujourd'hui";
        if (days == 1) return "utilisée hier";
        if (days < 60) return "utilisée il y a " + days + " j";
        return "utilisée il y a " + (days / 30) + " mois";
    }

    private void updateUninstallButton() {
        int n = selected.size();
        long size = 0;
        for (AppItem a : apps) if (selected.contains(a.pkg) && a.size > 0) size += a.size;
        String label = n == 0 ? "Coche des applis à désinstaller"
                : "Désinstaller " + n + " appli" + (n > 1 ? "s" : "")
                + (size > 0 ? " (" + Formatter.formatShortFileSize(this, size) + ")" : "");
        uninstallBtn.setText(label);
        uninstallBtn.setEnabled(n > 0);
        uninstallBtn.setAlpha(n > 0 ? 1f : 0.45f);
    }

    // ------------------------------------------------------------------ désinstallation

    private void confirmUninstall() {
        if (selected.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        int i = 0;
        for (AppItem a : apps) {
            if (!selected.contains(a.pkg)) continue;
            if (i < 8) names.append("• ").append(a.label).append('\n');
            i++;
        }
        if (i > 8) names.append("… et ").append(i - 8).append(" autre(s)\n");
        names.append("\nAndroid va te demander de confirmer pour chaque appli. "
                + "Leurs données seront effacées : pense à sauvegarder ce qui compte.");

        new AlertDialog.Builder(this)
                .setTitle("Désinstaller " + i + " appli" + (i > 1 ? "s" : "") + " ?")
                .setMessage(names.toString())
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer", (d, w) -> startUninstall())
                .show();
    }

    private void startUninstall() {
        queue.clear();
        queue.addAll(selected);
        removedCount = 0;
        uninstalling = true;
        launchNextUninstall();
    }

    @SuppressWarnings("deprecation")
    private void launchNextUninstall() {
        String pkg = queue.poll();
        if (pkg == null) {
            uninstalling = false;
            selected.clear();
            if (removedCount > 0) {
                Toast.makeText(this, removedCount + " appli" + (removedCount > 1 ? "s supprimées" : " supprimée")
                        + ". Bien joué !", Toast.LENGTH_LONG).show();
            }
            refresh();
            return;
        }
        Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + pkg));
        i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(i, REQ_UNINSTALL);
        } catch (Exception e) {
            launchNextUninstall();
        }
    }
}
