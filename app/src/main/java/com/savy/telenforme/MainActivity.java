package com.savy.telenforme;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tél en forme : un petit coach d'entretien pour Android.
 * - Note de santé du téléphone (stockage, mémoire, batterie, applis)
 * - Applis inutiles, gourmandes, préinstallées, lourdes, cache, hors Play Store
 * - Historique de chauffe sur 7 jours (relevé automatique toutes les 30 min)
 * - Rappel d'entretien hebdomadaire
 * - Désinstallation propre via la fenêtre officielle d'Android
 */
public class MainActivity extends Activity {

    private static final long MIN = 60L * 1000;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final int UNUSED_DAYS = 30;
    private static final long RARE_USE_MS = 5 * MIN;
    private static final int IMPACT_THRESHOLD = 15;
    private static final float HOT_TEMP = 37f;
    private static final long RECENT_WINDOW = 2 * HOUR;
    private static final long CACHE_MIN = 20L * 1024 * 1024;
    private static final int REQ_UNINSTALL = 42;
    private static final int REQ_NOTIF = 43;
    private static final int PAGE = 15;

    private static final int FILTER_UNUSED = 0;
    private static final int FILTER_IMPACT = 1;
    private static final int FILTER_PREINST = 2;
    private static final int FILTER_HEAVY = 3;
    private static final int FILTER_CACHE = 4;
    private static final int FILTER_SIDELOAD = 5;
    private static final int FILTER_ALL = 6;

    private static final Set<String> TRUSTED_STORES = new HashSet<>();

    static {
        TRUSTED_STORES.add("com.android.vending");
        TRUSTED_STORES.add("com.xiaomi.mipicks");
        TRUSTED_STORES.add("com.xiaomi.market");
        TRUSTED_STORES.add("com.xiaomi.discover");
        TRUSTED_STORES.add("com.amazon.venezia");
        TRUSTED_STORES.add("com.sec.android.app.samsungapps");
        TRUSTED_STORES.add("com.huawei.appmarket");
        TRUSTED_STORES.add("com.heytap.market");
        TRUSTED_STORES.add("com.oppo.market");
        TRUSTED_STORES.add("com.google.android.feedback");
    }

    static class AppItem {
        String pkg;
        String label;
        Drawable icon;
        int uid;
        boolean system;      // préinstallée (non supprimable)
        String known;        // description si c'est une appli préinstallée connue
        long size = -1;
        long cache = -1;
        long lastUsed = 0;
        long installed = 0;
        long fg7 = 0, fgs7 = 0, fg30 = 0, bgData7 = 0;
        long recentFg = 0, recentFgs = 0;
        boolean boot, bgLoc, noOptim;
        int impact = 0;
        final List<String> heatReasons = new ArrayList<>();
        String unusedReason;
        String sideloadFrom;  // non nul si installée hors d'une boutique officielle
    }

    // Couleurs
    private int cBg, cCard, cText, cSub, cAccent, cLine, cWarn, cField;
    private final int cDanger = Color.parseColor("#E5484D");

    private float dp;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView scoreView, scoreLabel, storageVal, storageSub, ramVal, ramSub, batVal, batSub;
    private TextView appsSummary, filterHint, hotTitle, histSummary;
    private LinearLayout tipsBox, appList, permCard, hotCard, hotList, histApps;
    private TempChart histChart;
    private Button uninstallBtn;
    private Switch remindSwitch;
    private final TextView[] chips = new TextView[7];

    private List<AppItem> apps = new ArrayList<>();
    private List<HeatJob.Reading> history = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> kept = new HashSet<>();
    private boolean usageGranted = false;
    private boolean appsLoaded = false;
    private int filter = -1;
    private int shownLimit = PAGE;
    private String query = "";

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean uninstalling = false;
    private int removedCount = 0;

