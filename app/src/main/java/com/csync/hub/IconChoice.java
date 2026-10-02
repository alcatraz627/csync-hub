package com.csync.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The launcher icons the owner can pick from, and the switch between them.
 *
 * Android has no "change my icon" call. Each icon is a separate launcher entry
 * (an activity alias in the manifest) and picking one enables it and disables the
 * rest. The launcher redraws when that happens, which can move the icon out of a
 * folder once.
 */
final class IconChoice {
    private IconChoice() {}

    static final String[] NAMES = {"Orbit", "Hub", "Signal", "Prism", "Terminal", "Mono",
        "Sync loop", "C link", "Pi chip", "Delta", "Venn lens", "Pi glyph", "Pulse ring", "Quad tiles", "S wire", "Tilt orbit"};
    /** Each icon's manifest alias, named com.csync.hub.Icon + key. */
    private static final String[] KEYS = {"Orbit", "Hub", "Signal", "Prism", "Terminal", "Mono",
        "Mark01", "Mark02", "Mark03", "Mark04", "Mark05", "Mark06", "Mark07", "Mark08", "Mark09", "Mark10"};
    static final String[] ABOUT = {
        "A ring with three satellites", "The mesh hub", "Arcs leaving a node",
        "A faceted shape", "A prompt mark", "One colour on black",
        "Two arrows chasing round the Pi", "A c with a device on each tip", "The Pi as a chip",
        "A trio of nodes", "Two circles sharing a lens", "The letter pi plugged into two devices",
        "A ring holding a heartbeat", "A grid of four tiles", "An s-shaped wire", "A tilted orbit with a moon"};
    static final int[] ART = {
        R.mipmap.ic_launcher_orbit, R.mipmap.ic_launcher, R.mipmap.ic_launcher_signal,
        R.mipmap.ic_launcher_prism, R.mipmap.ic_launcher_terminal, R.mipmap.ic_launcher_mono,
        R.mipmap.ic_launcher_mark01, R.mipmap.ic_launcher_mark02, R.mipmap.ic_launcher_mark03,
        R.mipmap.ic_launcher_mark04, R.mipmap.ic_launcher_mark05, R.mipmap.ic_launcher_mark06,
        R.mipmap.ic_launcher_mark07, R.mipmap.ic_launcher_mark08, R.mipmap.ic_launcher_mark09,
        R.mipmap.ic_launcher_mark10};

    private static ComponentName alias(Context c, String name) {
        int i = java.util.Arrays.asList(NAMES).indexOf(name);
        return new ComponentName(c, "com.csync.hub.Icon" + KEYS[Math.max(i, 0)]);
    }

    /** The icon the launcher shows now. Orbit until another is picked. */
    static String current(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String name : NAMES) {
            if (pm.getComponentEnabledSetting(alias(c, name))
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return name;
        }
        return NAMES[0];
    }

    /** Show this icon on the launcher and hide the others. The app keeps running. */
    static void use(Context c, String picked) {
        PackageManager pm = c.getPackageManager();
        // Enable the new one first, so the app is never without a launcher entry.
        pm.setComponentEnabledSetting(alias(c, picked),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (String name : NAMES) {
            if (name.equals(picked)) continue;
            pm.setComponentEnabledSetting(alias(c, name),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
