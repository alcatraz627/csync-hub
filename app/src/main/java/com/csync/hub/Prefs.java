package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Stores the home peer (this Mac) address and the shared mesh token. */
public final class Prefs {

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("csync", Context.MODE_PRIVATE);
    }

    static String homeIp(Context c) { return p(c).getString("home_ip", ""); }

    static String token(Context c) { return p(c).getString("token", ""); }

    static void save(Context c, String ip, String token) {
        p(c).edit().putString("home_ip", ip.trim()).putString("token", token.trim()).apply();
    }

    // The assistant peer (the Pi running csync-assist). Defaults to the MagicDNS
    // name, not a raw IP, so it keeps resolving if the Pi's tailnet IP changes.
    static String assistIp(Context c) { return p(c).getString("assist_ip", "raspberrypi"); }

    static void saveAssistIp(Context c, String ip) {
        p(c).edit().putString("assist_ip", ip.trim()).apply();
    }

    /** This device's name on the mesh; the phone model, sanitised for a folder name. */
    static String deviceName(Context c) {
        String m = Build.MODEL != null ? Build.MODEL : "android";
        return m.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
