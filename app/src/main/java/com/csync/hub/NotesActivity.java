package com.csync.hub;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import io.noties.markwon.Markwon;

/**
 * Notes and pins, both kept on the Pi.
 *
 * One place with two views, Notes and Pins. A note opens as a page that reads it, edits it and
 * lists what is kept with it. A pin opens as a page of its own. Every call to the Pi runs off
 * the main thread. The layout follows the Notes, Note and Pin screens of the mock.
 */
public final class NotesActivity extends AppCompatActivity {

    /** The four pages this activity draws. Back always goes one step toward the list. */
    private enum Page { LIST, NOTE, EDIT, PIN }

    private static final String[] MODES = {"preview", "rich", "plain"};

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, rows, topBar, tabsHost, modeHost, keptHost;
    private TextView status, preview, heading;
    private EditText title, body, search;
    private JSONArray listedNotes, listedPins;
    private boolean pinsAvailable = true;
    private MediaClient client;
    private Page page = Page.LIST;
    // Which of the two views the list shows, and whether its search field is open.
    private boolean showingPins, searching;
    private String noteId;
    private int revision;
    private boolean noteSaving;
    private Uri sharedImage;
    // A file that arrived to be kept beside a note, and the name it goes by.
    private Uri sharedFile;
    private String sharedFileName;
    private boolean sharedPrompted;
    private boolean uploading;
    private String editorMode = "plain";
    // The words under a note's title when nothing else is being said.
    private String resting;
    private Markwon markwon;
    private Runnable pendingNoteSearch;
    private int noteSearchGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Appearance.apply(this);
        markwon = Markwon.create(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        setContentView(root);
        getOnBackPressedDispatcher().addCallback(this, backInApp);
        root.getViewTreeObserver().addOnPreDrawListener(() -> { backInApp.setEnabled(page != Page.LIST); return true; });
        topBar = (LinearLayout) getLayoutInflater().inflate(R.layout.kit_page_top, root, false);
        root.addView(topBar);
        heading = new TextView(this);
        heading.setTextAppearance(R.style.Kit_Text_PageTitle);
        heading.setTextColor(getColor(R.color.text));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(dp(20), dp(6), dp(20), 0);
        root.addView(heading, headingParams);
        status = new TextView(this);
        status.setTextAppearance(R.style.Kit_Text_RowSub);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(dp(20), dp(4), dp(20), dp(6));
        root.addView(status, statusParams);
        tabsHost = new LinearLayout(this);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, -2);
        tabsParams.setMargins(dp(20), 0, dp(20), dp(12));
        root.addView(tabsHost, tabsParams);
        search = field("Search notes", "", false);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.setMargins(dp(20), 0, dp(20), dp(12));
        root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (page != Page.LIST) return;
                renderListRows();
                if (client == null || showingPins) return;
                if (pendingNoteSearch != null) ui.removeCallbacks(pendingNoteSearch);
                String term = s.toString().trim();
                pendingNoteSearch = () -> loadNotes(term);
                ui.postDelayed(pendingNoteSearch, 250);
            }
            public void afterTextChanged(Editable s) {}
        });
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
        // The bar grows by the navigation bar's height under it, so it wraps rather than fixing 64dp.
        nav.setMinimumHeight(dp(64));
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        Appearance.edgeToEdge(this, nav);
        Appearance.column(this, scroll, heading, status, tabsHost, search);
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) {
            Kit.pageTop(topBar, this::finish, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", null));
            heading.setVisibility(View.GONE);
            search.setVisibility(View.GONE);
            Kit.empty(rows, Kit.Icon.NOTES, "The Pi is not connected", "Notes and pins are kept on the Pi. Set its address and token in Settings.", null);
            say(null);
            return;
        }
        client = new MediaClient(host, token);
        sharedImage = state == null ? getIntent().getParcelableExtra("note_image_uri") :
            state.getParcelable("shared_image_uri");
        sharedFile = state == null ? getIntent().getParcelableExtra("note_file_uri") :
            state.getParcelable("shared_file_uri");
        sharedFileName = getIntent().getStringExtra("note_file_name");
        String pinUrl = getIntent().getStringExtra("pin_prefill_url");
        String pinText = getIntent().getStringExtra("pin_prefill_text");
        String noteBody = getIntent().getStringExtra("note_prefill_body");
        Uri pinFile = state == null ? getIntent().getParcelableExtra("pin_file_uri") : null;
        showingPins = pinFile != null || pinUrl != null || pinText != null;
        showList();
        if (pinFile != null) pinFile(pinFile, getIntent().getStringExtra("pin_file_name"));
        else if (pinUrl != null || pinText != null) showPin(null, pinUrl == null ? "" : pinUrl, pinText == null ? "" : pinText);
        else if (noteBody != null && sharedImage == null && sharedFile == null) showEditor("", noteBody, "plain");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable("shared_image_uri", sharedImage);
        state.putParcelable("shared_file_uri", sharedFile);
        super.onSaveInstanceState(state);
    }

    // ---- parts ----

    /** Notes lives under More, so its path starts there. */
    private Kit.Crumb moreCrumb() {
        return new Kit.Crumb(Kit.Icon.MORE, "More", () -> startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", "more")));
    }

    private int dp(int value) {
        return Kit.dp(this, value);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(R.color.surface));
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(1), getColor(R.color.border));
        return shape;
    }

    /** A box to type in, drawn like every other card on the page. */
    private EditText field(String hint, String value, boolean tall) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(15);
        field.setTextColor(getColor(R.color.text));
        field.setHintTextColor(getColor(R.color.dim));
        field.setBackground(cardBackground());
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setMinHeight(dp(48));
        if (tall) {
            field.setMinLines(3);
            field.setGravity(android.view.Gravity.TOP);
        } else field.setSingleLine(true);
        return field;
    }

    /** Add a field under a small label that names it. */
    private EditText labelled(String label, String hint, String value, boolean tall) {
        Kit.label(rows, label);
        EditText field = field(hint, value, tall);
        rows.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    /** Say what is happening under the heading, or with null go back to what the page says at rest. */
    private void say(String words) {
        String shown = words == null ? resting : words;
        status.setText(shown);
        status.setVisibility(shown == null ? View.GONE : View.VISIBLE);
    }

    private void toast(String words) {
        Toast.makeText(this, words, Toast.LENGTH_SHORT).show();
    }

    /** Start a page: clear what the last one drew and set what stays the same on every page. */
    private void open(Page next, String title, String restingWords) {
        page = next;
        rows.removeAllViews();
        tabsHost.removeAllViews();
        this.title = null;
        body = null;
        preview = null;
        modeHost = null;
        keptHost = null;
        heading.setText(title);
        heading.setVisibility(title == null ? View.GONE : View.VISIBLE);
        resting = restingWords;
        say(null);
        tabsHost.setVisibility(next == Page.LIST ? View.VISIBLE : View.GONE);
        search.setVisibility(next == Page.LIST && searching ? View.VISIBLE : View.GONE);
    }

    private void task(Work work) {
        new Thread(() -> {
            try { work.run(); }
            catch (Exception error) {
                ui.post(() -> say(error.getMessage() == null ? "The Pi did not answer." : error.getMessage()));
            }
        }, "csync-notes").start();
    }

    private interface Work { void run() throws Exception; }

    /** When something was last changed, in the words a person would use. */
    private String edited(long seconds) {
        if (seconds <= 0) return null;
        long at = seconds * 1000, day = 86400000L;
        if (android.text.format.DateUtils.isToday(at)) return "Edited today";
        if (android.text.format.DateUtils.isToday(at + day)) return "Edited yesterday";
        if (System.currentTimeMillis() - at < 6 * day)
            return "Edited on " + new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(new java.util.Date(at));
        return "Edited " + android.text.format.DateUtils.formatDateTime(this, at,
            android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    // ---- the list ----

    private void showList() {
        noteId = null;
        open(Page.LIST, null, null);
        Kit.pageTop(topBar, this::finish, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", null));
        Kit.topAction(topBar, Kit.Icon.SEARCH, showingPins ? "Search pins" : "Search notes", v -> {
            searching = !searching;
            if (!searching) search.setText("");
            search.setVisibility(searching ? View.VISIBLE : View.GONE);
            if (searching) search.requestFocus();
        });
        Kit.topAction(topBar, R.drawable.csi_plus, showingPins ? "New pin" : "New note", v -> {
            if (showingPins) showPin(null, "", "");
            else showEditor("", "", "plain");
        });
        search.setHint(showingPins ? "Search pins" : "Search notes");
        Kit.tabs(tabsHost, new int[]{Kit.Icon.NOTES, R.drawable.ic_pin}, new String[]{"Notes", "Pins"},
            showingPins ? 1 : 0, picked -> { showingPins = picked == 1; showList(); });
        renderListRows();
        loadNotes(search.getText().toString().trim());
        refreshPins();
    }

    private void loadNotes(String query) {
        int request = ++noteSearchGeneration;
        if (query.length() > 128) {
            listedNotes = new JSONArray();
            renderListRows();
            return;
        }
        if (listedNotes == null) say("Reading from the Pi");
        new Thread(() -> {
            try {
                String route = "/v1/notes" + (query.isEmpty() ? "" : "?q=" + MediaClient.enc(query));
                JSONArray notes = client.get(route).getJSONArray("notes");
                ui.post(() -> {
                    if (request != noteSearchGeneration || page != Page.LIST) return;
                    listedNotes = notes;
                    say(null);
                    renderListRows();
                    if ((sharedImage != null || sharedFile != null) && !sharedPrompted) chooseSharedNote();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (request == noteSearchGeneration && page == Page.LIST)
                        say("Notes could not be read from the Pi. " + error.getMessage());
                });
            }
        }, "csync-note-search").start();
    }

    private void refreshPins() {
        task(() -> {
            try {
                JSONArray pins = client.get("/v1/pins").getJSONArray("pins");
                ui.post(() -> {
                    listedPins = pins;
                    pinsAvailable = true;
                    if (page == Page.LIST) renderListRows();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    listedPins = null;
                    pinsAvailable = false;
                    if (page == Page.LIST) renderListRows();
                });
            }
        });
    }

    private void renderListRows() {
        if (page != Page.LIST || rows == null) return;
        rows.removeAllViews();
        String query = search.getText().toString().trim();
        if (showingPins) renderPins(query);
        else renderNotes(query);
    }

    private void renderNotes(String query) {
        if (listedNotes == null) return;
        if (listedNotes.length() == 0) {
            if (query.isEmpty()) Kit.empty(rows, Kit.Icon.NOTES, "No notes yet",
                "Notes are kept on the Pi and the assistant can read them.",
                Kit.button(this, R.drawable.csi_plus, "New note", R.color.text, () -> showEditor("", "", "plain")));
            else Kit.empty(rows, Kit.Icon.SEARCH, "No note matches \"" + query + "\"", null, null);
            return;
        }
        LinearLayout group = Kit.group(rows);
        for (int i = 0; i < listedNotes.length(); i++) {
            JSONObject item = listedNotes.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            int kept = item.optInt("items");
            Kit.bindRow(Kit.addRow(group), Kit.Icon.NOTES, item.optString("title", "Untitled"),
                edited(item.optLong("updatedAt")), kept > 0 ? plural(kept, "item", "items") : null, true)
                .setOnClickListener(v -> loadNote(id));
        }
    }

    /** The name a pin goes by: its own title, else the site, else its first line. */
    private String pinName(JSONObject pin) {
        String title = pin.optString("title"), url = pin.optString("url");
        if (!title.isEmpty()) return title;
        if (!url.isEmpty()) return Uri.parse(url).getHost();
        return pin.optString("content").split("\\n", 2)[0];
    }

    private void renderPins(String query) {
        if (listedPins == null) {
            if (!pinsAvailable) Kit.empty(rows, R.drawable.ic_pin, "Pins could not be read", "The Pi did not answer.",
                Kit.button(this, R.drawable.csi_refresh, "Try again", R.color.text, this::refreshPins));
            return;
        }
        String wanted = query.toLowerCase(java.util.Locale.ROOT);
        LinearLayout group = null;
        for (int i = 0; i < listedPins.length(); i++) {
            JSONObject pin = listedPins.optJSONObject(i);
            if (pin == null) continue;
            JSONArray tags = pin.optJSONArray("tags");
            String all = (pin.optString("title") + " " + pin.optString("url") + " " + pin.optString("content") + " "
                + (tags == null ? "" : tags.toString())).toLowerCase(java.util.Locale.ROOT);
            if (!wanted.isEmpty() && !all.contains(wanted)) continue;
            JSONObject held = pin.optJSONObject("file");
            String url = pin.optString("url");
            String what = held != null ? android.text.format.Formatter.formatShortFileSize(this, held.optLong("size"))
                : url.isEmpty() ? "Text" : pin.optString("title").isEmpty() ? "Link" : Uri.parse(url).getHost();
            int icon = held != null ? ItemActions.icon(ItemActions.kindOf(held.optString("mime")))
                : url.isEmpty() ? R.drawable.csi_text : R.drawable.csi_link;
            int tagged = tags == null ? 0 : tags.length();
            if (group == null) group = Kit.group(rows);
            Kit.bindRow(Kit.addRow(group), icon, pinName(pin), what, tagged > 0 ? plural(tagged, "tag", "tags") : null, true)
                .setOnClickListener(v -> showPin(pin, "", ""));
        }
        if (group != null) return;
        if (query.isEmpty()) Kit.empty(rows, R.drawable.ic_pin, "No pins yet", "Save a link or a snippet to find it again.",
            Kit.button(this, R.drawable.csi_plus, "New pin", R.color.text, () -> showPin(null, "", "")));
        else Kit.empty(rows, Kit.Icon.SEARCH, "No pin matches \"" + query + "\"", null, null);
    }

    // ---- things arriving from elsewhere ----

    /** Ask which note an arriving picture or file goes into. Closing the drawer drops it. */
    private void chooseSharedNote() {
        sharedPrompted = true;
        String caption = getIntent().getStringExtra("note_image_caption");
        java.util.List<Kit.Action> choices = new java.util.ArrayList<>();
        choices.add(new Kit.Action(R.drawable.csi_plus, "A new note", null,
            () -> showEditor("", caption == null ? "" : caption, "plain")));
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
                        showEditor(latest.optString("title"), source + (source.isEmpty() ? "" : "\n\n") + caption, "plain");
                    });
                });
            }));
        }
        Kit.sheet(this, "Add to a note", sharedFile == null ? "The picture is kept with the note" :
                sharedFileName + " is kept with the note", choices.toArray(new Kit.Action[0]))
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

    /** Once something has been added: redraw what the note keeps, without throwing away words being typed. */
    private void added(String id) {
        if (page == Page.EDIT && id.equals(noteId)) {
            say(null);
            loadKept(id);
        } else loadNote(id);
    }

    /** A note's pictures are kept as PNG of up to 1 MB, so a larger or different image is scaled down to fit. */
    private byte[] sharedImagePng(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("The picture could not be read");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new Exception("This is not a picture the phone can read");
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / decode.inSampleSize > 1600) decode.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("The picture could not be read");
            bitmap = BitmapFactory.decodeStream(input, null, decode);
        }
        if (bitmap == null) throw new Exception("This is not a picture the phone can read");
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
        throw new Exception("The picture is too large for a note");
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
        say("Saving " + name + " as a pin");
        new Thread(() -> {
            try {
                client.uploadPinFile(name, getContentResolver().getType(source), readAll(source));
                ui.post(() -> {
                    say(null);
                    toast("Saved to Pins");
                    refreshPins();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    say(null);
                    Kit.sheet(this, "The pin was not saved", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> pinFile(source, givenName)));
                });
            }
        }, "csync-pin-file").start();
    }

    private void uploadSharedFile(String id) {
        if (sharedFile == null || uploading || id == null || id.isEmpty()) return;
        Uri source = sharedFile;
        String name = sharedFileName == null || sharedFileName.isEmpty() ? "file" : sharedFileName;
        uploading = true;
        say("Adding " + name + " to the note");
        new Thread(() -> {
            try {
                client.uploadNoteFile(id, name, getContentResolver().getType(source), readAll(source));
                ui.post(() -> {
                    uploading = false;
                    sharedFile = null;
                    added(id);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    uploading = false;
                    say(null);
                    Kit.sheet(this, "The file was not added", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> uploadSharedFile(id)),
                        new Kit.Action(Kit.Icon.NOTES, "Carry on without it", null, () -> {
                            sharedFile = null;
                            added(id);
                        }));
                });
            }
        }, "csync-note-shared-file").start();
    }

    private void uploadSharedImage(String id) {
        if (sharedImage == null || uploading || id == null || id.isEmpty()) return;
        Uri source = sharedImage;
        uploading = true;
        say("Adding the picture to the note");
        new Thread(() -> {
            try {
                client.uploadNoteImage("/v1/notes/" + MediaClient.enc(id) + "/images", sharedImagePng(source));
                ui.post(() -> {
                    uploading = false;
                    sharedImage = null;
                    added(id);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    uploading = false;
                    say(null);
                    Kit.sheet(this, "The picture was not added", error.getMessage(),
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> uploadSharedImage(id)),
                        new Kit.Action(Kit.Icon.NOTES, "Carry on without it", null, () -> {
                            sharedImage = null;
                            added(id);
                        }));
                });
            }
        }, "csync-note-shared-image").start();
    }

    // ---- a pin ----

    /** The page for one pin. With no pin it starts a new one, filled with what was handed over. */
    private void showPin(JSONObject pin, String initialUrl, String initialText) {
        open(Page.PIN, null, null);
        Runnable back = () -> { showingPins = true; showList(); };
        Kit.pageTop(topBar, back, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", back),
            new Kit.Crumb(R.drawable.ic_pin, "Pin", null));
        JSONObject held = pin == null ? null : pin.optJSONObject("file");
        if (pin != null) {
            Kit.topAction(topBar, Kit.Icon.SHARE, "Send or share this pin", v -> pinChoices(pin));
            Kit.topAction(topBar, R.drawable.csi_trash, "Delete this pin", v ->
                Kit.confirm(this, "Delete this pin?", pinName(pin), R.drawable.csi_trash, "Delete", () -> task(() -> {
                    client.delete("/v1/pins/" + MediaClient.enc(pin.optString("id")),
                        new JSONObject().put("expectedRevision", pin.optInt("revision")));
                    ui.post(back);
                })));
        }
        EditText link = null, words = null;
        if (held != null) {
            // A pin that holds a file shows the file itself in place of a link or words.
            Kit.label(rows, "File");
            Kit.bindRow(Kit.addRow(Kit.group(rows)), ItemActions.icon(ItemActions.kindOf(held.optString("mime"))),
                held.optString("name", "File"), android.text.format.Formatter.formatShortFileSize(this, held.optLong("size")),
                null, true).setOnClickListener(v -> pinChoices(pin));
        } else {
            link = labelled("Link", "A web address", pin == null ? initialUrl : pin.optString("url"), false);
            link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            words = labelled("Text", "Some words to keep", pin == null ? initialText : pin.optString("content"), true);
        }
        String startTitle = pin != null ? pin.optString("title")
            : initialUrl.isEmpty() ? "" : String.valueOf(Uri.parse(initialUrl).getHost());
        EditText name = labelled("Title", "Title", startTitle, false);
        JSONArray existing = pin == null ? null : pin.optJSONArray("tags");
        java.util.ArrayList<String> tagList = new java.util.ArrayList<>();
        for (int i = 0; existing != null && i < existing.length(); i++) tagList.add(existing.optString(i));
        EditText tags = labelled("Tags", "Separate tags with commas", android.text.TextUtils.join(", ", tagList), false);
        EditText about = labelled("About", "Why you kept it", pin == null ? "" : pin.optString("description"), true);

        LinearLayout buttons = new LinearLayout(this);
        EditText linkField = link, wordsField = words;
        buttons.addView(Kit.primaryButton(this, R.drawable.csi_check, "Save",
            () -> savePin(pin, held != null, linkField, wordsField, name, tags, about, back)),
            new LinearLayout.LayoutParams(-2, -2));
        String url = pin == null ? "" : pin.optString("url");
        if (!url.isEmpty()) {
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(-2, -2);
            next.setMarginStart(dp(8));
            buttons.addView(Kit.button(this, R.drawable.csi_link, "Open the link", R.color.text, () -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception nothing) { toast("No app on this phone can open this link"); }
            }), next);
        }
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
        below.topMargin = dp(18);
        rows.addView(buttons, below);
    }

    private void savePin(JSONObject pin, boolean holdsFile, EditText link, EditText words, EditText name,
                         EditText tagField, EditText about, Runnable done) {
        if (noteSaving) return;
        String url = link == null ? "" : link.getText().toString().trim();
        String content = words == null ? "" : words.getText().toString().trim();
        if (url.isEmpty() && content.isEmpty() && !holdsFile) { words.setError("Add a link or some text"); return; }
        if (!url.isEmpty()) {
            Uri parsed = Uri.parse(url);
            if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) || parsed.getHost() == null) {
                link.setError("Use a full web address, starting with https");
                return;
            }
        }
        JSONArray tags = new JSONArray();
        for (String raw : tagField.getText().toString().split(",")) {
            String tag = raw.trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > 32 || tags.length() >= 12) { tagField.setError("Use up to 12 tags of 32 characters"); return; }
            tags.put(tag);
        }
        JSONObject data = new JSONObject();
        try {
            data.put("title", name.getText().toString().trim());
            data.put("url", url);
            data.put("content", content);
            data.put("tags", tags);
            data.put("description", about.getText().toString());
            if (pin != null) data.put("expectedRevision", pin.optInt("revision"));
        } catch (Exception error) { say(error.getMessage()); return; }
        noteSaving = true;
        say("Saving the pin on the Pi");
        new Thread(() -> {
            try {
                if (pin == null) client.post("/v1/pins", data);
                else client.put("/v1/pins/" + MediaClient.enc(pin.optString("id")), data);
                ui.post(() -> {
                    noteSaving = false;
                    toast("Saved");
                    done.run();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    noteSaving = false;
                    boolean changed = error instanceof MediaClient.MediaException &&
                        "PIN_CONFLICT".equals(((MediaClient.MediaException) error).code);
                    say(changed ? "This pin changed on the Pi. Copy your words, go back and open it again."
                        : "It was not saved. " + error.getMessage());
                });
            }
        }, "csync-pin-save").start();
    }

    /** Everything a pin can do, the same list any other item gets. */
    private void pinChoices(JSONObject pin) {
        String url = pin.optString("url"), content = pin.optString("content"), shown = pinName(pin);
        JSONObject held = pin.optJSONObject("file");
        ItemActions.Item item;
        if (held != null) {
            String name = held.optString("name", "file"), mime = held.optString("mime", "application/octet-stream");
            String pinId = pin.optString("id");
            item = new ItemActions.Item(ItemActions.kindOf(mime), shown);
            item.mime = mime;
            item.sub = android.text.format.Formatter.formatShortFileSize(this, held.optLong("size")) + ", pinned";
            item.file = got -> fetch("/v1/pins/" + MediaClient.enc(pinId) + "/file",
                pinId + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"), mime, got);
        } else {
            item = new ItemActions.Item(url.isEmpty() ? ItemActions.Kind.TEXT : ItemActions.Kind.LINK, shown);
            item.sub = "Pin";
            item.text = url + (url.isEmpty() || content.isEmpty() ? "" : "\n\n") + content;
            item.link = url.isEmpty() ? null : url;
            item.textName = shown + ".txt";
        }
        item.isPin = true;
        ItemActions.sheet(this, item);
    }

    // ---- a note ----

    private void loadNote(String id) {
        say("Reading the note");
        task(() -> {
            JSONObject note = client.get("/v1/notes/" + MediaClient.enc(id)).getJSONObject("note");
            ui.post(() -> showNote(note));
        });
    }

    private void showNote(JSONObject note) {
        noteSaving = false;
        noteId = note.optString("id");
        revision = note.optInt("revision");
        String noteTitle = note.optString("title"), source = note.optString("body");
        open(Page.NOTE, noteTitle, edited(note.optLong("updatedAt")));
        Runnable back = () -> { showingPins = false; showList(); };
        Kit.pageTop(topBar, back, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", back),
            new Kit.Crumb(R.drawable.csi_markdown, "Note", null));
        Kit.topAction(topBar, Kit.Icon.SHARE, "Send or share this note", v -> share(note));
        Kit.topAction(topBar, R.drawable.csi_trash, "Delete this note", v -> Kit.confirm(this, "Delete this note?",
            noteTitle + " and everything kept with it is removed from the Pi.", R.drawable.csi_trash, "Delete", this::deleteNote));
        modeHost = new LinearLayout(this);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, -2);
        modeParams.bottomMargin = dp(14);
        rows.addView(modeHost, modeParams);
        drawModes(0, picked -> showEditor(noteTitle, source, MODES[picked]));
        // The title is already the heading, so a first line that repeats it is left out of the reading view.
        String firstHeading = "# " + noteTitle;
        String shown = source.equals(firstHeading) ? "" :
            source.startsWith(firstHeading + "\n") ? source.substring(firstHeading.length()).trim() : source;
        if (!shown.trim().isEmpty()) {
            TextView markdown = new TextView(this);
            markdown.setTextColor(getColor(R.color.text));
            markdown.setTextSize(16);
            markdown.setTextIsSelectable(true);
            markwon.setMarkdown(markdown, shown);
            LinearLayout card = new LinearLayout(this);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(cardBackground());
            card.addView(markdown);
            rows.addView(card, new LinearLayout.LayoutParams(-1, -2));
        }
        keptHost = new LinearLayout(this);
        keptHost.setOrientation(LinearLayout.VERTICAL);
        rows.addView(keptHost, new LinearLayout.LayoutParams(-1, -2));
        loadKept(noteId, note.optJSONArray("files"), shown.trim().isEmpty());
    }

    private void drawModes(int selected, Kit.Pick pick) {
        Kit.tabs(modeHost, new int[]{R.drawable.csi_photo, R.drawable.csi_edit, R.drawable.csi_markdown},
            new String[]{"Preview", "Rich", "Plain"}, selected, pick);
    }

    private void loadKept(String id) {
        task(() -> {
            JSONObject note = client.get("/v1/notes/" + MediaClient.enc(id)).getJSONObject("note");
            ui.post(() -> loadKept(id, note.optJSONArray("files"), false));
        });
    }

    /** List what is kept with a note: its files at once, then its pictures when the Pi has named them. */
    private void loadKept(String id, JSONArray files, boolean noWords) {
        LinearLayout host = keptHost;
        if (host == null) return;
        task(() -> {
            JSONArray images = client.get("/v1/notes/" + MediaClient.enc(id) + "/images").getJSONArray("images");
            ui.post(() -> {
                if (host != keptHost || !id.equals(noteId)) return;
                host.removeAllViews();
                int count = (files == null ? 0 : files.length()) + images.length();
                if (count == 0) {
                    if (noWords && page == Page.NOTE) Kit.empty(host, Kit.Icon.NOTES, "This note is empty",
                        "Write in Rich or Plain, or add a picture or a file.", null);
                    return;
                }
                Kit.label(host, "In this note");
                LinearLayout group = Kit.group(host);
                for (int i = 0; i < images.length(); i++) {
                    JSONObject image = images.optJSONObject(i);
                    if (image == null) continue;
                    String imageId = image.optString("id");
                    Kit.bindRow(Kit.addRow(group), Kit.Icon.PHOTO, images.length() == 1 ? "Picture" : "Picture " + (i + 1),
                        "Image", null, true).setOnClickListener(v -> pictureChoices(id, imageId));
                }
                for (int i = 0; files != null && i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file != null) addFileRow(group, id, file);
                }
            });
        });
    }

    private static String kindWords(ItemActions.Kind kind) {
        switch (kind) {
            case VIDEO: return "Video";
            case AUDIO: return "Audio";
            case IMAGE: return "Image";
            default: return "File";
        }
    }

    private void addFileRow(LinearLayout group, String id, JSONObject file) {
        String name = file.optString("name", "File"), mime = file.optString("mime", "application/octet-stream");
        String fileId = file.optString("id");
        ItemActions.Kind kind = ItemActions.kindOf(mime);
        String size = android.text.format.Formatter.formatShortFileSize(this, file.optLong("size"));
        Kit.bindRow(Kit.addRow(group), ItemActions.icon(kind), name, kindWords(kind) + " · " + size, null, true)
            .setOnClickListener(v -> {
                ItemActions.Item item = new ItemActions.Item(kind, name);
                item.mime = mime;
                item.sub = kindWords(kind) + ", in this note";
                item.inNote = true;
                item.file = got -> fetch("/v1/notes/" + MediaClient.enc(id) + "/files/" + MediaClient.enc(fileId),
                    fileId + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"), mime, got);
                item.more.add(new Kit.Action(R.drawable.csi_trash, "Take out of this note", null, () ->
                    Kit.confirm(this, "Take it out of this note?", name + " is removed from the Pi.",
                        R.drawable.csi_trash, "Take out", () -> task(() -> {
                            client.delete("/v1/notes/" + MediaClient.enc(id) + "/files/" + MediaClient.enc(fileId), null);
                            ui.post(() -> added(id));
                        })), true));
                ItemActions.sheet(this, item);
            });
    }

    private void pictureChoices(String id, String imageId) {
        ItemActions.Item item = new ItemActions.Item(ItemActions.Kind.IMAGE, "Picture");
        item.sub = "Image, in this note";
        item.inNote = true;
        item.file = got -> fetch("/v1/notes/" + MediaClient.enc(id) + "/images/" + MediaClient.enc(imageId),
            "note-" + imageId.replaceAll("[^A-Za-z0-9_-]", "_") + ".png", "image/png", got);
        item.more.add(new Kit.Action(R.drawable.csi_trash, "Take out of this note", null, () ->
            Kit.confirm(this, "Take it out of this note?", "The picture is removed from the Pi.",
                R.drawable.csi_trash, "Take out", () -> task(() -> {
                    client.delete("/v1/notes/" + MediaClient.enc(id) + "/images/" + MediaClient.enc(imageId), null);
                    ui.post(() -> added(id));
                })), true));
        ItemActions.sheet(this, item);
    }

    /** Bring something kept on the Pi onto this phone, then hand it on as a file that can be read. */
    private void fetch(String route, String saveAs, String mime, ItemActions.Got got) {
        say("Getting it from the Pi");
        task(() -> {
            File folder = new File(getCacheDir(), "share");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("This phone has no room to fetch it");
            File file = new File(folder, saveAs);
            client.download(route, file);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".share", file);
            ui.post(() -> {
                say(null);
                got.file(uri, mime);
            });
        });
    }

    /** Ask what to add to the note being written: a picture, drawn from any image, or a file kept as it is. */
    private void chooseAddition() {
        Kit.sheet(this, "Add to this note", null,
            new Kit.Action(Kit.Icon.PHOTO, "A picture", "From this phone", () -> pick("image/*", 42), true),
            new Kit.Action(Kit.Icon.FILE, "A file", "Any file of up to 20 MB", () -> pick("*/*", 43), true));
    }

    private void pick(String type, int request) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType(type);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, request);
    }

    /** The name a picked file goes by, as the phone reports it. */
    private String pickedName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            int column = c == null ? -1 : c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (column >= 0 && c.moveToFirst() && c.getString(column) != null) return c.getString(column);
        } catch (Exception unknown) { }
        return "file";
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || noteId == null) return;
        if (requestCode == 43) {
            sharedFile = data.getData();
            sharedFileName = pickedName(sharedFile);
            uploadSharedFile(noteId);
        } else if (requestCode == 42) {
            sharedImage = data.getData();
            uploadSharedImage(noteId);
        }
    }

    // ---- writing a note ----

    private void showEditor(String currentTitle, String currentBody, String mode) {
        noteSaving = false;
        editorMode = mode;
        open(Page.EDIT, null, sharedFile != null ? sharedFileName + " is added when you save"
            : sharedImage != null ? "The picture is added when you save" : null);
        Runnable list = () -> { dropShared(); showingPins = false; showList(); };
        Runnable leave = () -> { if (noteId == null) list.run(); else loadNote(noteId); };
        Kit.pageTop(topBar, leave, moreCrumb(), new Kit.Crumb(Kit.Icon.NOTES, "Notes", list),
            new Kit.Crumb(R.drawable.csi_markdown, noteId == null ? "New note" : "Note", null));
        // A long note puts the Save button far below, so the bar carries one as well.
        Kit.topAction(topBar, R.drawable.csi_check, "Save", v -> saveNote());
        title = labelled("Title", "Title", currentTitle, false);
        modeHost = new LinearLayout(this);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, -2);
        modeParams.topMargin = dp(12);
        modeParams.bottomMargin = dp(14);
        rows.addView(modeHost, modeParams);
        body = field("Write in Markdown", currentBody, true);
        body.setMinLines(10);
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!"plain".equals(editorMode) && preview != null) markwon.setMarkdown(preview, s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        rows.addView(body, new LinearLayout.LayoutParams(-1, -2));
        preview = new TextView(this);
        preview.setTextColor(getColor(R.color.text));
        preview.setTextSize(16);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(14), dp(14), dp(14), dp(14));
        preview.setBackground(cardBackground());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.topMargin = dp(10);
        rows.addView(preview, previewParams);
        setEditorMode(mode);
        keptHost = new LinearLayout(this);
        keptHost.setOrientation(LinearLayout.VERTICAL);
        rows.addView(keptHost, new LinearLayout.LayoutParams(-1, -2));
        if (noteId != null) loadKept(noteId);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(Kit.primaryButton(this, R.drawable.csi_check, "Save", this::saveNote),
            new LinearLayout.LayoutParams(-2, -2));
        // A note has to exist on the Pi before anything can be kept with it.
        if (noteId != null) {
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(-2, -2);
            next.setMarginStart(dp(8));
            buttons.addView(Kit.button(this, R.drawable.csi_plus, "Add to this note", R.color.text, this::chooseAddition), next);
        }
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
        below.topMargin = dp(18);
        rows.addView(buttons, below);
    }

    /** Preview shows the words as they will read, Plain shows what is typed, Rich shows both. */
    private void setEditorMode(String mode) {
        if (body == null) return;
        editorMode = mode;
        if (!mode.equals("plain")) markwon.setMarkdown(preview, body.getText().toString());
        body.setVisibility(mode.equals("preview") ? View.GONE : View.VISIBLE);
        preview.setVisibility(mode.equals("plain") ? View.GONE : View.VISIBLE);
        drawModes(java.util.Arrays.asList(MODES).indexOf(mode), picked -> setEditorMode(MODES[picked]));
    }

    private void saveNote() {
        if (page != Page.EDIT || title == null || body == null || noteSaving) return;
        String name = title.getText().toString().trim();
        String source = body.getText().toString();
        if (name.isEmpty()) { title.setError("Give this note a title"); return; }
        String id = noteId;
        int expected = revision;
        noteSaving = true;
        Kit.tick(root);
        say("Saving on the Pi");
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject().put("title", name).put("body", source);
                JSONObject result = id == null ? client.post("/v1/notes", data) :
                    client.put("/v1/notes/" + MediaClient.enc(id), data.put("expectedRevision", expected));
                JSONObject saved = result.getJSONObject("note");
                ui.post(() -> {
                    if (sharedImage != null || sharedFile != null) {
                        // Leave the editor first, so what arrives lands on the saved note's page.
                        showNote(saved);
                        uploadShared(saved.optString("id"));
                    } else showNote(saved);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    noteSaving = false;
                    if (error instanceof MediaClient.MediaException &&
                        "NOTE_CONFLICT".equals(((MediaClient.MediaException) error).code)) {
                        say("This note changed on the Pi. Your words are still here.");
                        Kit.sheet(this, "This note changed on the Pi",
                            "Your words are still here. Close this to keep writing.",
                            new Kit.Action(R.drawable.csi_refresh, "Load the Pi's version", "What you wrote here is dropped",
                                () -> loadNote(id)));
                    } else say("It was not saved. " + error.getMessage());
                });
            }
        }, "csync-note-save").start();
    }

    private void deleteNote() {
        String id = noteId;
        int expected = revision;
        say("Deleting from the Pi");
        task(() -> {
            client.delete("/v1/notes/" + MediaClient.enc(id), new JSONObject().put("expectedRevision", expected));
            ui.post(() -> { showingPins = false; showList(); });
        });
    }

    // ---- sending a note on ----

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
        say("Sending the note to the Pi screen");
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try {
                String words = note.optString("body").trim();
                lit = client.post("/v1/display/show", new JSONObject()
                    .put("title", note.optString("title"))
                    .put("text", words.isEmpty() ? note.optString("title") : MediaClient.screenText(words)))
                    .optBoolean("sentToDisplay");
            } catch (Exception error) { problem = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String failure = problem;
            boolean shown = lit;
            ui.post(() -> {
                say(null);
                if (failure != null) Kit.sheet(this, "It was not shown", failure,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> showOnPi(note)));
                else toast(shown ? "Showing on the Pi screen" : "Sent. The Pi screen is off");
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

    // Back goes up one page inside Notes; from the list the callback is off, so the system's own
    // back preview runs and leaves Notes.
    private View pageView() { return root; }

    private final androidx.activity.OnBackPressedCallback backInApp = new androidx.activity.OnBackPressedCallback(false) {
        @Override public void handleOnBackProgressed(androidx.activity.BackEventCompat event) {
            Kit.peekBack(pageView(), event.getProgress());
        }
        @Override public void handleOnBackCancelled() { Kit.peekBack(pageView(), 0f); }
        @Override public void handleOnBackPressed() {
            Kit.peekBack(pageView(), 0f);
            if (page == Page.EDIT && noteId != null) loadNote(noteId);
            else if (page == Page.EDIT) { dropShared(); showingPins = false; showList(); }
            else if (page == Page.PIN) { showingPins = true; showList(); }
            else if (page == Page.NOTE) { showingPins = false; showList(); }
        }
    };
}
