package com.savy.telenforme;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.util.Locale;

/**
 * Réglage batterie conseillé pour une appli, selon ce qu'elle fait.
 * Android propose trois réglages : Sans restriction, Optimisée (par défaut) et Restreinte.
 */
final class Advice {

    static final int UNRESTRICTED = 0;
    static final int OPTIMIZED = 1;
    static final int RESTRICTED = 2;

    static final class Tip {
        final int level;
        final String why;

        Tip(int level, String why) {
            this.level = level;
            this.why = why;
        }
    }

    /** Doivent rester connectées en permanence : montres, bracelets, réveils, VPN. */
    private static final String[] NEEDS_BACKGROUND = {
            "com.xiaomi.wearable", "com.mi.health", "com.xiaomi.hm.health", "com.huami.", "com.zepp.",
            "com.samsung.android.app.watchmanager", "com.samsung.android.waterplugin",
            "com.fitbit.", "com.garmin.", "com.google.android.apps.wear", "com.polar.", "com.suunto.",
            "com.withings.", "com.coros.", "com.amazfit.", "com.urbandroid.sleep", "com.alarmy",
            "droom.sleepIfUCan", "com.nordvpn", "com.expressvpn", "ch.protonvpn", "com.wireguard"
    };

    /** Messageries et appels : les messages doivent arriver tout de suite. */
    private static final String[] MESSAGING = {
            "com.whatsapp", "org.telegram.", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.google.android.apps.messaging", "com.viber.voip", "jp.naver.line", "com.discord",
            "com.microsoft.teams", "com.slack", "com.skype", "us.zoom", "com.snapchat.android",
            "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail", "com.yahoo.mobile.client.android.mail",
            "fr.laposte.lapostemobile", "com.orange.mail", "net.sfr.mail", "fr.free.mail"
    };

    private static final String[] BANK_WORDS = {
            "bank", "banque", "bnp", "societegenerale", "creditagricole", "credit.agricole", "lcl",
            "boursorama", "caisse", "cic.", "creditmutuel", "laposte.banque", "labanquepostale", "revolut",
            "paypal", "lydia", "n26", "fortuneo", "hellobank", "monabanq", "shine", "qonto", "wise",
            "ameli", "impots", "franceconnect", "doctolib"
    };

    private static final String[] SHOP_WORDS = {
            "aliexpress", "amazon.mshop", "shein", "temu", "vinted", "leboncoin", "auchan", "carrefour",
            "lidl", "cdiscount", "wish", "ebay", "fnac", "darty", "zalando", "decathlon", "ikea", "action",
            "intermarche", "leclerc", "picard", "joom", "wallapop", "backmarket", "veepee", "showroomprive"
    };

    private static final String[] SOCIAL_WORDS = {
            "facebook.katana", "facebook.lite", "instagram", "zhiliaoapp", "musically", "tiktok", "twitter",
            "pinterest", "reddit", "linkedin", "threads", "bereal", "tumblr", "x.android"
    };

    private static final String[] NAV_WORDS = {
            "waze", "maps", "navigation", "sygic", "mappy", "coyote", "tomtom", "citymapper", "bonjourratp",
            "sncf", "blablacar", "uber", "bolt", "heetch"
    };