    private int storageUsedPct = 0;
    private int ramFreePct = 100;
    private int batHealth = BatteryManager.BATTERY_HEALTH_GOOD;
    private float batTemp = 0;
    private boolean charging = false;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = HeatJob.prefs(this);
        kept.addAll(prefs.getStringSet("kept", Collections.emptySet()));
        setupColors();
        buildUi();
        HeatJob.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.edit().putLong("lastOpen", System.currentTimeMillis()).apply();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setRemind(ok);
            if (!ok) Toast.makeText(this, "Sans les notifications, le rappel ne peut pas s'afficher.",
                    Toast.LENGTH_LONG).show();
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
            cWarn = Color.parseColor("#F2C25B");
            cField = Color.parseColor("#272B31");
        } else {
            cBg = Color.parseColor("#F4F6F8");
            cCard = Color.WHITE;
            cText = Color.parseColor("#15181C");
            cSub = Color.parseColor("#5F6670");
            cAccent = Color.parseColor("#1F9D63");
            cLine = Color.parseColor("#E3E6EA");
            cWarn = Color.parseColor("#A86B00");
            cField = Color.parseColor("#EEF1F4");
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
        root.addView(text("Version " + appVersion(), 15, cAccent, true));
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

        // --- Carte « ça chauffe »
        hotCard = card();
        hotTitle = text("Ton téléphone est chaud", 16, cDanger, true);
        hotCard.addView(hotTitle);
        TextView hotSub = text("Applis qui ont tourné ces 2 dernières heures :", 14, cSub, false);
        hotSub.setPadding(0, px(4), 0, px(4));
        hotCard.addView(hotSub);
        hotList = new LinearLayout(this);
        hotList.setOrientation(LinearLayout.VERTICAL);
        hotCard.addView(hotList);
        hotCard.setVisibility(View.GONE);
        root.addView(hotCard);

        // --- Carte autorisation
        permCard = card();
        permCard.addView(text("Une autorisation à donner", 16, cText, true));
        TextView pt = text("Pour repérer les applis inutiles et celles qui chauffent ou ralentissent ton téléphone, "
                + "active « Tél en forme » dans la liste qui va s'ouvrir, puis reviens ici.", 14, cSub, false);
        pt.setPadding(0, px(6), 0, 0);
        permCard.addView(pt);
        Button permBtn = button("Autoriser l'accès", true);
        permBtn.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permCard.addView(permBtn);
        TextView restricted = text("Interrupteur grisé ou message « Paramètre restreint » ? Touche le bouton "
                + "ci-dessous, puis ⋮ en haut à droite → « Infos sur l'application » → ⋮ → « Autoriser les "
                + "paramètres restreints ». Reviens ensuite sur « Autoriser l'accès ».", 13, cSub, false);
        restricted.setPadding(0, px(12), 0, 0);
        permCard.addView(restricted);
        Button ficheBtn = button("Ouvrir la fiche de Tél en forme", false);
        ficheBtn.setOnClickListener(v -> openAppInfo(getPackageName()));
        permCard.addView(ficheBtn);
        permCard.setVisibility(View.GONE);
        root.addView(permCard);

        // --- Historique de chauffe
        root.addView(sectionTitle("Historique de chauffe (7 jours)"));
        LinearLayout histCard = card();
        histSummary = text("Les relevés commencent : reviens dans quelques heures.", 14, cText, false);
        histCard.addView(histSummary);
        histChart = new TempChart(this, dp, cAccent, cLine, cDanger, cSub);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(130));
        clp.topMargin = px(12);
        histChart.setLayoutParams(clp);
        histChart.setVisibility(View.GONE);
        histCard.addView(histChart);
        histApps = new LinearLayout(this);
        histApps.setOrientation(LinearLayout.VERTICAL);
        histApps.setPadding(0, px(8), 0, 0);
        histCard.addView(histApps);
        if (Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("xiaomi")
                || Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("redmi")
                || Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("poco")) {
            TextView xi = text("Sur Xiaomi, pour que les relevés continuent appli fermée : fiche de l'appli → "
                    + "« Démarrage automatique » activé et « Économiseur de batterie » → « Pas de restrictions ».",
                    12, cSub, false);
            xi.setPadding(0, px(10), 0, 0);
            histCard.addView(xi);
            Button xiBtn = button("Ouvrir la fiche de Tél en forme", false);
            xiBtn.setOnClickListener(v -> openAppInfo(getPackageName()));
            histCard.addView(xiBtn);
        }
        root.addView(histCard);

        // --- Entretien rapide
        root.addView(sectionTitle("Entretien rapide"));
        LinearLayout maint = card();
        addAction(maint, "Stockage", "Voir ce qui prend de la place (photos, fichiers, cache)",
                new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        addAction(maint, "Batterie", "Voir quelles applis consomment le plus selon Android",
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
        Intent store = getPackageManager().getLaunchIntentForPackage("com.android.vending");
        addAction(maint, "Mettre à jour les applis", "Ouvre le Play Store → profil → Gérer les applis",
                store != null ? store : new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")));
        addAction(maint, "Mise à jour d'Android", "Vérifier les mises à jour de sécurité",
                new Intent("android.settings.SYSTEM_UPDATE_SETTINGS"));

        maint.addView(divider());
        LinearLayout remindRow = new LinearLayout(this);
        remindRow.setGravity(Gravity.CENTER_VERTICAL);
        remindRow.setPadding(0, px(10), 0, px(4));
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        rt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rt.addView(text("Rappel d'entretien", 16, cText, true));
        rt.addView(text("Une notification si tu n'as pas ouvert l'appli depuis une semaine", 13, cSub, false));
        remindRow.addView(rt);
        remindSwitch = new Switch(this);
        remindSwitch.setChecked(prefs.getBoolean("remind", false));
        remindSwitch.setThumbTintList(ColorStateList.valueOf(cAccent));
        remindSwitch.setOnCheckedChangeListener((b, on) -> {
            if (!on) {
                setRemind(false);
                return;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            } else {
                setRemind(true);
            }
        });
        remindRow.addView(remindSwitch);
        maint.addView(remindRow);
        root.addView(maint);

        // --- Applications
        root.addView(sectionTitle("Applications"));
        LinearLayout appsCard = card();
        appsSummary = text("Analyse des applis…", 14, cSub, false);
        appsCard.addView(appsSummary);

        EditText search = new EditText(this);
        search.setHint("Rechercher une appli…");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(cText);
        search.setHintTextColor(cSub);
        search.setBackground(rounded(cField, 12));
        search.setPadding(px(14), px(10), px(14), px(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(12);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                shownLimit = PAGE;
                if (appsLoaded) renderApps();
            }
        });
        appsCard.addView(search);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, px(12), 0, px(4));
        String[] names = {"Inutiles", "Gourmandes", "Préinstallées", "Lourdes", "Cache", "Hors Play Store", "Toutes"};
        for (int i = 0; i < chips.length; i++) {
            final int f = i;
            TextView c = text(names[i], 14, cText, true);
            c.setPadding(px(14), px(8), px(14), px(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(8);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> {
                filter = f;
                shownLimit = PAGE;
                styleChips();
                renderApps();
            });
            chips[i] = c;
            chipsRow.addView(c);
        }
        chipsScroll.addView(chipsRow);
        appsCard.addView(chipsScroll);

        filterHint = text("", 13, cSub, false);
        filterHint.setPadding(0, px(6), 0, 0);
        appsCard.addView(filterHint);

        uninstallBtn = button("Désinstaller la sélection", true);
        uninstallBtn.setOnClickListener(v -> confirmUninstall());
        appsCard.addView(uninstallBtn);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, px(8), 0, 0);
        appsCard.addView(appList);
        root.addView(appsCard);

        // --- Bons réflexes
        root.addView(sectionTitle("Les bons réflexes"));
        LinearLayout tips = card();
        String[] advice = {
                "Redémarre ton téléphone une fois par semaine : ça libère la mémoire proprement.",
                "Garde au moins 15 % de stockage libre, sinon tout ralentit.",
                "Pour une appli gourmande que tu veux garder : Infos → fiche Android → Batterie → « Restreinte ».",
                "Évite de jouer ou de filmer pendant la charge : c'est ce qui chauffe le plus.",
                "Évite les applis « booster » ou « nettoyeur » : elles tournent en fond et ralentissent plus qu'elles n'aident.",
                "N'installe des applis hors Play Store que si tu connais leur origine.",
                "Installe les mises à jour d'Android et des applis : elles corrigent des bugs et des failles."
        };
        for (String a : advice) {
            TextView t = text("•  " + a, 14, cText, false);
            t.setPadding(0, px(4), 0, px(4));
            tips.addView(t);
        }
        root.addView(tips);

        setContentView(sv);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void setRemind(boolean on) {
        prefs.edit().putBoolean("remind", on).apply();
        if (remindSwitch.isChecked() != on) remindSwitch.setChecked(on);
        if (on) Toast.makeText(this, "Rappel activé.", Toast.LENGTH_SHORT).show();
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

    private void openAppInfo(String pkg) {
        openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private void styleChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == filter;
            GradientDrawable g = rounded(on ? cAccent : Color.TRANSPARENT, 50);
            if (!on) g.setStroke(px(1), cLine);
            chips[i].setBackground(g);
            chips[i].setTextColor(on ? Color.WHITE : cText);
            boolean needsUsage = i == FILTER_UNUSED || i == FILTER_IMPACT || i == FILTER_HEAVY || i == FILTER_CACHE;
            chips[i].setVisibility(usageGranted || !needsUsage ? View.VISIBLE : View.GONE);
        }
        String hint;
        switch (filter) {
            case FILTER_UNUSED:
                hint = "Applis jamais ou presque jamais ouvertes depuis " + UNUSED_DAYS
                        + " jours. Coche celles à supprimer.";
                break;
            case FILTER_IMPACT:
                hint = "Applis qui tournent en fond, consomment des données en cachette ou suivent ta position : "
                        + "elles font chauffer et ralentissent. Supprime-les, ou touche « Infos » pour les "
                        + "restreindre ou les garder.";
                break;
            case FILTER_PREINST:
                hint = "Applis installées par le fabricant. On ne peut pas les supprimer, mais souvent les "
                        + "désactiver : touche « Désactiver » puis le bouton « Désactiver » de la fiche. "
                        + "S'il est grisé, Android ne le permet pas pour cette appli.";
                break;
            case FILTER_HEAVY:
                hint = "Les applis qui prennent le plus de place.";
                break;
            case FILTER_CACHE:
                hint = "Le cache se recrée tout seul : le vider libère de la place sans rien perdre. "
                        + "Touche « Infos » → fiche Android → Stockage → « Vider le cache ».";
                break;
            case FILTER_SIDELOAD:
                hint = "Applis installées en dehors d'une boutique officielle. Garde seulement celles dont "
                        + "tu connais l'origine.";
                break;
            default:
                hint = "Toutes les applis que tu as installées. « Infos » ouvre la fiche de l'appli "
                        + "ou permet de la garder.";
        }
        filterHint.setText(hint);
    }

    // ------------------------------------------------------------------ analyse

    private void refresh() {
        usageGranted = hasUsageAccess();
        permCard.setVisibility(usageGranted ? View.GONE : View.VISIBLE);
        if (filter == -1 || (!usageGranted && (filter == FILTER_UNUSED || filter == FILTER_IMPACT
                || filter == FILTER_HEAVY || filter == FILTER_CACHE))) {
            filter = usageGranted ? FILTER_UNUSED : FILTER_ALL;
        }
        styleChips();
        updateDeviceStats();
        updateScore();

        if (!appsLoaded) appsSummary.setText("Analyse des applis…");
        final boolean granted = usageGranted;
        final Context ctx = getApplicationContext();
        exec.submit(() -> {
            HeatJob.record(ctx, true);
            List<HeatJob.Reading> hist = HeatJob.history(ctx);
            List<AppItem> result = loadApps(granted);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps = result;
                history = hist;
                appsLoaded = true;
                Set<String> present = new HashSet<>();
                for (AppItem a : apps) if (!a.system) present.add(a.pkg);
                selected.retainAll(present);
                renderApps();
                renderHot();
                renderHistory();
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
            if (mode == AppOpsManager.MODE_ALLOWED) return true;
            if (mode == AppOpsManager.MODE_DEFAULT
                    && checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        } catch (Exception ignored) {
        }
        // Dernier recours : certains fabricants répondent mal, on essaie de lire réellement les données
        try {
            UsageStatsManager usm = getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            List<UsageStats> l = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY, now);
            return l != null && !l.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static long get(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    private Map<String, UsageStats> aggregate(UsageStatsManager usm, long from, long to) {
        try {
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(from, to);
            if (m != null) return m;
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    /** Données consommées en arrière-plan (Wi-Fi + mobile), par uid. */
    @SuppressWarnings("deprecation")
    private Map<Integer, Long> backgroundData(long from, long to) {
        Map<Integer, Long> out = new HashMap<>();
        NetworkStatsManager nsm;
        try {
            nsm = getSystemService(NetworkStatsManager.class);
        } catch (Exception e) {
            return out;
        }
        if (nsm == null) return out;
        int[] types = {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE};
        for (int type : types) {
            NetworkStats ns = null;
            try {
                ns = nsm.querySummary(type, null, from, to);
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (ns.hasNextBucket()) {
                    ns.getNextBucket(b);
                    if (b.getState() == NetworkStats.Bucket.STATE_DEFAULT) { // STATE_DEFAULT = arrière-plan
                        long bytes = b.getRxBytes() + b.getTxBytes();
                        Long old = out.get(b.getUid());
                        out.put(b.getUid(), (old == null ? 0 : old) + bytes);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ns != null) ns.close();
            }
        }
        return out;
    }

    /** D'où vient l'appli : null si d'une boutique officielle, sinon le nom de la source. */
    @SuppressWarnings("deprecation")
    private String sideloadSource(PackageManager pm, String pkg) {
        String installer = null;
        String initiating = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.pm.InstallSourceInfo info = pm.getInstallSourceInfo(pkg);
                installer = info.getInstallingPackageName();
                initiating = info.getInitiatingPackageName();
            } else {
                installer = pm.getInstallerPackageName(pkg);
            }
        } catch (Exception ignored) {
        }
        if (installer != null && TRUSTED_STORES.contains(installer)) return null;
        if (initiating != null && TRUSTED_STORES.contains(initiating)) return null;
        String src = initiating != null && !initiating.contains("packageinstaller") ? initiating : installer;
        if (src == null || src.contains("packageinstaller")) {
            return src == null ? "source inconnue" : "un fichier .apk";
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(src, 0)).toString();
        } catch (Exception e) {
            return src;
        }
    }

    private List<AppItem> loadApps(boolean granted) {
        PackageManager pm = getPackageManager();
        List<AppItem> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        Map<String, UsageStats> usageAll = Collections.emptyMap();
        Map<String, UsageStats> usage7 = Collections.emptyMap();
        Map<String, UsageStats> usage30 = Collections.emptyMap();
        Map<Integer, Long> bgData = Collections.emptyMap();
        Map<String, Long> recentFg = new HashMap<>();
        Map<String, Long> recentFgs = new HashMap<>();
        StorageStatsManager ssm = null;
        PowerManager power = null;

        if (granted) {
            try {
                UsageStatsManager usm = getSystemService(UsageStatsManager.class);
                usageAll = aggregate(usm, now - 400 * DAY, now);
                usage7 = aggregate(usm, now - 7 * DAY, now);
                usage30 = aggregate(usm, now - 30 * DAY, now);
                HeatJob.activity(usm, now - RECENT_WINDOW, now, recentFg, recentFgs);
            } catch (Exception ignored) {
            }
            bgData = backgroundData(now - 7 * DAY, now);
            try {
                ssm = getSystemService(StorageStatsManager.class);
            } catch (Exception ignored) {
            }
        }
        try {
            power = getSystemService(PowerManager.class);
        } catch (Exception ignored) {
        }

        List<ApplicationInfo> list;
        try {
            list = pm.getInstalledApplications(0);
        } catch (Exception e) {
            list = Collections.emptyList();
        }

        for (ApplicationInfo ai : list) {
            if (ai.packageName.equals(getPackageName())) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String known = null;
            if (system) {
                if (!ai.enabled) continue; // déjà désactivée
                known = Bloat.KNOWN.get(ai.packageName);
                if (known == null) {
                    if (Bloat.isEssential(ai.packageName)) continue;
                    if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue; // service invisible
                }
            }

            AppItem it = new AppItem();
            it.pkg = ai.packageName;
            it.uid = ai.uid;
            it.system = system;
            it.known = known;
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
                PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
                it.installed = system ? 0 : pi.firstInstallTime;
                if (pi.requestedPermissions != null) {
                    for (int i = 0; i < pi.requestedPermissions.length; i++) {
                        String perm = pi.requestedPermissions[i];
                        boolean ok = pi.requestedPermissionsFlags != null
                                && i < pi.requestedPermissionsFlags.length
                                && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        if ("android.permission.RECEIVE_BOOT_COMPLETED".equals(perm)) it.boot = true;
                        if ("android.permission.ACCESS_BACKGROUND_LOCATION".equals(perm) && ok) it.bgLoc = true;
                    }
                }
            } catch (Exception ignored) {
            }

            if (power != null) {
                try {
                    it.noOptim = power.isIgnoringBatteryOptimizations(ai.packageName);
                } catch (Exception ignored) {
                }
            }

            UsageStats us = usageAll.get(ai.packageName);
            if (us != null && us.getLastTimeUsed() > 1262304000000L) it.lastUsed = us.getLastTimeUsed();
            UsageStats u7 = usage7.get(ai.packageName);
            if (u7 != null) {
                it.fg7 = u7.getTotalTimeInForeground();
                if (Build.VERSION.SDK_INT >= 29) it.fgs7 = u7.getTotalTimeForegroundServiceUsed();
                if (u7.getLastTimeUsed() > it.lastUsed) it.lastUsed = u7.getLastTimeUsed();
            }
            UsageStats u30 = usage30.get(ai.packageName);
            if (u30 != null) it.fg30 = u30.getTotalTimeInForeground();
            Long bd = bgData.get(ai.uid);
            if (bd != null) it.bgData7 = bd;
            it.recentFg = get(recentFg, ai.packageName);
            it.recentFgs = get(recentFgs, ai.packageName);

            if (ssm != null) {
                try {
                    StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName,
                            Process.myUserHandle());
                    it.size = st.getAppBytes() + st.getDataBytes();
                    it.cache = st.getCacheBytes();
                } catch (Exception ignored) {
                }
            }

            if (!system) it.sideloadFrom = sideloadSource(pm, ai.packageName);

            if (granted) {
                computeImpact(it);
                it.unusedReason = unusedReason(it, now);
            }
            out.add(it);
        }
        return out;
    }

    /** Note d'impact : ce qui fait chauffer, vide la batterie et ralentit le téléphone. */
    private void computeImpact(AppItem a) {
        int score = 0;
        a.heatReasons.clear();

        double fgsH = a.fgs7 / (double) HOUR;
        if (fgsH >= 1) {
            score += (int) Math.min(35, Math.round(fgsH * 3));
            a.heatReasons.add("Tourne en arrière-plan : " + duration(a.fgs7) + " cette semaine");
        }
        long mb = a.bgData7 / (1024 * 1024);
        if (mb >= 50) {
            score += (int) Math.min(25, mb / 40 + 5);
            a.heatReasons.add("Utilise internet en cachette : " + Formatter.formatShortFileSize(this, a.bgData7)
                    + " cette semaine");
        }
        if (a.bgLoc) {
            score += 12;
            a.heatReasons.add("Peut suivre ta position en permanence");
        }
        if (a.noOptim) {
            score += 10;
            a.heatReasons.add("Échappe à l'économie de batterie d'Android");
        }
        double fgH = a.fg7 / (double) HOUR;
        if (fgH >= 7) {
            score += (int) Math.min(12, Math.round((fgH - 7) / 2) + 4);
            a.heatReasons.add("Très utilisée : " + duration(a.fg7) + " à l'écran cette semaine");
        }
        if (score > 0 && a.boot) {
            score += 3;
            a.heatReasons.add("Se lance toute seule au démarrage");
        }
        a.impact = score;
    }

    private String unusedReason(AppItem a, long now) {
        if (a.installed > 0 && now - a.installed < UNUSED_DAYS * DAY) return null; // installée récemment
        if (a.lastUsed <= 0) return a.system ? "Jamais utilisée d'après Android" : "Jamais ouverte d'après Android";
        long days = (now - a.lastUsed) / DAY;
        if (days > UNUSED_DAYS) {
            return days >= 60 ? "Pas ouverte depuis " + (days / 30) + " mois"
                    : "Pas ouverte depuis " + days + " jours";
        }
        if (a.fg30 < RARE_USE_MS) {
            long m = Math.max(1, a.fg30 / MIN);
            return "Presque jamais utilisée : " + (a.fg30 < MIN ? "moins d'1 min" : m + " min") + " en 30 jours";
        }
        return null;
    }

    private boolean isKept(AppItem a) {
        return kept.contains(a.pkg);
    }

    private boolean isUnused(AppItem a) {
        return usageGranted && !a.system && !isKept(a) && a.unusedReason != null;
    }

    private boolean isGreedy(AppItem a) {
        return usageGranted && !isKept(a) && a.impact >= IMPACT_THRESHOLD && (!a.system || a.known != null);
    }

    private boolean isPreinstalledToShow(AppItem a) {
        return a.system && !isKept(a);
    }

    private boolean isSideloaded(AppItem a) {
        return !a.system && !isKept(a) && a.sideloadFrom != null;
    }

    private static String duration(long ms) {
        long m = ms / MIN;
        if (m < 60) return Math.max(1, m) + " min";
        long h = m / 60;
        long rest = m % 60;
        if (h >= 10 || rest == 0) return h + " h";
        return String.format(Locale.FRANCE, "%d h %02d", h, rest);
    }

    private void updateDeviceStats() {
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

        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                batHealth = b.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
                batTemp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                int pct = scale > 0 ? Math.round(100f * level / scale) : level;
                batVal.setText(pct + " %");
                batVal.setTextColor(isBadBattery() ? cDanger : cText);
                batSub.setText(healthText(batHealth) + " · " + Math.round(batTemp) + " °C");
                batSub.setTextColor(batTemp >= HOT_TEMP ? cDanger : cSub);
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

    private String labelOf(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a.label;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private AppItem itemOf(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** Carte « ton téléphone est chaud » : quelles applis ont tourné récemment. */
    private void renderHot() {
        hotList.removeAllViews();
        if (!usageGranted || batTemp < HOT_TEMP) {
            hotCard.setVisibility(View.GONE);
            return;
        }
        List<AppItem> recent = new ArrayList<>();
        for (AppItem a : apps) if (a.recentFg + a.recentFgs >= 2 * MIN) recent.add(a);
        recent.sort((x, y) -> Long.compare(y.recentFg + y.recentFgs, x.recentFg + x.recentFgs));

        hotTitle.setText("Ton téléphone est chaud (" + Math.round(batTemp) + " °C)"
                + (charging ? " et en charge" : ""));
        if (recent.isEmpty()) {
            TextView t = text(charging
                    ? "Aucune de tes applis n'a beaucoup tourné : c'est sans doute la charge. Laisse-le à l'air libre, sans coque."
                    : "Aucune de tes applis n'a beaucoup tourné : vérifie la luminosité, le soleil, ou une appli système dans Réglages → Batterie.",
                    14, cText, false);
            hotList.addView(t);
        } else {
            int n = 0;
            for (AppItem a : recent) {
                if (n++ >= 5) break;
                StringBuilder d = new StringBuilder();
                if (a.recentFg >= MIN) d.append(duration(a.recentFg)).append(" à l'écran");
                if (a.recentFgs >= MIN) {
                    if (d.length() > 0) d.append(" · ");
                    d.append(duration(a.recentFgs)).append(" en arrière-plan");
                }
                hotList.addView(miniRow(a.icon, a.label, d.toString(), a));
            }
            TextView adv = text("Si l'une d'elles ne te sert pas en ce moment : Infos → fiche Android → « Forcer l'arrêt ».",
                    13, cSub, false);
            adv.setPadding(0, px(6), 0, 0);
            hotList.addView(adv);
        }
        hotCard.setVisibility(View.VISIBLE);
    }

    private View miniRow(Drawable iconD, String title, String sub, AppItem a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(6), 0, px(6));
        ImageView icon = new ImageView(this);
        if (iconD != null) icon.setImageDrawable(iconD);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(32), px(32));
        ilp.rightMargin = px(10);
        icon.setLayoutParams(ilp);
        row.addView(icon);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tt = text(title, 15, cText, true);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(tt);
        texts.addView(text(sub, 12, cSub, false));
        row.addView(texts);
        if (a != null) {
            TextView info = text("Infos", 13, cAccent, true);
            info.setPadding(px(10), px(8), px(4), px(8));
            info.setOnClickListener(v -> showOptions(a));
            row.addView(info);
        }
        return row;
    }

    /** Historique de chauffe : courbe sur 7 jours et applis présentes quand ça chauffe. */
    private void renderHistory() {
        histApps.removeAllViews();
        if (history.size() < 3) {
            histChart.setVisibility(View.GONE);
            histSummary.setText("Les relevés commencent (" + history.size() + " pour l'instant). "
                    + "Tél en forme note la température toutes les 30 minutes : reviens dans quelques heures.");
            return;
        }
        long[] times = new long[history.size()];
        float[] temps = new float[history.size()];
        int max = 0, hot = 0, hotCharging = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < history.size(); i++) {
            HeatJob.Reading r = history.get(i);
            times[i] = r.time;
            temps[i] = r.tenths / 10f;
            max = Math.max(max, r.tenths);
            if (r.tenths >= HeatJob.HOT_TENTHS) {
                hot++;
                if (r.charging) hotCharging++;
                for (String p : r.apps) {
                    Integer c = counts.get(p);
                    counts.put(p, c == null ? 1 : c + 1);
                }
            }
        }
        histChart.setData(times, temps);
        histChart.setVisibility(View.VISIBLE);

        long spanH = Math.max(1, (times[times.length - 1] - times[0]) / HOUR);
        String span = spanH >= 48 ? (spanH / 24) + " jours" : spanH + " h";
        StringBuilder s = new StringBuilder();
        s.append(history.size()).append(" relevés sur ").append(span)
                .append(" · maximum ").append(String.format(Locale.FRANCE, "%.1f", max / 10f)).append(" °C\n");
        if (hot == 0) {
            s.append("Aucune surchauffe (38 °C ou plus) : ton téléphone reste à bonne température.");
        } else {
            s.append(hot).append(" moment").append(hot > 1 ? "s" : "").append(" chaud").append(hot > 1 ? "s" : "")
                    .append(" (38 °C ou plus)");
            if (hotCharging > 0) s.append(", dont ").append(hotCharging).append(" pendant la charge");
            s.append(".");
        }
        histSummary.setText(s.toString());

        if (hot > 0) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (list.isEmpty()) {
                TextView t = text(hotCharging == hot
                        ? "Aucune appli active à ces moments : c'est la charge qui chauffe."
                        : "Aucune de tes applis n'était très active à ces moments.", 13, cSub, false);
                histApps.addView(t);
            } else {
                histApps.addView(text("Applis actives quand ça chauffe :", 14, cText, true));
                int n = 0;
                for (Map.Entry<String, Integer> en : list) {
                    if (n++ >= 5) break;
                    AppItem a = itemOf(en.getKey());
                    histApps.addView(miniRow(a != null ? a.icon : null, labelOf(en.getKey()),
                            "présente " + en.getValue() + " fois sur " + hot, a));
                }
            }
        }
    }

    private void updateScore() {
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
            int unused = 0, greedy = 0, bloat = 0, side = 0;
            long unusedSize = 0, cacheTotal = 0;
            for (AppItem a : apps) {
                if (isUnused(a)) {
                    unused++;
                    if (a.size > 0) unusedSize += a.size;
                }
                if (isGreedy(a)) greedy++;
                if (a.system && a.known != null && !isKept(a)) bloat++;
                if (isSideloaded(a)) side++;
                if (!a.system && a.cache > 0) cacheTotal += a.cache;
            }
            score -= Math.min(20, unused * 2);
            score -= Math.min(15, greedy * 3);
            score -= Math.min(10, bloat);
            if (greedy > 0) {
                tips.add(greedy + " appli" + (greedy > 1 ? "s gourmandes" : " gourmande")
                        + " (tourne" + (greedy > 1 ? "nt" : "") + " en fond, chauffe" + (greedy > 1 ? "nt" : "")
                        + ") : onglet « Gourmandes ».");
            }
            if (unused > 0) {
                tips.add(unused + " appli" + (unused > 1 ? "s inutiles" : " inutile")
                        + (unusedSize > 0 ? " (" + Formatter.formatShortFileSize(this, unusedSize) + " à récupérer)" : "")
                        + " : onglet « Inutiles ».");
            }
            if (bloat > 0) {
                tips.add(bloat + " appli" + (bloat > 1 ? "s préinstallées" : " préinstallée")
                        + " à désactiver (publicités, statistiques) : onglet « Préinstallées ».");
            }
            if (cacheTotal >= 2L * 1024 * 1024 * 1024) {
                tips.add(Formatter.formatShortFileSize(this, cacheTotal) + " de cache : onglet « Cache ».");
            }
            if (side > 0) {
                tips.add(side + " appli" + (side > 1 ? "s installées" : " installée")
                        + " hors Play Store : vérifie que tu les connais.");
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

    private boolean matchesFilter(AppItem a) {
        switch (filter) {
            case FILTER_UNUSED: return isUnused(a);
            case FILTER_IMPACT: return isGreedy(a);
            case FILTER_PREINST: return isPreinstalledToShow(a);
            case FILTER_HEAVY: return !a.system && a.size > 0;
            case FILTER_CACHE: return !a.system && a.cache >= CACHE_MIN;
            case FILTER_SIDELOAD: return isSideloaded(a);
            default: return !a.system;
        }
    }

    private void renderApps() {
        appList.removeAllViews();
        final long now = System.currentTimeMillis();

        List<AppItem> shown = new ArrayList<>();
        int userCount = 0, sysCount = 0, unusedCount = 0, greedyCount = 0;
        for (AppItem a : apps) {
            if (a.system) sysCount++;
            else userCount++;
            if (isUnused(a)) unusedCount++;
            if (isGreedy(a)) greedyCount++;
            if (!matchesFilter(a)) continue;
            if (!query.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(query)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown.add(a);
        }

        switch (filter) {
            case FILTER_IMPACT:
                shown.sort((x, y) -> Integer.compare(y.impact, x.impact));
                break;
            case FILTER_PREINST:
                shown.sort((x, y) -> {
                    if ((x.known != null) != (y.known != null)) return x.known != null ? -1 : 1;
                    return Long.compare(x.lastUsed, y.lastUsed);
                });
                break;
            case FILTER_HEAVY:
            case FILTER_UNUSED:
                shown.sort((x, y) -> Long.compare(y.size, x.size));
                break;
            case FILTER_CACHE:
                shown.sort((x, y) -> Long.compare(y.cache, x.cache));
                break;
            default:
                shown.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(x.label, y.label));
        }

        String summary = userCount + " applis installées par toi · " + sysCount + " préinstallées";
        if (usageGranted) {
            summary += "\n" + unusedCount + " inutile" + (unusedCount > 1 ? "s" : "")
                    + " · " + greedyCount + " gourmande" + (greedyCount > 1 ? "s" : "");
        }
        if (!kept.isEmpty()) summary += " · " + kept.size() + " gardée" + (kept.size() > 1 ? "s" : "");
        appsSummary.setText(summary);

        boolean preinst = filter == FILTER_PREINST;
        uninstallBtn.setVisibility(preinst ? View.GONE : View.VISIBLE);

        if (shown.isEmpty()) {
            String msg;
            if (!query.isEmpty()) msg = "Aucune appli ne correspond à « " + query + " » dans cet onglet.";
            else if (filter == FILTER_UNUSED) msg = "Aucune appli oubliée. Bravo, ton téléphone est bien rangé !";
            else if (filter == FILTER_IMPACT) msg = "Aucune appli ne tourne trop en arrière-plan. Parfait !";
            else if (filter == FILTER_PREINST) msg = "Aucune appli préinstallée à désactiver.";
            else if (filter == FILTER_CACHE) msg = "Aucune appli n'accumule beaucoup de cache.";
            else if (filter == FILTER_SIDELOAD) msg = "Toutes tes applis viennent d'une boutique officielle.";
            else msg = "Aucune appli à afficher.";
            TextView empty = text(msg, 14, cSub, false);
            empty.setPadding(0, px(12), 0, px(4));
            appList.addView(empty);
        }

        int limit = Math.min(shown.size(), shownLimit);
        for (int k = 0; k < limit; k++) appList.addView(appRow(shown.get(k), now));
        if (shown.size() > limit) {
            int rest = shown.size() - limit;
            Button more = button("Afficher " + Math.min(rest, PAGE) + " de plus (" + rest + " restantes)", false);
            more.setOnClickListener(v -> {
                shownLimit += PAGE;
                renderApps();
            });
            appList.addView(more);
        }
        updateUninstallButton();
    }

    private View appRow(AppItem a, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(8), 0, px(8));

        CheckBox cb = null;
        if (!a.system) {
            final CheckBox box = new CheckBox(this);
            box.setChecked(selected.contains(a.pkg));
            box.setButtonTintList(ColorStateList.valueOf(cAccent));
            box.setOnCheckedChangeListener((b, checked) -> {
                if (checked) selected.add(a.pkg);
                else selected.remove(a.pkg);
                updateUninstallButton();
            });
            row.addView(box);
            cb = box;
        }

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
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);

        StringBuilder d = new StringBuilder();
        if (a.size > 0) d.append(Formatter.formatShortFileSize(this, a.size));
        if (a.cache >= CACHE_MIN) {
            if (d.length() > 0) d.append(" · ");
            d.append("cache ").append(Formatter.formatShortFileSize(this, a.cache));
        }
        if (usageGranted) {
            if (d.length() > 0) d.append(" · ");
            d.append(lastUsedText(a, now));
        }
        if (isKept(a)) {
            if (d.length() > 0) d.append(" · ");
            d.append("gardée");
        }
        if (d.length() > 0) texts.addView(text(d.toString(), 12, cSub, false));

        // Raisons affichées selon l'onglet
        List<String> reasons = new ArrayList<>();
        if (filter == FILTER_PREINST) {
            reasons.add(a.known != null ? a.known : "Préinstallée : désactive-la si tu ne t'en sers pas");
            if (a.unusedReason != null && usageGranted) reasons.add(a.unusedReason);
        }
        if ((filter == FILTER_UNUSED || filter == FILTER_ALL) && isUnused(a)) reasons.add(a.unusedReason);
        if ((filter == FILTER_IMPACT || filter == FILTER_ALL) && isGreedy(a)) {
            if (a.system && a.known != null) reasons.add("Préinstallée : " + a.known);
            reasons.addAll(a.heatReasons);
        }
        if ((filter == FILTER_SIDELOAD || filter == FILTER_ALL) && isSideloaded(a)) {
            reasons.add("Installée depuis : " + a.sideloadFrom);
        }
        for (String r : reasons) {
            TextView rt = text("•  " + r, 12, cWarn, true);
            rt.setPadding(0, px(2), 0, 0);
            texts.addView(rt);
        }
        row.addView(texts);

        TextView info = text(a.system ? "Désactiver" : "Infos", 13, cAccent, true);
        info.setPadding(px(10), px(8), px(4), px(8));
        info.setOnClickListener(v -> showOptions(a));
        row.addView(info);

        if (cb != null) {
            final CheckBox box = cb;
            row.setOnClickListener(v -> box.setChecked(!box.isChecked()));
        } else {
            row.setOnClickListener(v -> showOptions(a));
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (appList.getChildCount() > 0) wrap.addView(divider());
        wrap.addView(row);
        return wrap;
    }

    /** Fenêtre d'options d'une appli : fiche Android ou « garder ». */
    private void showOptions(AppItem a) {
        boolean k = isKept(a);
        String first = a.system ? "Ouvrir la fiche Android (pour la désactiver)"
                : "Ouvrir la fiche Android (cache, batterie, arrêt)";
        String second = k ? "Ne plus la garder (la signaler à nouveau)" : "La garder (ne plus la signaler)";
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(new CharSequence[]{first, second}, (dlg, which) -> {
                    if (which == 0) {
                        openAppInfo(a.pkg);
                    } else {
                        if (k) kept.remove(a.pkg);
                        else {
                            kept.add(a.pkg);
                            selected.remove(a.pkg);
                        }
                        prefs.edit().putStringSet("kept", new HashSet<>(kept)).apply();
                        Toast.makeText(this, k ? a.label + " sera de nouveau signalée."
                                : a.label + " est gardée : elle n'apparaîtra plus dans les alertes.",
                                Toast.LENGTH_SHORT).show();
                        renderApps();
                        updateScore();
                    }
                })
                .setNegativeButton("Fermer", null)
                .show();
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
                .setPositiveButton("Continuer", (dlg, w) -> startUninstall())
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

    // ------------------------------------------------------------------ courbe de température

    /** Petite courbe de la température de la batterie, avec la ligne des 38 °C. */
    static class TempChart extends View {
        private long[] times = new long[0];
        private float[] temps = new float[0];
        private final float dp;
        private final int line, grid, hot, label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        TempChart(Context c, float dp, int line, int grid, int hot, int label) {
            super(c);
            this.dp = dp;
            this.line = line;
            this.grid = grid;
            this.hot = hot;
            this.label = label;
        }

        void setData(long[] t, float[] v) {
            times = t;
            temps = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (temps.length < 2) return;
            float w = getWidth(), h = getHeight();
            float left = 32 * dp, bottom = h - 16 * dp, top = 6 * dp;

            float min = temps[0], max = temps[0];
            for (float v : temps) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            min = (float) Math.floor(Math.min(min, 30f) / 5f) * 5f;
            max = (float) Math.ceil(Math.max(max, 40f) / 5f) * 5f;
            long t0 = times[0], t1 = Math.max(times[times.length - 1], t0 + 1);

            paint.setTextSize(10 * dp);
            paint.setStyle(Paint.Style.FILL);
            for (float g = min; g <= max + 0.01f; g += 5f) {
                float y = yOf(g, min, max, top, bottom);
                paint.setColor(grid);
                paint.setStrokeWidth(Math.max(1f, dp));
                cv.drawLine(left, y, w, y, paint);
                paint.setColor(label);
                cv.drawText(Math.round(g) + "°", 0, y + 3.5f * dp, paint);
            }

            float yHot = yOf(38f, min, max, top, bottom);
            paint.setColor(hot);
            paint.setStrokeWidth(1.5f * dp);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
            cv.drawLine(left, yHot, w, yHot, paint);
            paint.setPathEffect(null);

            path.reset();
            for (int i = 0; i < temps.length; i++) {
                float x = left + (w - left) * (times[i] - t0) / (float) (t1 - t0);
                float y = yOf(temps[i], min, max, top, bottom);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(line);
            paint.setStrokeWidth(2 * dp);
            paint.setStrokeJoin(Paint.Join.ROUND);
            cv.drawPath(path, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(hot);
            for (int i = 0; i < temps.length; i++) {
                if (temps[i] < 38f) continue;
                float x = left + (w - left) * (times[i] - t0) / (float) (t1 - t0);
                cv.drawCircle(x, yOf(temps[i], min, max, top, bottom), 3 * dp, paint);
            }

            paint.setColor(label);
            long spanH = (t1 - t0) / (60L * 60 * 1000);
            String start = spanH >= 48 ? "il y a " + (spanH / 24) + " j" : "il y a " + Math.max(1, spanH) + " h";
            cv.drawText(start, left, h - 2 * dp, paint);
            String end = "maintenant";
            cv.drawText(end, w - paint.measureText(end), h - 2 * dp, paint);
        }

        private static float yOf(float v, float min, float max, float top, float bottom) {
            return bottom - (bottom - top) * (v - min) / (max - min);
        }
    }
}
