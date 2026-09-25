package com.csync.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the Android system share sheet. Whatever the user shares (text, an
 * image, a file) is pushed to the configured home peer over the tailnet, then
 * this transparent activity finishes.
 */
public class ShareActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        android.util.Log.i("csynchub", "share in: action=" + action + " type=" + type
                + " hasStream=" + intent.hasExtra(Intent.EXTRA_STREAM)
                + " stream=" + intent.getParcelableExtra(Intent.EXTRA_STREAM)
                + " data=" + intent.getData());

        final List<Uri> uris = new ArrayList<>();
        final String sharedText;

        if (Intent.ACTION_SEND.equals(action)) {
            if (type != null && type.startsWith("text/") && intent.hasExtra(Intent.EXTRA_TEXT)
                    && intent.getParcelableExtra(Intent.EXTRA_STREAM) == null) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else {
                sharedText = null;
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            sharedText = null;
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            sharedText = null;
        }

        if (sharedText == null && uris.isEmpty()) {
            toast("Nothing to share");
            finish();
            return;
        }

        boolean mediaFile = uris.size() == 1 && isPlayableMedia(uris.get(0));
        String youtubeUrl = sharedText == null ? null : youtubeLink(sharedText);
        boolean youtube = youtubeUrl != null;
        if (mediaFile || youtube) {
            new AlertDialog.Builder(this).setTitle("Share with csync")
                .setItems(new String[]{"Play on Pi screen", "Send to peer"}, (dialog, choice) -> {
                    if (choice == 0) {
                        Intent cast = new Intent(this, MediaActivity.class).setAction(Intent.ACTION_SEND)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (mediaFile) {
                            cast.setType(getContentResolver().getType(uris.get(0)));
                            cast.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                            cast.setClipData(android.content.ClipData.newUri(getContentResolver(),
                                "Media to cast", uris.get(0)));
                        } else {
                            cast.setType("text/plain");
                            cast.putExtra(Intent.EXTRA_TEXT, youtubeUrl);
                        }
                        startActivity(cast);
                        finish();
                    } else sendToPeer(sharedText, uris);
                }).setNegativeButton("Cancel", (dialog, choice) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        sendToPeer(sharedText, uris);
    }

    private boolean isPlayableMedia(Uri uri) {
        String mime = getContentResolver().getType(uri);
        return mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"));
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

    private void sendToPeer(String sharedText, List<Uri> uris) {
        final String ip = Prefs.homeIp(this);
        final String token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) {
            toast("Set the home peer in csync → Settings first");
            finish();
            return;
        }
        final String from = Prefs.deviceName(this);
        new Thread(new Runnable() {
            public void run() {
                int ok = 0;
                String err = null;
                try {
                    if (sharedText != null) {
                        MeshClient.send(ip, token, from, "text", "shared.txt",
                                sharedText.getBytes("UTF-8"));
                        ok++;
                    }
                    for (Uri u : uris) {
                        byte[] data = read(u);
                        String name = displayName(u);
                        String kind = isImage(name, getContentResolver().getType(u)) ? "image" : "file";
                        MeshClient.send(ip, token, from, kind, name, data);
                        ok++;
                    }
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final int sent = ok;
                final String error = err;
                if (error == null) android.util.Log.i("csynchub", "shared " + sent + " item(s) to " + ip);
                else android.util.Log.e("csynchub", "share failed: " + error);
                main.post(new Runnable() {
                    public void run() {
                        if (error == null) {
                            toast("Sent " + sent + " to " + ip);
                        } else {
                            toast("Send failed: " + error);
                        }
                        finish();
                    }
                });
            }
        }).start();
    }

    private byte[] read(Uri u) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
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
