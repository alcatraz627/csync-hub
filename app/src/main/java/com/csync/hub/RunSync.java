package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone's side of timers and reminders that run on one chosen device, the phone or the Mac.
 *
 * A run is started on ONE device. This phone keeps the Pi told about what it runs, so the Mac can see it, and
 * picks up the runs the Mac aimed at it. A run aimed at the Mac is written to the Pi and waits there until the
 * Mac answers; the phone shows it as "Waiting for Mac" at once and only ever says what it last heard. Nothing
 * here raises an error: when the Pi cannot be reached, writes wait in {@link PiQueue} and the last thing heard
 * about the Mac stays on screen with how old it is.
 */
final class RunSync {
    private static final String FILE = "runsync";
    static final long TIMER_GRACE_MS = 2 * 60_000L, REMINDER_GRACE_MS = 60 * 60_000L;
    /** A Mac run not heard about for this long is shown as old; past the long one it is dimmed too. */
    static final long STALE_AFTER_S = 120, DIM_AFTER_S = 900;

    private RunSync() { }

    // ---- listening ----

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    private static void changed() {
        main.post(() -> { for (Runnable r : listeners) r.run(); });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static MediaClient client(Context c) {
        String host = Prefs.assistIp(c), token = Prefs.token(c);
        return host.isEmpty() || token.isEmpty() ? null : new MediaClient(host, token);
    }

    // ---- where the next run goes ----

    /** "phone" or "mac": the device a new timer or reminder will run on, kept between visits. */
    static String runOn(Context c) {
        return "mac".equals(prefs(c).getString("run_on", "phone")) ? "mac" : "phone";
    }

    static void setRunOn(Context c, String device) {
        prefs(c).edit().putString("run_on", "mac".equals(device) ? "mac" : "phone").apply();
    }

    // ---- small helpers ----

    static String newRid() {
        return "r-" + System.currentTimeMillis() / 1000 + "-" + String.format(java.util.Locale.ROOT, "%04x",
            new java.util.Random().nextInt(0x10000));
    }

    /** The phone's seven colour names to the Mac's eight, which is what the Pi stores. */
    static String toMac(String phone) {
        switch (phone == null ? "" : phone) {
            case "teal": return "teal";
            case "violet": return "purple";
            case "rust": return "red";
            case "blue": return "blue";
            case "leaf": return "green";
            case "rose": return "pink";
            default: return "orange";
        }
    }

    /** The Mac's eight colour names to the nearest of the phone's seven. */
    static String toPhone(String mac) {
        switch (mac == null ? "" : mac) {
            case "teal": return "teal";
            case "purple": return "violet";
            case "red": return "rust";
            case "blue": return "blue";
            case "green": return "leaf";
            case "pink": return "rose";
            default: return "coral";
        }
    }

    private static String labelOf(String label) {
        String l = label == null ? "" : label.trim();
        if (l.isEmpty()) l = "Timer";
        return l.length() > 80 ? l.substring(0, 80) : l;
    }

    // ---- runs on this phone ----

    /** A timer started here writes its request and its running state, so the Mac can see it. */
    static void announce(Context c, Timers.Timer t) {
        if (t.rid.isEmpty()) return;
        try {
            JSONObject request = new JSONObject().put("id", t.rid).put("kind", "timer").put("label", labelOf(t.label))
                .put("color", toMac(t.colour)).put("total_ms", Math.max(1000, t.total)).put("fire_at", t.end)
                .put("target", "phone").put("template", JSONObject.NULL).put("note", JSONObject.NULL);
            PiQueue.put(c, "run-" + t.rid, "POST", "/v1/runs", request);
        } catch (org.json.JSONException impossible) { }
        report(c, t, "running");
    }

    /** What this phone's run is doing now; only the latest word per run is kept waiting. */
    static void report(Context c, Timers.Timer t, String status) {
        if (t.rid.isEmpty()) return;
        try {
            JSONObject body = new JSONObject().put("status", status);
            if (status.equals("running")) body.put("end_ms", t.end);
            else if (status.equals("paused")) body.put("remain_ms", t.remain);
            PiQueue.put(c, "state-" + t.rid, "PUT", "/v1/runs/" + MediaClient.enc(t.rid) + "/state", body);
        } catch (org.json.JSONException impossible) { }
        flushSoon(c);
    }

    /** Say a run the Mac aimed here was turned away or came too late. */
    static void reportRaw(Context c, String rid, String status, String note) {
        try {
            JSONObject body = new JSONObject().put("status", status).put("note", note);
            PiQueue.put(c, "state-" + rid, "PUT", "/v1/runs/" + MediaClient.enc(rid) + "/state", body);
        } catch (org.json.JSONException impossible) { }
    }

    // ---- runs aimed at the Mac ----

    /** Ask the Mac to run a timer or reminder. Shown as waiting at once; never blocks and never complains. */
    static String sendToMac(Context c, String kind, String label, String phoneColour, long totalMs, long fireAt, String template) {
        String rid = newRid();
        long now = System.currentTimeMillis();
        JSONObject request = new JSONObject();
        try {
            request.put("id", rid).put("kind", kind).put("label", labelOf(label)).put("color", toMac(phoneColour))
                .put("total_ms", totalMs).put("fire_at", fireAt).put("target", "mac")
                .put("template", template == null ? JSONObject.NULL : template).put("note", JSONObject.NULL);
            PiQueue.put(c, "run-" + rid, "POST", "/v1/runs", request);
            JSONObject local = new JSONObject().put("id", rid).put("kind", kind).put("label", labelOf(label))
                .put("color", toMac(phoneColour)).put("totalMs", totalMs).put("fireAt", fireAt).put("target", "mac")
                .put("from", "phone").put("createdAt", now).put("status", "waiting").put("waitingFor", "mac");
            JSONArray rows = localRows(c);
            rows.put(local);
            prefs(c).edit().putString("local", rows.toString()).apply();
        } catch (org.json.JSONException impossible) { }
        changed();
        flushSoon(c);
        return rid;
    }

    /** Ask for a run to stop; the one it runs on stops it on its next look. */
    static void stop(Context c, String rid) {
        PiQueue.put(c, "stop-" + rid, "POST", "/v1/runs/" + MediaClient.enc(rid) + "/stop", new JSONObject());
        flushSoon(c);
    }

    private static JSONArray localRows(Context c) {
        try { return new JSONArray(prefs(c).getString("local", "[]")); }
        catch (Exception unreadable) { return new JSONArray(); }
    }

    // ---- reading what the Pi knows ----

    static final class Row {
        String id = "", kind = "timer", label = "", target = "", status = "waiting", stateNote = "";
        long totalMs, fireAt, createdAt, endMs, remainMs;
        long staleSeconds = -1;
        boolean stopRequested, fromMacFile;
    }

    private static Row rowOf(JSONObject o) {
        Row r = new Row();
        r.id = o.optString("id");
        r.kind = o.optString("kind", "timer");
        r.label = o.optString("label");
        r.target = o.optString("target");
        r.status = o.optString("status", "waiting");
        r.stateNote = o.optString("stateNote");
        r.totalMs = o.optLong("totalMs");
        r.fireAt = o.optLong("fireAt");
        r.createdAt = o.optLong("createdAt");
        r.endMs = o.optLong("endMs");
        r.remainMs = o.optLong("remainMs");
        r.stopRequested = o.optBoolean("stopRequested");
        r.staleSeconds = o.isNull("staleSeconds") ? -1 : o.optLong("staleSeconds", -1);
        return r;
    }

    /** True for a run that has ended and needs no place on screen. */
    static boolean over(String status) {
        return status.equals("done") || status.equals("stopped");
    }

    /**
     * What the Mac is running or has been asked to run, from the last thing heard. Seconds since it was
     * last heard are carried forward from the moment the answer came, so an old answer reads as old.
     */
    static List<Row> macRows(Context c) {
        List<Row> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        JSONObject cache = null;
        long fetched = prefs(c).getLong("fetched", 0);
        try { cache = new JSONObject(prefs(c).getString("cache", "{}")); } catch (Exception ignored) { }
        long age = fetched > 0 ? (now - fetched) / 1000 : 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray runs = cache == null ? null : cache.optJSONArray("runs");
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject o = runs.optJSONObject(i);
            if (o == null || !"mac".equals(o.optString("target"))) continue;
            Row r = rowOf(o);
            seen.add(r.id);
            if (r.staleSeconds >= 0) r.staleSeconds += age;
            if (keep(r, now)) out.add(r);
        }
        JSONArray local = localRows(c);
        for (int i = 0; i < local.length(); i++) {
            JSONObject o = local.optJSONObject(i);
            if (o == null || seen.contains(o.optString("id"))) continue;
            Row r = rowOf(o);
            seen.add(r.id);
            if (keep(r, now)) out.add(r);
        }
        JSONObject mac = cache == null || cache.optJSONObject("devices") == null ? null : cache.optJSONObject("devices").optJSONObject("mac");
        JSONArray running = mac == null ? null : mac.optJSONArray("running");
        for (int i = 0; running != null && i < running.length(); i++) {
            JSONObject o = running.optJSONObject(i);
            if (o == null || seen.contains(o.optString("run"))) continue;
            Row r = new Row();
            r.id = o.optString("run");
            r.label = o.optString("label");
            r.target = "mac";
            r.status = "running";
            r.endMs = o.optLong("endMs");
            r.fromMacFile = true;
            r.staleSeconds = mac.isNull("staleSeconds") ? -1 : mac.optLong("staleSeconds", -1) + age;
            out.add(r);
        }
        out.sort((a, b) -> Long.compare(a.endMs > 0 ? a.endMs : a.fireAt, b.endMs > 0 ? b.endMs : b.fireAt));
        return out;
    }

