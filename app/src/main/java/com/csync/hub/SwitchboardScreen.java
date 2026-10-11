package com.csync.hub;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Switchboard's list tabs, read from the Mac: Ledger, Queue, Plugins, Rules and Library, each
 * as the same groups and rows the Mac panel draws. Tapping a row opens its details in a drawer.
 *
 * The rows come from the Mac's csync agent, which runs Switchboard's own export, so the phone
 * never keeps a second copy. A tab that loaded once stays on screen when a later refresh fails.
 */
final class SwitchboardScreen {
    private static final String[] TABS = {"ledger", "queue", "plugins", "rules", "library"};
    private static final String[] TAB_WORDS = {"Ledger", "Queue", "Plugins", "Rules", "Library"};
    private static final int[] TAB_ICONS = {R.drawable.csi_history, R.drawable.csi_view_list,
        R.drawable.csi_tools, R.drawable.csi_check, R.drawable.csi_files};

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
                } else {
                    unreachable("The Mac did not answer",
                        (failure == null ? "No reply" : failure) + ". Asked " + ip + "; the Mac may be asleep or off the tailnet.",
                        "Try again", this::load);
                }
            });
        }).start();
    }

    private void unreachable(String title, String why, String go, Runnable action) {
        if (host.getChildCount() < 2) render();
        Kit.notice((LinearLayout) host.getChildAt(1), R.drawable.csi_alert, title, why, go, action);
    }

    private void groups(LinearLayout body, JSONObject got) {
        JSONArray groups = got.optJSONArray("groups");
        if (groups == null || groups.length() == 0) {
            Kit.empty(body, TAB_ICONS[tabIndex()], "Nothing in " + TAB_WORDS[tabIndex()], "The Mac's Switchboard shows no rows here either.", null);
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
        Kit.label(body, total > 0 ? title + "  " + total : title);
        LinearLayout box = Kit.group(body);
        if (shown == 0) {
            Kit.bindRow(Kit.addRow(box), TAB_ICONS[tabIndex()], "Nothing here", null, null, false);
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
        View row = Kit.addRow(box);
        Kit.bindRow(row, TAB_ICONS[tabIndex()], label, firstLine(note), end, opens);
        if ("on".equals(state)) Kit.rowStatus(row, Kit.Status.GOOD, "On");
        if (!r.optBoolean("enabled", true)) row.setAlpha(0.5f);
        row.setOnClickListener(v -> details(r));
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
        actions.add(new Kit.Action(R.drawable.csi_copy, "Copy", firstLine(whole), () -> copy(whole)));
        sections.add(new Kit.Section(null, actions));
        Kit.sheet(a, label, note, sections);
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
