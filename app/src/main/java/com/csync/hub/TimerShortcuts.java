package com.csync.hub;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Collections;
import java.util.List;

/**
 * Saved timer tiles where Android offers them outside the app: under the app icon when it is
 * pressed and held, and as icons pinned to the home screen. Tapping one starts that timer.
 */
final class TimerShortcuts {
    private TimerShortcuts() {}

    private static final String PREFIX = "timer-tile-";

    /** Offer every saved tile under the app icon, up to as many as the launcher shows. */
    static void tiles(Context c) {
        List<Timers.Tile> tiles = Timers.tiles(c);
        int room = Math.max(1, ShortcutManagerCompat.getMaxShortcutCountPerActivity(c));
        // Pushed last-to-first so the first tile ends up newest, which launchers list first.
        for (int i = Math.min(tiles.size(), room) - 1; i >= 0; i--) {
            try { ShortcutManagerCompat.pushDynamicShortcut(c, build(c, tiles.get(i), i)); }
            catch (RuntimeException launcherRefused) { }
        }
    }

    /** Ask the launcher to pin a tile to the home screen. False when this launcher cannot. */
    static boolean pin(Context c, Timers.Tile tile) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return false;
        try { return ShortcutManagerCompat.requestPinShortcut(c, build(c, tile, 0), null); }
        catch (RuntimeException launcherRefused) { return false; }
    }

    static void forgetTile(Context c, long id) {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(c, Collections.singletonList(PREFIX + id));
            ShortcutManagerCompat.disableShortcuts(c, Collections.singletonList(PREFIX + id), "This timer tile was deleted");
        } catch (RuntimeException launcherRefused) { }
    }

    private static ShortcutInfoCompat build(Context c, Timers.Tile tile, int rank) {
        Intent start = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .putExtra("destination", "more").putExtra("detail", "timers").putExtra("start_tile", tile.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String name = tile.label.equals("Quick") ? Timers.lengthShort(tile.minutes) : tile.label + ", " + Timers.lengthShort(tile.minutes);
        return new ShortcutInfoCompat.Builder(c, PREFIX + tile.id)
            .setShortLabel(name.length() > 24 ? Timers.lengthShort(tile.minutes) : name)
            .setLongLabel("Start " + name)
            .setIcon(IconCompat.createWithBitmap(icon(c, tile)))
            .setIntent(start)
            .setRank(rank)
            .build();
    }

    // A round badge in the tile's colour with its length written in it.
    private static android.graphics.Bitmap icon(Context c, Timers.Tile tile) {
        int size = Kit.dp(c, 48);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
        android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Timers.colour(c, tile.colour));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        android.graphics.Paint words = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        words.setColor(0xFFFFFFFF);
        words.setTextAlign(android.graphics.Paint.Align.CENTER);
        words.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        String number = tile.minutes >= 60 && tile.minutes % 60 == 0 ? (tile.minutes / 60) + "h" : String.valueOf(tile.minutes);
        words.setTextSize(size * (number.length() > 2 ? 0.30f : 0.38f));
        canvas.drawText(number, size / 2f, size / 2f - (words.descent() + words.ascent()) / 2, words);
        return b;
    }
}
