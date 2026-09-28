package com.csync.hub;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.MaterialColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Search across the Pi media index and the phone's saved chats, inbox, and roster. */
public final class SearchActivity extends AppCompatActivity {
    private static final String[] SCOPES = {"All", "Media", "Chats", "Files", "Devices"};
    private static final int[] SCOPE_ICONS = {R.drawable.csi_menu, R.drawable.csi_media,
        R.drawable.csi_chat, R.drawable.csi_file, R.drawable.csi_device};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final List<Result> local = new ArrayList<>();
    private final List<Result> media = new ArrayList<>();
    private final List<TextView> scopeViews = new ArrayList<>();
    private EditText queryView;
    private LinearLayout resultsView;
    private TextView countView, sourceView;
    private View clearView;
    private String scope = "All", mediaState = "", peersState = "";
    private boolean mediaLoading, peersLoading;
    private int generation;
    private Runnable scheduledSearch;

    private static final class Result {
        final String domain, title, subtitle, id, driveId, path;
        final int icon;

        Result(String domain, String title, String subtitle, int icon,
               String id, String driveId, String path) {
            this.domain = domain;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.id = id;
            this.driveId = driveId;
            this.path = path;
        }
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Appearance.apply(this);
        setContentView(R.layout.activity_search);
        Appearance.applySystemBars(this);
        queryView = findViewById(R.id.search_query);
        resultsView = findViewById(R.id.search_results);
        countView = findViewById(R.id.search_count);
        sourceView = findViewById(R.id.search_source_state);
        clearView = findViewById(R.id.search_clear);
        Kit.pageTop(findViewById(R.id.search_top), this::finish,
            new Kit.Crumb(Kit.Icon.HOME, "Home", this::finish), new Kit.Crumb(Kit.Icon.SEARCH, "Search", null));
        clearView.setOnClickListener(v -> queryView.setText(""));