    static Tip recommend(String pkg, int category, long fg7, long fgs7) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (startsWithAny(p, NEEDS_BACKGROUND)) {
            return new Tip(UNRESTRICTED, "Elle doit rester connectée en permanence (montre, bracelet, réveil ou VPN). "
                    + "Restreinte, elle se déconnecterait ou sonnerait en retard.");
        }
        if (startsWithAny(p, MESSAGING)) {
            return new Tip(OPTIMIZED, "Messagerie ou e-mail : en « Restreinte », tes messages et appels "
                    + "pourraient arriver en retard. Le réglage par défaut est le bon.");
        }
        if (containsAny(p, BANK_WORDS)) {
            return new Tip(OPTIMIZED, "Banque ou démarche officielle : les alertes et les validations de paiement "
                    + "doivent arriver à temps. Le réglage par défaut est le bon.");
        }
        if (category == ApplicationInfo.CATEGORY_AUDIO) {
            return new Tip(OPTIMIZED, "Musique, radio ou podcast : en « Restreinte », la lecture pourrait "
                    + "s'arrêter quand l'écran s'éteint.");
        }
        if (category == ApplicationInfo.CATEGORY_MAPS || containsAny(p, NAV_WORDS)) {
            return new Tip(OPTIMIZED, "GPS ou transport : en « Restreinte », le guidage pourrait s'interrompre "
                    + "écran éteint.");
        }
        if (containsAny(p, SHOP_WORDS)) {
            return new Tip(RESTRICTED, "Appli d'achats : elle n'a aucune raison de tourner quand tu ne l'utilises "
                    + "pas. Tu recevras seulement les promos un peu plus tard.");
        }
        if (category == ApplicationInfo.CATEGORY_GAME) {
            return new Tip(RESTRICTED, "Jeu : il n'a pas besoin de tourner quand tu n'y joues pas. "
                    + "Tu ne perds rien.");
        }
        if (category == ApplicationInfo.CATEGORY_SOCIAL || containsAny(p, SOCIAL_WORDS)) {
            return new Tip(RESTRICTED, "Réseau social : il recharge son fil en fond sans arrêt, ce qui chauffe. "
                    + "Les notifications importantes arrivent quand même, parfois avec un peu de retard.");
        }
        if (category == ApplicationInfo.CATEGORY_VIDEO || category == ApplicationInfo.CATEGORY_NEWS
                || category == ApplicationInfo.CATEGORY_IMAGE) {
            return new Tip(RESTRICTED, "Vidéo, actualités ou photo : rien ne l'oblige à tourner en fond. "
                    + "Elle se met à jour dès que tu l'ouvres.");
        }
        if (fgs7 >= 60L * 60 * 1000 && fg7 < 30L * 60 * 1000) {
            return new Tip(RESTRICTED, "Tu l'ouvres peu, mais elle tourne beaucoup en fond : c'est typiquement "
                    + "ce qui fait chauffer.");
        }
        return new Tip(OPTIMIZED, "Le réglage par défaut d'Android convient : elle peut faire un peu de travail en "
                + "fond, mais Android la freine quand il faut.");
    }

    static boolean isXiaomi() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    /** Nom du réglage tel qu'affiché sur le téléphone. */
    static String name(int level) {
        if (isXiaomi()) {
            switch (level) {
                case UNRESTRICTED: return "Pas de restrictions";
                case RESTRICTED: return "Restreindre l'activité en arrière-plan";
                default: return "Économiseur de batterie";
            }
        }
        switch (level) {
            case UNRESTRICTED: return "Sans restriction";
            case RESTRICTED: return "Restreinte";
            default: return "Optimisée";
        }
    }

    /** Ce que fait ce réglage, en clair. */
    static String effect(int level) {
        switch (level) {
            case UNRESTRICTED:
                return "Elle peut tourner en permanence. À réserver aux montres, bracelets et réveils.";
            case RESTRICTED:
                return "Elle ne tourne plus en fond. Elle marche normalement quand tu l'ouvres.";
            default:
                return "Elle peut faire un peu de travail en fond, Android la freine quand il faut.";
        }
    }

    /** Où toucher pour appliquer le réglage. */
    static String steps(int level) {
        if (isXiaomi()) {
            String s = "Touche « Ouvrir la fiche », puis « Économiseur de batterie » et choisis « " + name(level) + " ».";
            if (level == RESTRICTED) s += " Désactive aussi « Démarrage automatique » s'il est présent.";
            if (level == UNRESTRICTED) s += " Active aussi « Démarrage automatique ».";
            return s + " (Le nom exact peut varier un peu selon la version.)";
        }
        return "Touche « Ouvrir la fiche », puis « Batterie » (ou « Utilisation de la batterie ») et choisis « "
                + name(level) + " ».";
    }

    private static boolean startsWithAny(String p, String[] list) {
        for (String s : list) if (p.startsWith(s.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean containsAny(String p, String[] list) {
        for (String s : list) if (p.contains(s)) return true;
        return false;
    }

    private Advice() {
    }
}
