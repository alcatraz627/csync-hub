package com.csync.hub;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Switchboard's tabs, read from the Mac: Approvals, Ledger, Queue, Plugins, Rules, Library, Usage, Bulbs and
 * Agents, each as the same groups and rows the Mac panel draws. Tapping a row opens its details in
 * a drawer; Usage draws meters, and Bulbs have a switch each that presses on the Mac.
 *
 * The rows come from the Mac's csync agent, which runs Switchboard's own export, so the phone
 * never keeps a second copy. A tab that loaded once stays on screen when a later refresh fails.
 */
final class SwitchboardScreen {
    private static final String[] TABS = {"approvals", "ledger", "queue", "plugins", "rules", "library", "usage", "bulbs", "agents"};
    private static final String[] TAB_WORDS = {"Approvals", "Ledger", "Queue", "Plugins", "Rules", "Library", "Usage", "Bulbs", "Agents"};
    private static final int[] TAB_ICONS = {R.drawable.csi_bell, R.drawable.csi_history, R.drawable.csi_view_list,
        R.drawable.csi_tools, R.drawable.csi_check, R.drawable.csi_files,
        R.drawable.csi_bars, R.drawable.csi_sun, R.drawable.csi_chat};

    private final Activity a;
    private final LinearLayout host;
    private final Runnable openConnection;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // The last good answer per tab, so a failed refresh keeps what was already shown.
    private final Map<String, JSONObject> loaded = new HashMap<>();
    private String tab;
    private boolean showing;
    // Bumped on every load, so an answer for a tab the owner has since left is dropped.
    private int asked;

    SwitchboardScreen(Activity a, LinearLayout host, Runnable openConnection) {
        this.a = a;
        this.host = host;
        this.openConnection = openConnection;
        tab = a.getSharedPreferences("switchboard_view", Activity.MODE_PRIVATE).getString("tab", "ledger");
    }

    void show() {
        showing = true;
        render();
        load();
    }

    void hide() {
        showing = false;
        asked++;
    }

    private int tabIndex() {
        int i = java.util.Arrays.asList(TABS).indexOf(tab);
        return Math.max(0, i);
    }

    private void render() {
        host.removeAllViews();
        LinearLayout strip = new LinearLayout(a);
        host.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        Kit.tabs(strip, TAB_ICONS, TAB_WORDS, tabIndex(), i -> {
            tab = TABS[i];
            a.getSharedPreferences("switchboard_view", Activity.MODE_PRIVATE).edit().putString("tab", tab).apply();
            render();
            load();
        });
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        host.addView(body, new LinearLayout.LayoutParams(-1, -2));
        JSONObject got = loaded.get(tab);
        if (got == null) Kit.empty(body, TAB_ICONS[tabIndex()], "Asking the Mac", "Switchboard's " + TAB_WORDS[tabIndex()] + " tab", null);
        else groups(body, got);
    }

    private void load() {
        final String ip = Prefs.homeIp(a), token = Prefs.token(a), want = tab;
        if (ip.isEmpty() || token.isEmpty()) {
            unreachable("No Mac address saved", "Save the Mac's address and the access token in Connection.", "Open Connection", openConnection);
            return;
        }
        final int ticket = ++asked;
        new Thread(() -> {
            JSONObject got = null;
            String why = null;
            try {
                got = MeshClient.switchboard(ip, token, want, null, 0);
            } catch (Exception e) {
                why = e.getMessage();
            }
            final JSONObject answer = got;
            final String failure = why;
            ui.post(() -> {
                if (!showing || ticket != asked || !want.equals(tab)) return;
                if (answer != null) {
                    loaded.put(want, answer);
                    render();
                } else if (loaded.containsKey(want)) {
                    Kit.failed(a, "The Mac did not answer, so this is the last list it sent", this::load);
                } else if (want.equals("approvals") && failure != null && notOnMac(failure)) {
                    // A Mac whose Switchboard predates the Approvals export: say so, rather than blame the network.
                    unreachable("Approvals are not on the Mac yet",
                        "The Mac's Switchboard does not send this list yet. Update Switchboard on the Mac, then open this tab again.",
                        "Try again", this::load);
                } else {
                    unreachable("The Mac did not answer",
                        (failure == null ? "No reply" : failure) + ". Asked " + ip + "; the Mac may be asleep or off the tailnet.",
                        "Try again", this::load);
                }
            });
        }).start();
    }

    private static boolean notOnMac(String failure) {
        String f = failure.toLowerCase(java.util.Locale.ROOT);
        return f.contains("no list tab") || f.contains("unknown tab") || f.contains("not found");
    }

    private void unreachable(String title, String why, String go, Runnable action) {
        if (host.getChildCount() < 2) render();
        Kit.notice((LinearLayout) host.getChildAt(1), R.drawable.csi_alert, title, why, go, action);
    }

