package com.csync.hub;

import android.content.Intent;
import android.content.ClipData;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.TextWatcher;
import android.text.Editable;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import io.noties.markwon.Markwon;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Pi-backed Markdown notes. All network operations run off the main thread. */
public final class NotesActivity extends AppCompatActivity {

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, rows, actions, topBar, modeTabs;
    private TextView status, preview, heading;
    private EditText title, body, search;
    private JSONArray listedNotes, listedPins;
    private boolean pinsAvailable = true;
    private MediaClient client;
    private String noteId;
    private int revision;
    private boolean noteSaving;
    private Uri sharedImage;
    // A file that arrived to be kept beside a note, and the name it goes by.
    private Uri sharedFile;
    private String sharedFileName;
    private boolean sharedImagePrompted;
    private boolean imageUploading;
    private boolean editing;
    private String editorMode = "plain";
    private Markwon markwon;
    private Runnable pendingNoteSearch;
    private int noteSearchGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Appearance.apply(this);
        Appearance.applySystemBars(this);
        markwon = Markwon.create(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        setContentView(root);
        topBar = (LinearLayout) getLayoutInflater().inflate(R.layout.kit_page_top, root, false);
        root.addView(topBar);
        heading = new TextView(this);
        heading.setText("Notes");
        heading.setTextAppearance(R.style.Kit_Text_PageTitle);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(dp(20), dp(6), dp(20), 0);
        root.addView(heading, headingParams);
        status = new TextView(this);
        status.setTextAppearance(R.style.Kit_Text_PageSub);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(dp(20), dp(5), dp(20), dp(14));
        root.addView(status, statusParams);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Search notes and pins");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(cardBackground());
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.setMargins(dp(20), 0, dp(20), dp(8));
        root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderListRows();
                if (client == null || noteId != null || editing) return;
                if (pendingNoteSearch != null) ui.removeCallbacks(pendingNoteSearch);
                String term = s.toString().trim();
                pendingNoteSearch = () -> loadNotes(term);
                ui.postDelayed(pendingNoteSearch, 250);
            }
            public void afterTextChanged(Editable s) {}
        });
        actions = new LinearLayout(this);
        actions.setPadding(dp(20), 0, dp(20), dp(8));
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setFillViewport(true);
        actionScroll.addView(actions);
        root.addView(actionScroll);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20), 0, dp(20), dp(16));
        scroll.addView(rows);
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
            new com.google.android.material.bottomnavigation.BottomNavigationView(this);
        nav.inflateMenu(R.menu.nav_menu);
        nav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED);
        nav.setItemIconSize(dp(24));
        nav.setItemIconTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_icon_tint));
        nav.setItemActiveIndicatorColor(ColorStateList.valueOf(getColor(R.color.nav_indicator)));
        nav.setBackgroundColor(getColor(R.color.surface));
        nav.setSelectedItemId(R.id.nav_more);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_more) { finish(); return false; }
            if (item.getItemId() == R.id.nav_home) {
                startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("destination", "home"));
                return false;
            }
            if (item.getItemId() == R.id.nav_media) {
                startActivity(new Intent(this, MediaActivity.class));
                return false;
            }
            String destination = item.getItemId() == R.id.nav_share ? "share" :
                item.getItemId() == R.id.nav_chat ? "chat" : "more";
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("destination", destination));
            return false;
        });
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(64)));
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) {
            status.setText("Connect to the Pi in Settings to use Notes");
            return;
        }
        client = new MediaClient(host, token);
        sharedImage = state == null ? getIntent().getParcelableExtra("note_image_uri") :
            state.getParcelable("shared_image_uri");
        sharedFile = state == null ? getIntent().getParcelableExtra("note_file_uri") :
            state.getParcelable("shared_file_uri");
        sharedFileName = getIntent().getStringExtra("note_file_name");
        showList();
        String pinUrl = getIntent().getStringExtra("pin_prefill_url");
        String pinText = getIntent().getStringExtra("pin_prefill_text");
        String noteBody = getIntent().getStringExtra("note_prefill_body");
        Uri pinFile = state == null ? getIntent().getParcelableExtra("pin_file_uri") : null;
        if (pinFile != null) pinFile(pinFile, getIntent().getStringExtra("pin_file_name"));
        else if (pinUrl != null || pinText != null) editPin(null, pinUrl == null ? "" : pinUrl,
            pinText == null ? "" : pinText);
        else if (noteBody != null && sharedImage == null && sharedFile == null) showEditor("", noteBody);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable("shared_image_uri", sharedImage);
        state.putParcelable("shared_file_uri", sharedFile);
        super.onSaveInstanceState(state);
    }

    /** Notes lives under More, so its path starts there. */
    private Kit.Crumb moreCrumb() {
        return new Kit.Crumb(Kit.Icon.MORE, "More", () -> startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", "more")));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(R.color.surface));
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(1), getColor(R.color.border));
        return shape;
    }

    private Button action(String label, Runnable click) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        boolean primary = "Save".equals(label);
        boolean destructive = "Delete".equals(label);
        button.setTextColor(primary ? getColor(R.color.onAccent) :
            destructive ? getColor(R.color.danger) : getColor(R.color.text));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? accentColor() : getColor(R.color.surface2));
        background.setCornerRadius(dp(11));
        button.setBackground(background);
        button.setMinimumHeight(dp(42));
        button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(42));
        params.setMargins(0, 0, dp(8), 0);
        actions.addView(button, params);
        return button;
    }

    private void reset() {
        actions.removeAllViews();
        rows.removeAllViews();
        title = null;
        body = null;
        preview = null;
        modeTabs = null;
    }

    private void addModeTabs(JSONObject note) {
        modeTabs = new LinearLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(getColor(R.color.surface2));
        track.setCornerRadius(dp(13));
        modeTabs.setBackground(track);
        modeTabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(0, 0, 0, dp(16));
        rows.addView(modeTabs, params);
        for (String mode : new String[]{"preview", "rich", "plain"}) {
            TextView tab = new TextView(this);
            tab.setTag(mode);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setTextSize(13);
            boolean selected = editing ? mode.equals(editorMode) : mode.equals("preview");
            styleModeTab(tab, selected);
            modeTabs.addView(tab, new LinearLayout.LayoutParams(0, dp(42), 1));
            tab.setOnClickListener(v -> {
                if (editing) setEditorMode(mode);
                else if (!mode.equals("preview")) showEditor(note.optString("title"), note.optString("body"), mode);
            });
        }
    }

    private void styleModeTab(TextView tab, boolean selected) {
        String mode = (String) tab.getTag();
        String label = Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
        int iconId = "preview".equals(mode) ? R.drawable.csi_photo :
            "rich".equals(mode) ? R.drawable.csi_edit : R.drawable.csi_note;
        android.graphics.drawable.Drawable icon = getDrawable(iconId).mutate();
        icon.setBounds(0, 0, dp(16), dp(16));
        icon.setTint(selected ? accentColor() : getColor(R.color.dim));
        android.text.SpannableString content = new android.text.SpannableString("  " + label);
        content.setSpan(new android.text.style.ImageSpan(icon,
            android.text.style.ImageSpan.ALIGN_BOTTOM), 0, 1,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tab.setText(content);
        tab.setContentDescription(label + (selected ? ", selected" : ""));
        tab.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tab.setTextColor(selected ? Kit.accentText(this) : getColor(R.color.dim));
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? getColor(R.color.surface) : getColor(R.color.surface2));
        background.setCornerRadius(dp(10));
        tab.setBackground(background);
        tab.setSelected(selected);
    }

    private int accentColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, value, true);
        return value.data;
    }

    private void task(Work work) {
        new Thread(() -> {
            try { work.run(); }
            catch (Exception error) { ui.post(() -> status.setText(error.getMessage())); }
        }, "csync-notes").start();
    }

    private interface Work { void run() throws Exception; }

    private void showList() {
        noteId = null;
        editing = false;
        reset();
        Kit.pageTop(topBar, this::finish, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", null));
        heading.setText("Notes");
        search.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);
        loadNotes(search.getText().toString().trim());
        refreshPins();
    }

    private void loadNotes(String query) {
        int request = ++noteSearchGeneration;
        if (query.length() > 128) {
            listedNotes = new JSONArray();
            status.setText("Search notes with up to 128 characters");
            renderListRows();
            return;
        }
        status.setText(query.isEmpty() ? "Loading notes from Pi" : "Searching Pi notes");
        new Thread(() -> {
            try {
                String route = "/v1/notes" + (query.isEmpty() ? "" : "?q=" + MediaClient.enc(query));
                JSONArray notes = client.get(route).getJSONArray("notes");
                ui.post(() -> {
                    if (request != noteSearchGeneration || noteId != null || editing) return;
                    listedNotes = notes;
                    status.setText(query.isEmpty() ?
                        (listedPins == null ? notes.length() + " saved Markdown notes on Pi" :
                            notes.length() + " notes and " + listedPins.length() + " pins on Pi") :
                        notes.length() + " matching Pi notes");
                    renderListRows();
                    if ((sharedImage != null || sharedFile != null) && !sharedImagePrompted) chooseSharedNote();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (request == noteSearchGeneration && noteId == null && !editing)
                        status.setText("Pi notes unavailable: " + error.getMessage());
                });
            }
        }, "csync-note-search").start();
    }

    /** Ask which note an arriving picture or file goes into. Closing the drawer drops it. */
    private void chooseSharedNote() {
        sharedImagePrompted = true;
        String caption = getIntent().getStringExtra("note_image_caption");
        java.util.List<Kit.Action> choices = new java.util.ArrayList<>();
        choices.add(new Kit.Action(R.drawable.csi_plus, "A new note", null,
            () -> showEditor("", caption == null ? "" : caption)));
        for (int i = 0; listedNotes != null && i < listedNotes.length(); i++) {
            JSONObject note = listedNotes.optJSONObject(i);
            if (note == null) continue;
            choices.add(new Kit.Action(Kit.Icon.NOTES, note.optString("title", "Untitled note"), null, () -> {
                if (caption == null || caption.isEmpty()) uploadShared(note.optString("id"));
                else task(() -> {
                    JSONObject latest = client.get("/v1/notes/" +
                        MediaClient.enc(note.optString("id"))).getJSONObject("note");
                    ui.post(() -> {
                        noteId = latest.optString("id");
                        revision = latest.optInt("revision");
                        String source = latest.optString("body");
                        showEditor(latest.optString("title"), source +
                            (source.isEmpty() ? "" : "\n\n") + caption);
                    });
                });
            }));
        }
        Kit.sheet(this, "Add to a note", sharedFile == null ? "The picture is drawn inside the note" :
                sharedFileName + " is kept beside the note", choices.toArray(new Kit.Action[0]))
            .setOnCancelListener(closed -> dropShared());
    }

    private void dropShared() {
        sharedImage = null;
        sharedFile = null;
    }

    /** Add whatever arrived, a picture or a file, to the note that was picked. */
    private void uploadShared(String id) {
        if (sharedFile != null) uploadSharedFile(id);
        else uploadSharedImage(id);
    }

    /** Read a file that was handed over, up to the 20 MB the Pi keeps. */
    private byte[] readAll(Uri source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = getContentResolver().openInputStream(source)) {
            if (input == null) throw new Exception("The file could not be read");
            byte[] chunk = new byte[65536];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > 20 * 1024 * 1024) throw new Exception("Choose a file under 20 MB");
                bytes.write(chunk, 0, count);
            }
        }
        return bytes.toByteArray();
    }

    /** Keep a file on the Pi as a pin of its own, named after the file. */
    private void pinFile(Uri source, String givenName) {
        String name = givenName == null || givenName.isEmpty() ? "file" : givenName;
        status.setText("Saving " + name + " as a pin");
        new Thread(() -> {
            try {
                client.uploadPinFile(name, getContentResolver().getType(source), readAll(source));
                ui.post(() -> {
                    android.widget.Toast.makeText(this, "Saved to Pins", android.widget.Toast.LENGTH_SHORT).show();
                    refreshPins();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    status.setText("The pin was not saved");
                    Kit.sheet(this, "The pin was not saved", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> pinFile(source, givenName)));
                });
            }
        }, "csync-pin-file").start();
    }

    private void uploadSharedFile(String id) {
        if (sharedFile == null || imageUploading || id == null || id.isEmpty()) return;
        Uri source = sharedFile;
        String name = sharedFileName == null || sharedFileName.isEmpty() ? "file" : sharedFileName;
        imageUploading = true;
        status.setText("Adding " + name + " to the note");
        new Thread(() -> {
            try {
                client.uploadNoteFile(id, name, getContentResolver().getType(source), readAll(source));
                ui.post(() -> {
                    imageUploading = false;
                    sharedFile = null;
                    loadNote(id);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    imageUploading = false;
                    status.setText("The file was not added");
                    Kit.sheet(this, "The file was not added", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> uploadSharedFile(id)),
                        new Kit.Action(Kit.Icon.NOTES, "Open the note without it", null, () -> {
                            sharedFile = null;
                            loadNote(id);
                        }));
                });
            }
        }, "csync-note-shared-file").start();
    }

    private byte[] sharedImagePng(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Cannot read shared image");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new Exception("Unsupported image");
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / decode.inSampleSize > 1600) decode.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Cannot read shared image");
            bitmap = BitmapFactory.decodeStream(input, null, decode);
        }
        if (bitmap == null) throw new Exception("Unsupported image");
        try {
            for (int edge = 1600; edge >= 400; edge /= 2) {
                int width = Math.min(bitmap.getWidth(), edge);
                int height = Math.min(bitmap.getHeight(), edge);
                if (bitmap.getWidth() > edge || bitmap.getHeight() > edge) {
                    float scale = Math.min((float) edge / bitmap.getWidth(),
                        (float) edge / bitmap.getHeight());
                    width = Math.max(1, Math.round(bitmap.getWidth() * scale));
                    height = Math.max(1, Math.round(bitmap.getHeight() * scale));
                }
                Bitmap output = width == bitmap.getWidth() && height == bitmap.getHeight() ?
                    bitmap : Bitmap.createScaledBitmap(bitmap, width, height, true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                output.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                if (output != bitmap) output.recycle();
                if (bytes.size() <= 1024 * 1024) return bytes.toByteArray();
            }
        } finally { bitmap.recycle(); }
        throw new Exception("Image is too large for a Pi note");
    }

    private void uploadSharedImage(String id) {
        if (sharedImage == null || imageUploading || id == null || id.isEmpty()) return;
        Uri source = sharedImage;
        imageUploading = true;
        status.setText("Adding image to Pi note");
        new Thread(() -> {
            try {
                client.uploadNoteImage("/v1/notes/" + MediaClient.enc(id) + "/images",
                    sharedImagePng(source));
                ui.post(() -> {
                    imageUploading = false;
                    sharedImage = null;
                    loadNote(id);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    imageUploading = false;
                    status.setText("The picture was not added");
                    Kit.sheet(this, "The picture was not added", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> uploadSharedImage(id)),
                        new Kit.Action(Kit.Icon.NOTES, "Open the note without it", null, () -> {
                            sharedImage = null;
                            loadNote(id);
                        }));
                });
            }
        }, "csync-note-shared-image").start();
    }

    private void refreshPins() {
        task(() -> {
            try {
                JSONArray pins = client.get("/v1/pins").getJSONArray("pins");
                ui.post(() -> {
                    listedPins = pins;
                    pinsAvailable = true;
                    if (noteId == null && !editing) {
                        if (search.length() == 0) status.setText(
                            (listedNotes == null ? 0 : listedNotes.length()) +
                            " notes and " + pins.length() + " pins on Pi");
                        renderListRows();
                    }
                });
            } catch (Exception error) {
                ui.post(() -> {
                    listedPins = null;
                    pinsAvailable = false;
                    if (noteId == null && !editing) renderListRows();
                });
            }
        });
    }

    private void renderListRows() {
        if (noteId != null || editing || rows == null) return;
        rows.removeAllViews();
        if (listedNotes == null) return;
        String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        Kit.label(rows, "Saved notes");
        LinearLayout notesGroup = Kit.group(rows);
        int shown = 0;
        for (int i = 0; i < listedNotes.length(); i++) {
            JSONObject item = listedNotes.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            View row = Kit.addRow(notesGroup);
            Kit.bindRow(row, Kit.Icon.NOTES, item.optString("title", "Untitled"),
                "Markdown · revision " + item.optInt("revision"), null, true);
            row.setOnClickListener(v -> loadNote(id));
            shown++;
        }
        if (shown == 0) Kit.bindRow(Kit.addRow(notesGroup), Kit.Icon.NOTES,
            query.isEmpty() ? "No notes yet" : "No notes match this search",
            query.isEmpty() ? "Create one with New note" : "Try another word", null, false);
        com.google.android.material.button.MaterialButton newNote = new com.google.android.material.button.MaterialButton(this);
        newNote.setText("New note");
        newNote.setIconResource(R.drawable.csi_plus);
        newNote.setOnClickListener(v -> showEditor("", ""));
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(-2, dp(48));
        newParams.topMargin = dp(10);
        rows.addView(newNote, newParams);

        Kit.label(rows, "Saved pins");
        LinearLayout pinsGroup = Kit.group(rows);
        View addPin = Kit.addRow(pinsGroup);
        Kit.bindRow(addPin, R.drawable.csi_plus, "Save a pin", "A link or a text snippet, kept on the Pi", null, true);
        addPin.setOnClickListener(v -> editPin(null));
        if (listedPins == null) {
            Kit.bindRow(Kit.addRow(pinsGroup), R.drawable.csi_link,
                pinsAvailable ? "Loading pins from the Pi" : "Pins are unavailable on this Pi", null, null, false);
            return;
        }
        int pinShown = 0;
        for (int i = 0; i < listedPins.length(); i++) {
            JSONObject pin = listedPins.optJSONObject(i);
            if (pin == null) continue;
            String pinTitle = pin.optString("title");
            String pinUrl = pin.optString("url");
            String pinContent = pin.optString("content");
            String pinTags = pin.optJSONArray("tags") == null ? "" : pin.optJSONArray("tags").toString();
            if (!query.isEmpty() && !pinTitle.toLowerCase(java.util.Locale.ROOT).contains(query)
                && !pinUrl.toLowerCase(java.util.Locale.ROOT).contains(query)
                && !pinContent.toLowerCase(java.util.Locale.ROOT).contains(query)
                && !pinTags.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            JSONObject held = pin.optJSONObject("file");
            String displayTitle = pinTitle.isEmpty() ?
                (pinUrl.isEmpty() ? pinContent.split("\\n", 2)[0] : Uri.parse(pinUrl).getHost()) : pinTitle;
            String source = held != null ? android.text.format.Formatter.formatShortFileSize(this, held.optLong("size"))
                : pinUrl.isEmpty() ? "Text snippet" : Uri.parse(pinUrl).getHost();
            String tags = pinTagsLabel(pin);
            View row = Kit.addRow(pinsGroup);
            Kit.bindRow(row, held != null ? ItemActions.icon(ItemActions.kindOf(held.optString("mime")))
                    : pinUrl.isEmpty() ? R.drawable.csi_text : R.drawable.csi_link, displayTitle,
                source + (tags.isEmpty() ? "" : " · " + tags), null, true);
            row.setOnClickListener(v -> showPin(pin));
            pinShown++;
        }
        if (pinShown == 0 && !query.isEmpty())
            Kit.bindRow(Kit.addRow(pinsGroup), R.drawable.csi_link, "No pins match this search", null, null, false);
    }

    private void showPin(JSONObject pin) {
        String url = pin.optString("url");
        String content = pin.optString("content");
        String description = pin.optString("description");
        String title = pin.optString("title");
        // The same name the list gives it: its own title, else the site, else its first line.
        if (title.isEmpty()) title = url.isEmpty() ? content.split("\\n", 2)[0] : Uri.parse(url).getHost();
        String shareText = url + (url.isEmpty() || content.isEmpty() ? "" : "\n\n") + content;
        String tags = pinTagsLabel(pin);
        String shown = title;
        JSONObject held = pin.optJSONObject("file");
        ItemActions.Item item;
        if (held != null) {
            // A pin that holds a file offers what that kind of file offers anywhere else.
            String name = held.optString("name", "file"), mime = held.optString("mime", "application/octet-stream");
            String pinId = pin.optString("id");
            item = new ItemActions.Item(ItemActions.kindOf(mime), shown);
            item.sub = android.text.format.Formatter.formatShortFileSize(this, held.optLong("size")) + ", pinned"
                + (description.isEmpty() ? "" : "\n\n" + description) + (tags.isEmpty() ? "" : "\n\n" + tags);
            item.file = got -> fetch("/v1/pins/" + MediaClient.enc(pinId) + "/file",
                pinId + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"), mime, got);
        } else {
            item = new ItemActions.Item(url.isEmpty() ? ItemActions.Kind.TEXT : ItemActions.Kind.LINK, shown);
            item.sub = shareText + (description.isEmpty() ? "" : "\n\n" + description) + (tags.isEmpty() ? "" : "\n\n" + tags);
            item.text = shareText;
            item.link = url.isEmpty() ? null : url;
            item.textName = shown + ".txt";
        }
        item.isPin = true;
        item.more.add(new Kit.Action(R.drawable.csi_edit, "Edit", null, () -> editPin(pin)));
        item.more.add(new Kit.Action(R.drawable.csi_trash, "Delete", null, () ->
            Kit.confirm(this, "Delete this pin?", shown, R.drawable.csi_trash, "Delete", () -> task(() -> {
                client.delete("/v1/pins/" + MediaClient.enc(pin.optString("id")),
                    new JSONObject().put("expectedRevision", pin.optInt("revision")));
                ui.post(this::refreshPins);
            })), true));
        ItemActions.sheet(this, item);
    }

    private String pinTagsLabel(JSONObject pin) {
        JSONArray tags = pin.optJSONArray("tags");
        if (tags == null || tags.length() == 0) return "";
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i).trim();
            if (tag.isEmpty()) continue;
            if (label.length() > 0) label.append("  ");
            label.append('#').append(tag);
        }
        return label.toString();
    }

    private void editPin(JSONObject pin) { editPin(pin, "", ""); }

    private void editPin(JSONObject pin, String initialUrl, String initialText) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        EditText pinTitle = new EditText(this);
        pinTitle.setHint("Title (optional)");
        pinTitle.setSingleLine(true);
        pinTitle.setText(pin == null ? Uri.parse(initialUrl).getHost() : pin.optString("title"));
        form.addView(pinTitle);
        EditText pinUrl = new EditText(this);
        pinUrl.setHint("Web URL (optional for text pin)");
        pinUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        pinUrl.setSingleLine(true);
        pinUrl.setText(pin == null ? initialUrl : pin.optString("url"));
        form.addView(pinUrl);
        EditText pinContent = new EditText(this);
        pinContent.setHint("Text snippet (optional for URL pin)");
        pinContent.setMinLines(2);
        pinContent.setText(pin == null ? initialText : pin.optString("content"));
        form.addView(pinContent);
        EditText pinTags = new EditText(this);
        pinTags.setHint("Tags, separated by commas (optional)");
        pinTags.setSingleLine(true);
        JSONArray existingTags = pin == null ? null : pin.optJSONArray("tags");
        if (existingTags != null) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (int i = 0; i < existingTags.length(); i++) values.add(existingTags.optString(i));
            pinTags.setText(android.text.TextUtils.join(", ", values));
        }
        form.addView(pinTags);
        EditText pinDescription = new EditText(this);
        pinDescription.setHint("Description (optional)");
        pinDescription.setMinLines(2);
        pinDescription.setText(pin == null ? "" : pin.optString("description"));
        form.addView(pinDescription);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(pin == null ? "Save pin" : "Edit pin")
            .setView(formScroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = pinTitle.getText().toString().trim();
            String url = pinUrl.getText().toString().trim();
            String content = pinContent.getText().toString().trim();
            boolean holdsFile = pin != null && pin.optJSONObject("file") != null;
            if (url.isEmpty() && content.isEmpty() && !holdsFile) { pinContent.setError("Add a URL or text"); return; }
            if (!url.isEmpty()) {
                Uri parsed = Uri.parse(url);
                if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) ||
                        parsed.getHost() == null) { pinUrl.setError("Use a full web URL"); return; }
            }
            JSONArray tags = new JSONArray();
            for (String raw : pinTags.getText().toString().split(",")) {
                String tag = raw.trim();
                if (tag.isEmpty()) continue;
                if (tag.length() > 32 || tags.length() >= 12) {
                    pinTags.setError("Use up to 12 tags of 32 characters"); return;
                }
                tags.put(tag);
            }
            JSONObject data = new JSONObject();
            try {
                data.put("title", name);
                data.put("url", url);
                data.put("content", content);
                data.put("tags", tags);
                data.put("description", pinDescription.getText().toString());
                if (pin != null) data.put("expectedRevision", pin.optInt("revision"));
            } catch (Exception error) { status.setText(error.getMessage()); return; }
            android.widget.Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setEnabled(false);
            status.setText("Saving pin to Pi");
            new Thread(() -> {
                try {
                    if (pin == null) client.post("/v1/pins", data);
                    else client.put("/v1/pins/" + MediaClient.enc(pin.optString("id")), data);
                    ui.post(() -> { dialog.dismiss(); refreshPins(); });
                } catch (Exception error) {
                    ui.post(() -> {
                        saveButton.setEnabled(true);
                        String message = error instanceof MediaClient.MediaException &&
                            "PIN_CONFLICT".equals(((MediaClient.MediaException) error).code)
                            ? "Changed on Pi. Copy your edits, close, and reopen to refresh."
                            : "Save failed: " + error.getMessage();
                        pinTitle.setError(message);
                        status.setText(message);
                    });
                }
            }, "csync-pin-save").start();
        }));
        dialog.show();
    }

    private void loadNote(String id) {
        status.setText("Loading note");
        task(() -> {
            JSONObject note = client.get("/v1/notes/" + MediaClient.enc(id)).getJSONObject("note");
            ui.post(() -> showNote(note));
        });
    }

    private void showNote(JSONObject note) {
        noteSaving = false;
        noteId = note.optString("id");
        revision = note.optInt("revision");
        editing = false;
        reset();
        Kit.pageTop(topBar, this::showList, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", this::showList),
            new Kit.Crumb(R.drawable.csi_markdown, "Note", null));
        heading.setText(note.optString("title"));
        search.setVisibility(View.GONE);
        actions.setVisibility(View.VISIBLE);
        action("Share", () -> share(note));
        action("Add image", this::chooseImage);
        action("Add a file", this::chooseFile);
        action("Delete", () -> Kit.confirm(this, "Delete this note?",
            note.optString("title") + " and everything kept with it is removed from the Pi.",
            R.drawable.csi_trash, "Delete", this::deleteNote));
        String noteTitle = note.optString("title");
        String source = note.optString("body");
        addModeTabs(note);
        String firstHeading = "# " + noteTitle;
        String renderedBody = source.equals(firstHeading) ? "" :
            source.startsWith(firstHeading + "\n") ? source.substring(firstHeading.length()).trim() : source;
        TextView markdown = new TextView(this);
        markdown.setTextColor(getColor(R.color.text));
        markdown.setTextSize(16);
        markdown.setTextIsSelectable(true);
        markwon.setMarkdown(markdown, renderedBody);
        LinearLayout contentCard = new LinearLayout(this);
        contentCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        contentCard.setBackground(cardBackground());
        contentCard.addView(markdown);
        rows.addView(contentCard, new LinearLayout.LayoutParams(-1, -2));
        status.setText("Pi note · revision " + revision);
        showFiles(noteId, note.optJSONArray("files"));
        loadImages(noteId);
    }

    /** List the files kept beside a note. Each one offers the same choices as a file anywhere else. */
    private void showFiles(String id, JSONArray files) {
        if (files == null || files.length() == 0) return;
        Kit.label(rows, "Files");
        LinearLayout group = Kit.group(rows);
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("name", "File"), mime = file.optString("mime", "application/octet-stream");
            String fileId = file.optString("id");
            ItemActions.Kind kind = ItemActions.kindOf(mime);
            String size = android.text.format.Formatter.formatShortFileSize(this, file.optLong("size"));
            View row = Kit.addRow(group);
            Kit.bindRow(row, ItemActions.icon(kind), name, size, null, true);
            row.setOnClickListener(v -> {
                ItemActions.Item item = new ItemActions.Item(kind, name);
                item.sub = size + ", in this note";
                item.inNote = true;
                item.file = got -> fetch("/v1/notes/" + MediaClient.enc(id) + "/files/" + MediaClient.enc(fileId),
                    fileId + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"), mime, got);
                item.more.add(new Kit.Action(R.drawable.csi_trash, "Take out of this note", null, () ->
                    Kit.confirm(this, "Take it out of this note?", name + " is removed from the Pi.",
                        R.drawable.csi_trash, "Take out", () -> task(() -> {
                            client.delete("/v1/notes/" + MediaClient.enc(id) + "/files/" + MediaClient.enc(fileId), null);
                            ui.post(() -> loadNote(id));
                        })), true));
                ItemActions.sheet(this, item);
            });
        }
    }

    /** Bring something kept on the Pi onto this phone, then hand it on as a file that can be read. */
    private void fetch(String route, String saveAs, String mime, ItemActions.Got got) {
        CharSequence before = status.getText();
        status.setText("Getting it from the Pi");
        task(() -> {
            File folder = new File(getCacheDir(), "share");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("This phone has no room to fetch it");
            File file = new File(folder, saveAs);
            client.download(route, file);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".share", file);
            ui.post(() -> {
                status.setText(before);
                got.file(uri, mime);
            });
        });
    }

    private void loadImages(String id) {
        task(() -> {
            JSONArray images = client.get("/v1/notes/" + MediaClient.enc(id) + "/images").getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject item = images.optJSONObject(i);
                if (item == null) continue;
                byte[] bytes = client.getBytes("/v1/notes/" + MediaClient.enc(id) +
                    "/images/" + MediaClient.enc(item.optString("id")), 1024 * 1024);
                BitmapFactory.Options thumbnail = new BitmapFactory.Options();
                thumbnail.inSampleSize = 4;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, thumbnail);
                if (bitmap == null) continue;
                String imageId = item.optString("id");
                ui.post(() -> {
                    if (!id.equals(noteId) || editing) return;
                    ImageView image = new ImageView(this);
                    image.setContentDescription("Note screenshot");
                    image.setAdjustViewBounds(true);
                    image.setImageBitmap(bitmap);
                    image.setOnClickListener(v -> openNoteImage(id, imageId));
                    rows.addView(image, new LinearLayout.LayoutParams(-1, -2));
                });
            }
        });
    }

    private void openNoteImage(String id, String imageId) {
        ItemActions.Item item = new ItemActions.Item(ItemActions.Kind.IMAGE, "Picture in this note");
        item.sub = heading.getText().toString();
        item.inNote = true;
        item.file = got -> fetch("/v1/notes/" + MediaClient.enc(id) + "/images/" + MediaClient.enc(imageId),
            "note-" + imageId.replaceAll("[^A-Za-z0-9_-]", "_") + ".png", "image/png", got);
        item.more.add(new Kit.Action(R.drawable.csi_trash, "Take out of this note", null, () ->
            Kit.confirm(this, "Take it out of this note?", "The picture is removed from the Pi.",
                R.drawable.csi_trash, "Take out", () -> task(() -> {
                    client.delete("/v1/notes/" + MediaClient.enc(id) + "/images/" + MediaClient.enc(imageId), null);
                    ui.post(() -> loadNote(id));
                })), true));
        ItemActions.sheet(this, item);
    }

    private void chooseFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, 43);
    }

    /** The name a picked file goes by, as the phone reports it. */
    private String pickedName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            int column = c == null ? -1 : c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (column >= 0 && c.moveToFirst() && c.getString(column) != null) return c.getString(column);
        } catch (Exception unknown) { }
        return "file";
    }

    private void chooseImage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("image/png");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, 42);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 43 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null && noteId != null) {
            sharedFile = data.getData();
            sharedFileName = pickedName(sharedFile);
            uploadSharedFile(noteId);
            return;
        }
        if (requestCode != 42 || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || noteId == null) return;
        Uri selected = data.getData();
        String id = noteId;
        status.setText("Adding image to Pi note");
        task(() -> {
            byte[] bytes;
            try (java.io.InputStream input = getContentResolver().openInputStream(selected);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (input == null) throw new Exception("Cannot read this image");
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new Exception("Choose a PNG under 1 MB");
                    output.write(chunk, 0, count);
                }
                bytes = output.toByteArray();
            }
            client.uploadNoteImage("/v1/notes/" + MediaClient.enc(id) + "/images", bytes);
            ui.post(() -> loadNote(id));
        });
    }

    private void showEditor(String currentTitle, String currentBody) {
        showEditor(currentTitle, currentBody, "plain");
    }

    private void showEditor(String currentTitle, String currentBody, String mode) {
        noteSaving = false;
        editing = true;
        editorMode = mode;
        reset();
        Runnable leave = () -> {
            if (noteId == null) { dropShared(); showList(); }
            else loadNote(noteId);
        };
        Kit.pageTop(topBar, leave, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", this::showList),
            new Kit.Crumb(R.drawable.csi_edit, "Edit", null));
        heading.setText(noteId == null ? "New note" : "Edit note");
        search.setVisibility(View.GONE);
        actions.setVisibility(View.VISIBLE);
        action("Cancel", () -> {
            if (noteId == null) { dropShared(); showList(); }
            else loadNote(noteId);
        });
        action("Save", this::saveNote);
        addModeTabs(null);
        title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("Title");
        title.setText(currentTitle);
        rows.addView(title);
        body = new EditText(this);
        body.setGravity(android.view.Gravity.TOP);
        body.setMinLines(12);
        body.setHint("Write Markdown");
        body.setText(currentBody);
        body.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!"plain".equals(editorMode)) markwon.setMarkdown(preview, s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        rows.addView(body);
        preview = new TextView(this);
        preview.setTextColor(getColor(R.color.text));
        preview.setTextSize(16);
        preview.setTextIsSelectable(true);
        rows.addView(preview);
        setEditorMode(mode);
        status.setText(noteId == null ?
            (sharedFile != null ? sharedFileName + " is added after you save"
                : sharedImage != null ? "The picture is added after you save" : "New Pi note") :
            "Editing revision " + revision);
    }

    private void setEditorMode(String mode) {
        if (body == null) return;
        editorMode = mode;
        if (!mode.equals("plain")) markwon.setMarkdown(preview, body.getText().toString());
        body.setVisibility(mode.equals("preview") ? View.GONE : View.VISIBLE);
        preview.setVisibility(mode.equals("plain") ? View.GONE : View.VISIBLE);
        if (modeTabs != null) {
            String[] modes = {"preview", "rich", "plain"};
            for (int i = 0; i < modes.length; i++) {
                TextView tab = (TextView) modeTabs.getChildAt(i);
                boolean selected = modes[i].equals(mode);
                styleModeTab(tab, selected);
            }
        }
    }

    private void saveNote() {
        if (!editing || title == null || body == null || noteSaving) return;
        String name = title.getText().toString().trim();
        String source = body.getText().toString();
        if (name.isEmpty()) { title.setError("Give this note a title"); return; }
        String id = noteId;
        int expected = revision;
        noteSaving = true;
        status.setText("Saving to Pi");
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject().put("title", name).put("body", source);
                JSONObject result = id == null ? client.post("/v1/notes", data) :
                    client.put("/v1/notes/" + MediaClient.enc(id),
                        data.put("expectedRevision", expected));
                JSONObject saved = result.getJSONObject("note");
                ui.post(() -> {
                    if (sharedImage != null || sharedFile != null) uploadShared(saved.optString("id"));
                    else showNote(saved);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    noteSaving = false;
                    if (error instanceof MediaClient.MediaException &&
                        "NOTE_CONFLICT".equals(((MediaClient.MediaException) error).code)) {
                        status.setText("This note changed on Pi. Your draft is still here.");
                        Kit.sheet(this, "This note changed on the Pi",
                            "Your draft is still here. Close this to keep editing it.",
                            new Kit.Action(R.drawable.csi_refresh, "Load the Pi's version", "Your draft is dropped",
                                () -> loadNote(id)));
                    } else status.setText("Save failed: " + error.getMessage());
                });
            }
        }, "csync-note-save").start();
    }

    private void deleteNote() {
        String id = noteId;
        int expected = revision;
        status.setText("Deleting from Pi");
        task(() -> {
            client.delete("/v1/notes/" + MediaClient.enc(id),
                new JSONObject().put("expectedRevision", expected));
            ui.post(this::showList);
        });
    }

    private void share(JSONObject note) {
        ItemActions.Item item = new ItemActions.Item(ItemActions.Kind.TEXT, note.optString("title"));
        item.sub = "Note";
        item.text = sharedText(note);
        item.textName = note.optString("title") + ".md";
        item.inNote = true;
        item.own.put(ItemActions.Act.CHAT, () -> shareToConversation(note));
        item.own.put(ItemActions.Act.SHOW_PI, () -> showOnPi(note));
        ItemActions.sheet(this, item);
    }

    /** Put the note up on the Pi screen under its own title, so the screen names what it is showing. */
    private void showOnPi(JSONObject note) {
        status.setText("Sending the note to the Pi screen");
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try {
                String words = note.optString("body").trim();
                lit = client.post("/v1/display/show", new JSONObject()
                    .put("title", note.optString("title"))
                    .put("text", words.isEmpty() ? note.optString("title") : words)).optBoolean("sentToDisplay");
            } catch (Exception error) { problem = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String failure = problem;
            boolean shown = lit;
            ui.post(() -> {
                status.setText("Pi note · revision " + revision);
                if (failure != null) Kit.sheet(this, "It was not shown", failure,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> showOnPi(note)));
                else android.widget.Toast.makeText(this, shown ? "Showing on the Pi screen" : "Sent. The Pi screen is off",
                    android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "csync-note-show").start();
    }

    private String sharedText(JSONObject note) {
        return "# " + note.optString("title") + "\n\n" + note.optString("body");
    }

    /** A note can join a conversation already under way, so this asks which one. */
    private void shareToConversation(JSONObject note) {
        JSONArray index = ChatStore.index(this);
        java.util.List<Kit.Action> choices = new java.util.ArrayList<>();
        choices.add(new Kit.Action(R.drawable.csi_plus, "A new conversation", null, () -> openConversation(note, null)));
        for (int i = 0; i < index.length(); i++) {
            JSONObject item = index.optJSONObject(i);
            if (item == null) continue;
            choices.add(new Kit.Action(Kit.Icon.CHAT, item.optString("title", "Untitled"), null,
                () -> openConversation(note, item.optString("id"))));
        }
        Kit.sheet(this, "Send to a conversation", note.optString("title"), choices.toArray(new Kit.Action[0]));
    }

    private void openConversation(JSONObject note, String session) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("destination", "chat");
        intent.putExtra("chat_prefill", sharedText(note));
        if (session != null) intent.putExtra("chat_session", session);
        startActivity(intent);
    }

    @Override public void onBackPressed() {
        if (editing && noteId == null) { dropShared(); showList(); }
        else if (editing) loadNote(noteId);
        else if (noteId != null) showList();
        else super.onBackPressed();
    }
}
