package com.csync.hub;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * What is slowing this phone down, found on request. Some of the worst culprits are not busy apps
 * at all but permissions: an accessibility service sees every tap before the app does, a
 * notification listener wakes for every notification, an overlay is drawn over every frame. So
 * the report reads those through Shizuku alongside memory, heat and dropped frames, groups them
 * by app, and ranks each app by how much it is likely to cost.
 */
final class Culprits {

    /** One power an app holds, with the shell command that takes it away when there is one. */
    static final class Power {
        final String what, undo, undoLabel;
        final int weight;
        Power(String what, int weight, String undo, String undoLabel) {
            this.what = what; this.weight = weight; this.undo = undo; this.undoLabel = undoLabel;
        }
    }

    /** A row of the report: an app (or the phone itself) and everything found about it. */
    static final class Finding {
        final String key, name, why;
        final int icon;
        final List<Power> powers = new ArrayList<>();
        int score;
        long pssMb = -1;
        String settings;   // an Intent action that opens the relevant setting, or null
        Finding(String key, String name, int icon, String why) {
            this.key = key; this.name = name; this.icon = icon; this.why = why;
        }
        /** The app's package, or null for a row about the phone as a whole. */
        String pkg() { return key.startsWith("@") ? null : key; }
        Kit.Status status() { return score >= 80 ? Kit.Status.BAD : score >= 30 ? Kit.Status.WARN : Kit.Status.IDLE; }
        String level() { return score >= 80 ? "Heavy" : score >= 30 ? "Some" : "Light"; }
        String summary() {
            if (powers.isEmpty() || pkg() == null) return why;
            StringBuilder s = new StringBuilder();
            for (Power p : powers) s.append(s.length() == 0 ? "" : ", ").append(p.what);
            String text = s.substring(0, 1).toUpperCase() + s.substring(1);
            return pssMb > 0 ? text + ", holds " + pssMb + " MB" : text;
        }
    }

    static final class Report {
        final List<Finding> findings = new ArrayList<>();
        int systemParts;   // phone makers' own parts with these powers, left out of the ranking
        String error;
        long at = System.currentTimeMillis();
    }

    private Culprits() {}

    // Parts that ship with the phone. They hold these powers by design and cannot be turned off
    // from here without breaking the phone, so they are counted but not ranked.
    private static boolean isSystem(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.") || pkg.startsWith("com.google.")
            || pkg.startsWith("com.nothing.") || pkg.startsWith("com.qualcomm.") || pkg.startsWith("vendor.")
            || pkg.startsWith("com.qti.");
    }

    private static final String SCRIPT =
        "echo __A__; settings get secure enabled_accessibility_services;"
        + " echo __N__; settings get secure enabled_notification_listeners;"
        + " echo __O__; cmd appops query-op SYSTEM_ALERT_WINDOW allow;"
        + " echo __B__; dumpsys deviceidle whitelist;"
        + " echo __T__; dumpsys thermalservice | grep -m 1 'Thermal Status';"
        + " h=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1);"
        + " h=${h%%/*}; echo __G__ $h; dumpsys gfxinfo $h | grep -E 'Total frames rendered|Janky frames:|High input latency';"
        + " echo __P__; dumpsys meminfo | grep -m 1 -A 30 'Total PSS by process';"
        + " echo __END__";

