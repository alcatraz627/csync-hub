package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local persistence for chat conversations, so history survives app restarts and
 * any past chat can be reopened and continued. Stored in SharedPreferences as one
 * index of conversations plus one transcript per conversation. A transcript entry
 * is either a user message ({"role":"user","text":...}) or an assistant turn (a
 * turn object carrying a "type"), rendered in order when the chat is reopened.
 */
public final class ChatStore {
    private static final String PREF = "csync_chats";
    private static final String INDEX = "index";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Conversations, newest first: each {id, title, updated}. */
    static JSONArray index(Context c) {
        try { return new JSONArray(p(c).getString(INDEX, "[]")); }
        catch (Throwable e) { return new JSONArray(); }
    }

    /** The ordered transcript for one conversation. */
    static JSONArray transcript(Context c, String id) {
        try { return new JSONArray(p(c).getString("t_" + id, "[]")); }
        catch (Throwable e) { return new JSONArray(); }
    }

    /** Append one entry (a user message or an assistant turn) and touch the index. */
    static synchronized void append(Context c, String id, String title, JSONObject entry) {
        if (id == null || entry == null) return;
        JSONArray t = transcript(c, id);
        t.put(entry);
        p(c).edit()
                .putString("t_" + id, t.toString())
                .putString(INDEX, upsert(index(c), id, title).toString())
                .apply();
    }

    // Set a field on a conversation's index entry (title, favorite, archived).
    static synchronized void patch(Context c, String id, String key, Object val) {
        JSONArray idx = index(c);
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try { o.put(key, val); } catch (Throwable ignore) {}
                break;
            }
        }
        p(c).edit().putString(INDEX, idx.toString()).apply();
        try { onPi(c, "POST", "/conversations/" + id, new JSONObject().put(key, val)); } catch (Throwable ignore) { }
    }

    // ---- the Pi owns the conversations; this phone keeps a copy for reading offline ----

    /** Send one change to the Pi in the background. A change made while the Pi is away stays on this phone only. */
    private static void onPi(Context c, String method, String path, JSONObject body) {
        final String ip = Prefs.assistIp(c), token = Prefs.token(c);
        if (ip.isEmpty() || token.isEmpty()) return;
        new Thread(() -> {
            try { MeshClient.conversations(ip, token, method, path, body); } catch (Throwable ignore) { }
        }, "chat-to-pi").start();
    }

    /** Cut a conversation's copy on this phone back to its first keep entries. */
    static synchronized void truncate(Context c, String id, int keep) {
        JSONArray t = transcript(c, id), kept = new JSONArray();
        for (int i = 0; i < t.length() && i < keep; i++) kept.put(t.optJSONObject(i));
        p(c).edit().putString("t_" + id, kept.toString()).apply();
    }

    /**
     * Bring this phone's copy up to date with the Pi: conversations started on another
     * device appear, and ones the Pi has newer are replaced. Call it off the main thread.
     * Returns true when anything on this phone changed.
     */
    static boolean pull(Context c) {
        final String ip = Prefs.assistIp(c), token = Prefs.token(c);
        if (ip.isEmpty() || token.isEmpty()) return false;
        boolean changed = false;
        try {
            JSONArray remote = MeshClient.conversations(ip, token, "GET", "/conversations", null).optJSONArray("conversations");
            for (int i = 0; remote != null && i < remote.length(); i++) {
                JSONObject meta = remote.optJSONObject(i);
                if (meta == null) continue;
                String id = meta.optString("id");
                JSONObject local = find(index(c), id);
                if (local != null && local.optLong("synced") >= meta.optLong("updated")) continue;
                JSONObject full = MeshClient.conversations(ip, token, "GET", "/conversations/" + id, null);
                store(c, full);
                changed = true;
            }
        } catch (Throwable ignore) { }
        return changed;
    }

    private static JSONObject find(JSONArray idx, String id) {
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /** Replace this phone's copy of one conversation with the Pi's. */
    private static synchronized void store(Context c, JSONObject full) throws org.json.JSONException {
        String id = full.optString("id");
        JSONArray idx = index(c), next = new JSONArray();
        JSONObject entry = find(idx, id);
        if (entry == null) entry = new JSONObject().put("id", id);
        entry.put("title", full.optString("title")).put("updated", full.optLong("updated"))
            .put("synced", full.optLong("updated")).put("favorite", full.optBoolean("favorite"))
            .put("archived", full.optBoolean("archived")).put("model", full.optString("model"))
            .put("effort", full.optString("effort"));
        next.put(entry);
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        // Keep the list newest first, whatever order the Pi's copies arrived in.
        java.util.List<JSONObject> sorted = new java.util.ArrayList<>();
        for (int i = 0; i < next.length(); i++) sorted.add(next.optJSONObject(i));
        sorted.sort((a, b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        JSONArray transcript = full.optJSONArray("transcript");
        p(c).edit().putString(INDEX, new JSONArray(sorted).toString())
            .putString("t_" + id, (transcript == null ? new JSONArray() : transcript).toString()).apply();
    }

    static synchronized void delete(Context c, String id) {
        onPi(c, "DELETE", "/conversations/" + id, null);
        JSONArray idx = index(c), keep = new JSONArray();
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) keep.put(o);
        }
        p(c).edit().remove("t_" + id).putString(INDEX, keep.toString()).apply();
    }

    // Move id to the front, filling a title the first time and always bumping updated.
    private static JSONArray upsert(JSONArray idx, String id, String title) {
        JSONObject cur = null;
        JSONArray rest = new JSONArray();
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) cur = o; else rest.put(o);
        }
        try {
            if (cur == null) { cur = new JSONObject(); cur.put("id", id); }
            if (cur.optString("title").isEmpty() && title != null && !title.isEmpty())
                cur.put("title", title.length() > 60 ? title.substring(0, 60) : title);
            cur.put("updated", System.currentTimeMillis());
        } catch (Throwable ignore) {}
        JSONArray out = new JSONArray();
        out.put(cur);
        for (int i = 0; i < rest.length(); i++) out.put(rest.optJSONObject(i));
        return out;
    }

    private ChatStore() {}
}