    private static boolean keep(Row r, long now) {
        if (over(r.status)) return false;
        // A run that was turned away or came too late stays a little while, as one calm line.
        if (r.status.equals("rejected") || r.status.equals("missed")) return now - Math.max(r.createdAt, r.fireAt) < 30 * 60_000L;
        return true;
    }

    /** Read what the Pi knows and act on what the Mac asked of this phone. Blocks; call off the screen's thread. */
    static void refresh(Context c) {
        MediaClient client = client(c);
        if (client == null) return;
        try {
            if (PiQueue.any(c)) PiQueue.flush(c, client);
            JSONObject answer = client.get("/v1/runs");
            JSONArray runs = answer.optJSONArray("runs");
            java.util.Set<String> known = new java.util.HashSet<>();
            for (int i = 0; runs != null && i < runs.length(); i++) known.add(runs.getJSONObject(i).optString("id"));
            // A local waiting row goes once the Pi lists the run (or once it is no longer waiting to be sent).
            JSONArray local = localRows(c), keep = new JSONArray();
            for (int i = 0; i < local.length(); i++) {
                JSONObject o = local.optJSONObject(i);
                if (o != null && !known.contains(o.optString("id")) && PiQueue.has(c, "run-" + o.optString("id"))) keep.put(o);
            }
            prefs(c).edit().putString("cache", answer.toString()).putLong("fetched", System.currentTimeMillis())
                .putString("local", keep.toString()).apply();
            pickUp(c, runs);
            // What picking up decided (started, turned away, too late) goes to the Pi now, not on the next look.
            if (PiQueue.any(c)) PiQueue.flush(c, client);
            tellDevice(c, client);
            changed();
        } catch (Exception calm) {
            // Not reachable, or the Pi answered something unexpected: keep what is on screen, say nothing.
        }
    }

