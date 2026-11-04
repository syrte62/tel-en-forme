package com.savy.telenforme;

import java.util.HashMap;
import java.util.Map;

/** Applis préinstallées connues (surtout Xiaomi) qu'on peut désactiver sans risque. */
final class Bloat {

    static final Map<String, String> KNOWN = new HashMap<>();

    static {
        // Xiaomi : publicités, statistiques, doublons
        KNOWN.put("com.miui.msa.global", "Service de publicités de Xiaomi");
        KNOWN.put("com.miui.analytics", "Envoie des statistiques d'utilisation à Xiaomi");
        KNOWN.put("com.miui.daemon", "Collecte de données pour Xiaomi");
        KNOWN.put("com.xiaomi.mipicks", "Boutique GetApps : publicités et notifications");
        KNOWN.put("com.miui.android.fashiongallery", "Carrousel de fonds d'écran : publicités sur l'écran verrouillé");
        KNOWN.put("com.mi.globalbrowser", "Navigateur Xiaomi, doublon de Chrome, avec publicités");
        KNOWN.put("com.miui.videoplayer", "Mi Vidéo : lecteur avec publicités");
        KNOWN.put("com.miui.player", "Musique Xiaomi : lecteur avec publicités");
        KNOWN.put("com.xiaomi.glgm", "Jeux Xiaomi : publicités de jeux");
        KNOWN.put("com.mi.global.bbs", "Communauté Xiaomi (forum)");
        KNOWN.put("com.mi.global.shop", "Boutique en ligne Xiaomi");
        KNOWN.put("com.miui.yellowpage", "Annuaire Xiaomi");
        KNOWN.put("com.miui.hybrid", "Applis instantanées Xiaomi, tournent en fond");
        KNOWN.put("com.miui.hybrid.accessory", "Applis instantanées Xiaomi, tournent en fond");
        KNOWN.put("com.miui.bugreport", "Envoie des rapports de bugs à Xiaomi");
        KNOWN.put("com.miui.miservice", "Services et commentaires Xiaomi");
        KNOWN.put("com.xiaomi.midrop", "ShareMe : inutile si tu ne partages pas de fichiers avec");
        KNOWN.put("com.android.thememanager", "Thèmes Xiaomi : publicités et notifications");
        KNOWN.put("com.miui.cleaner", "Nettoyeur Xiaomi : tourne en fond, Tél en forme le remplace");
        KNOWN.put("com.miui.cleanmaster", "Nettoyeur Xiaomi : tourne en fond, Tél en forme le remplace");
        KNOWN.put("com.mipay.wallet", "Mi Pay, inutile si tu ne paies pas avec");
        KNOWN.put("com.xiaomi.payment", "Mi Pay, inutile si tu ne paies pas avec");
        // Partenaires préinstallés
        KNOWN.put("com.facebook.appmanager", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        KNOWN.put("com.facebook.services", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        KNOWN.put("com.facebook.system", "Service Facebook préinstallé : tourne en fond même sans Facebook");
        KNOWN.put("com.netflix.partner.activation", "Activation Netflix préinstallée");
        KNOWN.put("com.amazon.appmanager", "Gestionnaire Amazon préinstallé");
        KNOWN.put("com.linkedin.android", "LinkedIn préinstallé");
        KNOWN.put("com.booking", "Booking préinstallé");
    }

    private static final String[] ESSENTIAL_PREFIXES = {
            "com.android.", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.dialer", "com.google.android.apps.messaging", "com.google.android.contacts",
            "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.miui.securitycenter", "com.miui.home", "com.miui.gallery", "com.miui.packageinstaller",
            "com.xiaomi.misettings", "com.miui.system", "com.qualcomm.", "com.mediatek."
    };

    static boolean isEssential(String pkg) {
        for (String p : ESSENTIAL_PREFIXES) if (pkg.startsWith(p)) return true;
        return false;
    }

    private Bloat() {
    }
}
