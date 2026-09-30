package com.csync.hub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a quick-rail slot can do, and what the owner has put in the slots.
 *
 * An action is a small JSON object: {"id", "label", "icon"} and, for a folder on a drive,
 * "driveId", "driveLabel" and "path". The catalog lists the fixed ones; a folder is added
 * from Settings. The rail (bar 1) is an ordered list; the theme slot holds one action.
 */
final class RailActions {
    private RailActions() {}

    static final String DEFAULT_SLOT = "theme";

    /** The fixed catalog, grouped the way Settings shows it. */
    static final String[][] CATALOG = {
        // group, id, label
        {"Places", "home", "Home"}, {"Places", "search", "Search"}, {"Places", "media", "Media"},
        {"Places", "pi-screen", "Pi screen"}, {"Places", "share", "Share"}, {"Places", "received", "Received"},
        {"Places", "chat", "Chat"}, {"Places", "chat-new", "New conversation"}, {"Places", "camera", "Pi camera"},
        {"Places", "notes", "Notes"}, {"Places", "tools", "Tools"}, {"Places", "process", "Process monitor"},
        {"Places", "widgets", "Widgets"}, {"Places", "settings", "Settings"}, {"Places", "connection", "Connection"},
        {"Pi screen", "pi-stop", "Stop the Pi screen"}, {"Pi screen", "pi-camera-show", "Pi camera on the Pi screen"},
        {"Pi screen", "screen-share", "Share this phone's screen"},
        {"This app", "theme", "Switch light and dark"}, {"This app", "update", "Check for an update"},
    };

    static int icon(String id) {
        switch (id) {
            case "home": return Kit.Icon.HOME;
            case "search": return Kit.Icon.SEARCH;
            case "media": return Kit.Icon.MEDIA;
            case "pi-screen": case "pi-stop": case "pi-camera-show": return Kit.Icon.DISPLAY;
            case "share": case "received": return Kit.Icon.SHARE;
            case "chat": case "chat-new": return Kit.Icon.CHAT;
            case "camera": return Kit.Icon.CAMERA;
            case "notes": return Kit.Icon.NOTES;
            case "tools": case "process": return Kit.Icon.TOOLS;
            case "widgets": return R.drawable.csi_launcher;
            case "settings": case "connection": return Kit.Icon.SETTINGS;
            case "screen-share": return R.drawable.csi_screen;
            case "theme": return R.drawable.csi_sun;
            case "update": return R.drawable.csi_download;
            case "folder": return Kit.Icon.FOLDER;
            default: return Kit.Icon.FILE;
        }
    }

    static JSONObject fixed(String id) {
        for (String[] entry : CATALOG)
            if (entry[1].equals(id)) {
                try { return new JSONObject().put("id", id).put("label", entry[2]); } catch (Exception ignored) { }
            }
        return null;
    }

    static JSONObject folder(String driveId, String driveLabel, String path) {
        String name = path.isEmpty() ? driveLabel : path.substring(path.lastIndexOf('/') + 1);
        try {
            return new JSONObject().put("id", "folder").put("label", name)
                .put("driveId", driveId).put("driveLabel", driveLabel).put("path", path);
        } catch (Exception impossible) { return null; }
    }

    // ---- what the owner picked ----

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("csync", Context.MODE_PRIVATE);
    }

    /** The rail's actions in order; empty until the owner picks some. */
    static List<JSONObject> rail(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(c).getString("rail_items", "[]"));
            for (int i = 0; i < saved.length(); i++) if (saved.optJSONObject(i) != null) out.add(saved.optJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    static void saveRail(Context c, List<JSONObject> items) {
        JSONArray saved = new JSONArray();
        for (JSONObject item : items) saved.put(item);
        prefs(c).edit().putString("rail_items", saved.toString()).apply();
    }

    /** The one action on the right, the theme switch unless the owner chose another. */
    static JSONObject slot(Context c) {
        try {
            String saved = prefs(c).getString("rail_slot", "");
            if (!saved.isEmpty()) return new JSONObject(saved);
        } catch (Exception ignored) { }
        return fixed(DEFAULT_SLOT);
    }

    static void saveSlot(Context c, JSONObject action) {
        prefs(c).edit().putString("rail_slot", action == null ? "" : action.toString()).apply();
    }

    static boolean same(JSONObject a, JSONObject b) {
        return a != null && b != null && a.optString("id").equals(b.optString("id"))
            && a.optString("driveId").equals(b.optString("driveId")) && a.optString("path").equals(b.optString("path"));
    }

    // ---- doing it ----

    static void run(Activity a, JSONObject action) {
        if (action == null) return;
        String id = action.optString("id");
        switch (id) {
            case "theme": {
                boolean dark = Prefs.darkTheme(a);
                Prefs.saveThemeMode(a, dark ? "light" : "dark");
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                    ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                return;
            }
            case "home": case "share": case "chat": case "chat-new": case "tools": case "settings": case "camera":
            case "more":
                main(a, id.equals("chat-new") ? "chat-new" : id, null);
                return;
            case "received": main(a, "share", "received"); return;
            case "process": main(a, "tools", "process"); return;
            case "widgets": main(a, "tools", "widgets"); return;
            case "connection": main(a, "settings", "connection"); return;
            case "update": main(a, "tools", "update"); return;
            case "search": jump(a, new Intent(a, SearchActivity.class)); return;
            case "notes": jump(a, new Intent(a, NotesActivity.class)); return;
            case "media": jump(a, new Intent(a, MediaActivity.class)); return;
            case "pi-screen":
                jump(a, new Intent(a, MediaActivity.class).putExtra("player_target", "pi"));
                return;
            case "folder":
                jump(a, new Intent(a, MediaActivity.class)
                    .putExtra("open_drive_id", action.optString("driveId"))
                    .putExtra("open_drive_label", action.optString("driveLabel"))
                    .putExtra("open_path", action.optString("path")));
                return;
            case "pi-stop": pi(a, "/v1/player/pi/immediate", "{\"action\":\"stop\"}", "The Pi screen is stopped"); return;
            case "pi-camera-show": pi(a, "/v1/display/camera", null, "The Pi camera is on the Pi screen"); return;
            case "screen-share":
                jump(a, new Intent(a, MediaActivity.class).putExtra("player_target", "pi").putExtra("share_screen", true));
                return;
            default:
        }
    }

    /**
     * Open one of the pages that live in their own activity. A rail item is a jump, never a second
     * copy: if that page is already open underneath, it is restarted with this intent instead.
     */
    private static void jump(Activity a, Intent open) {
        a.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private static void main(Activity a, String destination, String detail) {
        Intent open = new Intent(a, MainActivity.class).putExtra("destination", destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (detail != null) open.putExtra("detail", detail);
        a.startActivity(open);
    }

    /** A Pi call with no page: the words say what happened, or why not. */
    private static void pi(Activity a, String route, String body, String done) {
        String host = Prefs.assistIp(a), token = Prefs.token(a);
        if (host.isEmpty() || token.isEmpty()) { toast(a, "Connect the Pi first, in Settings"); return; }
        new Thread(() -> {
            String words;
            try {
                MediaClient client = new MediaClient(host, token);
                client.post(route, body == null ? new JSONObject() : new JSONObject(body));
                words = done;
            } catch (Exception error) { words = error.getMessage() == null ? "The Pi did not answer" : error.getMessage(); }
            String said = words;
            a.runOnUiThread(() -> toast(a, said));
        }, "rail-pi").start();
    }

    private static void toast(Activity a, String words) {
        android.widget.Toast.makeText(a, words, android.widget.Toast.LENGTH_SHORT).show();
    }
}
