package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Notes written while the Pi cannot be reached. Each one waits here, on the phone, shown in the list as
 * "not synced yet", and is sent as soon as the Pi answers again. Nothing here ever raises an error to the
 * person: a note that cannot go yet just stays.
 *
 * An entry is {key, id, data, expected, at}: id is the note's id (empty for a new note), data is what the
 * save would have sent, expected is the revision the edit was made on. A second save of the same note
 * replaces its entry, so the Pi gets the latest words once.
 */
final class NoteOutbox {
    private static final String FILE = "notes_outbox", KEY = "ops";

    private NoteOutbox() { }

    /** Marks a list row that stands for a waiting entry rather than a note the Pi sent. */
    static final String WAITING = "waiting";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static JSONArray all(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY, "[]")); }
        catch (Exception unreadable) { return new JSONArray(); }
    }

    static boolean any(Context c) {
        return all(c).length() > 0;
    }

    /** The key a note's waiting edit goes by: one per existing note, a fresh one per new note. */
    static String keyFor(String noteId) {
        return noteId == null || noteId.isEmpty() ? "new-" + System.currentTimeMillis() : "edit-" + noteId;
    }

    /** Keep this entry, replacing any earlier one with the same key. */
    static synchronized void put(Context c, String key, String noteId, JSONObject data, int expected) {
        try {
            JSONArray kept = new JSONArray();
            JSONArray old = all(c);
            for (int i = 0; i < old.length(); i++) {
                JSONObject op = old.optJSONObject(i);
                if (op != null && !key.equals(op.optString("key"))) kept.put(op);
            }
            kept.put(new JSONObject().put("key", key).put("id", noteId == null ? "" : noteId).put("data", data)
                .put("expected", expected).put("at", System.currentTimeMillis() / 1000)
                // An entry is only ever kept after a send that did not get an answer, which the Pi may have taken anyway.
                .put("tried", noteId == null || noteId.isEmpty()));
            prefs(c).edit().putString(KEY, kept.toString()).apply();
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
    static synchronized void markTried(Context c, String key) {
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

    /** A waiting entry shaped like a note from the Pi's list, so the list can draw it with the rest. */
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
     * Send what is waiting, oldest first. Stops quietly at the first entry the Pi cannot be reached for.
     * A note that changed on the Pi in the meantime is not overwritten: the phone's words are kept as a
     * new note called "... (phone copy)". Blocks; call off the screen's thread. Returns how many went.
     */
    static int flush(Context c, MediaClient client) {
        JSONArray ops = all(c);
        int sent = 0;
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;
            String id = op.optString("id"), key = op.optString("key");
            JSONObject data = op.optJSONObject("data");
            if (data == null) { remove(c, key); continue; }
            try {
                if (id.isEmpty()) {
                    if (op.optBoolean("tried") && alreadyThere(client, op, data)) { remove(c, key); sent++; continue; }
                    markTried(c, key);
                    client.post("/v1/notes", data);
                } else client.put("/v1/notes/" + MediaClient.enc(id), new JSONObject(data.toString()).put("expectedRevision", op.optInt("expected")));
                remove(c, key);
                sent++;
            } catch (MediaClient.MediaException refused) {
                if (!"NOTE_CONFLICT".equals(refused.code) && !"NOTE_NOT_FOUND".equals(refused.code)) continue;
                try {
                    JSONObject copy = new JSONObject(data.toString());
                    copy.put("title", data.optString("title") + " (phone copy)");
                    client.post("/v1/notes", copy);
                    remove(c, key);
                    sent++;
                } catch (Exception later) { /* stays waiting */ }
            } catch (Exception unreached) {
                break;
            }
        }
        return sent;
    }
}
