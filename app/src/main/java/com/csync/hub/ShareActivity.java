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

    /** The kinds of thing that can arrive, each with its own set of places to go. */
    private enum Kind { MEDIA, IMAGE, DOCUMENT, FILES, VIDEO_LINK, LINK, TEXT }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> files = new ArrayList<>();
    private String text;
    // The conversation picked in the share menu, when one was.
    private String conversation;
    private Kind kind;
    private LinearLayout body;
    private TextView progress;

    @Override
    protected void onCreate(Bundle b) {
        Appearance.apply(this);
        super.onCreate(b);
        Appearance.applySystemBars(this);
        setContentView(R.layout.activity_share);
        body = findViewById(R.id.share_body);
        Kit.pageTop(findViewById(R.id.share_top), this::finish,
            new Kit.Crumb(Kit.Icon.SHARE, "From another app", null));

        final Intent intent = getIntent();
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
        if (conversation != null && kind != Kind.FILES) { toConversation(); return; }
        String entry = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        if (entry.endsWith(".SharePiScreen") && playOnPi()) return;
        render();
        String last = PeerStore.selected(this);
        if (entry.endsWith(".ShareLastDevice") && !last.isEmpty()) sendToPeer(last, 0);
    }

    private Kind kindOf() {
        if (files.size() > 1) return Kind.FILES;
        if (files.size() == 1) {
            Uri file = files.get(0);
            if (playableType(file) != null) return Kind.MEDIA;
            String type;
            try { type = getContentResolver().getType(file); } catch (RuntimeException unreadable) { type = null; }
            return isImage(displayName(file), type) ? Kind.IMAGE : Kind.DOCUMENT;
        }
        if (youtubeLink(text) != null) return Kind.VIDEO_LINK;
        Uri link = Uri.parse(text.trim());
        boolean web = ("http".equalsIgnoreCase(link.getScheme()) || "https".equalsIgnoreCase(link.getScheme()))
            && link.getHost() != null;
        return web ? Kind.LINK : Kind.TEXT;
    }

    // ---- the page ----

    private void render() {
        LinearLayout item = Kit.group(body);
        Kit.bindRow(Kit.addRow(item), itemIcon(), itemTitle(), itemWords(), null, false).setClickable(false);

        progress = new TextView(this);
        progress.setTextAppearance(R.style.Kit_Text_RowSub);
        progress.setPadding(Kit.dp(this, 4), Kit.dp(this, 10), 0, 0);
        progress.setVisibility(View.GONE);
        body.addView(progress);

        // What can be done with it straight away comes first, then the places it can be kept or sent.
        boolean first = true;
        if (kind == Kind.MEDIA || kind == Kind.VIDEO_LINK) {
            Kit.label(body, "Play");
            row(Kit.group(body), Kit.Icon.DISPLAY, "Play on Pi screen", null, false, this::playOnPi);
        } else if (kind == Kind.IMAGE) {
            Kit.label(body, "Show");
            LinearLayout show = Kit.group(body);
            row(show, Kit.Icon.DISPLAY, "Show on Pi screen", null, false, () -> sendImageToPi(files.get(0), false));
            row(show, R.drawable.csi_image, "Set as the Pi cover", null, true, this::confirmPiCover);
        } else if (kind == Kind.TEXT) {
            Kit.label(body, "Show");
            LinearLayout show = Kit.group(body);
            row(show, Kit.Icon.DISPLAY, "Show on Pi screen", null, false, this::showTextOnPi);
            row(show, R.drawable.csi_copy, "Copy the text", null, false, this::copyText);
        } else first = false;

        if (first) Kit.label(body, "Keep or send");
        else body.addView(new View(this), new LinearLayout.LayoutParams(-1, Kit.dp(this, 16)));
        LinearLayout places = Kit.group(body);
        row(places, Kit.Icon.DEVICE, "Send to a device", null, true, this::chooseDevice);
        if (kind != Kind.FILES)
            row(places, Kit.Icon.CHAT, "Send to a conversation", null, false, this::toConversation);
        if (text != null && files.isEmpty() || kind == Kind.IMAGE)
            row(places, Kit.Icon.NOTES, "Add to a note", null, false, this::toNote);
        if (files.isEmpty())
            row(places, R.drawable.ic_pin, "Save as a pin", null, false, this::toPin);
    }

    private void row(LinearLayout group, int icon, String title, String sub, boolean opens, Runnable run) {
        View row = Kit.addRow(group);
        Kit.bindRow(row, icon, title, sub, null, opens);
        row.setOnClickListener(v -> run.run());
    }

    private int itemIcon() {
        switch (kind) {
            case MEDIA: case VIDEO_LINK: return Kit.Icon.VIDEO;
            case IMAGE: return Kit.Icon.PHOTO;
            case LINK: return R.drawable.csi_link;
            case TEXT: return R.drawable.csi_text;
            case FILES: return Kit.Icon.FILES;
            default: return Kit.Icon.FILE;
        }
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
            case MEDIA: what = "Video or audio"; break;
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
        if (kind == Kind.VIDEO_LINK) {
            Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain");
            cast.putExtra(Intent.EXTRA_TEXT, youtubeLink(text));
            startActivity(cast);
        } else if (kind == Kind.MEDIA) {
            Uri file = files.get(0);
            Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cast.setType(playableType(file));
            cast.putExtra(Intent.EXTRA_STREAM, file);
            cast.setClipData(android.content.ClipData.newUri(getContentResolver(), "Media to play", file));
            startActivity(cast);
        } else return false;
        finish();
        return true;
    }

    private void toConversation() {
        Intent chat = new Intent(this, MainActivity.class).putExtra("destination", "chat");
        if (conversation != null) chat.putExtra("chat_session", conversation);
        if (text != null) chat.putExtra("chat_prefill", text);
        if (files.size() == 1) {
            Uri file = files.get(0);
            chat.putExtra("chat_attach_uri", file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chat.setClipData(android.content.ClipData.newUri(getContentResolver(), "File for the assistant", file));
        }
        startActivity(chat);
        finish();
    }

    private void toNote() {
        Intent note = new Intent(this, NotesActivity.class);
        if (kind == Kind.IMAGE) {
            Uri image = files.get(0);
            note.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("note_image_uri", image);
            if (text != null) note.putExtra("note_image_caption", text);
            note.setClipData(android.content.ClipData.newUri(getContentResolver(), "Image for a note", image));
        } else note.putExtra("note_prefill_body", text);
        startActivity(note);
        finish();
    }

    private void toPin() {
        boolean link = kind == Kind.LINK || kind == Kind.VIDEO_LINK;
        startActivity(new Intent(this, NotesActivity.class)
            .putExtra(link ? "pin_prefill_url" : "pin_prefill_text", text));
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
        Kit.sheet(this, "Use this image on the Pi screen?", "It becomes the Pi cover and replaces what is playing there.",
            new Kit.Action(R.drawable.csi_image, "Set as the Pi cover", null, () -> sendImageToPi(files.get(0), true)));
    }

    /** Put shared words up on the Pi screen, large enough to read from across the room. */
    private void showTextOnPi() {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) { connectFirst(); return; }
        say("Sending the text to the Pi");
        new Thread(() -> {
            String failure = null;
            JSONObject result = null;
            try { result = new MediaClient(host, token).post("/v1/display/show", new JSONObject().put("text", text)); }
            catch (Exception error) { failure = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            final String error = failure;
            final JSONObject shown = result;
            main.post(() -> {
                say(null);
                if (error != null) {
                    Kit.sheet(this, "It was not shown", error,
                        new Kit.Action(R.drawable.csi_refresh, "Try again", null, this::showTextOnPi));
                    return;
                }
                toast(shown.optBoolean("sentToDisplay") ? "Showing on the Pi screen" : "Sent. The Pi screen is off");
                finish();
            });
        }, "share-show-text").start();
    }

    private void connectFirst() {
        Kit.sheet(this, "Connect the Pi first", "The Pi address and the token are set in Settings. Share this again afterwards.",
            new Kit.Action(Kit.Icon.SETTINGS, "Open More", null, () -> {
                startActivity(new Intent(this, MainActivity.class).putExtra("destination", "more"));
                finish();
            }));
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
                result = asCover ? pi.uploadWallpaper(output.toByteArray()) : pi.showImage(output.toByteArray());
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
                } else Kit.sheet(this, asCover ? "The Pi cover was not set" : "It was not shown", error,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> sendImageToPi(image, asCover)));
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
            devices.add(new Kit.Action(Kit.Icon.DEVICE, name, name.equals(last) ? state + " · you sent here last" : state, () -> {
                PeerStore.select(this, name);
                sendToPeer(name, 0);
            }));
        }
        if (devices.isEmpty()) {
            Kit.sheet(this, "No devices known yet", "csync looks for your devices through the Pi.",
                new Kit.Action(R.drawable.csi_refresh, "Look for devices", null, this::scanThenChoose));
            return;
        }
        Kit.sheet(this, "Send to a device", itemTitle(), devices.toArray(new Kit.Action[0]));
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
                else Kit.sheet(this, "Your devices could not be found", failure,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, this::scanThenChoose));
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
            String pendingName = "shared.txt", pendingKind = "text";
            try {
                for (int item = nextItem; item < total; item++) {
                    if (item < textItems) {
                        pendingName = "shared.txt";
                        pendingKind = "text";
                        MeshClient.send(peer, token, from, "text", "shared.txt", text.getBytes("UTF-8"));
                    } else {
                        Uri u = files.get(item - textItems);
                        byte[] data = read(u);
                        String name = displayName(u);
                        String sentAs = isImage(name, getContentResolver().getType(u)) ? "image" : "file";
                        pendingName = name;
                        pendingKind = sentAs;
                        MeshClient.send(peer, token, from, sentAs, name, data);
                    }
                    recordSent(pendingName, pendingKind, peer, true);
                    sent++;
                    if (sent < total) {
                        final int next = sent + 1;
                        main.post(() -> say("Sending " + next + " of " + total + " to " + peer));
                    }
                }
            } catch (Throwable e) {
                err = e.getMessage() == null ? "The device did not answer." : e.getMessage();
                recordSent(pendingName, pendingKind, peer, false);
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
                Kit.sheet(this, "It was not sent", delivered + " of " + total + " reached " + peer + ". " + error,
                    new Kit.Action(R.drawable.csi_refresh, delivered == 0 ? "Try again" : "Send the rest", null,
                        () -> sendToPeer(peer, delivered)),
                    new Kit.Action(Kit.Icon.DEVICE, "Choose another device", null, this::chooseDevice));
            });
        }, "share-send").start();
    }

    private void recordSent(String name, String sentAs, String target, boolean delivered) {
        android.content.SharedPreferences prefs = getSharedPreferences("csync_share", MODE_PRIVATE);
        JSONArray prior;
        try { prior = new JSONArray(prefs.getString("sent", "[]")); }
        catch (Exception ignored) { prior = new JSONArray(); }
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", name);
            entry.put("kind", sentAs);
            entry.put("target", target);
            entry.put("delivered", delivered);
            entry.put("at", System.currentTimeMillis());
        } catch (Exception ignored) { }
        JSONArray next = new JSONArray().put(entry);
        for (int i = 0; i < Math.min(prior.length(), 49); i++) next.put(prior.optJSONObject(i));
        prefs.edit().putString("sent", next.toString()).apply();
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
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignore) {
        }
        String last = u.getLastPathSegment();
        return last != null ? last : "shared-" + System.currentTimeMillis();
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
