package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small writes to the Pi that wait on the phone while it cannot be reached: a reminder set on a note, a
 * timer started on the Mac, a state change for a run. Each goes the next time the Pi answers. Nothing here
 * raises anything to the person; an entry that cannot go yet simply stays.
 *
 * An entry is {key, method, path, body, at}. A second entry with the same key replaces the first, so only
 * the latest word on a thing is sent. Every write queued here is safe to repeat: a note's meta is set to a
 * value, and a run is posted with its own id so the Pi answers with the run it already holds.
 */
final class PiQueue {
    private static final String FILE = "pi_queue", KEY = "ops";

    private PiQueue() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static JSONArray all(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY, "[]")); }
        catch (Exception unreadable) { return new JSONArray(); }
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

    /** Keep this write, replacing an earlier one with the same key. */
    static synchronized void put(Context c, String key, String method, String path, JSONObject body) {
        try {
            JSONArray kept = new JSONArray();
            JSONArray old = all(c);
            for (int i = 0; i < old.length(); i++) {
                JSONObject op = old.optJSONObject(i);
                if (op != null && !key.equals(op.optString("key"))) kept.put(op);
            }
            kept.put(new JSONObject().put("key", key).put("method", method).put("path", path)
                .put("body", body == null ? JSONObject.NULL : body).put("at", System.currentTimeMillis() / 1000));
            prefs(c).edit().putString(KEY, kept.toString()).apply();
        } catch (org.json.JSONException impossible) { }
    }

    static synchronized void remove(Context c, String key) {
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

    /**
     * Send what is waiting, oldest first, stopping quietly at the first write the Pi cannot be reached for.
     * A write the Pi answers with a no (it is gone, or no longer makes sense) is dropped, since sending it
     * again would only get the same answer. Blocks; call off the screen's thread. Returns how many went.
     */
    static int flush(Context c, MediaClient client) {
        JSONArray ops = all(c);
        int sent = 0;
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;
            String key = op.optString("key");
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
}
