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
    }

    static synchronized void delete(Context c, String id) {
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
