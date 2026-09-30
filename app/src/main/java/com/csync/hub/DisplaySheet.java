package com.csync.hub;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The screens the Pi has been plugged into, and how each one is driven. The Pi remembers a
 * separate starting volume and turn for every screen, so moving it between a monitor and a
 * projector needs no resetting. A screen appears here the first time it is plugged in.
 */
final class DisplaySheet {
    private DisplaySheet() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Ask the Pi which screens it knows, then open the list. */
    static void open(Activity a) {
        final MediaClient pi = new MediaClient(Prefs.assistIp(a), Prefs.token(a));
        new Thread(() -> {
            JSONArray found = null;
            try { found = pi.get("/v1/displays").optJSONArray("displays"); }
            catch (Exception unreachable) { }
            final JSONArray screens = found;
            UI.post(() -> {
                if (a.isFinishing()) return;
                if (screens == null) { Kit.sheet(a, "Display", "The Pi cannot be reached, so its screens are not known."); return; }
                if (screens.length() == 0) { Kit.sheet(a, "Display", "No screen has been plugged into the Pi yet."); return; }
                List<Kit.Action> rows = new ArrayList<>();
                for (int i = 0; i < screens.length(); i++) {
                    final JSONObject screen = screens.optJSONObject(i);
                    if (screen == null) continue;
                    rows.add(new Kit.Action(Kit.Icon.DISPLAY, screen.optString("name", "Screen"),
                        where(screen), () -> settings(a, pi, screen)));
                }
                Kit.sheet(a, "Display", "Each screen keeps its own settings. A new one appears here when it is plugged in.",
                    rows.toArray(new Kit.Action[0]));
            });
        }, "displays").start();
    }

    /** The name of the screen in use, or null when the Pi has none or cannot be reached. */
    static String current(MediaClient pi) {
        JSONObject screen = screenInUse(pi);
        return screen == null ? null : screen.optString("name");
    }

    /** The screen in use with its settings, or null when the Pi has none or cannot be reached. Call it off the main thread. */
    static JSONObject screenInUse(MediaClient pi) {
        try {
            JSONObject all = pi.get("/v1/displays");
            JSONArray screens = all.optJSONArray("displays");
            for (int i = 0; screens != null && i < screens.length(); i++) {
                JSONObject screen = screens.optJSONObject(i);
                if (screen != null && screen.optString("id").equals(all.optString("current"))) return screen;
            }
        } catch (Exception unreachable) { }
        return null;
    }

    /** Set how loud playback starts on the screen in use. {@code after} runs once the Pi has saved it. */
    static void startVolume(Activity a, Runnable after) {
        final MediaClient pi = new MediaClient(Prefs.assistIp(a), Prefs.token(a));
        new Thread(() -> {
            final JSONObject screen = screenInUse(pi);
            UI.post(() -> {
                if (a.isFinishing()) return;
                if (screen == null) { Kit.sheet(a, "Starting volume", "The Pi cannot be reached, or no screen is plugged into it."); return; }
                JSONObject kept = screen.optJSONObject("settings");
                Kit.sliderSheet(a, "Starting volume on " + screen.optString("name", "the Pi screen"), 0, 100, 5,
                    kept == null ? 0 : kept.optInt("startVolume"),
                    value -> value == 0 ? "Muted" : Math.round(value) + "%",
                    value -> new Thread(() -> {
                        boolean saved = false;
                        try {
                            pi.put("/v1/displays/" + MediaClient.enc(screen.optString("id")),
                                new JSONObject().put("settings", new JSONObject().put("startVolume", Math.round(value))));
                            saved = true;
                        } catch (Exception refused) { }
                        final boolean done = saved;
                        UI.post(() -> {
                            if (a.isFinishing()) return;
                            if (done) after.run();
                            else Kit.sheet(a, "It was not saved", "The Pi did not take the change. Nothing was altered.");
                        });
                    }, "start-volume-save").start());
            });
        }, "start-volume").start();
    }

    private static String where(JSONObject screen) {
        if (screen.optBoolean("connected")) return "Connected · " + screen.optString("size") + ", " + screen.optString("port");
        long days = (System.currentTimeMillis() - screen.optLong("lastSeen")) / 86400000L;
        return screen.optLong("lastSeen") == 0 ? "Not connected"
            : "Last seen " + (days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago");
    }

    /** One screen's settings: how loud playback starts on it and which way up the picture goes. */
    private static void settings(Activity a, MediaClient pi, JSONObject screen) {
        JSONObject now = screen.optJSONObject("settings") == null ? new JSONObject() : screen.optJSONObject("settings");
        final int volume = now.optInt("startVolume"), turn = now.optInt("rotate");
        Kit.sheet(a, screen.optString("name", "Screen"), where(screen),
            new Kit.Action(Kit.Icon.VOLUME, "Playback starts " + (volume == 0 ? "muted" : "at " + volume + "%"),
                "How loud a video is when it begins on this screen",
                () -> Kit.sliderSheet(a, "Starting volume", 0, 100, 5, volume,
                    value -> value == 0 ? "Muted" : Math.round(value) + "%",
                    value -> save(a, pi, screen, "startVolume", Math.round(value)))),
            new Kit.Action(Kit.Icon.ROTATE, "Picture turned " + turn + "°", "Tap to turn it a quarter further",
                () -> save(a, pi, screen, "rotate", (turn + 90) % 360)));
    }

    private static void save(Activity a, MediaClient pi, JSONObject screen, String setting, int value) {
        new Thread(() -> {
            JSONObject saved = null;
            try {
                saved = pi.put("/v1/displays/" + MediaClient.enc(screen.optString("id")),
                    new JSONObject().put("settings", new JSONObject().put(setting, value))).optJSONObject("display");
            } catch (Exception refused) { }
            final JSONObject after = saved;
            UI.post(() -> {
                if (a.isFinishing()) return;
                if (after == null) Kit.sheet(a, "It was not saved", "The Pi did not take the change. Nothing was altered.");
                else settings(a, pi, after);
            });
        }, "display-save").start();
    }
}
