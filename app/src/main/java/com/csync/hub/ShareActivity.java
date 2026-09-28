package com.csync.hub;

import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes Android Sharesheet content to a Pi screen, a local draft, or a peer.
 */
public class ShareActivity extends AppCompatActivity {

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Uri imageToSave;

    @Override
    protected void onCreate(Bundle b) {
        Appearance.apply(this);
        super.onCreate(b);
        Appearance.applySystemBars(this);
        if (b != null) imageToSave = b.getParcelable("pending_image_uri");
        if (imageToSave != null) return;

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final List<Uri> uris = new ArrayList<>();
        String incomingText = null;

        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (caption != null) incomingText = caption.toString();
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            CharSequence caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (caption != null) incomingText = caption.toString();
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        final String sharedText = incomingText == null || incomingText.isEmpty() ? null : incomingText;

        if (sharedText == null && uris.isEmpty()) {
            toast("Nothing to share");
            finish();
            return;
        }

        boolean mediaFile = uris.size() == 1 && isPlayableMedia(uris.get(0));
        String youtubeUrl = sharedText == null ? null : youtubeLink(sharedText);
        boolean youtube = youtubeUrl != null;
        if (sharedText != null && uris.isEmpty()) {
            Uri link = Uri.parse(sharedText.trim());
            boolean webLink = ("http".equalsIgnoreCase(link.getScheme()) ||
                "https".equalsIgnoreCase(link.getScheme())) && link.getHost() != null;
            List<String> choices = new ArrayList<>();
            if (youtube) choices.add("Show on Pi screen");
            if (webLink) choices.add("Save URL pin");
            else choices.add("Save text pin");
            choices.add("New note");
            choices.add("Send to chat");
            choices.add("Send to peer");
            new AlertDialog.Builder(this).setTitle("Share with csync")
                .setItems(choices.toArray(new String[0]), (dialog, selected) -> {
                    String choice = choices.get(selected);
                    if ("Show on Pi screen".equals(choice)) {
                        Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND);
                        cast.setType("text/plain");
                        cast.putExtra(Intent.EXTRA_TEXT, youtubeUrl);
                        startActivity(cast);
                        finish();
                    } else if ("Save URL pin".equals(choice) || "Save text pin".equals(choice)
                        || "New note".equals(choice)) {
                        Intent save = new Intent(this, NotesActivity.class);
                        String destination = "Save URL pin".equals(choice) ? "pin_prefill_url" :
                            "Save text pin".equals(choice) ? "pin_prefill_text" : "note_prefill_body";
                        save.putExtra(destination, sharedText);
                        startActivity(save);
                        finish();
                    } else if ("Send to chat".equals(choice)) {
                        Intent chat = new Intent(this, MainActivity.class);
                        chat.putExtra("destination", "chat");
                        chat.putExtra("chat_prefill", sharedText);
                        startActivity(chat);
                        finish();
                    } else choosePeerAndSend(sharedText, uris);
                }).setNegativeButton("Cancel", (dialog, choice) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        if (mediaFile) {
            new AlertDialog.Builder(this).setTitle("Share with csync")
                .setItems(new String[]{"Play on Pi screen", "Send to peer"}, (dialog, choice) -> {
                    if (choice == 0) {
                        Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        cast.setType(playableType(uris.get(0)));
                        cast.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                        cast.setClipData(android.content.ClipData.newUri(getContentResolver(),
                            "Media to cast", uris.get(0)));
                        startActivity(cast);
                        finish();
                    } else choosePeerAndSend(sharedText, uris);
                }).setNegativeButton("Cancel", (dialog, choice) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        if (uris.size() == 1 && isImage(displayName(uris.get(0)),
                getContentResolver().getType(uris.get(0)))) {
            Uri image = uris.get(0);
            new AlertDialog.Builder(this).setTitle("Shared image")
                .setItems(new String[]{"Save on this phone", "Add to Pi note", "Set as Pi cover", "Send to device"},
                    (dialog, choice) -> {
                        if (choice == 0) saveImageOnPhone(image);
                        else if (choice == 1) {
                            Intent note = new Intent(this, NotesActivity.class)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            note.putExtra("note_image_uri", image);
                            if (sharedText != null) note.putExtra("note_image_caption", sharedText);
                            note.setClipData(android.content.ClipData.newUri(getContentResolver(),
                                "Image for Pi note", image));
                            startActivity(note);
                            finish();
                        } else if (choice == 2) new AlertDialog.Builder(this)
                            .setTitle("Use image on Pi display?")
                            .setMessage("This saves the image as the Pi cover and replaces active Pi playback.")
                            .setPositiveButton("Set cover", (confirm, decision) -> setPiCover(image))
                            .setNegativeButton("Cancel", (confirm, decision) -> finish())
                            .show();
                        else choosePeerAndSend(sharedText, uris);
                    })
                .setNegativeButton("Cancel", (dialog, choice) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Share with csync")
            .setItems(new String[]{uris.size() == 1 ? "Send file to device" :
                "Send " + uris.size() + " files to device"},
                (dialog, choice) -> choosePeerAndSend(sharedText, uris))
            .setNegativeButton("Cancel", (dialog, choice) -> finish())
            .setOnCancelListener(dialog -> finish()).show();
    }

    private boolean isPlayableMedia(Uri uri) {
        return playableType(uri) != null;
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

    private void saveImageOnPhone(Uri image) {
        imageToSave = image;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(imageType(image))
            .putExtra(Intent.EXTRA_TITLE, displayName(image));
        startActivityForResult(create, 90);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable("pending_image_uri", imageToSave);
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 90) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || imageToSave == null) {
            finish();
            return;
        }
        Uri source = imageToSave, destination = data.getData();
        new Thread(() -> {
            String failure = null;
            try (InputStream input = getContentResolver().openInputStream(source);
                 java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (input == null || output == null) throw new Exception("Could not open the image");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (Exception error) {
                failure = error.getMessage();
                try { getContentResolver().delete(destination, null, null); }
                catch (Exception ignored) { }
            }
            String result = failure;
            main.post(() -> {
                if (result == null) { toast("Image saved on this phone"); finish(); }
                else new AlertDialog.Builder(this).setTitle("Could not save image")
                    .setMessage(result)
                    .setPositiveButton("Try again", (dialog, choice) -> saveImageOnPhone(source))
                    .setNegativeButton("Cancel", (dialog, choice) -> finish()).show();
            });
        }, "share-image-save").start();
    }

    private String imageType(Uri image) {
        String type = getContentResolver().getType(image);
        return type != null && type.startsWith("image/") ? type : "image/png";
    }

    private void setPiCover(Uri image) {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Connect the Pi")
                .setMessage("Set the Pi address and mesh token in More → Settings first.")
                .setPositiveButton("Close", (dialog, choice) -> finish()).show();
            return;
        }
        toast("Preparing Pi cover");
        new Thread(() -> {
            String failure = null;
            JSONObject result = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = getContentResolver().openInputStream(image)) {
                    if (input == null) throw new Exception("Could not read shared image");
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new Exception("Could not decode shared image");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 1920 ||
                       bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
                Bitmap bitmap;
                try (InputStream input = getContentResolver().openInputStream(image)) {
                    if (input == null) throw new Exception("Could not read shared image");
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) throw new Exception("Could not decode shared image");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); }
                finally { bitmap.recycle(); }
                result = new MediaClient(host, token).uploadWallpaper(output.toByteArray());
            } catch (Exception error) { failure = error.getMessage(); }
            String error = failure;
            JSONObject applied = result;
            main.post(() -> {
                if (error == null) {
                    toast(applied.optBoolean("sentToDisplay") ?
                        "Pi cover saved and shown" : "Pi cover saved; display unavailable");
                    finish();
                } else new AlertDialog.Builder(this).setTitle("Could not set Pi cover")
                    .setMessage(error)
                    .setPositiveButton("Retry", (dialog, choice) -> setPiCover(image))
                    .setNegativeButton("Cancel", (dialog, choice) -> finish()).show();
            });
        }, "share-pi-cover").start();
    }

    private String youtubeLink(String sharedText) {
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

    private void choosePeerAndSend(String sharedText, List<Uri> uris) {
        JSONArray roster = PeerStore.load(this);
        List<String> names = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String selected = PeerStore.selected(this);
        for (int i = 0; i < roster.length(); i++) {
            JSONObject peer = roster.optJSONObject(i);
            if (peer == null) continue;
            String name = peer.optString("name");
            if (name.isEmpty()) continue;
            names.add(name);
            labels.add(name + (peer.optBoolean("online") ? " · online" : " · last seen offline")
                + (name.equals(selected) ? " · selected" : ""));
        }
        if (names.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Choose a device")
                .setMessage("Scan your mesh devices to choose where this item goes.")
                .setPositiveButton("Scan devices", (dialog, choice) -> scanPeersAndChoose(sharedText, uris))
                .setNegativeButton("Cancel", (dialog, choice) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Send to device")
            .setItems(labels.toArray(new String[0]), (dialog, choice) -> {
                String peer = names.get(choice);
                PeerStore.select(this, peer);
                sendToPeer(peer, sharedText, uris, 0);
            })
            .setNegativeButton("Cancel", (dialog, choice) -> finish())
            .setOnCancelListener(dialog -> finish()).show();
    }

    private void scanPeersAndChoose(String sharedText, List<Uri> uris) {
        String home = Prefs.homeIp(this);
        String backup = Prefs.assistIp(this);
        if (home.isEmpty()) home = backup;
        String token = Prefs.token(this);
        if (home.isEmpty() || token.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Connect csync")
                .setMessage("Set the Pi or home peer and mesh token in More → Settings, then share this item again.")
                .setPositiveButton("Open More", (dialog, choice) -> {
                    startActivity(new Intent(this, MainActivity.class).putExtra("destination", "more"));
                    finish();
                })
                .setNegativeButton("Cancel", (dialog, choice) -> finish()).show();
            return;
        }
        toast("Scanning devices");
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
                if (failure == null) choosePeerAndSend(sharedText, uris);
                else new AlertDialog.Builder(this).setTitle("Could not scan devices")
                    .setMessage(failure)
                    .setPositiveButton("Retry", (dialog, choice) -> scanPeersAndChoose(sharedText, uris))
                    .setNegativeButton("Cancel", (dialog, choice) -> finish()).show();
            });
        }, "share-peer-scan").start();
    }

