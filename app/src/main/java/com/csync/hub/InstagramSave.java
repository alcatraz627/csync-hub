package com.csync.hub;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saving an Instagram post shared into csync: the post's pictures and videos, picked one,
 * some or all, saved into the phone's gallery.
 *
 * The Pi reads the post and hands each slide over; the phone shows the slides as a grid,
 * remembers which are saved already, and saves the chosen ones as background work with a
 * progress notification and Stop. When Instagram asks for a login, the page offers to sign in.
 */
final class InstagramSave {
    static final int SIGN_IN = 4211;
    private static final Pattern POST = Pattern.compile(
        "https?://(?:www\\.|m\\.)?instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,40})[^\\s]*");

    private final Activity a;
    private final LinearLayout body;
    private final String link;
    private final Runnable others;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Integer> chosen = new TreeSet<>();
    private final List<View> tiles = new ArrayList<>();
    private JSONObject post;
    private Set<String> savedNames;
    private LinearLayout page;
    private View saveButton;
    private TextView pickAll;

    /** The Instagram post link inside shared words, or null when there is none. */
    static String link(String shared) {
        if (shared == null) return null;
        Matcher m = POST.matcher(shared);
        return m.find() ? m.group(0) : null;
    }

    InstagramSave(Activity a, LinearLayout body, String link, Runnable others) {
        this.a = a; this.body = body; this.link = link; this.others = others;
    }

    /** Draw the page and look the post up. */
    void show() {
        page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        body.addView(page, 0);
        lookUp();
    }

    private void lookUp() {
        page.removeAllViews();
        View row = Kit.addRow(Kit.group(page));
        Kit.bindRow(row, Kit.Icon.PHOTO, "A post from Instagram", "Looking at it on the Pi", null, false).setClickable(false);
        Kit.rowStatus(row, Kit.Status.WARN, "Checking");
        MediaClient client = client();
        if (client == null) { problem("Connect to the Pi first", null, false); return; }
        new Thread(() -> {
            try {
                JSONObject found = client.post("/v1/instagram/inspect", new JSONObject().put("url", link));
                Set<String> saved = Gallery.saved(a, "instagram-" + found.optString("code") + "-");
                main.post(() -> { post = found; savedNames = saved; drawPost(); });
            } catch (MediaClient.MediaException refused) {
                main.post(() -> problem(refused.getMessage(), refused.code, "INSTAGRAM_SIGN_IN".equals(refused.code)));
            } catch (Exception failed) {
                main.post(() -> problem("The Pi did not answer", null, false));
            }
        }, "instagram-look").start();
    }

    private MediaClient client() {
        String host = Prefs.assistIp(a), token = Prefs.token(a);
        return host.isEmpty() || token.isEmpty() ? null : new MediaClient(host, token);
    }

    private void problem(String words, String code, boolean signIn) {
        if (a.isFinishing()) return;
        page.removeAllViews();
        View row = Kit.addRow(Kit.group(page));
        Kit.bindRow(row, Kit.Icon.PHOTO, "A post from Instagram", words, null, false).setClickable(false);
        Kit.rowStatus(row, signIn ? Kit.Status.WARN : Kit.Status.BAD, signIn ? "Sign in" : "Failed");
        LinearLayout actions = actionRow();
        if (signIn) actions.addView(Kit.primaryButton(a, R.drawable.csi_link, "Sign in to Instagram",
            () -> a.startActivityForResult(new Intent(a, InstagramSignIn.class), SIGN_IN)));
        else actions.addView(Kit.tonalButton(a, R.drawable.csi_refresh, "Try again", this::lookUp));
        drawOthers();
    }

    private boolean othersDrawn;

    /** The link's other choices, drawn once below whatever the post page shows. */
    private void drawOthers() {
        if (othersDrawn) return;
        othersDrawn = true;
        others.run();
    }

    /** After the sign-in page closes: look again when it signed in. */
    void signedIn(boolean ok) { if (ok) lookUp(); }