        LinearLayout tabs = findViewById(R.id.search_scopes);
        for (int i = 0; i < SCOPES.length; i++) {
            final String target = SCOPES[i];
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(dp(42));
            tab.setPadding(dp(8), 0, dp(8), 0);
            ImageView icon = new ImageView(this);
            icon.setImageResource(SCOPE_ICONS[i]);
            icon.setColorFilter(color(R.color.dim));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon, new LinearLayout.LayoutParams(dp(15), dp(15)));
            TextView label = new TextView(this);
            label.setText(target);
            label.setTextSize(11);
            label.setTypeface(null, Typeface.BOLD);
            label.setTextColor(color(R.color.dim));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
            labelParams.leftMargin = dp(4);
            tab.addView(label, labelParams);
            tab.setContentDescription(target + " search results");
            tab.setOnClickListener(v -> { scope = target; render(); });
            tabs.addView(tab);
            scopeViews.add(label);
        }
        queryView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearView.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                scheduleSearch();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        com.google.android.material.bottomnavigation.BottomNavigationView nav =
            findViewById(R.id.search_nav);
        nav.setBackgroundColor(color(R.color.surface));
        nav.setElevation(0f);
        nav.setSelectedItemId(R.id.nav_home);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) { finish(); return true; }
            if (id == R.id.nav_media) startActivity(new Intent(this, MediaActivity.class));
            else openMain(id == R.id.nav_share ? "share" : id == R.id.nav_chat ? "chat" : "more", null, null);
            return false;
        });

        if (saved != null) {
            scope = saved.getString("scope", "All");
            queryView.setText(saved.getString("query", ""));
            queryView.setSelection(queryView.length());
        } else {
            String initial = getIntent().getStringExtra("query");
            if (initial != null) queryView.setText(initial);
        }
        clearView.setVisibility(queryView.length() == 0 ? View.GONE : View.VISIBLE);
        if (queryView.length() == 0) scheduleSearch();
    }

    @Override protected void onResume() {
        super.onResume();
        scheduleSearch();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("scope", scope);
        out.putString("query", queryView.getText().toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        generation++;
        if (scheduledSearch != null) main.removeCallbacks(scheduledSearch);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void scheduleSearch() {
        if (scheduledSearch != null) main.removeCallbacks(scheduledSearch);
        int current = ++generation;
        String query = queryView.getText().toString().trim();
        collectLocal(query);
        media.clear();
        mediaLoading = !query.isEmpty() && query.length() <= 128 && !Prefs.token(this).isEmpty();
        peersLoading = !Prefs.token(this).isEmpty();
        mediaState = query.length() > 128 ? "Pi media search accepts up to 128 characters." :
            query.isEmpty() ? "Enter a name to search Pi media." :
            Prefs.token(this).isEmpty() ? "Set a mesh token in Settings to search Pi media." : "";
        peersState = Prefs.token(this).isEmpty() ? "Device availability has not been checked." : "";
        render();
        scheduledSearch = () -> {
            if (Prefs.token(this).isEmpty()) return;
            if (!query.isEmpty() && query.length() <= 128)
                worker.execute(() -> fetchMedia(current, query));
            worker.execute(() -> fetchPeers(current, query));
        };
        main.postDelayed(scheduledSearch, 300);
    }

    private void collectLocal(String query) {
        local.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        JSONArray conversations = ChatStore.index(this);
        for (int i = 0; i < conversations.length(); i++) {
            JSONObject chat = conversations.optJSONObject(i);
            if (chat == null) continue;
            String id = chat.optString("id"), title = chat.optString("title");
            if (id.isEmpty()) continue;
            boolean titleMatch = title.toLowerCase(Locale.ROOT).contains(needle);
            boolean messageMatch = false;
            if (!needle.isEmpty() && !titleMatch) {
                JSONArray turns = ChatStore.transcript(this, id);
                for (int t = 0; t < turns.length(); t++) {
                    JSONObject turn = turns.optJSONObject(t);
                    if (turn != null && turn.optString("text").toLowerCase(Locale.ROOT).contains(needle)) {
                        messageMatch = true;
                        break;
                    }
                }
            }
            if (titleMatch || messageMatch) local.add(new Result("Chats",
                title.isEmpty() ? "Untitled conversation" : title,
                messageMatch && !titleMatch ? "Saved chat · matching message" : "Saved chat",
                R.drawable.csi_chat, id, "", ""));
        }
        File inbox = getExternalFilesDir("inbox");
        File[] senders = inbox == null ? null : inbox.listFiles();
        if (senders != null) for (File sender : senders) {
            File[] files = sender.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                String subtitle = "Inbox · received from " + sender.getName();
                if (matches(needle, file.getName(), subtitle)) local.add(new Result("Files",
                    file.getName(), subtitle, R.drawable.csi_file,
                    file.getAbsolutePath(), "", ""));
            }
        }
        JSONArray roster = PeerStore.load(this);
        for (int i = 0; i < roster.length(); i++) {
            JSONObject peer = roster.optJSONObject(i);
            if (peer == null) continue;
            String name = peer.optString("name");
            if (name.isEmpty()) continue;
            String subtitle = "Saved device · availability not checked";
            if (matches(needle, name, peer.optString("platform"))) local.add(new Result("Devices",
                name, subtitle, R.drawable.csi_device, name, "", ""));
        }
    }

    private void fetchMedia(int request, String query) {
        String token = Prefs.token(this);
        String host = Prefs.assistIp(this);
        try {
            MediaClient client = new MediaClient(host, token);
            JSONObject response = client.get("/v1/search?q=" + MediaClient.enc(query));
            JSONArray items = response.optJSONArray("items");
            List<Result> found = new ArrayList<>();
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String mime = item.optString("mime");
                String drive = item.optString("driveId");
                String path = item.optString("relativePath");
                found.add(new Result("Media", item.optString("name"),
                    "Pi media · " + drive + (path.isEmpty() ? "" : " · " + path),
                    mime.startsWith("video/") ? R.drawable.csi_video :
                    mime.startsWith("image/") ? R.drawable.csi_photo : R.drawable.csi_file,
                    item.optString("id"), drive, path));
            }
            String availability = "";
            if (found.isEmpty()) {
                try {
                    JSONArray drives = client.get("/v1/drives").optJSONArray("drives");
                    boolean mounted = false;
                    if (drives != null) for (int i = 0; i < drives.length(); i++) {
                        JSONObject drive = drives.optJSONObject(i);
                        if (drive != null && drive.optBoolean("online")) mounted = true;
                    }
                    if (!mounted) availability = "No Pi media drives are mounted.";
                } catch (Exception ignored) {
                    availability = "Pi drive availability could not be checked.";
                }
            }
            String finalAvailability = availability;
            main.post(() -> {
                if (request != generation) return;
                media.clear();
                media.addAll(found);
                mediaLoading = false;
                mediaState = response.optBoolean("truncated") ?
                    "Pi media results are limited. Narrow your search." : finalAvailability;
                render();
            });
        } catch (Exception error) {
            main.post(() -> {
                if (request != generation) return;
                mediaLoading = false;
                mediaState = "Pi media unavailable. Saved results are still shown.";
                render();
            });
        }
    }

    private void fetchPeers(int request, String query) {
        String token = Prefs.token(this);
        String host = Prefs.assistIp(this);
        try {
            JSONArray scanned = MeshClient.peers(host, token);
            main.post(() -> {
                if (request != generation) return;
                PeerStore.mergeScan(this, scanned);
                collectLocal(query);
                peersLoading = false;
                peersState = "";
                for (int i = 0; i < local.size(); i++) {
                    Result result = local.get(i);
                    if (!"Devices".equals(result.domain)) continue;
                    JSONArray roster = PeerStore.load(this);
                    for (int p = 0; p < roster.length(); p++) {
                        JSONObject peer = roster.optJSONObject(p);
                        if (peer != null && result.id.equals(peer.optString("name"))) {
                            local.set(i, new Result("Devices", result.title,
                                peer.optBoolean("online") ? "Device · online" : "Device · offline",
                                result.icon, result.id, "", ""));
                            break;
                        }
                    }
                }
                render();
            });
        } catch (Exception error) {
            main.post(() -> {
                if (request != generation) return;
                peersLoading = false;
                peersState = "Device scan unavailable. Saved devices may be out of date.";
                render();
            });
        }
    }

    private void render() {
        int accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary,
            color(R.color.coral));
        LinearLayout tabs = findViewById(R.id.search_scopes);
        for (int i = 0; i < scopeViews.size(); i++) {
            boolean selected = SCOPES[i].equals(scope);
            LinearLayout tab = (LinearLayout) tabs.getChildAt(i);
            TextView label = scopeViews.get(i);
            label.setTextColor(selected ? accent : color(R.color.dim));
            ((ImageView) tab.getChildAt(0)).setColorFilter(selected ? accent : color(R.color.dim));
            tab.setBackground(selected ? shape(color(R.color.surface), 9) : null);
            tab.setSelected(selected);
        }

        List<Result> visible = new ArrayList<>();
        if ("All".equals(scope) || "Media".equals(scope)) visible.addAll(media);
        for (Result result : local) if ("All".equals(scope) || scope.equals(result.domain)) visible.add(result);
        countView.setText(visible.size() + (visible.size() == 1 ? " RESULT" : " RESULTS"));
        resultsView.removeAllViews();
        if (visible.isEmpty()) {
            String empty = mediaLoading || peersLoading ? "Searching your sources" :
                queryView.length() == 0 ? "Enter a name to search your hub." :
                "No matches in " + scope.toLowerCase(Locale.ROOT) + ".";
            TextView message = new TextView(this);
            message.setText(empty);
            message.setTextColor(color(R.color.dim));
            message.setTextSize(13);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(14), dp(22), dp(14), dp(22));
            resultsView.addView(message);
        } else {
            for (Result result : visible) addRow(result);
        }
        String status = (mediaLoading && ("All".equals(scope) || "Media".equals(scope)) ?
            "Searching Pi media\n" : "") +
            (peersLoading && ("All".equals(scope) || "Devices".equals(scope)) ?
            "Checking devices\n" : "") +
            (("All".equals(scope) || "Media".equals(scope)) ? mediaState + "\n" : "") +
            (("All".equals(scope) || "Devices".equals(scope)) ? peersState : "");
        sourceView.setText(status.trim());
        sourceView.setVisibility(status.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addRow(Result result) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        ImageView icon = new ImageView(this);
        icon.setImageResource(result.icon);
        icon.setColorFilter(MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorPrimary, color(R.color.coral)));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(shape(color(R.color.nav_indicator), 10));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(10);
        row.addView(copy, copyParams);
        TextView title = new TextView(this);
        title.setText(result.title);
        title.setTextSize(14);
        title.setTextColor(color(R.color.text));
        title.setTypeface(null, Typeface.BOLD);
        copy.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(result.subtitle);
        subtitle.setTextSize(11);
        subtitle.setTextColor(color(R.color.dim));
        copy.addView(subtitle);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.csi_forward);
        arrow.setColorFilter(color(R.color.dim));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        row.setContentDescription(result.title + ", " + result.subtitle);
        row.setOnClickListener(v -> openResult(result));
        resultsView.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.border));
        resultsView.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void openResult(Result result) {
        switch (result.domain) {
            case "Media":
                startActivity(new Intent(this, MediaActivity.class)
                    .putExtra("search_item_id", result.id)
                    .putExtra("search_drive_id", result.driveId)
                    .putExtra("search_relative_path", result.path)
                    .putExtra("search_query", queryView.getText().toString()));
                break;
            case "Chats":
                openMain("chat", "conversation_id", result.id);
                break;
            case "Files":
                openMain("share", "inbox_file_path", result.id);
                break;
            case "Devices":
                openMain("share", "recipient_name", result.id);
                break;
        }
    }

    private void openMain(String destination, String key, String value) {
        Intent intent = new Intent(this, MainActivity.class).putExtra("destination", destination);
        if (key != null) intent.putExtra(key, value);
        startActivity(intent);
    }

    private static boolean matches(String needle, String title, String subtitle) {
        return title.toLowerCase(Locale.ROOT).contains(needle) ||
            subtitle.toLowerCase(Locale.ROOT).contains(needle);
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int color(int id) { return getColor(id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