    private void sendToPeer(String peer, String sharedText, List<Uri> uris, int nextItem) {
        final String token = Prefs.token(this);
        if (token.isEmpty()) {
            toast("Set the mesh token in csync Settings first");
            finish();
            return;
        }
        final String from = Prefs.deviceName(this);
        final int textItems = sharedText == null ? 0 : 1;
        final int total = textItems + uris.size();
        AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("Sending to " + peer)
            .setMessage("Sending item " + (nextItem + 1) + " of " + total)
            .setCancelable(false).create();
        progress.show();
        new Thread(new Runnable() {
            public void run() {
                int sent = nextItem;
                String err = null;
                String pendingName = "shared.txt", pendingKind = "text";
                try {
                    for (int item = nextItem; item < total; item++) {
                        if (item < textItems) {
                            pendingName = "shared.txt";
                            pendingKind = "text";
                            MeshClient.send(peer, token, from, "text", "shared.txt",
                                sharedText.getBytes("UTF-8"));
                        } else {
                            Uri u = uris.get(item - textItems);
                            byte[] data = read(u);
                            String name = displayName(u);
                            String kind = isImage(name, getContentResolver().getType(u)) ? "image" : "file";
                            pendingName = name;
                            pendingKind = kind;
                            MeshClient.send(peer, token, from, kind, name, data);
                        }
                        recordSent(pendingName, pendingKind, peer, true);
                        sent++;
                        if (sent < total) {
                            final int next = sent + 1;
                            main.post(() -> progress.setMessage("Sending item " + next + " of " + total));
                        }
                    }
                } catch (Throwable e) {
                    err = e.getMessage();
                    recordSent(pendingName, pendingKind, peer, false);
                }
                final int delivered = sent;
                final String error = err;
                if (error == null) android.util.Log.i("csynchub", "shared " + delivered + " item(s) to " + peer);
                else android.util.Log.e("csynchub", "share failed: " + error);
                main.post(new Runnable() {
                    public void run() {
                        progress.dismiss();
                        if (error == null) {
                            toast("Sent " + delivered + " to " + peer);
                            finish();
                        } else {
                            new AlertDialog.Builder(ShareActivity.this).setTitle("Share failed")
                                .setMessage(delivered + " of " + total + " items confirmed sent to " + peer + ". " +
                                    (error == null ? "The item could not be sent." : error))
                                .setPositiveButton("Retry remaining", (dialog, choice) ->
                                    sendToPeer(peer, sharedText, uris, delivered))
                                .setNegativeButton("Cancel", (dialog, choice) -> finish())
                                .setOnCancelListener(dialog -> finish()).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void recordSent(String name, String kind, String target, boolean delivered) {
        android.content.SharedPreferences prefs = getSharedPreferences("csync_share", MODE_PRIVATE);
        JSONArray prior;
        try { prior = new JSONArray(prefs.getString("sent", "[]")); }
        catch (Exception ignored) { prior = new JSONArray(); }
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", name);
            entry.put("kind", kind);
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
