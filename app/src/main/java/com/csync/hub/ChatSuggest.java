package com.csync.hub;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The list that opens above the message box as you type. A message that starts with "/" offers
 * the things the assistant is asked for most and the skills saved on the Pi; "@" anywhere offers
 * your devices and your notes by name. Picking one writes it into the message; nothing is sent
 * until you press Send.
 */
final class ChatSuggest {

    private static final class Choice {
        final int icon; final String title, sub, writes;
        Choice(int icon, String title, String sub, String writes) {
            this.icon = icon; this.title = title; this.sub = sub; this.writes = writes;
        }
    }

    private static final int SHOWN_ROWS = 3, ROW_DP = 60;

    private final Context context;
    private final EditText input;
    private final ScrollView frame;
    private final LinearLayout rows;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Choice> skills = new ArrayList<>(), notes = new ArrayList<>();
    private volatile boolean learned;
    private long askedAt;

    ChatSuggest(View page, EditText input) {
        context = page.getContext();
        this.input = input;
        frame = page.findViewById(R.id.chat_suggest);
        rows = page.findViewById(R.id.chat_suggest_rows);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { show(); }
        });
    }

    boolean isOpen() { return frame.getVisibility() == View.VISIBLE; }

    void close() { frame.setVisibility(View.GONE); }

    /** The word being typed: from the last space or line break up to the cursor. */
    private String word() {
        int end = Math.max(0, input.getSelectionStart());
        String before = input.getText().toString().substring(0, Math.min(end, input.length()));
        int from = Math.max(before.lastIndexOf(' '), before.lastIndexOf('\n')) + 1;
        return before.substring(from);
    }

    private void show() {
        String word = word();
        boolean slash = word.startsWith("/") && word.length() == input.getSelectionStart();
        boolean at = word.startsWith("@");
        if (!slash && !at) { close(); return; }
        learn();
        String typed = word.substring(1).toLowerCase(Locale.ROOT);
        List<Choice> matching = new ArrayList<>();
        for (Choice choice : slash ? asks() : mentions())
            if (choice.title.toLowerCase(Locale.ROOT).contains(typed)) matching.add(choice);
        if (matching.isEmpty()) { close(); return; }
        rows.removeAllViews();
        LinearLayout group = Kit.group(rows);
        for (Choice choice : matching) {
            View row = Kit.addRow(group);
            Kit.bindRow(row, choice.icon, choice.title, choice.sub, null, false);
            row.setOnClickListener(v -> write(choice));
        }
        // More than a few choices scroll inside the list, so the conversation stays in view.
        frame.getLayoutParams().height = matching.size() > SHOWN_ROWS
            ? Kit.dp(context, SHOWN_ROWS * ROW_DP + ROW_DP / 3) : LinearLayout.LayoutParams.WRAP_CONTENT;
        frame.requestLayout();
        frame.scrollTo(0, 0);
        frame.setVisibility(View.VISIBLE);
    }

    private void write(Choice choice) {
        int end = Math.max(0, input.getSelectionStart());
        int from = end - word().length();
        input.getText().replace(from, end, choice.writes);
        input.setSelection(Math.min(input.length(), from + choice.writes.length()));
    }

    private List<Choice> asks() {
        List<Choice> all = new ArrayList<>();
        all.add(new Choice(Kit.Icon.MEDIA, "Play", "Find something and play it on the Pi screen", "Play on the Pi screen: "));
        all.add(new Choice(Kit.Icon.SEARCH, "Find", "Look for a file on the drives", "Find on the drives: "));
        all.add(new Choice(Kit.Icon.SHARE, "Send", "Send text to one of your devices", "Send to @"));
        all.add(new Choice(Kit.Icon.NOTES, "Note", "Add to your notes on the Pi", "Add to my notes: "));
        all.add(new Choice(Kit.Icon.CAMERA, "Camera", "Take a photo with the Pi camera", "Take a photo with the Pi camera"));
        all.add(new Choice(Kit.Icon.TOOLS, "Health", "Check storage, memory and services", "Is the Pi healthy?"));
        all.addAll(skills);
        return all;
    }

    private List<Choice> mentions() {
        List<Choice> all = new ArrayList<>();
        JSONArray devices = PeerStore.load(context);
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null || device.optString("name").isEmpty()) continue;
            all.add(new Choice(Kit.Icon.DEVICE, device.optString("name"),
                device.optBoolean("online") ? "Device, online" : "Device, offline",
                "@" + device.optString("name") + " "));
        }
        all.addAll(notes);
        return all;
    }

    /**
     * Ask the Pi for its saved skills and your notes. Until it answers, the built-in choices show
     * alone. A Pi that cannot answer is asked again after half a minute, not on every keystroke.
     */
    private void learn() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (learned || (askedAt != 0 && now - askedAt < 30000)) return;
        askedAt = now;
        final String host = Prefs.assistIp(context), token = Prefs.token(context);
        if (host.isEmpty() || token.isEmpty()) return;
        new Thread(() -> {
            final List<Choice> foundSkills = new ArrayList<>(), foundNotes = new ArrayList<>();
            boolean complete = true;
            try {
                JSONArray list = MeshClient.conversations(host, token, "GET", "/skills", null).optJSONArray("skills");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject skill = list.optJSONObject(i);
                    if (skill == null || skill.optString("name").isEmpty()) continue;
                    String summary = skill.optString("summary");
                    foundSkills.add(new Choice(Kit.Icon.FILE, skill.optString("name"),
                        summary.isEmpty() ? "Saved skill" : summary, "Use the skill " + skill.optString("name") + ". "));
                }
            } catch (Exception unavailable) { complete = false; }
            try {
                JSONArray list = new MediaClient(host, token).get("/v1/notes").optJSONArray("notes");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject note = list.optJSONObject(i);
                    if (note == null || note.optString("title").isEmpty()) continue;
                    foundNotes.add(new Choice(Kit.Icon.NOTES, note.optString("title"), "Note on the Pi",
                        "@\"" + note.optString("title") + "\" "));
                }
            } catch (Exception unavailable) { complete = false; }
            learned = complete;
            ui.post(() -> {
                skills.clear(); skills.addAll(foundSkills);
                notes.clear(); notes.addAll(foundNotes);
                if (isOpen()) show();
            });
        }, "chat-suggest").start();
    }
}