    private void groups(LinearLayout body, JSONObject got) {
        JSONArray groups = got.optJSONArray("groups");
        if (groups == null || groups.length() == 0) {
            if (tab.equals("approvals")) Kit.empty(body, TAB_ICONS[tabIndex()], "Nothing is waiting on you",
                "No push, ask or prompt is held for an answer. This list fills when a session needs you.", null);
            else Kit.empty(body, TAB_ICONS[tabIndex()], "Nothing in " + TAB_WORDS[tabIndex()], "The Mac's Switchboard shows no rows here either.", null);
            return;
        }
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            if (group != null) group(body, group);
        }
    }

    private void group(LinearLayout body, JSONObject group) {
        String title = group.optString("title");
        JSONArray rows = group.optJSONArray("rows");
        int shown = rows == null ? 0 : rows.length(), total = group.optInt("total", shown);
        boolean counted = !tab.equals("usage") && !tab.equals("bulbs");
        Kit.label(body, total > 0 && counted ? title + "  " + total : title);
        LinearLayout box = Kit.group(body);
        if (shown == 0) {
            // The Mac says why a section is empty (no reading yet, no bulbs answered).
            Kit.bindRow(Kit.addRow(box), TAB_ICONS[tabIndex()], "Nothing here", firstLine(group.optString("status", null)), null, false);
            return;
        }
        for (int i = 0; i < shown; i++) row(box, rows.optJSONObject(i));
        if (shown < total) more(box, title, shown, total);
    }

    // The tail of a long group: one row that fetches the next page and puts it in place of itself.
    private void more(LinearLayout box, String title, int shown, int total) {
        View row = Kit.addRow(box);
        Kit.bindRow(row, R.drawable.csi_plus, "Show more", (total - shown) + " more on the Mac", null, false);
        row.setOnClickListener(v -> {
            row.setEnabled(false);
            Kit.bindRow(row, R.drawable.csi_refresh, "Asking the Mac", null, null, false);
            final String ip = Prefs.homeIp(a), token = Prefs.token(a), want = tab;
            new Thread(() -> {
                JSONObject page = null;
                try {
                    page = MeshClient.switchboard(ip, token, want, title, shown);
                } catch (Exception ignored) {
                }
                final JSONObject got = page;
                ui.post(() -> {
                    if (!showing || !want.equals(tab)) return;
                    JSONArray next = rowsOf(got, title);
                    if (next == null) {
                        row.setEnabled(true);
                        Kit.bindRow(row, R.drawable.csi_plus, "Show more", (total - shown) + " more on the Mac", null, false);
                        Kit.failed(a, "The Mac did not answer", row::performClick);
                        return;
                    }
                    int at = box.indexOfChild(row);
                    // The divider above this row belongs to it; the new rows bring their own.
                    box.removeViewAt(at);
                    box.removeViewAt(at - 1);
                    for (int i = 0; i < next.length(); i++) row(box, next.optJSONObject(i));
                    remember(want, title, next);
                    int now = shown + next.length();
                    if (now < total && next.length() > 0) more(box, title, now, total);
                });
            }).start();
        });
    }

    private static JSONArray rowsOf(JSONObject page, String title) {
        if (page == null) return null;
        JSONArray groups = page.optJSONArray("groups");
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            if (group != null && title.equals(group.optString("title"))) return group.optJSONArray("rows");
        }
        return null;
    }

    // Keep fetched pages in the tab's answer, so coming back to the tab does not lose them.
    private void remember(String want, String title, JSONArray next) {
        JSONArray have = rowsOf(loaded.get(want), title);
        for (int i = 0; have != null && i < next.length(); i++) have.put(next.opt(i));
    }

    private void row(LinearLayout box, JSONObject r) {
        if (r == null) return;
        String label = r.optString("label"), note = r.optString("note", null);
        String state = r.optString("state");
        JSONArray children = r.optJSONArray("children");
        boolean opens = children != null && children.length() > 0 || !r.optString("tip").isEmpty();
        String end = "count".equals(state) ? String.valueOf(r.optInt("count")) : null;
        if (tab.equals("usage") && r.has("percent")) {
            usageRow(box, r);
            return;
        }
        if (tab.equals("bulbs")) {
            bulbRow(box, r);
            return;
        }
        if (tab.equals("agents") && end == null) {
            String value = r.optString("value");
            if (!value.isEmpty() && value.length() <= 10) end = value;
        }
        View row = Kit.addRow(box);
        Kit.bindRow(row, TAB_ICONS[tabIndex()], label, firstLine(note), end, opens);
        if ("on".equals(state) && !tab.equals("approvals")) Kit.rowStatus(row, Kit.Status.GOOD, "On");
        if (!r.optBoolean("enabled", true)) row.setAlpha(0.5f);
        row.setOnClickListener(v -> details(r));
        // A held push or ask is answered with one tap on the row; Deny, Cancel and Copy are in its drawer.
        if (tab.equals("approvals") && hasRunnable(r, "Approve")) {
            Kit.rowAction(row, R.drawable.csi_check, "Approve " + label, v -> press(r, "Approve", false));
        }
    }

    // A usage window: its name and when it resets, with a meter and the percent at the end.
    private void usageRow(LinearLayout box, JSONObject r) {
        int percent = r.optInt("percent");
        String reset = r.optString("reset");
        View row = Kit.addRow(box);
        Kit.bindRow(row, TAB_ICONS[tabIndex()], r.optString("label"), reset.isEmpty() ? null : "Resets " + reset, null, false);
        LinearLayout meters = new LinearLayout(a);
        meters.setOrientation(LinearLayout.HORIZONTAL);
        meters.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meters.setTag("kit-action");
        TextView number = new TextView(a);
        number.setTextAppearance(R.style.Kit_Text_Meta);
        number.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        number.setText(percent + "%");
        number.setMinWidth(Kit.dp(a, 44));
        number.setGravity(android.view.Gravity.END);
        number.setPadding(0, 0, Kit.dp(a, 8), 0);
        meters.addView(number);
        meters.addView(Kit.meter(a, percent, 72, 6));
        LinearLayout line = (LinearLayout) row;
        line.addView(meters, line.indexOfChild(row.findViewById(R.id.kit_chevron)));
        row.setContentDescription(r.optString("label") + ", " + percent + " percent used" + (reset.isEmpty() ? "" : ", resets " + reset));
    }

    // A bulb: a switch that presses On or Off on the Mac, and a drawer button for brightness and colour.
    // A bulb that does not answer is dimmed and says so; it has nothing to press.
    private void bulbRow(LinearLayout box, JSONObject r) {
        View row = Kit.addRow(box);
        boolean reachable = r.optBoolean("reachable", true), on = r.optBoolean("on");
        Kit.bindRow(row, TAB_ICONS[tabIndex()], r.optString("label"), r.optString("note", null), null, false);
        if (!reachable) {
            row.setAlpha(0.5f);
            row.setContentDescription(r.optString("label") + ", not answering");
            return;
        }
        // One press at a time: a second act would race the first and leave the wrong state on screen.
        Kit.rowToggle(row, on, now -> {
            row.setEnabled(false);
            row.setAlpha(0.5f);
            act(r, now ? "On" : "Off", null, true, () -> {
                row.setEnabled(true);
                row.setAlpha(1f);
            });
        });
        Kit.rowAction(row, R.drawable.csi_sliders, "Brightness and colour", v -> bulbSheet(r));
    }

    private void bulbSheet(JSONObject r) {
        int brightness = r.optInt("brightness", 100);
        List<Kit.Section> sections = new ArrayList<>();
        List<Kit.Action> set = new ArrayList<>();
        set.add(new Kit.Action(R.drawable.csi_sun, "Brightness", "Slide, then lift your finger", () ->
            Kit.sliderSheet(a, r.optString("label") + " brightness", 10, 100, 5, brightness, v -> Math.round(v) + "%",
                v -> act(r, "Brightness", String.valueOf(Math.round(v)), false)), true).value(brightness + "%"));
        sections.add(new Kit.Section(null, set));
        List<Kit.Action> colours = new ArrayList<>();
        JSONArray swatches = r.optJSONArray("swatches");
        for (int i = 0; swatches != null && i < swatches.length(); i++) {
            JSONObject s = swatches.optJSONObject(i);
            if (s == null) continue;
            String hex = s.optString("hex");
            Kit.Action swatch = new Kit.Action(R.drawable.csi_palette, s.optString("name"), null, () -> act(r, "Colour", hex, false));
            try {
                swatch.tint(android.graphics.Color.parseColor("#" + hex));
            } catch (IllegalArgumentException malformed) {
                // a swatch the Mac sent with a bad colour still works, just without its tint
            }
            colours.add(swatch);
        }
        colours.add(new Kit.Action(R.drawable.csi_sun, "White", "Back to warm or cool white", () -> act(r, "Colour", "white", false)));
        sections.add(new Kit.Section("Colour", colours));
        Kit.sheet(a, r.optString("label"), r.optString("note", null), sections);
    }

    // A row's drawer: everything the Mac shows under it, and Copy for the words it carries.
    private void details(JSONObject r) {
        String label = r.optString("label"), note = r.optString("note", "");
        List<Kit.Section> sections = new ArrayList<>();
        JSONArray children = r.optJSONArray("children");
        List<Kit.Action> facts = new ArrayList<>();
        for (int i = 0; children != null && i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) continue;
            String words = child.optString("note", "");
            facts.add(new Kit.Action(R.drawable.csi_info, child.optString("label"), words.isEmpty() ? null : words,
                () -> copy(words.isEmpty() ? child.optString("label") : words)));
        }
        if (!facts.isEmpty()) sections.add(new Kit.Section("Tap a line to copy it", facts));
        String tip = r.optString("tip", "");
        String whole = tip.isEmpty() ? note : tip;
        List<Kit.Action> actions = new ArrayList<>();
        // The Mac's own buttons run on the Mac; Copy is done here, on the phone.
        JSONArray buttons = r.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length(); i++) {
            JSONObject spec = buttons.optJSONObject(i);
            String button = spec == null ? "" : spec.optString("label");
            if (button.isEmpty() || button.equals("Copy")) continue;
            // The Mac says which buttons it can run from here; an older Mac says nothing, so all are shown.
            if (spec.has("runs") && !spec.optBoolean("runs")) continue;
            actions.add(new Kit.Action(buttonIcon(button), button, "On the Mac", () -> press(r, button, false)));
        }
        actions.add(new Kit.Action(R.drawable.csi_copy, "Copy", firstLine(whole), () -> copy(whole)));
        sections.add(new Kit.Section(null, actions));
        Kit.sheet(a, label, note, sections);
    }

    private static boolean hasRunnable(JSONObject r, String button) {
        JSONArray buttons = r.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length(); i++) {
            JSONObject spec = buttons.optJSONObject(i);
            if (spec != null && button.equals(spec.optString("label")) && (!spec.has("runs") || spec.optBoolean("runs"))) return true;
        }
        return false;
    }

    private static int buttonIcon(String button) {
        switch (button) {
            case "Enable": case "Start": return R.drawable.csi_play;
            case "Approve": return R.drawable.csi_check;
            case "Deny": case "Cancel": return R.drawable.csi_close;
            case "Disable": case "Stop": case "Kill": return R.drawable.csi_stop;
            case "Open": return R.drawable.csi_link;
            default: return R.drawable.csi_tools;
        }
    }

    // Press a row's button on the Mac. A button that asks first gets its question asked here;
    // when it is done the tab is read again, since the row's state lives on the Mac.
    private void press(JSONObject r, String button, boolean confirmed) {
        act(r, button, null, confirmed);
    }

    // The same with a value for a button that asks for a line. A switch that fails is put back
    // by reading the tab again, since it already moved under the owner's finger.
    private void act(JSONObject r, String button, String value, boolean confirmed) {
        act(r, button, value, confirmed, null);
    }

    // settled runs on the screen's thread once the act has an answer or has failed, however it ended.
    private void act(JSONObject r, String button, String value, boolean confirmed, Runnable settled) {
        final String ip = Prefs.homeIp(a), token = Prefs.token(a), want = tab, key = r.optString("key");
        new Thread(() -> {
            JSONObject got;
            String why = null;
            boolean sentThenSilent = false;
            try {
                got = MeshClient.switchboardAct(ip, token, want, key, button, value, confirmed);
            } catch (Exception e) {
                got = null;
                why = e.getMessage();
                // A read timeout means the request went out and the Mac may have acted; a connect failure means it did not.
                sentThenSilent = e instanceof java.net.SocketTimeoutException
                    && (why == null || !why.toLowerCase(java.util.Locale.ROOT).contains("connect"));
            }
            final JSONObject answer = got;
            final String failure = why;
            final boolean mayHaveRun = sentThenSilent;
            ui.post(() -> {
                if (settled != null) settled.run();
                if (answer == null) {
                    String words = failure == null ? "The Mac did not answer" : failure;
                    if (mayHaveRun) {
                        // Pressing again could do it twice; show what the Mac now says instead.
                        Kit.failed(a, "The Mac did not answer in time. It may have done it.", "Reload", () -> {
                            if (showing && want.equals(tab)) load();
                        });
                    } else {
                        Kit.failed(a, words, () -> act(r, button, value, confirmed, settled));
                        if (showing && want.equals(tab)) load();
                    }
                } else if (answer.has("confirm")) {
                    Kit.confirm(a, button + " " + r.optString("label") + "?", answer.optString("confirm"),
                        buttonIcon(button), button, () -> act(r, button, value, true));
                } else if (!answer.optBoolean("ok")) {
                    Kit.failed(a, answer.optString("error", "The Mac could not do that"), null);
                    if (showing && want.equals(tab)) load();
                } else if (showing && want.equals(tab)) {
                    // The list stays where it is until the fresh one arrives.
                    load();
                }
            });
        }).start();
    }

    private void copy(String words) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("csync", words));
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return null;
        int cut = s.indexOf('\n');
        return cut < 0 ? s : s.substring(0, cut);
    }
}
