package com.csync.hub;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone's own list of the owner's devices, kept by name rather than by IP so
 * it survives a peer's tailnet address changing. Each entry is a name, a platform
 * label, whether it was last seen online, and when. The roster is seeded from a
 * reachable agent's /peers and refreshed by later scans; it persists so the Share
 * picker has something to show before the first scan of a session completes.
 */
public final class PeerStore {

    private static android.content.SharedPreferences p(Context c) {
        return c.getSharedPreferences("csync", Context.MODE_PRIVATE);
    }

    /** The persisted roster, newest scan wins. Empty array if nothing stored yet. */
    static JSONArray load(Context c) {
        try {
            return new JSONArray(p(c).getString("roster", "[]"));
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray roster) {
        p(c).edit().putString("roster", roster.toString()).apply();
    }

    /** The name of the device the owner picked as the send target, or "". */
    static String selected(Context c) { return p(c).getString("send_target", ""); }

    static void select(Context c, String name) {
        p(c).edit().putString("send_target", name).apply();
    }

    /**
     * Fold a fresh /peers result into the stored roster: known names are updated
     * in place (online, platform, lastSeen), new names are appended, and names the
     * scan did not mention are kept but marked offline. Returns the merged roster,
     * also persisted. This is what lets a downed peer show offline rather than
     * vanish, and a never-scanned peer still appear from a prior session.
     */
    static JSONArray mergeScan(Context c, JSONArray scanned) {
        JSONArray roster = load(c);
        long now = System.currentTimeMillis();
        java.util.Map<String, JSONObject> byName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < roster.length(); i++) {
            JSONObject o = roster.optJSONObject(i);
            if (o == null) continue;
            o.remove("online"); // recomputed below from this scan
            try { o.put("online", false); } catch (Throwable ignore) {}
            byName.put(o.optString("name"), o);
        }
        for (int i = 0; i < scanned.length(); i++) {
            JSONObject s = scanned.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("name");
            if (name.isEmpty()) continue;
            boolean online = s.optBoolean("reachable", false) || s.optBoolean("online", false);
            JSONObject o = byName.get(name);
            if (o == null) o = new JSONObject();
            try {
                o.put("name", name);
                o.put("platform", s.optString("platform", o.optString("platform", "")));
                o.put("online", online);
                if (online) o.put("lastSeen", now);
            } catch (Throwable ignore) {}
            byName.put(name, o);
        }
        JSONArray merged = new JSONArray();
        for (JSONObject o : byName.values()) merged.put(o);
        save(c, merged);
        return merged;
    }

    private PeerStore() {}
}
