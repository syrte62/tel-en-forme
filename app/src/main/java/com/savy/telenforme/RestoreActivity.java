package com.savy.telenforme;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Écran des points de restauration : liste, comparaison avec aujourd'hui, retour en arrière pas à pas. */
public class RestoreActivity extends Activity {

    private static final int REQ_EXPORT = 51;
    private static final int REQ_IMPORT = 52;

    private int cBg, cCard, cText, cSub, cAccent, cLine, cWarn;
    private final int cDanger = Color.parseColor("#E5484D");
    private float dp;
    private LinearLayout root;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private List<Snapshot> points = new ArrayList<>();
    private Snapshot current;       // point ouvert, ou null pour la liste
    private Snapshot pendingExport; // point à enregistrer dans un fichier

    // Restauration pas à pas
    private final List<Snapshot.Change> steps = new ArrayList<>();
    private int stepIndex = -1;
    private boolean awaitingReturn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = HeatJob.prefs(this);
        setupColors();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(20), px(16), px(32));
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stepIndex >= 0) {
            if (awaitingReturn) {
                awaitingReturn = false;
                nextStep();
            }
            return;
        }
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (current != null) {
            current = null;
            render(null);
            return;
        }
        super.onBackPressed();
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
        } else {
            cBg = Color.parseColor("#F4F6F8");
            cCard = Color.WHITE;
            cText = Color.parseColor("#15181C");
            cSub = Color.parseColor("#5F6670");
            cAccent = Color.parseColor("#1F9D63");
            cLine = Color.parseColor("#E3E6EA");
            cWarn = Color.parseColor("#A86B00");
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

    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
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
        lp.topMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(cLine);
        d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        return d;
    }

    private String when(long t) {
        return DateFormat.getDateFormat(this).format(new Date(t)) + " à "
                + DateFormat.getTimeFormat(this).format(new Date(t));
    }

    private Set<String> keptNow() {
        return new HashSet<>(prefs.getStringSet("kept", new HashSet<>()));
    }

    // ------------------------------------------------------------------ chargement

    private void reload() {
        root.removeAllViews();
        root.addView(text("Chargement…", 15, cSub, false));
        final long open = current != null ? current.time : -1;
        exec.submit(() -> {
            List<Snapshot> list = Snapshot.list(getApplicationContext());
            Snapshot now = Snapshot.capture(getApplicationContext(), "", keptNow());
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                points = list;
                current = null;
                if (open > 0) for (Snapshot s : points) if (s.time == open) current = s;
                render(now);
            });
        });
    }

    private void render(Snapshot now) {
        if (now == null) {
            reload();
            return;
        }
        root.removeAllViews();
        if (current == null) renderList(now);
        else renderDetail(current, now);
    }

    // ------------------------------------------------------------------ liste des points

    private void renderList(Snapshot now) {
        root.addView(text("Points de restauration", 26, cText, true));

        LinearLayout intro = card();
        LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) intro.getLayoutParams();
        ilp.topMargin = px(12);
        intro.addView(text("Un point de restauration est une photo de ton téléphone : applis installées, "
                + "applis désactivées, réglages batterie. Tél en forme en crée un automatiquement avant chaque "
                + "modification. Ouvre un point pour voir ce qui a changé depuis et le remettre comme avant.",
                14, cText, false));
        TextView limits = text("Android ne permet à aucune appli de tout remettre seule : pour chaque changement, "
                + "Tél en forme t'emmène sur le bon écran et tu confirmes. Une appli supprimée se réinstalle, "
                + "mais ses données ne reviennent que si elle les sauvegardait (compte, cloud).", 13, cSub, false);
        limits.setPadding(0, px(8), 0, 0);
        intro.addView(limits);
        Button create = button("Créer un point maintenant", true);
        create.setOnClickListener(v -> exec.submit(() -> {
            Snapshot.save(getApplicationContext(),
                    Snapshot.capture(getApplicationContext(), "Créé à la main", keptNow()));
            ui.post(() -> {
                Toast.makeText(this, "Point de restauration créé.", Toast.LENGTH_SHORT).show();
                reload();
            });
        }));
        intro.addView(create);
        Button imp = button("Importer un point depuis un fichier", false);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMPORT);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Aucun gestionnaire de fichiers disponible.", Toast.LENGTH_SHORT).show();
            }
        });
        intro.addView(imp);
        root.addView(intro);

        if (points.isEmpty()) {
            root.addView(text("Aucun point pour l'instant.", 14, cSub, false));
            return;
        }
        LinearLayout list = card();
        for (Snapshot s : points) {
            int n = 0;
            for (Snapshot.Change ch : s.diff(now)) if (ch.type != Snapshot.CH_ADDED) n++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(10), 0, px(10));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(text(when(s.time), 16, cText, true));
            texts.addView(text(s.reason, 13, cSub, false));
            texts.addView(text(n == 0 ? "Rien n'a changé depuis" : n + " changement" + (n > 1 ? "s" : "")
                    + " depuis", 13, n == 0 ? cAccent : cWarn, true));
            row.addView(texts);
            row.addView(text("›", 26, cAccent, true));
            row.setOnClickListener(v -> {
                current = s;
                render(now);
            });
            if (list.getChildCount() > 0) list.addView(divider());
            list.addView(row);
        }
        root.addView(list);
    }

    // ------------------------------------------------------------------ détail d'un point

    private void renderDetail(Snapshot s, Snapshot now) {
        TextView back = text("‹ Tous les points", 15, cAccent, true);
        back.setPadding(0, 0, 0, px(10));
        back.setOnClickListener(v -> {
            current = null;
            render(now);
        });
        root.addView(back);
        root.addView(text(when(s.time), 24, cText, true));
        TextView reason = text(s.reason, 14, cSub, false);
        reason.setPadding(0, px(2), 0, px(12));
        root.addView(reason);

        List<Snapshot.Change> changes = s.diff(now);
        List<Snapshot.Change> undoable = new ArrayList<>();
        List<Snapshot.Change> added = new ArrayList<>();
        StringBuilder adb = new StringBuilder();
        for (Snapshot.Change ch : changes) {
            if (ch.type == Snapshot.CH_ADDED) added.add(ch);
            else if (ch.type == Snapshot.CH_REMOVED_SYSTEM) {
                adb.append("adb shell cmd package install-existing ").append(Snapshot.pkg(ch)).append('\n');
            } else undoable.add(ch);
        }
        boolean keptChanged = !s.kept.equals(keptNow());

        LinearLayout summary = card();
        int total = undoable.size() + (adb.length() > 0 ? 1 : 0) + (keptChanged ? 1 : 0);
        if (total == 0) {
            summary.addView(text("Rien à restaurer : ton téléphone est comme à ce moment-là.", 15, cAccent, true));
        } else {
            summary.addView(text(undoable.size() + " changement" + (undoable.size() > 1 ? "s" : "")
                    + " à défaire sur le téléphone", 16, cText, true));
            if (!undoable.isEmpty()) {
                TextView how = text("« Tout restaurer pas à pas » ouvre le bon écran pour chaque changement. "
                        + "Fais l'action demandée, puis reviens avec le bouton retour : l'écran suivant s'ouvre.",
                        13, cSub, false);
                how.setPadding(0, px(6), 0, 0);
                summary.addView(how);
                Button all = button("Tout restaurer pas à pas", true);
                all.setOnClickListener(v -> startSteps(undoable));
                summary.addView(all);
            }
        }
        if (keptChanged) {
            Button k = button("Remettre ma liste d'applis gardées (" + s.kept.size() + ")", false);
            k.setOnClickListener(v -> {
                prefs.edit().putStringSet("kept", new HashSet<>(s.kept)).apply();
                Toast.makeText(this, "Liste des applis gardées remise comme avant.", Toast.LENGTH_SHORT).show();
                reload();
            });
            summary.addView(k);
        }
        root.addView(summary);

        if (!undoable.isEmpty()) {
            root.addView(section("Changements depuis ce point"));
            LinearLayout box = card();
            for (Snapshot.Change ch : undoable) {
                if (box.getChildCount() > 0) box.addView(divider());
                box.addView(changeRow(ch));
            }
            root.addView(box);
        }

        if (adb.length() > 0) {
            root.addView(section("Retirées avec ton PC"));
            LinearLayout box = card();
            box.addView(text("Ces applis préinstallées ont été retirées par commande PC (ADB). Pour les remettre, "
                    + "branche le téléphone au PC et tape ces commandes :", 14, cText, false));
            TextView code = text(adb.toString().trim(), 12, cText, false);
            code.setTypeface(Typeface.MONOSPACE);
            code.setTextIsSelectable(true);
            code.setBackground(rounded(cLine, 8));
            code.setPadding(px(10), px(10), px(10), px(10));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = px(8);
            code.setLayoutParams(clp);
            box.addView(code);
            final String cmds = adb.toString();
            Button copy = button("Copier les commandes", false);
            copy.setOnClickListener(v -> copy(cmds));
            box.addView(copy);
            root.addView(box);
        }

        if (!added.isEmpty()) {
            root.addView(section("Installées depuis"));
            LinearLayout box = card();
            StringBuilder sb = new StringBuilder();
            for (Snapshot.Change ch : added) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(Snapshot.label(ch));
            }
            box.addView(text(sb.toString(), 14, cText, false));
            TextView note = text("Rien à faire : ces applis sont nouvelles depuis ce point. Supprime-les si tu "
                    + "veux revenir exactement à l'état d'avant.", 13, cSub, false);
            note.setPadding(0, px(6), 0, 0);
            box.addView(note);
            root.addView(box);
        }

        root.addView(section("Ce point"));
        LinearLayout tools = card();
        Button exp = button("Enregistrer une copie dans un fichier", false);
        exp.setOnClickListener(v -> export(s));
        tools.addView(exp);
        TextView expNote = text("Une copie dans tes fichiers reste disponible même si tu désinstalles Tél en forme.",
                12, cSub, false);
        expNote.setPadding(0, px(6), 0, 0);
        tools.addView(expNote);
        if (points.size() > 1 && points.get(points.size() - 1).time != s.time) {
            Button del = button("Supprimer ce point", false);
            del.setTextColor(cDanger);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Supprimer ce point ?")
                    .setMessage("Tu ne pourras plus revenir à l'état du " + when(s.time) + ".")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer", (d, w) -> {
                        Snapshot.delete(getApplicationContext(), s.time);
                        current = null;
                        reload();
                    })
                    .show());
            tools.addView(del);
        }
        root.addView(tools);
    }

    private TextView section(String s) {
        TextView t = text(s, 18, cText, true);
        t.setPadding(px(4), px(12), 0, px(8));
        return t;
    }

    private String describe(Snapshot.Change ch) {
        switch (ch.type) {
            case Snapshot.CH_REMOVED_USER:
                return isPlayStore(ch.then.installer)
                        ? "Supprimée depuis : à réinstaller depuis le Play Store. Ses données ne reviennent que si "
                        + "elle les sauvegardait (compte, cloud)."
                        : "Supprimée depuis. Elle ne venait pas du Play Store : réinstalle-la depuis sa source "
                        + "d'origine (fichier .apk ou autre boutique).";
            case Snapshot.CH_DISABLED:
                return "Désactivée depuis : touche « Réactiver », puis « Activer » sur la fiche.";
            case Snapshot.CH_ENABLED:
                return "Réactivée depuis : touche « Désactiver » sur la fiche si tu veux revenir en arrière.";
            case Snapshot.CH_BATTERY_LOST:
                return "Était en « " + Advice.name(Advice.UNRESTRICTED) + " » : remets ce réglage dans "
                        + "« Batterie » de la fiche.";
            case Snapshot.CH_BATTERY_GAINED:
                return "Est passée en « " + Advice.name(Advice.UNRESTRICTED) + " » depuis : remets « "
                        + Advice.name(Advice.OPTIMIZED) + " » dans « Batterie » de la fiche.";
            default:
                return "";
        }
    }

    private String actionLabel(Snapshot.Change ch) {
        switch (ch.type) {
            case Snapshot.CH_REMOVED_USER:
                return isPlayStore(ch.then.installer) ? "Réinstaller" : "Infos";
            case Snapshot.CH_DISABLED:
                return "Réactiver";
            default:
                return "Ouvrir";
        }
    }

    private View changeRow(Snapshot.Change ch) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(10), 0, px(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(text(Snapshot.label(ch), 15, cText, true));
        texts.addView(text(describe(ch), 12, cWarn, false));
        row.addView(texts);
        TextView act = text(actionLabel(ch), 14, cAccent, true);
        act.setPadding(px(12), px(8), px(4), px(8));
        act.setOnClickListener(v -> undo(ch));
        row.addView(act);
        return row;
    }

    private static boolean isPlayStore(String installer) {
        return "com.android.vending".equals(installer);
    }

    // ------------------------------------------------------------------ actions

    /** Ouvre l'écran qui permet de défaire ce changement. Renvoie false s'il n'y a rien à ouvrir. */
    private boolean undo(Snapshot.Change ch) {
        String pkg = Snapshot.pkg(ch);
        if (ch.type == Snapshot.CH_REMOVED_USER) {
            if (isPlayStore(ch.then.installer)) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
                try {
                    startActivity(i);
                    return true;
                } catch (ActivityNotFoundException e) {
                    return open(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(Snapshot.label(ch))
                    .setMessage("Cette appli ne venait pas du Play Store. Pour la remettre, réinstalle-la depuis "
                            + "l'endroit où tu l'avais trouvée (fichier .apk, autre boutique).\n\nNom technique : " + pkg)
                    .setPositiveButton("OK", null)
                    .show();
            return false;
        }
        return open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir cet écran sur ce téléphone.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void startSteps(List<Snapshot.Change> list) {
        steps.clear();
        for (Snapshot.Change ch : list) {
            if (ch.type == Snapshot.CH_REMOVED_USER && !isPlayStore(ch.then.installer)) continue;
            steps.add(ch);
        }
        if (steps.isEmpty()) {
            Toast.makeText(this, "Rien à ouvrir automatiquement.", Toast.LENGTH_SHORT).show();
            return;
        }
        stepIndex = 0;
        nextStep();
    }

    private void nextStep() {
        if (stepIndex < 0) return;
        if (stepIndex >= steps.size()) {
            stepIndex = -1;
            steps.clear();
            Toast.makeText(this, "Restauration terminée. Vérifie la liste : ce qui reste n'a pas été défait.",
                    Toast.LENGTH_LONG).show();
            reload();
            return;
        }
        final Snapshot.Change ch = steps.get(stepIndex);
        final int pos = stepIndex + 1;
        stepIndex++;
        root.removeAllViews();
        LinearLayout c = card();
        c.addView(text("Étape " + pos + " sur " + steps.size(), 14, cSub, false));
        c.addView(text(Snapshot.label(ch), 20, cText, true));
        TextView d = text(describe(ch), 14, cText, false);
        d.setPadding(0, px(6), 0, 0);
        c.addView(d);
        Button go = button(actionLabel(ch), true);
        go.setOnClickListener(v -> {
            if (undo(ch)) awaitingReturn = true;
            else nextStep();
        });
        c.addView(go);
        Button skip = button("Passer", false);
        skip.setOnClickListener(v -> nextStep());
        c.addView(skip);
        Button stop = button("Arrêter", false);
        stop.setOnClickListener(v -> {
            stepIndex = -1;
            awaitingReturn = false;
            steps.clear();
            reload();
        });
        c.addView(stop);
        root.addView(c);
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Commandes ADB", s));
            Toast.makeText(this, "Commandes copiées.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Copie impossible.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ fichiers

    private void export(Snapshot s) {
        pendingExport = s;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        String date = DateFormat.format("yyyy-MM-dd_HH-mm", new Date(s.time)).toString();
        i.putExtra(Intent.EXTRA_TITLE, "tel-en-forme-restauration-" + date + ".json");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucun gestionnaire de fichiers disponible.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT && pendingExport != null) {
            final String json = pendingExport.toJson();
            pendingExport = null;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) os.write(json.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Copie enregistrée.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Enregistrement impossible.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_IMPORT) {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            } catch (Exception e) {
                Toast.makeText(this, "Lecture du fichier impossible.", Toast.LENGTH_SHORT).show();
                return;
            }
            Snapshot s = Snapshot.fromJson(sb.toString());
            if (s == null) {
                Toast.makeText(this, "Ce fichier n'est pas un point de restauration de Tél en forme.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Snapshot.save(this, s);
            Toast.makeText(this, "Point importé.", Toast.LENGTH_SHORT).show();
            current = null;
        }
    }
}
