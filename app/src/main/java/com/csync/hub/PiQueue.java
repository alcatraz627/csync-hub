package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything the phone wants to tell the Pi while it cannot be reached, in one line with the oldest first:
 * a note written offline, a pin or colour or move or reminder on a note, a timer started on the Mac, a
 * state change for a run. Each goes the next time the Pi answers, and the list shows a note with anything
 * waiting as "not synced yet". Nothing here raises anything to the person; an entry that cannot go yet
 * simply stays.
 *
 * Two shapes share the line. A plain write is {key, method, path, body, at}: it is safe to repeat, since a
 * note's meta is set to a value and a run is posted with its own id. A note save is {key, kind "note", id,
 * data, expected, at, tried}: id is the note's id (empty for a new note), data is what the save would have
 * sent and expected is the revision the edit was made on. A second entry with the same key replaces the
 * first, so only the latest word on a thing is sent.
 */
final class PiQueue {
    private static final String FILE = "pi_queue", KEY = "ops";
    // Where waiting note saves lived before they joined this line; read once and emptied.
    private static final String OLD_FILE = "notes_outbox";

    private PiQueue() { }

    /** Marks a list row that stands for a waiting note save rather than a note the Pi sent. */
    static final String WAITING = "waiting";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static JSONArray all(Context c) {
        adoptOldNotes(c);
        try { return new JSONArray(prefs(c).getString(KEY, "[]")); }
        catch (Exception unreadable) { return new JSONArray(); }
    }

    /** Move note saves left by the app's earlier separate outbox onto this line, then empty it. */
    private static synchronized void adoptOldNotes(Context c) {
        SharedPreferences old = c.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE);
        String had = old.getString(KEY, null);
        if (had == null) return;
        try {
            JSONArray fromOld = new JSONArray(had);
            JSONArray merged = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < fromOld.length(); i++) {
                JSONObject op = fromOld.optJSONObject(i);
                if (op != null) merged.put(op.put("kind", "note"));
            }
            JSONArray sorted = new JSONArray();
            java.util.List<JSONObject> list = new java.util.ArrayList<>();
            for (int i = 0; i < merged.length(); i++) if (merged.optJSONObject(i) != null) list.add(merged.optJSONObject(i));
            java.util.Collections.sort(list, (a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
            for (JSONObject op : list) sorted.put(op);
            prefs(c).edit().putString(KEY, sorted.toString()).commit();
        } catch (Exception unreadable) { /* an unreadable old entry is dropped with the file */ }
        old.edit().remove(KEY).commit();
    }

    static boolean any(Context c) { return all(c).length() > 0; }

