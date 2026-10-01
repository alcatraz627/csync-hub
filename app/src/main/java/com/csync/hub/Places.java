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
        /** A capability page reached straight from Home, such as the Pi or Notes. */
        boolean primary() { return parent == null || "home".equals(parent); }
    }

    private static final Map<String, Place> ALL = new LinkedHashMap<>();

    private static void add(String id, String label, int icon, String parent) {
        ALL.put(id, new Place(id, label, icon, parent));
    }

    static {
        add("home", "Home", R.drawable.csi_home, null);
        add("search", "Search", R.drawable.csi_search, "home");
        // The Pi and Notes are capabilities in their own right, one tap from Home, not kept in More.
        add("pi", "Raspberry Pi", R.drawable.csi_system, "home");
        add("camera", "Pi camera", R.drawable.csi_camera, "pi");
        add("captures", "Captures", R.drawable.csi_photo, "camera");
        add("notes", "Notes", R.drawable.csi_note, "home");
        add("note", "Note", R.drawable.csi_note, "notes");
        add("media", "Media", R.drawable.csi_media, null);
        add("pi-screen", "Pi screen", R.drawable.csi_screen, "media");
        add("phone-player", "This phone", R.drawable.csi_device, "media");
        add("share", "Share", R.drawable.csi_share, null);
        add("received", "Received", R.drawable.csi_download, "share");
        add("chat", "Chat", R.drawable.csi_chat, null);
        add("conversation", "Conversation", R.drawable.csi_chat, "chat");
        add("more", "More", R.drawable.csi_more, null);
        add("process", "Process monitor", R.drawable.csi_device, "more");
        add("widgets", "Widgets", R.drawable.csi_launcher, "more");
        add("settings", "Settings", R.drawable.csi_settings, "more");
        add("connection", "Connection", R.drawable.csi_wifi, "settings");
        add("guide", "Assistant guide", R.drawable.csi_help, "more");
        add("help", "About", R.drawable.csi_info, "more");
    }

    static Place of(String id) { return ALL.get(id); }

    /** The places directly under this one, in map order. */
    static List<Place> children(String id) {
        List<Place> out = new ArrayList<>();
        for (Place place : ALL.values()) if (id.equals(place.parent)) out.add(place);
        return out;
    }

    /** What a place is for, in a few words, for the sheet of places beside the current one. */
    static String purpose(String id) {
        switch (id) {
            case "home": return "Everything the app can do";
            case "search": return "Films, chats, files";
            case "pi": return "Screen, camera, health";
            case "camera": return "Live picture and captures";
            case "notes": return "Notes and pins on the Pi";
            case "media": return "Drives, videos, history";
            case "pi-screen": return "Play, show or share on the screen";
            case "share": return "Send to your devices";
            case "received": return "What your devices sent";
            case "chat": return "The Pi assistant";
            case "more": return "This phone, settings, reading";
            case "process": return "What this phone is busy with";
            case "widgets": return "Tiles and shortcuts";
            case "settings": return "Connect, play, look";
            case "connection": return "The Pi's address and token";
            case "guide": return "What to ask the assistant";
            case "help": return "Version and your Pi";
            default: return null;
        }
    }

    /** The path from the bar place down to this place, bar place first. */
    static List<Place> path(String id) {
        List<Place> path = new ArrayList<>();
        for (Place at = of(id); at != null; at = at.parent == null ? null : of(at.parent)) path.add(0, at);
        return path;
    }
}