    /** Take every reading and rank what it found. Slow (a few seconds): call it off the main thread. */
    static Report gather(Context c) {
        Report report = new Report();
        String raw = shell(SCRIPT);
        if (raw == null || !raw.contains("__END__")) {
            report.error = raw == null ? "Shizuku did not answer" : raw.trim();
            return report;
        }
        Map<String, Finding> apps = new LinkedHashMap<>();
        Set<String> system = new LinkedHashSet<>();

        // Accessibility: each service sees every input event, so the phone waits on it.
        String a11y = section(raw, "__A__").trim();
        for (String component : splitComponents(a11y)) {
            String pkg = component.substring(0, component.indexOf('/'));
            if (isSystem(pkg)) { system.add(pkg); continue; }
            String rest = without(a11y, component);
            String undo = rest.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + rest + "'";
            app(c, apps, pkg).powers.add(new Power("sees every tap and screen change", 100, undo, "Turn off its accessibility service"));
            app(c, apps, pkg).settings = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS;
        }
        // Notification listeners wake for every notification any app posts.
        for (String component : splitComponents(section(raw, "__N__").trim())) {
            String pkg = component.substring(0, component.indexOf('/'));
            if (isSystem(pkg)) { system.add(pkg); continue; }
            Finding f = app(c, apps, pkg);
            if (hasPower(f, "reads every notification")) continue;
            f.powers.add(new Power("reads every notification", 30, "cmd notification disallow_listener " + component,
                "Stop it reading notifications"));
            if (f.settings == null) f.settings = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
        }
        // Drawing over other apps: an overlay left on screen is composed into every frame.
        for (String line : section(raw, "__O__").split("\n")) {
            String pkg = line.trim();
            if (pkg.isEmpty() || pkg.contains(" ")) continue;
            if (isSystem(pkg)) { system.add(pkg); continue; }
            Finding f = app(c, apps, pkg);
            if (hasPower(f, "draws over other apps")) continue;
            f.powers.add(new Power("draws over other apps", 25,"appops set " + pkg + " SYSTEM_ALERT_WINDOW deny",
                "Stop it drawing over apps"));
            if (f.settings == null) f.settings = android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
        }
        // Battery exempt: the app keeps running while the phone sleeps. Only the owner's own
        // choices ("user") are listed; the system's are part of the phone.
        for (String line : section(raw, "__B__").split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length < 2 || !f[0].equals("user")) continue;
            String pkg = f[1];
            if (isSystem(pkg)) continue;
            Finding app = app(c, apps, pkg);
            if (hasPower(app, "runs while the phone sleeps")) continue;
            app.powers.add(new Power("runs while the phone sleeps", 20, "dumpsys deviceidle whitelist -" + pkg,
                "Let the phone pause it when asleep"));
            if (app.settings == null) app.settings = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS;
        }
        // Memory, from PSS: what each app really holds, shared pages split fairly.
        for (String line : section(raw, "__P__").split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([0-9,]+)K: (\\S+) \\(pid").matcher(line);
            if (!m.find()) continue;
            String pkg = m.group(2);
            int colon = pkg.indexOf(':');
            if (colon > 0) pkg = pkg.substring(0, colon);
            if (!pkg.contains(".") || isSystem(pkg) || pkg.startsWith("com.csync.")) continue;
            long mb = Long.parseLong(m.group(1).replace(",", "")) / 1024;
            Finding f = apps.get(pkg);
            if (f == null && mb < 300) continue;
            f = app(c, apps, pkg);
            f.pssMb = Math.max(f.pssMb, mb);
        }
        for (Finding f : apps.values()) {
            int score = 0;
            for (Power p : f.powers) score += p.weight;
            if (f.pssMb > 0) score += (int) Math.min(40, f.pssMb / 10);
            f.score = score;
            report.findings.add(f);
        }

        // Heat: a warm phone slows its own processor to cool down.
        String thermal = matchOne(section(raw, "__T__"), "Thermal Status:\\s*(\\d+)");
        if (!thermal.isEmpty() && Integer.parseInt(thermal) > 0) {
            int level = Integer.parseInt(thermal);
            String[] names = {"cool", "a little warm", "warm", "hot", "very hot", "near shutdown", "shutting down"};
            Finding heat = new Finding("@heat", "The phone is " + names[Math.min(level, 6)], R.drawable.csi_temp,
                "It slows its own processor until it cools. Charging, games and the camera warm it most.");
            heat.score = level * 30;
            report.findings.add(heat);
        }
        // Dropped frames on the home screen: the plainest measure of lag a person feels.
        String gfx = section(raw, "__G__");
        String home = gfx.split("\n")[0].trim();
        long frames = parseLong(matchOne(gfx, "Total frames rendered:\\s*(\\d+)"));
        long janky = parseLong(matchOne(gfx, "Janky frames:\\s*(\\d+)"));
        long late = parseLong(matchOne(gfx, "High input latency:\\s*(\\d+)"));
        if (frames >= 60) {
            int jankPct = (int) Math.round(janky * 100.0 / frames);
            int latePct = (int) Math.round(Math.min(late, frames) * 100.0 / frames);
            Finding lag = new Finding("@jank:" + home, "Home screen frames", R.drawable.csi_speed,
                jankPct + "% of " + frames + " frames drawn late, " + latePct + "% waited on input. "
                    + "Counted since it was last reset.");
            lag.score = jankPct * 3 + (latePct >= 50 ? 30 : 0);
            lag.powers.add(new Power(jankPct + "% of frames late, " + latePct + "% waited on input", 0,
                "dumpsys gfxinfo " + home + " reset", "Start counting again"));
            report.findings.add(lag);
        }
        report.findings.sort((x, y) -> Integer.compare(y.score, x.score));
        report.systemParts = system.size();
        return report;
    }

