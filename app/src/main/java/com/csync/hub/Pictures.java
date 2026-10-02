package com.csync.hub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A grid of pictures, used wherever the app shows several images from the Pi: Captures, the
 * covers kept for the Pi screen, and a note's pictures. Each tile shows its picture as soon as it
 * arrives; a video shows its mark, and a tile can carry a ring for "this one is in use".
 */
final class Pictures {

    /** How a tile gets its picture's bytes; called off the main thread. */
    interface Fetch { byte[] get() throws Exception; }

    static final class Tile {
        final String key;
        final Fetch fetch;
        boolean video, marked;
        String label;
        Runnable open;
        Tile(String key, Fetch fetch) { this.key = key; this.fetch = fetch; }
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final ExecutorService LOADERS = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Pictures() {}

    /** Add a grid of square tiles to the page, as wide as the page's column. */
    static GridLayout grid(ViewGroup parent, List<Tile> tiles, int columns) {
        Context c = parent.getContext();
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(columns);
        parent.addView(grid, new ViewGroup.LayoutParams(-1, -2));
        int gap = Kit.dp(c, 3);
        int width = Math.min(c.getResources().getDisplayMetrics().widthPixels
            - 2 * c.getResources().getDimensionPixelSize(R.dimen.kit_gutter), Kit.dp(c, 640));
        int side = width / columns - gap * 2;
        for (Tile t : tiles) grid.addView(tile(c, t, side, gap));
        return grid;
    }

    private static View tile(Context c, Tile t, int side, int gap) {
        FrameLayout tile = new FrameLayout(c);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = side;
        params.height = side;
        params.setMargins(gap, gap, gap, gap);
        tile.setLayoutParams(params);
        tile.setBackgroundResource(R.drawable.card_bg);
        tile.setClipToOutline(true);
        ImageView picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        if (t.fetch == null || t.video) {
            // No picture to show: the kind's mark, centred and quiet.
            ImageView mark = new ImageView(c);
            mark.setImageResource(t.video ? Kit.Icon.VIDEO : Kit.Icon.PHOTO);
            mark.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.dim)));
            tile.addView(mark, new FrameLayout.LayoutParams(Kit.dp(c, 28), Kit.dp(c, 28), Gravity.CENTER));
        }
        if (t.fetch != null && !t.video) load(t, picture, side);
        if (t.label != null) {
            TextView label = new TextView(c);
            label.setTextAppearance(R.style.Kit_Text_Meta);
            label.setTextColor(0xFFFFFFFF);
            label.setShadowLayer(4, 0, 1, 0xCC000000);
            label.setText(t.label);
            label.setSingleLine(true);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            at.setMargins(Kit.dp(c, 6), 0, Kit.dp(c, 6), Kit.dp(c, 4));
            tile.addView(label, at);
        }
        if (t.marked) {
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setCornerRadius(Kit.dp(c, 12));
            ring.setStroke(Kit.dp(c, 3), Kit.accentText(c));
            View frame = new View(c);
            frame.setBackground(ring);
            tile.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        }
        tile.setContentDescription((t.video ? "Video" : "Picture") + (t.label == null ? "" : ", " + t.label)
            + (t.marked ? ", in use" : ""));
        if (t.open != null) tile.setOnClickListener(v -> { Kit.tick(v); t.open.run(); });
        return tile;
    }

    private static void load(Tile t, ImageView into, int side) {
        into.setTag(t.key);
        Bitmap hit = CACHE.get(t.key);
        if (hit != null) { into.setImageBitmap(hit); return; }
        LOADERS.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = t.fetch.get();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                o.inSampleSize = 1;
                while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= side) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            } catch (Exception ignored) { }
            if (bitmap == null) return;
            Bitmap done = bitmap;
            CACHE.put(t.key, done);
            UI.post(() -> { if (t.key.equals(into.getTag())) into.setImageBitmap(done); });
        });
    }

    /** Forget a picture, as when it was deleted or replaced on the Pi. */
    static void forget(String key) { CACHE.remove(key); }
}