    private void drawPost() {
        if (a.isFinishing()) return;
        page.removeAllViews();
        JSONArray items = post.optJSONArray("items");
        int count = items == null ? 0 : items.length();
        String uploader = post.optString("uploader");
        String caption = post.optString("caption").split("\n", 2)[0];
        View row = Kit.addRow(Kit.group(page));
        Kit.bindRow(row, count > 1 ? Kit.Icon.PHOTO : kindIcon(items.optJSONObject(0)),
            uploader.isEmpty() ? "A post from Instagram" : "A post from " + uploader,
            caption.isEmpty() ? null : caption,
            count == 1 ? null : count + " parts", false).setClickable(false);

        chosen.clear();
        for (int i = 0; i < count; i++) if (!isSaved(items.optJSONObject(i).optInt("index"))) chosen.add(i + 1);

        Kit.label(page, count > 1 ? "Choose what to keep" : "Keep it");
        GridLayout grid = new GridLayout(a);
        int columns = count == 1 ? 1 : count == 2 || count == 4 ? 2 : 3;
        grid.setColumnCount(columns);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        tiles.clear();
        for (int i = 0; i < count; i++) tiles.add(tile(grid, items.optJSONObject(i), columns));

        LinearLayout actions = actionRow();
        if (count > 1) {
            View all = Kit.tonalButton(a, R.drawable.csi_check, "Select all", this::toggleAll);
            pickAll = findText(all);
            actions.addView(all);
            View spacer = new View(a);
            actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        }
        saveButton = Kit.primaryButton(a, R.drawable.csi_download, "", this::save);
        actions.addView(saveButton);
        refresh();
        drawOthers();
    }

    private boolean isSaved(int index) {
        String stem = "instagram-" + post.optString("code") + "-" + index + ".";
        for (String name : savedNames) if (name.startsWith(stem)) return true;
        return false;
    }

    private static int kindIcon(JSONObject item) {
        return item != null && "video".equals(item.optString("kind")) ? Kit.Icon.VIDEO : Kit.Icon.PHOTO;
    }

