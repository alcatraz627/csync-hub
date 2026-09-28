package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
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

    static String themeMode(Context c) {
        SharedPreferences prefs = p(c);
        return prefs.getString("theme_mode", prefs.getBoolean("dark_theme", false) ? "dark" : "light");
    }

    static boolean darkTheme(Context c) {
        String mode = themeMode(c);
        return "dark".equals(mode) || ("system".equals(mode) &&
            (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    }

    static void saveThemeMode(Context c, String mode) {
        p(c).edit().putString("theme_mode", mode)
            .putBoolean("dark_theme", "dark".equals(mode)).apply();
    }

    static void saveDarkTheme(Context c, boolean dark) {
        saveThemeMode(c, dark ? "dark" : "light");
    }

    static String accent(Context c) { return p(c).getString("accent", "coral"); }

    static void saveAccent(Context c, String accent) {
        p(c).edit().putString("accent", accent).apply();
    }

    static int customAccent(Context c) {
        return p(c).getInt("custom_accent", 0xFF8B5CF6);
    }

    static void saveCustomAccent(Context c, int color) {
        p(c).edit().putInt("custom_accent", color).putString("accent", "custom").apply();
    }

    static String textSize(Context c) { return p(c).getString("text_size", "md"); }

    static void saveTextSize(Context c, String size) {
        p(c).edit().putString("text_size", size).apply();
    }

    /** This device's name on the mesh; the phone model, sanitised for a folder name. */
    static String deviceName(Context c) {
        String m = Build.MODEL != null ? Build.MODEL : "android";
        return m.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