    /** True when a write with this key is still waiting. */
    static boolean has(Context c, String key) {
        JSONArray ops = all(c);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op != null && key.equals(op.optString("key"))) return true;
        }
        return false;
    }

    /** The key a note's waiting edit goes by: one per existing note, a fresh one per new note. */
    static String keyFor(String noteId) {
        return noteId == null || noteId.isEmpty() ? "new-" + System.currentTimeMillis() : "edit-" + noteId;
    }

    /** True for an entry that carries a note's words rather than a plain write. */
    static boolean isNote(JSONObject op) {
        return "note".equals(op.optString("kind"));
    }

    /** The ids of existing notes that have anything waiting: their words, a pin, a colour, a move or a reminder. */
    static java.util.Set<String> noteIdsWaiting(Context c) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONArray ops = all(c);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;
            if (isNote(op)) {
                if (!op.optString("id").isEmpty()) ids.add(op.optString("id"));
                continue;
            }
            String[] parts = op.optString("path").split("/");
            if (parts.length >= 4 && parts[1].equals("v1") && parts[2].equals("notes")) ids.add(parts[3]);
        }
        return ids;
    }

    private static synchronized void keep(Context c, String key, JSONObject entry) {
        try {
            JSONArray kept = new JSONArray();
            JSONArray old = all(c);
            for (int i = 0; i < old.length(); i++) {
                JSONObject op = old.optJSONObject(i);
                if (op != null && !key.equals(op.optString("key"))) kept.put(op);
            }
            kept.put(entry);
            prefs(c).edit().putString(KEY, kept.toString()).apply();
        } catch (Exception impossible) { }
    }

    /** Keep this write, replacing an earlier one with the same key. */
    static void put(Context c, String key, String method, String path, JSONObject body) {
        try {
            keep(c, key, new JSONObject().put("key", key).put("method", method).put("path", path)
                .put("body", body == null ? JSONObject.NULL : body).put("at", System.currentTimeMillis() / 1000));
        } catch (org.json.JSONException impossible) { }
    }

    /** Keep a note's words, replacing any earlier entry with the same key. */
    static void putNote(Context c, String key, String noteId, JSONObject data, int expected) {
        try {
            keep(c, key, new JSONObject().put("key", key).put("kind", "note").put("id", noteId == null ? "" : noteId)
                .put("data", data).put("expected", expected).put("at", System.currentTimeMillis() / 1000)
                // An entry is only ever kept after a send that did not get an answer, which the Pi may have taken anyway.
                .put("tried", noteId == null || noteId.isEmpty()));
        } catch (org.json.JSONException impossible) { }
    }

    static synchronized void remove(Context c, String key) {
        if (key == null) return;
        JSONArray kept = new JSONArray();
        JSONArray old = all(c);
        boolean changed = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject op = old.optJSONObject(i);
            if (op != null && key.equals(op.optString("key"))) changed = true;
            else if (op != null) kept.put(op);
        }
        if (changed) prefs(c).edit().putString(KEY, kept.toString()).apply();
    }

    /** Note that a send of this entry has begun, so a later send first checks the Pi did not already take it. */
    private static synchronized void markTried(Context c, String key) {
        try {
            JSONArray ops = all(c);
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.optJSONObject(i);
                if (op != null && key.equals(op.optString("key"))) op.put("tried", true);
            }
            prefs(c).edit().putString(KEY, ops.toString()).apply();
        } catch (org.json.JSONException impossible) { }
    }

    /**
     * Whether the Pi already holds the note this entry would create. A send whose answer was lost can have
     * been kept by the Pi all the same, and sending it again would make two. The Pi gives a new note no
     * id of the phone's choosing, so the check is by what the note is: a note created since the entry was
     * queued, with the same title, whose words match. Called only for an entry that was tried before.
     */
    private static boolean alreadyThere(MediaClient client, JSONObject op, JSONObject data) {
        try {
            String title = data.optString("title"), body = data.optString("body").trim();
            String query = (title.isEmpty() ? body : title).trim();
            if (query.length() > 128) query = query.substring(0, 128);
            if (query.isEmpty()) return false;
            JSONArray found = client.get("/v1/notes?q=" + MediaClient.enc(query)).getJSONArray("notes");
            long since = op.optLong("at") - 5;
            for (int i = 0; i < found.length(); i++) {
                JSONObject n = found.optJSONObject(i);
                if (n == null || n.optLong("createdAt") < since) continue;
                if (!title.isEmpty() && !title.equals(n.optString("title"))) continue;
                JSONObject whole = client.get("/v1/notes/" + MediaClient.enc(n.optString("id"))).getJSONObject("note");
                if (body.equals(whole.optString("body").trim())) return true;
            }
        } catch (Exception cannotTell) {
            // If the Pi cannot be asked now, sending is the next thing that will find out.
        }
        return false;
    }

    /** True when the failure is the Pi not being reached, as opposed to the Pi answering no. */
    static boolean unreached(Exception error) {
        return error instanceof java.io.IOException;
    }

    /** A waiting note save shaped like a note from the Pi's list, so the list can draw it with the rest. */
    static JSONObject asNote(JSONObject op) {
        JSONObject data = op.optJSONObject("data");
        JSONObject note = new JSONObject();
        try {
            note.put("id", op.optString("id").isEmpty() ? op.optString("key") : op.optString("id"))
                .put("title", data == null ? "" : data.optString("title"))
                .put("body", data == null ? "" : data.optString("body"))
                .put("revision", op.optInt("expected"))
                .put("updatedAt", op.optLong("at"))
                .put(WAITING, op.optString("key"));
            if (data != null) {
                if (data.has("tags")) note.put("tags", data.opt("tags"));
                if (data.has("color")) note.put("color", data.opt("color"));
                if (data.has("pinned")) note.put("pinned", data.opt("pinned"));
            }
        } catch (org.json.JSONException impossible) { }
        return note;
    }

    /**
     * Send what is waiting, oldest first, stopping quietly at the first entry the Pi cannot be reached for.
     * A plain write the Pi answers with a no (it is gone, or no longer makes sense) is dropped, since sending
     * it again would only get the same answer. A note save the Pi refuses because the note changed in the
     * meantime is not overwritten: the phone's words are kept as a new note called "... (phone copy)".
     * Blocks; call off the screen's thread. Returns how many went.
     */
    static int flush(Context c, MediaClient client) {
        JSONArray ops = all(c);
        int sent = 0;
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;
            String key = op.optString("key");
            if (isNote(op)) {
                int result = flushNote(c, client, op);
                if (result < 0) break;
                sent += result;
                continue;
            }
            try {
                JSONObject body = op.optJSONObject("body");
                String method = op.optString("method"), path = op.optString("path");
                if (method.equals("POST")) client.post(path, body);
                else if (method.equals("PUT")) client.put(path, body);
                else if (method.equals("DELETE")) client.delete(path, body);
                else client.get(path);
                remove(c, key);
                sent++;
            } catch (MediaClient.MediaException refused) {
                remove(c, key);
            } catch (Exception unreached) {
                break;
            }
        }
        return sent;
    }

    /** Send one waiting note save. Returns 1 when it went, 0 when it stays, -1 when the Pi cannot be reached. */
    private static int flushNote(Context c, MediaClient client, JSONObject op) {
        String id = op.optString("id"), key = op.optString("key");
        JSONObject data = op.optJSONObject("data");
        if (data == null) { remove(c, key); return 0; }
        try {
            if (id.isEmpty()) {
                if (op.optBoolean("tried") && alreadyThere(client, op, data)) { remove(c, key); return 1; }
                markTried(c, key);
                client.post("/v1/notes", data);
            } else client.put("/v1/notes/" + MediaClient.enc(id), new JSONObject(data.toString()).put("expectedRevision", op.optInt("expected")));
            remove(c, key);
            return 1;
        } catch (MediaClient.MediaException refused) {
            if (!"NOTE_CONFLICT".equals(refused.code) && !"NOTE_NOT_FOUND".equals(refused.code)) return 0;
            try {
                JSONObject copy = new JSONObject(data.toString());
                copy.put("title", data.optString("title") + " (phone copy)");
                client.post("/v1/notes", copy);
                remove(c, key);
                return 1;
            } catch (Exception later) { return 0; }
        } catch (Exception unreached) {
            return -1;
        }
    }
}