    private View tile(GridLayout grid, JSONObject item, int columns) {
        int index = item.optInt("index");
        boolean video = "video".equals(item.optString("kind"));
        FrameLayout tile = new FrameLayout(a);
        int gap = Kit.dp(a, 4);
        int width = (a.getResources().getDisplayMetrics().widthPixels
            - body.getPaddingStart() - body.getPaddingEnd()) ;
        int side = Math.min(width, Kit.dp(a, 640)) / columns - gap * 2;
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = side;
        params.height = columns == 1 ? side * 5 / 4 : side;
        params.setMargins(gap, gap, gap, gap);
        tile.setLayoutParams(params);
        tile.setBackgroundResource(R.drawable.card_bg);
        tile.setClipToOutline(true);
        grid.addView(tile);

        ImageView picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        load(item.optString("thumbnail"), picture, columns == 1 ? 900 : 360);

        if (video) tile.addView(badge(R.drawable.csi_video, null), corner(Gravity.BOTTOM | Gravity.START));
        if (isSaved(index)) tile.addView(badge(R.drawable.csi_download, "Saved"), corner(Gravity.BOTTOM | Gravity.END));
        ImageView check = new ImageView(a);
        check.setImageResource(R.drawable.csi_check);
        check.setPadding(Kit.dp(a, 4), Kit.dp(a, 4), Kit.dp(a, 4), Kit.dp(a, 4));
        check.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.onAccent)));
        tile.addView(check, corner(Gravity.TOP | Gravity.END));
        tile.setTag(index);
        tile.setOnClickListener(v -> {
            if (!chosen.remove(index)) chosen.add(index);
            Kit.tick(v);
            refresh();
        });
        return tile;
    }

    private FrameLayout.LayoutParams corner(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
        p.setMargins(Kit.dp(a, 8), Kit.dp(a, 8), Kit.dp(a, 8), Kit.dp(a, 8));
        return p;
    }

    /** A small dark label over a picture: an icon, and words when given. */
    private View badge(int icon, String words) {
        LinearLayout badge = new LinearLayout(a);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(Kit.dp(a, 6), Kit.dp(a, 3), Kit.dp(a, 8), Kit.dp(a, 3));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x99000000);
        shape.setCornerRadius(Kit.dp(a, 10));
        badge.setBackground(shape);
        ImageView symbol = new ImageView(a);
        symbol.setImageResource(icon);
        symbol.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        badge.addView(symbol, new LinearLayout.LayoutParams(Kit.dp(a, 14), Kit.dp(a, 14)));
        if (words != null) {
            TextView label = new TextView(a);
            label.setText(words);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(12);
            label.setPadding(Kit.dp(a, 4), 0, 0, 0);
            badge.addView(label);
        }
        return badge;
    }

    /** Redraw what depends on the choice: the tiles' marks, Select all, and the Save button. */
    private void refresh() {
        int accent = Kit.accentText(a);
        for (View view : tiles) {
            int index = (Integer) view.getTag();
            boolean on = chosen.contains(index);
            FrameLayout tile = (FrameLayout) view;
            View check = tile.getChildAt(tile.getChildCount() - 1);
            GradientDrawable mark = new GradientDrawable();
            mark.setShape(GradientDrawable.OVAL);
            mark.setColor(on ? accent : 0x66000000);
            mark.setStroke(Kit.dp(a, 2), 0xFFFFFFFF);
            check.setBackground(mark);
            check.setAlpha(on ? 1f : 0.85f);
            ((ImageView) check).setImageAlpha(on ? 255 : 0);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(Kit.dp(a, 14));
            ring.setStroke(on ? Kit.dp(a, 3) : 0, accent);
            tile.setForeground(ring);
            tile.setContentDescription("Part " + index + (on ? ", chosen" : ", not chosen"));
        }
        int total = tiles.size();
        if (pickAll != null) pickAll.setText(chosen.size() == total ? "Clear" : "Select all");
        // Save shows only when it can act, and names how many it keeps.
        saveButton.setVisibility(chosen.isEmpty() ? View.GONE : View.VISIBLE);
        TextView words = findText(saveButton);
        if (words != null) words.setText(total == 1 ? "Save to the gallery"
            : chosen.size() == total ? "Save all " + total : "Save " + chosen.size());
        saveButton.setContentDescription(words == null ? "Save" : words.getText());
    }

    private void toggleAll() {
        if (chosen.size() == tiles.size()) chosen.clear();
        else for (View tile : tiles) chosen.add((Integer) tile.getTag());
        refresh();
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Kit.dp(a, 12);
        page.addView(row, params);
        return row;
    }

    private static TextView findText(View button) {
        if (button instanceof TextView) return (TextView) button;
        if (button instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) button;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void load(String url, ImageView into, int size) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            Bitmap picture = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1 && bytes.size() < 8 * 1024 * 1024) bytes.write(buffer, 0, count);
                    byte[] data = bytes.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / size);
                    picture = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { }
            Bitmap shown = picture;
            if (shown != null) main.post(() -> {
                into.setAlpha(0f);
                into.setImageBitmap(shown);
                into.animate().alpha(1f).setDuration(180).start();
            });
        }, "instagram-thumb").start();
    }

    /** Save the chosen parts as background work, then hand the screen back. */
    private void save() {
        MediaClient client = client();
        if (client == null || chosen.isEmpty()) return;
        List<Integer> picked = new ArrayList<>(chosen);
        String code = post.optString("code"), url = post.optString("url", link);
        String who = post.optString("uploader");
        WorkService.start(a, picked.size() == 1 ? "Saving from Instagram" : "Saving " + picked.size() + " from Instagram",
            R.drawable.csi_download, true, work -> {
                int saved = 0;
                String last = null;
                for (int n = 0; n < picked.size(); n++) {
                    if (work.stopped()) break;
                    int index = picked.get(n), step = n;
                    String where = picked.size() == 1 ? "" : (n + 1) + " of " + picked.size() + " · ";
                    work.progress(n * 100 / picked.size(), where + "Fetching on the Pi");
                    Uri entry = null;
                    java.io.File temp = new java.io.File(a.getCacheDir(), "instagram-" + code + "-" + index + ".part");
                    try {
                        String type;
                        try (OutputStream out = new java.io.FileOutputStream(temp)) {
                            type = client.stream("/v1/instagram/item?url=" + MediaClient.enc(url) + "&index=" + index,
                                out, 600000, (done, total) -> work.progress(
                                    (int) ((step + (total > 0 ? done / (double) total : 0.5)) * 100 / picked.size()),
                                    where + (total > 0 ? Math.max(1, done * 100 / total) + "%" : "Saving")),
                                work::stopped);
                        }
                        String mime = type == null ? "image/jpeg" : type.split(";")[0].trim();
                        String ext = mime.startsWith("video/") ? ".mp4" : mime.endsWith("webp") ? ".webp" : ".jpg";
                        entry = Gallery.begin(a, "instagram-" + code + "-" + index + ext, mime);
                        try (InputStream in = new java.io.FileInputStream(temp);
                             OutputStream out = a.getContentResolver().openOutputStream(entry)) {
                            if (out == null) throw new Exception("The gallery could not be written");
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        Gallery.done(a, entry);
                        saved++;
                    } catch (Exception failed) {
                        Gallery.discard(a, entry);
                        if (work.stopped()) break;
                        last = failed.getMessage();
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        temp.delete();
                    }
                }
                if (work.stopped()) return saved == 0 ? "Stopped, nothing saved" : "Stopped after saving " + saved;
                if (saved == 0) throw new Exception(last == null ? "Nothing was saved" : "Nothing was saved. " + last);
                String from = who.isEmpty() ? "" : " from " + who;
                if (saved < picked.size()) return "Saved " + saved + " of " + picked.size() + from + " to the gallery";
                return saved == 1 ? "Saved to the gallery" + from : "Saved " + saved + from + " to the gallery";
            });
        Toast.makeText(a, "Saving. Follow it in the notification.", Toast.LENGTH_SHORT).show();
        a.finish();
    }
}
