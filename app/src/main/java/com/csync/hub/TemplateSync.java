package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the phone's quick-timer tiles and the Pi's shared template list the same list. The tiles stay on
 * the phone as the copy that works with no Pi; this reads the shared list when the Pi answers and sends the
 * phone's own changes through {@link PiQueue}, so a tile made offline reaches the Mac later.
 *
 * The four starter tiles are sent once, under fixed ids, so sending them twice never doubles them. After
 * that the shared list wins: a template removed on the Mac leaves the phone's tiles too.
 */
final class TemplateSync {
    private static final String FILE = "templatesync";

    private TemplateSync() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The shared id for a tile: a starter tile has a fixed one, others are named for when they were made. */
    static String tidFor(long tileId) {
        return tileId >= 1 && tileId <= 4 ? "t-seed-" + tileId : "t-ph-" + tileId;
    }

    /** A stable local id for a template that came from the Pi. */
    static long tileIdFor(String tid) {
        if (tid.matches("t-seed-[1-4]")) return Long.parseLong(tid.substring(7));
        if (tid.matches("t-ph-\\d+")) {
            try { return Long.parseLong(tid.substring(5)); } catch (NumberFormatException ignored) { }
        }
        return 1_000_000_000L + (tid.hashCode() & 0x7fffffff);
    }

    private static JSONObject body(Timers.Tile q) throws org.json.JSONException {
        String label = q.label == null || q.label.trim().isEmpty() ? "Timer" : q.label.trim();
        return new JSONObject().put("label", label.length() > 80 ? label.substring(0, 80) : label)
            .put("minutes", Math.max(1, q.minutes)).put("color", RunSync.toMac(q.colour));
    }

    /** A new or changed tile goes to the shared list. A create names its own id, so a repeat is harmless. */
    static void pushSave(Context c, Timers.Tile q, boolean created) {
        try {
            String tid = q.tid.isEmpty() ? tidFor(q.id) : q.tid;
            if (created) PiQueue.put(c, "tpl-" + tid, "POST", "/v1/templates", body(q).put("id", tid));
            else PiQueue.put(c, "tpl-" + tid, "PUT", "/v1/templates/" + MediaClient.enc(tid), body(q));
        } catch (org.json.JSONException impossible) { }
        RunSync.flushSoon(c);
    }

    static void pushDelete(Context c, Timers.Tile q) {
        String tid = q.tid.isEmpty() ? tidFor(q.id) : q.tid;
        PiQueue.put(c, "tpl-" + tid, "DELETE", "/v1/templates/" + MediaClient.enc(tid), new JSONObject());
        RunSync.flushSoon(c);
    }

    private static final AtomicBoolean busy = new AtomicBoolean();

    static void refreshAsync(Context c) {
        Context app = c.getApplicationContext();
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { refresh(app); } finally { busy.set(false); }
        }, "csync-templates").start();
    }

    /** Read the shared list and bring the tiles into line with it. Blocks; call off the screen's thread. */
    static void refresh(Context c) {
        MediaClient client = RunSync.client(c);
        if (client == null) return;
        try {
            // Anything the phone changed goes first, so reading back never undoes it.
            if (PiQueue.any(c)) PiQueue.flush(c, client);
            if (!prefs(c).getBoolean("seeded", false)) {
                for (Timers.Tile q : Timers.tiles(c)) {
                    String tid = q.tid.isEmpty() ? tidFor(q.id) : q.tid;
                    try { client.post("/v1/templates", body(q).put("id", tid)); }
                    catch (MediaClient.MediaException refused) { /* this one will not be taken; the rest still go */ }
                }
                prefs(c).edit().putBoolean("seeded", true).apply();
            }
            JSONArray shared = client.get("/v1/templates").optJSONArray("templates");
            if (shared == null) return;
            List<Timers.Tile> now = Timers.tiles(c), next = new ArrayList<>();
            java.util.Set<String> inShared = new java.util.HashSet<>();
            for (int i = 0; i < shared.length(); i++) {
                JSONObject t = shared.getJSONObject(i);
                String tid = t.optString("id");
                inShared.add(tid);
                Timers.Tile have = null;
                for (Timers.Tile q : now) if (tid.equals(q.tid.isEmpty() ? tidFor(q.id) : q.tid)) have = q;
                String colour = RunSync.toPhone(t.optString("color"));
                int minutes = Math.max(1, Math.min(180, t.optInt("minutes", 5)));
                next.add(new Timers.Tile(have == null ? tileIdFor(tid) : have.id, t.optString("label", "Timer"), minutes, colour, tid));
            }
            // A tile the phone made that is still waiting to be sent stays, so it is not lost between the two.
            for (Timers.Tile q : now) {
                String tid = q.tid.isEmpty() ? tidFor(q.id) : q.tid;
                if (!inShared.contains(tid) && PiQueue.has(c, "tpl-" + tid)) next.add(q);
            }
            if (!same(now, next)) Timers.saveTilesQuiet(c, next);
        } catch (Exception calm) {
            // Not reachable: the tiles on the phone are the copy to use.
        }
    }

    private static boolean same(List<Timers.Tile> a, List<Timers.Tile> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Timers.Tile x = a.get(i), y = b.get(i);
            if (x.id != y.id || x.minutes != y.minutes || !x.label.equals(y.label) || !x.colour.equals(y.colour)) return false;
        }
        return true;
    }
}