    private static final AtomicBoolean refreshing = new AtomicBoolean();

    /** {@link #refresh} on its own thread; a second call while one runs is dropped. */
    static void refreshAsync(Context c) {
        Context app = c.getApplicationContext();
        if (!refreshing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { refresh(app); } finally { refreshing.set(false); }
        }, "csync-runs").start();
    }

    private static final AtomicBoolean flushing = new AtomicBoolean(), dirty = new AtomicBoolean();

    /** Send what is waiting, soon and off the caller's thread. Changes made while it runs are caught by a second pass. */
    static void flushSoon(Context c) {
        Context app = c.getApplicationContext();
        dirty.set(true);
        if (!flushing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                while (dirty.getAndSet(false)) {
                    MediaClient client = client(app);
                    if (client == null) break;
                    if (PiQueue.any(app)) PiQueue.flush(app, client);
                    tellDevice(app, client);
                    changed();
                }
            } finally { flushing.set(false); }
        }, "csync-runs-flush").start();
    }

    // ---- what the Mac asked of this phone ----

    private static void pickUp(Context c, JSONArray runs) throws org.json.JSONException {
        long now = System.currentTimeMillis();
        List<Timers.Timer> mine = Timers.all(c);
        SharedPreferences.Editor remember = prefs(c).edit();
        JSONArray adopted;
        try { adopted = new JSONArray(prefs(c).getString("adopted", "[]")); } catch (Exception e) { adopted = new JSONArray(); }
        java.util.Set<String> done = new java.util.HashSet<>();
        for (int i = 0; i < adopted.length(); i++) done.add(adopted.optString(i));
        boolean learned = false;
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject o = runs.getJSONObject(i);
            if (!"phone".equals(o.optString("target"))) continue;
            String rid = o.optString("id");
            Timers.Timer have = null;
            for (Timers.Timer t : mine) if (rid.equals(t.rid)) have = t;
            if (have != null) {
                // The Mac asked for it to stop: stop it here, which tells the Pi.
                if (o.optBoolean("stopRequested") && !have.done) Timers.stop(c, have.id);
                continue;
            }
            if (!"waiting".equals(o.optString("status")) || done.contains(rid) || !"mac".equals(o.optString("from"))) continue;
            long fireAt = o.optLong("fireAt");
            String label = o.optString("label");
            done.add(rid);
            learned = true;
            if ("reminder".equals(o.optString("kind"))) {
                if (now - fireAt <= REMINDER_GRACE_MS) {
                    Reminders.add(c, label, Math.max(fireAt, now + 1000));
                    reportRaw(c, rid, "running", "");
                } else reportRaw(c, rid, "missed", "Arrived after its time");
                continue;
            }
            if (now - fireAt > TIMER_GRACE_MS) { reportRaw(c, rid, "missed", "Arrived after its time"); continue; }
            Timers.Timer started = Timers.startMs(c, Math.max(500L, fireAt - now), label, toPhone(o.optString("color")), rid);
            if (started == null) reportRaw(c, rid, "rejected", Timers.MAX + " timers already running");
        }
        if (learned) {
            JSONArray all = new JSONArray();
            for (String id : done) all.put(id);
            // Only the latest few hundred are worth remembering.
            while (all.length() > 300) all.remove(0);
            remember.putString("adopted", all.toString()).apply();
        }
    }

    // ---- telling the Pi what this phone runs ----

    private static void tellDevice(Context c, MediaClient client) {
        try {
            JSONArray running = new JSONArray();
            long now = System.currentTimeMillis();
            for (Timers.Timer t : Timers.all(c)) {
                if (t.done || t.rid.isEmpty()) continue;
                running.put(new JSONObject().put("run", t.rid).put("label", labelOf(t.label))
                    .put("end_ms", t.paused ? now + t.remain : t.end));
                if (running.length() >= 20) break;
            }
            String text = running.toString();
            long last = prefs(c).getLong("told_at", 0);
            if (text.equals(prefs(c).getString("told", "[]")) && now - last < 9 * 60_000L) return;
            // Nothing running and nothing ever said: no need to write an empty file.
            if (running.length() == 0 && last == 0) return;
            client.put("/v1/devices/phone", new JSONObject().put("running", running));
            prefs(c).edit().putString("told", text).putLong("told_at", now).apply();
        } catch (Exception calm) { /* said again on the next pass */ }
    }
}