    /** Why a power slows the phone, in a sentence for the drawer that offers its fix. */
    static String reason(Finding f) {
        if (f.pkg() == null) return f.why;
        StringBuilder s = new StringBuilder(f.summary()).append(".");
        for (Power p : f.powers) {
            if (p.what.startsWith("sees every tap"))
                s.append(" An accessibility service is handed every tap and screen change before the app on screen,"
                    + " so a slow one makes the whole phone feel late.");
            else if (p.what.startsWith("reads every notification"))
                s.append(" It wakes for every notification any app posts.");
            else if (p.what.startsWith("draws over"))
                s.append(" Anything it leaves on screen is drawn into every frame.");
            else if (p.what.startsWith("runs while"))
                s.append(" Android lets it keep working while the phone sleeps, which costs battery and heat.");
        }
        if (f.pssMb > 0) s.append(" Stopping it frees its memory until it is opened again.");
        return s.toString();
    }

    /** Run one fix through Shizuku; true when the command succeeded. */
    static boolean apply(String command) {
        String out = shell(command + " && echo __OK__");
        return out != null && out.contains("__OK__");
    }

    /** The report as the Pi agent reads it. */
    static JSONObject toJson(Report r) {
        JSONObject o = new JSONObject();
        try {
            o.put("at", r.at);
            if (r.error != null) o.put("error", r.error);
            o.put("system_parts_left_out", r.systemParts);
            JSONArray rows = new JSONArray();
            for (Finding f : r.findings) {
                JSONObject row = new JSONObject();
                row.put("name", f.name);
                if (f.pkg() != null) row.put("package", f.pkg());
                row.put("level", f.level());
                row.put("score", f.score);
                row.put("why", f.summary());
                if (f.pssMb > 0) row.put("pss_mb", f.pssMb);
                JSONArray fixes = new JSONArray();
                for (Power p : f.powers) if (p.undo != null) fixes.put(p.undoLabel);
                if (f.pkg() != null && f.pssMb > 0) fixes.put("Stop the app");
                row.put("fixes_on_phone", fixes);
                rows.put(row);
            }
            o.put("findings", rows);
        } catch (Exception ignored) { }
        return o;
    }

    // ---- helpers ----

    private static Finding app(Context c, Map<String, Finding> apps, String pkg) {
        Finding f = apps.get(pkg);
        if (f == null) {
            f = new Finding(pkg, label(c, pkg), R.drawable.csi_device, "");
            apps.put(pkg, f);
        }
        return f;
    }

    private static boolean hasPower(Finding f, String what) {
        for (Power p : f.powers) if (p.what.equals(what)) return true;
        return false;
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            String last = pkg.substring(pkg.lastIndexOf('.') + 1);
            return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        }
    }

    private static List<String> splitComponents(String list) {
        List<String> out = new ArrayList<>();
        if (list.isEmpty() || list.equals("null")) return out;
        for (String part : list.split(":")) if (part.contains("/")) out.add(part.trim());
        return out;
    }

    private static String without(String list, String component) {
        StringBuilder rest = new StringBuilder();
        for (String part : splitComponents(list))
            if (!part.equals(component)) rest.append(rest.length() == 0 ? "" : ":").append(part);
        return rest.toString();
    }

    private static String section(String raw, String marker) {
        int at = raw.indexOf(marker);
        if (at < 0) return "";
        int from = at + marker.length();
        int next = raw.indexOf("\n__", from);
        return raw.substring(from, next < 0 ? raw.length() : next).replaceFirst("^\\s*\\n", "");
    }

    private static String matchOne(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String shell(String script) {
        BufferedReader r = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", script}, null, null);
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }
}
