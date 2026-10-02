package com.csync.hub;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the app can do, named once, so Home can be the map of it.
 *
 * Primary capabilities get large cards on Home; secondary ones get smaller cards below.
 * A card opens a capability inside a place, never a bottom-bar root, because the bar
 * already reaches those. The id is what MainActivity.openCapability acts on.
 */
final class Catalogue {
    private Catalogue() {}

    static final class Entry {
        final String id, title, sub;
        final int icon;
        final boolean primary;
        Entry(String id, int icon, String title, String sub, boolean primary) {
            this.id = id; this.icon = icon; this.title = title; this.sub = sub; this.primary = primary;
        }
    }

    static final List<Entry> ALL = new ArrayList<>();

    private static void add(String id, int icon, String title, String sub, boolean primary) {
        ALL.add(new Entry(id, icon, title, sub, primary));
    }

    static {
        add("pi-screen", Kit.Icon.DISPLAY, "Pi screen", "Play, show or share on the screen", true);
        add("notes", Kit.Icon.NOTES, "Notes", "Notes and pins kept on the Pi", true);
        add("camera", Kit.Icon.CAMERA, "Pi camera", "Live picture and captures", true);
        add("pi", R.drawable.csi_system, "Raspberry Pi", "Health, drives and power", true);
        add("chat-new", Kit.Icon.CHAT, "New conversation", "Ask the Pi assistant", true);
        add("videos", Kit.Icon.VIDEO, "Videos", "Every film on the drives", true);
        add("timers", R.drawable.csi_timer, "Timers", "Up to four at once", true);
        add("reminders", R.drawable.csi_bell, "Reminders", "Something to remember at a time", true);
        add("received", R.drawable.csi_download, "Received", "From your devices", false);
        add("process", Kit.Icon.DEVICE, "Process monitor", "What this phone is busy with", false);
        add("search", Kit.Icon.SEARCH, "Search", "Films, chats, files", false);
        add("guide", R.drawable.csi_help, "Assistant guide", "What to ask", false);
        add("widgets", R.drawable.csi_launcher, "Widgets", "Tiles and shortcuts", false);
        add("settings", Kit.Icon.SETTINGS, "Settings", "Connect, play, look", false);
    }

    static List<Entry> tier(boolean primary) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ALL) if (entry.primary == primary) out.add(entry);
        return out;
    }
}
