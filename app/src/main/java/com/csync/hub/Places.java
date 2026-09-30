package com.csync.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where every screen of the app lives: its name, its icon and the place above it.
 *
 * The top bar's path and Back are read from this map, so a screen cannot describe
 * its own position. It mirrors model.js in the HTML mock; the reasoning is in
 * docs/android-app-model.md section 3 of the csync repo.
 */
final class Places {
    private Places() {}

    static final class Place {
        final String id, label, parent;
        final int icon;
        Place(String id, String label, int icon, String parent) {
            this.id = id; this.label = label; this.icon = icon; this.parent = parent;
        }
        /** One of the five places on the bottom bar. */
        boolean onBar() { return parent == null; }
    }

    private static final Map<String, Place> ALL = new LinkedHashMap<>();

    private static void add(String id, String label, int icon, String parent) {
        ALL.put(id, new Place(id, label, icon, parent));
    }

    static {
        add("home", "Home", R.drawable.csi_home, null);
        add("search", "Search", R.drawable.csi_search, "home");
        add("media", "Media", R.drawable.csi_media, null);
        add("pi-screen", "Pi screen", R.drawable.csi_screen, "media");
        add("phone-player", "This phone", R.drawable.csi_device, "media");
        add("share", "Share", R.drawable.csi_share, null);
        add("received", "Received", R.drawable.csi_download, "share");
        add("chat", "Chat", R.drawable.csi_chat, null);
        add("conversation", "Conversation", R.drawable.csi_chat, "chat");
        add("more", "More", R.drawable.csi_more, null);
        add("camera", "Pi camera", R.drawable.csi_camera, "more");
        add("captures", "Captures", R.drawable.csi_photo, "camera");
        add("notes", "Notes", R.drawable.csi_note, "more");
        add("note", "Note", R.drawable.csi_note, "notes");
        add("tools", "Tools", R.drawable.csi_tools, "more");
        add("process", "Process monitor", R.drawable.csi_device, "tools");
        add("widgets", "Widgets", R.drawable.csi_launcher, "tools");
        add("settings", "Settings", R.drawable.csi_settings, "more");
        add("connection", "Connection", R.drawable.csi_wifi, "settings");
        add("guide", "Assistant guide", R.drawable.csi_help, "more");
        add("help", "Help and about", R.drawable.csi_help, "more");
    }

    static Place of(String id) { return ALL.get(id); }

    /** The path from the bar place down to this place, bar place first. */
    static List<Place> path(String id) {
        List<Place> path = new ArrayList<>();
        for (Place at = of(id); at != null; at = at.parent == null ? null : of(at.parent)) path.add(0, at);
        return path;
    }
}
