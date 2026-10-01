package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The record of what this phone has sent, newest first, kept so a send can be opened,
 * sent again, or retried later. Share's page and the share menu both write here.
 */
final class Transfers {
    private Transfers() {}

    private static final int KEPT = 200;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("csync_share", Context.MODE_PRIVATE);
    }

    /** Add one send. A file keeps its link on this phone and words keep their whole text. */
    static void recordSent(Context c, String name, String kind, String target, boolean delivered, String uri, String text) {
        JSONArray prior = sent(c);
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", name);
            entry.put("kind", kind);
            entry.put("target", target);
            entry.put("delivered", delivered);
            entry.put("at", System.currentTimeMillis());
            if (uri != null) entry.put("uri", uri);
            if (text != null) entry.put("text", text);
        } catch (Exception ignored) { }
        JSONArray next = new JSONArray().put(entry);
        for (int i = 0; i < Math.min(prior.length(), KEPT - 1); i++) next.put(prior.optJSONObject(i));
        prefs(c).edit().putString("sent", next.toString()).apply();
    }

    /** Every send kept, newest first. */
    static JSONArray sent(Context c) {
        try { return new JSONArray(prefs(c).getString("sent", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    /** Take one send off the list. */
    static void forget(Context c, long at) {
        JSONArray all = sent(c), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null && entry.optLong("at") != at) kept.put(entry);
        }
        prefs(c).edit().putString("sent", kept.toString()).apply();
    }

    /** True when the sent file can still be opened from this phone. */
    static boolean stillHere(Context c, JSONObject entry) {
        String uri = entry.optString("uri");
        if (uri.isEmpty()) return false;
        try (android.database.Cursor found = c.getContentResolver().query(Uri.parse(uri),
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return found != null && found.moveToFirst();
        } catch (Exception gone) {
            return false;
        }
    }
}
