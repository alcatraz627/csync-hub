package com.csync.hub;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The screens the Pi has been plugged into, and how each one is driven. The Pi remembers a
 * separate starting volume and turn for every screen, so moving it between a monitor and a
 * projector needs no resetting. A screen appears here the first time it is plugged in.
 *
 * The screens are drawn in the page as a list. Tapping one opens its settings under it; the
 * screen in use starts open, because that is the one being set up nearly every time.
 */
final class DisplaySheet {
    private DisplaySheet() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Ask the Pi which screens it knows, then draw them into {@code host}, replacing what it held. */
    static void inline(Activity a, LinearLayout host) {
        final MediaClient pi = new MediaClient(Prefs.assistIp(a), Prefs.token(a));
        new Thread(() -> {
            JSONObject all = null;
            try { all = pi.get("/v1/displays"); }
            catch (Exception unreachable) { }
            final JSONObject answer = all;
            UI.post(() -> {
                if (a.isFinishing()) return;
                draw(a, pi, host, answer, null);
            });
        }, "displays").start();
    }

    private static void draw(Activity a, MediaClient pi, LinearLayout host, JSONObject all, String openId) {
        JSONArray screens = all == null ? null : all.optJSONArray("displays");
        if (screens == null) {
            Kit.notice(host, Kit.Icon.DISPLAY, "The Pi cannot be reached",
                "Its screens are listed once it answers.", "Try again", () -> inline(a, host));
            return;
        }
        if (screens.length() == 0) {
            Kit.notice(host, Kit.Icon.DISPLAY, "No screen yet",
                "A screen appears here the first time it is plugged into the Pi.", null, null);
            return;
        }
        host.removeAllViews();
        String current = all.optString("current");
        String open = openId != null ? openId : current;
        LinearLayout group = Kit.group(host);
        for (int i = 0; i < screens.length(); i++) {
            final JSONObject screen = screens.optJSONObject(i);
            if (screen == null) continue;
            final String id = screen.optString("id");
            View row = Kit.addRow(group);
            Kit.bindRow(row, Kit.Icon.DISPLAY, screen.optString("name", "Screen"), where(screen),
                id.equals(current) ? "In use" : null, false);
            final LinearLayout detail = settings(a, pi, host, all, screen);
            group.addView(detail);
            detail.setVisibility(id.equals(open) ? View.VISIBLE : View.GONE);
            row.setContentDescription(screen.optString("name", "Screen") + ", settings "
                + (detail.getVisibility() == View.VISIBLE ? "shown" : "hidden"));
            row.setOnClickListener(v -> {
                boolean show = detail.getVisibility() != View.VISIBLE;
                detail.setVisibility(show ? View.VISIBLE : View.GONE);
                row.setContentDescription(screen.optString("name", "Screen") + ", settings " + (show ? "shown" : "hidden"));
            });
        }
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

    private static String where(JSONObject screen) {
        if (screen.optBoolean("connected")) return "Connected · " + screen.optString("size") + ", " + screen.optString("port");
        long days = (System.currentTimeMillis() - screen.optLong("lastSeen")) / 86400000L;
        return screen.optLong("lastSeen") == 0 ? "Not connected"
            : "Last seen " + (days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago");
    }

    /** One screen's settings, drawn under its row: how loud playback starts on it and which way up the picture goes. */
    private static LinearLayout settings(Activity a, MediaClient pi, LinearLayout host, JSONObject all, JSONObject screen) {
        JSONObject now = screen.optJSONObject("settings") == null ? new JSONObject() : screen.optJSONObject("settings");
        final int volume = now.optInt("startVolume"), turn = now.optInt("rotate");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // The slider is inset; the turn row below it runs edge to edge like the screen's row.
        LinearLayout volumeBox = new LinearLayout(a);
        volumeBox.setOrientation(LinearLayout.VERTICAL);
        int pad = Kit.dp(a, 16);
        volumeBox.setPadding(pad, Kit.dp(a, 4), pad, Kit.dp(a, 8));
        box.addView(volumeBox);

        final TextView reading = new TextView(a);
        reading.setTextAppearance(R.style.Kit_Text_RowSub);
        reading.setText(startsAt(volume));
        volumeBox.addView(reading);
        com.google.android.material.slider.Slider slider = new com.google.android.material.slider.Slider(a);
        slider.setValueFrom(0);
        slider.setValueTo(100);
        slider.setStepSize(5);
        slider.setTickVisible(false);
        slider.setValue(Math.max(0, Math.min(100, Math.round(volume / 5f) * 5)));
        slider.setLabelFormatter(v -> v == 0 ? "Muted" : Math.round(v) + "%");
        slider.setContentDescription("Starting volume on " + screen.optString("name", "this screen"));
        slider.addOnChangeListener((s, v, fromUser) -> reading.setText(startsAt(Math.round(v))));
        // The value is sent when the finger lifts, so dragging does not flood the Pi.
        slider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(com.google.android.material.slider.Slider s) {}
            @Override public void onStopTrackingTouch(com.google.android.material.slider.Slider s) {
                save(a, pi, host, all, screen, "startVolume", Math.round(s.getValue()));
            }
        });
        volumeBox.addView(slider, new LinearLayout.LayoutParams(-1, Kit.dp(a, 48)));

        // The turn sits in the screen's own card, so it is a plain row, not a second card.
        View turnRow = Kit.addRow(box);
        Kit.bindRow(turnRow, Kit.Icon.ROTATE, "Picture turned " + turn + "°", "Tap to turn it a quarter further", null, false);
        turnRow.setOnClickListener(v -> save(a, pi, host, all, screen, "rotate", (turn + 90) % 360));
        return box;
    }

    private static String startsAt(int volume) {
        return volume == 0 ? "Playback starts muted" : "Playback starts at " + volume + "%";
    }

    /** Send one setting to the Pi, then redraw the list from what it kept, with this screen still open. */
    private static void save(Activity a, MediaClient pi, LinearLayout host, JSONObject all, JSONObject screen,
                             String setting, int value) {
        final String id = screen.optString("id");
        new Thread(() -> {
            JSONObject saved = null;
            try {
                saved = pi.put("/v1/displays/" + MediaClient.enc(id),
                    new JSONObject().put("settings", new JSONObject().put(setting, value))).optJSONObject("display");
            } catch (Exception refused) { }
            final JSONObject after = saved;
            UI.post(() -> {
                if (a.isFinishing()) return;
                if (after == null) {
                    Kit.failed(a, "The Pi did not take the change. Nothing was altered.",
                        () -> save(a, pi, host, all, screen, setting, value));
                    draw(a, pi, host, all, id);
                    return;
                }
                JSONArray screens = all.optJSONArray("displays");
                for (int i = 0; screens != null && i < screens.length(); i++) {
                    JSONObject s = screens.optJSONObject(i);
                    if (s != null && id.equals(s.optString("id"))) {
                        try { screens.put(i, after); } catch (Exception ignored) { }
                    }
                }
                draw(a, pi, host, all, id);
            });
        }, "display-save").start();
    }
}
