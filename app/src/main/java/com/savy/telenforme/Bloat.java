package com.savy.telenforme;

import java.util.HashMap;
import java.util.Map;

/**
 * Applis préinstallées connues, avec leur niveau de risque.
 * SAFE    : on peut la désactiver ou la retirer sans aucun souci pour le téléphone
 * LOSS    : sans risque pour le téléphone, mais on perd une fonction précise
 * KEEP    : à laisser, la toucher peut dérégler le téléphone
 * UNKNOWN : rôle inconnu, dans le doute on la laisse
 */
final class Bloat {

    static final int SAFE = 0;
    static final int LOSS = 1;
    static final int KEEP = 2;
    static final int UNKNOWN = 3;

    static final class Info {
        final int level;
        final String desc;
        final String loss;

        Info(int level, String desc, String loss) {
            this.level = level;
            this.desc = desc;
            this.loss = loss;
        }
    }

    private static final Map<String, Info> KNOWN = new HashMap<>();

    private static void safe(String pkg, String desc) {
        KNOWN.put(pkg, new Info(SAFE, desc, null));
    }

    private static void loss(String pkg, String desc, String loss) {
        KNOWN.put(pkg, new Info(LOSS, desc, loss));
    }

    private static void keep(String pkg, String desc) {
        KNOWN.put(pkg, new Info(KEEP, desc, null));
    }

    static {
        // Xiaomi : publicités, statistiques, doublons
        safe("com.miui.msa.global", "Service de publicités de Xiaomi");
        safe("com.miui.analytics", "Envoie des statistiques d'utilisation à Xiaomi");
        safe("com.miui.daemon", "Collecte de données pour Xiaomi");
        safe("com.miui.android.fashiongallery", "Carrousel de fonds d'écran : publicités sur l'écran verrouillé");
        safe("com.mi.globalbrowser", "Navigateur Xiaomi avec publicités, doublon de Chrome");
        safe("com.xiaomi.glgm", "Jeux Xiaomi : publicités de jeux");
        safe("com.mi.global.bbs", "Communauté Xiaomi (forum)");
        safe("com.mi.global.shop", "Boutique en ligne Xiaomi");
        safe("com.miui.hybrid", "Applis instantanées Xiaomi, tournent en fond");
        safe("com.miui.hybrid.accessory", "Applis instantanées Xiaomi, tournent en fond");
        safe("com.miui.bugreport", "Envoie des rapports de bugs à Xiaomi");
        safe("com.miui.miservice", "Services et commentaires Xiaomi");
        loss("com.xiaomi.mipicks", "Boutique GetApps : publicités et notifications",
                "les mises à jour de quelques applis Xiaomi qui passent par GetApps");
        loss("com.miui.yellowpage", "Annuaire Xiaomi",
                "l'affichage du nom des entreprises qui t'appellent");
        loss("com.miui.videoplayer", "Mi Vidéo : lecteur avec publicités",
                "le lecteur vidéo Xiaomi (la Galerie ou VLC le remplacent)");
        loss("com.miui.player", "Musique Xiaomi : lecteur avec publicités",
                "le lecteur de musique Xiaomi");
        loss("com.xiaomi.midrop", "ShareMe : partage de fichiers entre téléphones",
                "l'envoi rapide de fichiers vers un autre téléphone Xiaomi");
        loss("com.miui.cleaner", "Nettoyeur Xiaomi, tourne en fond",
                "le bouton « Nettoyer » de l'appli Sécurité (Tél en forme le remplace)");
        loss("com.miui.cleanmaster", "Nettoyeur Xiaomi, tourne en fond",
                "le bouton « Nettoyer » de l'appli Sécurité (Tél en forme le remplace)");
        loss("com.mipay.wallet", "Mi Pay", "le paiement sans contact avec Mi Pay");
        loss("com.xiaomi.payment", "Mi Pay", "le paiement sans contact avec Mi Pay");
        loss("com.miui.notes", "Notes Xiaomi", "tes notes Xiaomi (sauvegarde-les avant)");
        loss("com.miui.compass", "Boussole Xiaomi", "la boussole");
        loss("com.xiaomi.scanner", "Scanner Xiaomi", "le scanner de documents et QR codes Xiaomi");
        loss("com.miui.fm", "Radio FM", "la radio FM");
        // Xiaomi : à laisser
        keep("com.android.thememanager", "Gère les fonds d'écran et les thèmes : la désactiver peut dérégler l'affichage");
        keep("com.xiaomi.discover", "Mises à jour du système Xiaomi");
        keep("com.android.updater", "Mises à jour du système");
        keep("com.xiaomi.finddevice", "Localiser mon appareil, utile en cas de vol ou de perte");
        keep("com.xiaomi.account", "Compte Xiaomi");
        keep("com.miui.cloudservice", "Sauvegarde Xiaomi Cloud");
        keep("com.miui.backup", "Sauvegarde et restauration du téléphone");
        keep("com.miui.weather2", "Météo, utilisée par les widgets de l'écran d'accueil");
        // Google préinstallées
        loss("com.google.android.youtube", "YouTube", "YouTube (le site youtube.com reste accessible)");
        loss("com.google.android.apps.youtube.music", "YouTube Music", "YouTube Music");
        loss("com.google.android.apps.photos", "Google Photos", "la sauvegarde de tes photos dans Google Photos");
        loss("com.google.android.apps.maps", "Google Maps", "la navigation GPS Google Maps");
        loss("com.google.android.gm", "Gmail", "tes e-mails dans l'appli Gmail");
        loss("com.google.android.apps.docs", "Google Drive", "l'accès à tes fichiers Google Drive");
        loss("com.google.android.apps.tachyon", "Google Meet", "les appels vidéo Google Meet");
        loss("com.google.android.videos", "Google TV", "tes films achetés sur Google");
        loss("com.google.android.apps.subscriptions.red", "Google One", "la gestion de ton stockage Google");
        // Partenaires préinstallés
        safe("com.facebook.appmanager", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        safe("com.facebook.services", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        safe("com.facebook.system", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        safe("com.netflix.partner.activation", "Activation Netflix préinstallée");
        safe("com.amazon.appmanager", "Gestionnaire Amazon préinstallé");
        safe("com.linkedin.android", "LinkedIn préinstallé");
        safe("com.booking", "Booking préinstallé");
    }

    private static final String[] ESSENTIAL_PREFIXES = {
            "com.android.", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.dialer", "com.google.android.apps.messaging", "com.google.android.contacts",
            "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.google.android.inputmethod", "com.google.android.webview", "com.google.android.ext.",
            "com.miui.securitycenter", "com.miui.home", "com.miui.gallery", "com.miui.packageinstaller",
            "com.xiaomi.misettings", "com.miui.system", "com.qualcomm.", "com.mediatek."
    };

    /** Infos connues sur une appli préinstallée, ou null. */
    static Info known(String pkg) {
        return KNOWN.get(pkg);
    }

    static boolean isEssential(String pkg) {
        for (String p : ESSENTIAL_PREFIXES) if (pkg.startsWith(p)) return true;
        return false;
    }

    static String levelName(int level) {
        switch (level) {
            case SAFE: return "Sans risque";
            case LOSS: return "Sans risque, mais tu perds une fonction";
            case KEEP: return "À laisser";
            default: return "Rôle inconnu : dans le doute, laisse-la";
        }
    }

    private Bloat() {
    }
}
