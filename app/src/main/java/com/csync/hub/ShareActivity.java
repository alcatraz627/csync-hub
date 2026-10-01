package com.csync.hub;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What another app shared into csync, and every place it can go: the Pi screen, one of your
 * devices, a conversation, a note or a pin. One page for every kind of item, so the same thing
 * is offered under the same words whatever was shared.
 *
 * Two more entries in Android's share menu skip the page: "Send to Pi screen" plays the item
 * at once, and "Send to your last device" sends it to the device you sent to last. Either one
 * opens the page instead when it cannot do its job, so nothing is dropped silently.
 */
public class ShareActivity extends AppCompatActivity {

    /**
     * Names the place an item from inside csync is going, so the page sends it there without
     * asking. One of pi, cover, device, chat, note or pin.
     */
    static final String PLACE = "csync_place";
    /** The file name words from inside csync arrive under on another device, such as a note's title. */
    static final String TEXT_NAME = "csync_text_name";
    /**
     * True when the item came from a page inside csync, so the page it lands on is a visit:
     * Back there returns to the page the item was on, instead of climbing the new page's own path.
     */
    static final String RETURN = "csync_return";

    /** The visit flag, carried from the item's page to the page it lands on. */
    private Intent visit(Intent destination) {
        if (getIntent().getBooleanExtra(RETURN, false)) destination.putExtra(RETURN, true);
        return destination;
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> files = new ArrayList<>();
    private String text;
    // The conversation picked in the share menu, when one was.
    private String conversation;
    private ItemActions.Kind kind;
    private LinearLayout body;
    private TextView progress;
    // From inside csync the page is a step, not a place: it shows the item and the progress of the
    // one action asked for, and a drawer closed without a pick puts the item's own page back.
    private boolean step;
    private boolean continuing;
    private InstagramSave instagram;

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == InstagramSave.SIGN_IN && instagram != null) instagram.signedIn(result == RESULT_OK);
    }

    /** In a step, a drawer closed without choosing ends the page; a choice sets {@code continuing} first. */
    private void endsStep(android.app.Dialog drawer) {
        if (!step) return;
        continuing = false;
        drawer.setOnDismissListener(d -> { if (!continuing && !isFinishing()) finish(); });
    }

    private Runnable go(Runnable next) {
        return () -> { continuing = true; next.run(); };
    }

    @Override
    protected void onCreate(Bundle b) {
        Appearance.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_share);
        Appearance.edgeToEdge(this, null);
        Rail.attach(this);
        body = findViewById(R.id.share_body);
        Appearance.column(this, body);
        final Intent intent = getIntent();
        final String place = intent.getStringExtra(PLACE);
        step = place != null;
        Kit.pageTop(findViewById(R.id.share_top), this::finish,
            new Kit.Crumb(Kit.Icon.SHARE, step ? "Send" : "From another app", null));

        CharSequence caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (caption != null && caption.length() > 0) text = caption.toString();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) files.add(one);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) files.addAll(many);
        }
        if (text == null && files.isEmpty()) {
            toast("Nothing was shared");
            finish();
            return;
        }
        kind = kindOf();

        // A conversation picked straight from the share menu needs no question.
        conversation = ChatShortcuts.picked(intent);
        if (conversation != null && kind != ItemActions.Kind.FILES) { toConversation(); return; }
        String entry = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        if (entry.endsWith(".SharePiScreen") && playOnPi()) return;
        String post = files.isEmpty() && !step ? InstagramSave.link(text) : null;
        if (post != null) {
            // An Instagram post is saved from its own page; sending the link stays below it.
            // Sending the link is four short choices, so they are small cards rather than a list.
            instagram = new InstagramSave(this, body, post, () -> {
                Kit.label(body, "Or send the link");
                Kit.tileGrid(body, java.util.Arrays.asList(
                    new Kit.Action(Kit.Icon.DEVICE, "Device", null, this::chooseDevice),
                    new Kit.Action(Kit.Icon.CHAT, "Chat", null, this::toConversation),
                    new Kit.Action(Kit.Icon.NOTES, "Note", null, this::toNote),
                    new Kit.Action(R.drawable.ic_pin, "Pin", null, this::toPin)));
            });
            instagram.show();
            return;
        }
        render();
        String last = PeerStore.selected(this);
        if (entry.endsWith(".ShareLastDevice") && !last.isEmpty()) sendToPeer(last, 0);
        if (place != null) go(place);
    }

    @Override protected void onResume() {
        super.onResume();
        Rail.attach(this);
        // An item arriving from another app has no page of ours behind it, so it always fades through.
        Kit.lastPlace = null;
        Kit.arrive(this, "share-in");
    }

    /** Send the item to the place that was chosen before this page opened. */
    private void go(String place) {
        switch (place) {
            case "pi": toPiScreen(); break;
            case "cover": if (kind == ItemActions.Kind.IMAGE) confirmPiCover(); break;
            case "device": chooseDevice(); break;
            case "chat": toConversation(); break;
            case "note": toNote(); break;
            case "pin": toPin(); break;
            default: break;
        }
    }

    /** The file's type as its provider says, else as the sharing app said, else unknown. */
    private String fileType(Uri file) {
        String type;
        try { type = getContentResolver().getType(file); } catch (RuntimeException unreadable) { type = null; }
        if (type == null || type.isEmpty() || type.endsWith("/*")) type = getIntent().getType();
        return type == null || type.endsWith("/*") ? null : type;
    }

    private ItemActions.Kind kindOf() {
        if (files.size() > 1) return ItemActions.Kind.FILES;
        if (files.size() == 1) {
            Uri file = files.get(0);
            String plays = playableType(file);
            if (plays != null) return plays.startsWith("audio/") ? ItemActions.Kind.AUDIO : ItemActions.Kind.VIDEO;
            String type;
            try { type = getContentResolver().getType(file); } catch (RuntimeException unreadable) { type = null; }
            return isImage(displayName(file), type) ? ItemActions.Kind.IMAGE : ItemActions.Kind.DOCUMENT;
        }
        if (youtubeLink(text) != null) return ItemActions.Kind.VIDEO_LINK;
        Uri link = Uri.parse(text.trim());
        boolean web = ("http".equalsIgnoreCase(link.getScheme()) || "https".equalsIgnoreCase(link.getScheme()))
            && link.getHost() != null;
        return web ? ItemActions.Kind.LINK : ItemActions.Kind.TEXT;
    }

    // ---- the page ----

    private void render() {
        LinearLayout item = Kit.group(body);
        Kit.bindRow(Kit.addRow(item), ItemActions.icon(kind), itemTitle(), itemWords(), null, false).setClickable(false);

        progress = new TextView(this);
        progress.setTextAppearance(R.style.Kit_Text_RowSub);
        progress.setPadding(Kit.dp(this, 4), Kit.dp(this, 10), 0, 0);
        progress.setVisibility(View.GONE);
        body.addView(progress);
        // A step already knows its action; the list it came from is not offered a second time.
        if (step) return;
        renderActions();
    }

    private void renderActions() {
        // The same list every other place uses. This page does each choice itself, since the item is already here.
        ItemActions.Item shared = new ItemActions.Item(kind, itemTitle());
        shared.incoming = true;
        shared.text = text;
        if (!files.isEmpty()) {
            shared.mime = fileType(files.get(0));
            shared.file = got -> got.file(files.get(0), shared.mime);
        }
        shared.own.put(ItemActions.Act.PLAY_PI, this::toPiScreen);
        shared.own.put(ItemActions.Act.SHOW_PI, this::toPiScreen);
        shared.own.put(ItemActions.Act.COVER, this::confirmPiCover);
        shared.own.put(ItemActions.Act.COPY, this::copyText);
        shared.own.put(ItemActions.Act.DEVICE, this::chooseDevice);
        shared.own.put(ItemActions.Act.CHAT, this::toConversation);
        shared.own.put(ItemActions.Act.NOTE, this::toNote);
        shared.own.put(ItemActions.Act.PIN, this::toPin);
        Kit.sections(body, ItemActions.sections(this, shared), null);
    }

    /** Play it on the Pi screen, or show it there when it is a picture or words. */
    private void toPiScreen() {
        if (playOnPi()) return;
        if (kind == ItemActions.Kind.IMAGE) sendImageToPi(files.get(0), false);
        else if (kind == ItemActions.Kind.DOCUMENT && ItemActions.isPdf(displayName(files.get(0)), fileType(files.get(0))))
            sendDocumentToPi(files.get(0));
        else if (text != null && files.isEmpty()) showTextOnPi();
        else endsStep(Kit.sheet(this, "It cannot be shown on the Pi screen", "The Pi screen shows video, audio, pictures, PDFs and words."));
    }

    /** Send a PDF to the Pi, which draws its pages and shows the first; the pages are turned from the player. */
    private void sendDocumentToPi(Uri document) {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) { connectFirst(); return; }
        say("Sending the document to the Pi");
        new Thread(() -> {
            String failure = null;
            JSONObject result = null;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = getContentResolver().openInputStream(document)) {
                    if (input == null) throw new Exception("The document could not be read");
                    byte[] chunk = new byte[65536];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (bytes.size() + count > 25 * 1024 * 1024) throw new Exception("Choose a PDF under 25 MB");
                        bytes.write(chunk, 0, count);
                    }
                }
                result = new MediaClient(host, token).showDocument(bytes.toByteArray(), displayName(document));
            } catch (Exception error) { failure = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String error = failure;
            JSONObject applied = result;
            main.post(() -> {
                say(null);
                if (error == null) {
                    int pages = applied.optJSONObject("player") == null ? 0 : applied.optJSONObject("player").optInt("count");
                    toast(applied.optBoolean("sentToDisplay")
                        ? (pages > 1 ? "Showing page 1 of " + pages + " on the Pi screen" : "Showing on the Pi screen")
                        : "Sent. The Pi screen is off");
                    finish();
                } else endsStep(Kit.sheet(this, "It was not shown", error,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, go(() -> sendDocumentToPi(document)))));
            });
        }, "share-pi-document").start();
    }

    /** The readable name: the file's own name, or the first line of the text cut at a word. */
    private String itemTitle() {
        if (files.size() > 1) return files.size() + " files";
        if (files.size() == 1) return displayName(files.get(0));
        String line = text.trim().split("\n", 2)[0];
        if (line.length() <= 70) return line;
        int cut = line.lastIndexOf(' ', 70);
        return line.substring(0, cut > 30 ? cut : 70);
    }

    private String itemWords() {
        String what;
        switch (kind) {
            case VIDEO: what = "Video"; break;
            case AUDIO: what = "Audio"; break;
            case VIDEO_LINK: what = "YouTube link"; break;
            case IMAGE: what = "Image"; break;
            case LINK: what = "Link"; break;
            case TEXT: what = "Text"; break;
            case FILES: what = "Files"; break;
            default: what = "File";
        }
        String from = sharedFrom();
        return from == null ? what : what + " · from " + from;
    }

    /** The name of the app that shared it, when Android says which one it was. */
    private String sharedFrom() {
        try {
            Uri referrer = getReferrer();
            if (referrer == null || !"android-app".equals(referrer.getScheme())) return null;
            android.content.pm.PackageManager apps = getPackageManager();
            return apps.getApplicationLabel(apps.getApplicationInfo(referrer.getHost(), 0)).toString();
        } catch (Exception unknown) { return null; }
    }

    private void say(String words) {
        progress.setText(words);
        progress.setVisibility(words == null ? View.GONE : View.VISIBLE);
    }

    // ---- the places ----

    /** Start it on the Pi screen. False when this kind of item cannot be played there. */
    private boolean playOnPi() {
        if (kind == ItemActions.Kind.VIDEO_LINK) {
            Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain");
            cast.putExtra(Intent.EXTRA_TEXT, youtubeLink(text));
            startActivity(visit(cast));
        } else if (kind == ItemActions.Kind.VIDEO || kind == ItemActions.Kind.AUDIO) {
            Uri file = files.get(0);
            Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cast.setType(playableType(file));
            cast.putExtra(Intent.EXTRA_STREAM, file);
            cast.setClipData(android.content.ClipData.newUri(getContentResolver(), "Media to play", file));
            startActivity(visit(cast));
        } else return false;
        finish();
        return true;
    }

    /** Which conversation the item goes to: a new one, or one of the recent ones, unless the share menu already chose. */
    private void toConversation() {
        if (conversation != null) { toConversation(null); return; }
        org.json.JSONArray recent = ChatStore.index(this);
        List<Kit.Action> choices = new ArrayList<>();
        choices.add(new Kit.Action(R.drawable.csi_plus, "A new conversation", null, go(() -> toConversation(null))));
        for (int i = 0; i < Math.min(recent.length(), 8); i++) {
            org.json.JSONObject entry = recent.optJSONObject(i);
            if (entry == null || entry.optString("id").isEmpty()) continue;
            String id = entry.optString("id");
            choices.add(new Kit.Action(Kit.Icon.CHAT, entry.optString("title", "Conversation"), null, go(() -> toConversation(id))));
        }
        endsStep(Kit.sheet(this, "Send to a conversation", itemTitle(), choices.toArray(new Kit.Action[0])));
    }

    private void toConversation(String picked) {
        if (picked != null) conversation = picked;
        Intent chat = new Intent(this, MainActivity.class).putExtra("destination", "chat");
        if (conversation != null) chat.putExtra("chat_session", conversation);
        if (text != null) chat.putExtra("chat_prefill", text);
        if (files.size() == 1) {
            Uri file = files.get(0);
            chat.putExtra("chat_attach_uri", file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chat.setClipData(android.content.ClipData.newUri(getContentResolver(), "File for the assistant", file));
        }
        startActivity(visit(chat));
        finish();
    }

    private void toNote() {
        Intent note = new Intent(this, NotesActivity.class);
        if (!files.isEmpty()) {
            // A picture is drawn inside the note; any other file is kept beside it.
            Uri file = files.get(0);
            note.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(kind == ItemActions.Kind.IMAGE ? "note_image_uri" : "note_file_uri", file);
            if (kind != ItemActions.Kind.IMAGE) note.putExtra("note_file_name", displayName(file));
            if (text != null) note.putExtra("note_image_caption", text);
            note.setClipData(android.content.ClipData.newUri(getContentResolver(), "File for a note", file));
        } else note.putExtra("note_prefill_body", text);
        startActivity(visit(note));
        finish();
    }

    private void toPin() {
        if (!files.isEmpty()) {
            Uri file = files.get(0);
            Intent pin = new Intent(this, NotesActivity.class).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("pin_file_uri", file).putExtra("pin_file_name", displayName(file));
            pin.setClipData(android.content.ClipData.newUri(getContentResolver(), "File for a pin", file));
            startActivity(visit(pin));
            finish();
            return;
        }
        boolean link = kind == ItemActions.Kind.LINK || kind == ItemActions.Kind.VIDEO_LINK;
        startActivity(visit(new Intent(this, NotesActivity.class)
            .putExtra(link ? "pin_prefill_url" : "pin_prefill_text", text)));
        finish();
    }

    private void copyText() {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(android.content.ClipData.newPlainText("Shared text", text));
        toast("Copied");
        finish();
    }

    private String playableType(Uri uri) {
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (RuntimeException error) { mime = null; }
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")))
            return mime;
        String name = displayName(uri).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov") ||
            name.endsWith(".mkv") || name.endsWith(".webm")) return "video/*";
        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") ||
            name.endsWith(".flac") || name.endsWith(".ogg")) return "audio/*";
        return null;
    }

    private void confirmPiCover() {
        endsStep(Kit.confirm(this, "Replace the Pi cover with this image?", "It also takes the place of whatever is on the Pi screen now.",
            R.drawable.csi_image, "Replace", go(() -> sendImageToPi(files.get(0), true))));
    }

    /** Put shared words up on the Pi screen, large enough to read from across the room. */
    private void showTextOnPi() {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) { connectFirst(); return; }
        say("Sending the text to the Pi");
        new Thread(() -> {
            String failure = null;
            JSONObject result = null;
            try { result = new MediaClient(host, token).post("/v1/display/show", new JSONObject().put("text", MediaClient.screenText(text))); }
            catch (Exception error) { failure = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            final String error = failure;
            final JSONObject shown = result;
            main.post(() -> {
                say(null);
                if (error != null) {
                    endsStep(Kit.sheet(this, "It was not shown", error,
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, go(this::showTextOnPi))));
                    return;
                }
                toast(shown.optBoolean("sentToDisplay") ? "Showing on the Pi screen" : "Sent. The Pi screen is off");
                finish();
            });
        }, "share-show-text").start();
    }

    private void connectFirst() {
        endsStep(Kit.sheet(this, "Connect the Pi first", "The Pi address and the token are set in Settings. Share this again afterwards.",
            new Kit.Action(Kit.Icon.SETTINGS, "Open More", null, go(() -> {
                startActivity(new Intent(this, MainActivity.class).putExtra("destination", "more"));
                finish();
            }))));
    }

    /** Send a shared image to the Pi, either to show now or to keep as the cover as well. */
    private void sendImageToPi(Uri image, boolean asCover) {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) { connectFirst(); return; }
        say("Sending the image to the Pi");
        new Thread(() -> {
            String failure = null;
            JSONObject result = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = getContentResolver().openInputStream(image)) {
                    if (input == null) throw new Exception("The image could not be read");
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new Exception("The image could not be read");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 1920 ||
                       bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
                Bitmap bitmap;
                try (InputStream input = getContentResolver().openInputStream(image)) {
                    if (input == null) throw new Exception("The image could not be read");
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) throw new Exception("The image could not be read");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); }
                finally { bitmap.recycle(); }
                MediaClient pi = new MediaClient(host, token);
                result = asCover ? pi.uploadWallpaper(output.toByteArray()) : pi.showImage(output.toByteArray(), displayName(image));
            } catch (Exception error) { failure = error.getMessage(); }
            String error = failure;
            JSONObject applied = result;
            main.post(() -> {
                say(null);
                if (error == null) {
                    boolean lit = applied.optBoolean("sentToDisplay");
                    toast(asCover ? (lit ? "Saved as the Pi cover and shown" : "Saved as the Pi cover. The Pi screen is off")
                        : (lit ? "Showing on the Pi screen" : "Sent. The Pi screen is off"));
                    finish();
                } else endsStep(Kit.sheet(this, asCover ? "The Pi cover was not set" : "It was not shown", error,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, go(() -> sendImageToPi(image, asCover)))));
            });
        }, "share-pi-cover").start();
    }

    private String youtubeLink(String sharedText) {
        if (sharedText == null) return null;
        try {
            java.util.regex.Matcher links = java.util.regex.Pattern.compile("https://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sharedText);
            if (!links.find()) return null;
            String value = links.group().replaceAll("[.,;!?)]+$", "");
            if (links.find()) return null;
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            boolean allowed = host.equals("youtube.com") || host.equals("www.youtube.com") ||
                host.equals("m.youtube.com") || host.equals("music.youtube.com") ||
                host.equals("youtu.be");
            if (!allowed) return null;
            String id = host.endsWith("youtu.be") ? uri.getLastPathSegment() :
                "/watch".equals(uri.getPath()) ? uri.getQueryParameter("v") :
                uri.getPath() != null && uri.getPath().matches("/(shorts|live)/[A-Za-z0-9_-]{11}") ?
                    uri.getLastPathSegment() : null;
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? value : null;
        } catch (Exception error) { return null; }
    }

    // ---- sending to a device ----

    private void chooseDevice() {
        JSONArray roster = PeerStore.load(this);
        List<Kit.Action> devices = new ArrayList<>();
        String last = PeerStore.selected(this);
        for (int i = 0; i < roster.length(); i++) {
            JSONObject peer = roster.optJSONObject(i);
            if (peer == null || peer.optString("name").isEmpty()) continue;
            String name = peer.optString("name");
            String state = peer.optBoolean("online") ? "Online" : "Offline when last seen";
            devices.add(new Kit.Action(Kit.Icon.DEVICE, name, name.equals(last) ? state + " · you sent here last" : state, go(() -> {
                PeerStore.select(this, name);
                sendToPeer(name, 0);
            })));
        }
        if (devices.isEmpty()) {
            endsStep(Kit.sheet(this, "No devices known yet", "csync looks for your devices through the Pi.",
                new Kit.Action(R.drawable.csi_refresh, "Look for devices", null, go(this::scanThenChoose))));
            return;
        }
        endsStep(Kit.sheet(this, "Send to a device", itemTitle(), devices.toArray(new Kit.Action[0])));
    }

    private void scanThenChoose() {
        String home = Prefs.homeIp(this);
        String backup = Prefs.assistIp(this);
        if (home.isEmpty()) home = backup;
        String token = Prefs.token(this);
        if (home.isEmpty() || token.isEmpty()) { connectFirst(); return; }
        say("Looking for your devices");
        final String scanHost = home;
        new Thread(() -> {
            String error = null;
            try { PeerStore.mergeScan(this, MeshClient.peers(scanHost, token)); }
            catch (Exception failed) {
                if (!backup.isEmpty() && !backup.equals(scanHost)) {
                    try { PeerStore.mergeScan(this, MeshClient.peers(backup, token)); }
                    catch (Exception fallbackFailed) { error = fallbackFailed.getMessage(); }
                } else error = failed.getMessage();
            }
            final String failure = error;
            main.post(() -> {
                say(null);
                if (failure == null) chooseDevice();
                else endsStep(Kit.sheet(this, "Your devices could not be found", failure,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, go(this::scanThenChoose))));
            });
        }, "share-peer-scan").start();
    }

    private void sendToPeer(String peer, int nextItem) {
        final String token = Prefs.token(this);
        if (token.isEmpty()) { connectFirst(); return; }
        final String from = Prefs.deviceName(this);
        final int textItems = text == null ? 0 : 1;
        final int total = textItems + files.size();
        say(total == 1 ? "Sending to " + peer : "Sending " + (nextItem + 1) + " of " + total + " to " + peer);
        new Thread(() -> {
            int sent = nextItem;
            String err = null;
            String asked = getIntent().getStringExtra(TEXT_NAME);
            final String textName = asked == null || asked.trim().isEmpty() ? "shared.txt"
                : asked.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
            String pendingName = textName, pendingKind = "text", pendingUri = null, pendingText = null;
            try {
                for (int item = nextItem; item < total; item++) {
                    if (item < textItems) {
                        pendingName = textName;
                        pendingKind = "text";
                        pendingUri = null;
                        pendingText = text;
                        MeshClient.send(peer, token, from, "text", textName, text.getBytes("UTF-8"));
                    } else {
                        Uri u = files.get(item - textItems);
                        pendingUri = u.toString();
                        pendingText = null;
                        byte[] data = read(u);
                        String name = displayName(u);
                        String sentAs = isImage(name, getContentResolver().getType(u)) ? "image" : "file";
                        pendingName = name;
                        pendingKind = sentAs;
                        MeshClient.send(peer, token, from, sentAs, name, data);
                    }
                    Transfers.recordSent(this, pendingName, pendingKind, peer, true, pendingUri, pendingText);
                    sent++;
                    if (sent < total) {
                        final int next = sent + 1;
                        main.post(() -> say("Sending " + next + " of " + total + " to " + peer));
                    }
                }
            } catch (Throwable e) {
                err = e.getMessage() == null ? "The device did not answer." : e.getMessage();
                Transfers.recordSent(this, pendingName, pendingKind, peer, false, pendingUri, pendingText);
            }
            final int delivered = sent;
            final String error = err;
            main.post(() -> {
                say(null);
                if (error == null) {
                    toast(total == 1 ? "Sent to " + peer : "Sent " + delivered + " to " + peer);
                    finish();
                    return;
                }
                endsStep(Kit.sheet(this, "It was not sent", delivered + " of " + total + " reached " + peer + ". " + error,
                    new Kit.Action(R.drawable.csi_refresh, delivered == 0 ? "Try again" : "Send the rest", null,
                        go(() -> sendToPeer(peer, delivered))),
                    new Kit.Action(Kit.Icon.DEVICE, "Choose another device", null, go(this::chooseDevice))));
            });
        }, "share-send").start();
    }

    private byte[] read(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u);
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("The shared file cannot be opened");
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (bo.size() + r > 20 * 1024 * 1024) throw new Exception("Choose a file under 20 MB");
                bo.write(buf, 0, r);
            }
            return bo.toByteArray();
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignore) {
        }
        // A provider that will not say the name leaves its document id, such as primary:Download/x.pdf; the name is its tail.
        String last = u.getLastPathSegment();
        if (last == null || last.isEmpty()) return "shared-" + System.currentTimeMillis();
        last = last.substring(Math.max(last.lastIndexOf('/'), last.lastIndexOf(':')) + 1);
        return last.isEmpty() ? "shared-" + System.currentTimeMillis() : last;
    }

    private boolean isImage(String name, String mime) {
        if (mime != null && mime.startsWith("image/")) return true;
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
