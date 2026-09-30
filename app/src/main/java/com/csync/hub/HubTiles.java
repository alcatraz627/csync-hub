package com.csync.hub;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

/**
 * The Quick Settings tiles: one tap from the notification shade to the Pi
 * camera, to send the clipboard or the newest photo to the last device, and
 * to stop what the Pi screen is playing.
 *
 * Three of them open the app at the right place, because Android only lets an
 * app read the clipboard or the photo library while it is on screen. The Pi
 * screen tile acts on its own.
 */
public final class HubTiles {
    private HubTiles() {}

    /** Close the shade and open a place in the app. */
    private static void open(TileService tile, String destination, String shareAction) {
        Intent i = new Intent(tile, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", destination);
        if (shareAction != null) i.putExtra("share_action", shareAction);
        if (Build.VERSION.SDK_INT >= 34) {
            tile.startActivityAndCollapse(PendingIntent.getActivity(tile, destination.hashCode() + (shareAction == null ? 0 : shareAction.hashCode()),
                i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            tile.startActivityAndCollapse(i);
        }
    }

    /** A tile that only opens something: never lit, with a second line saying what the tap does. */
    private static void rest(TileService service, String does) {
        Tile tile = service.getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(does);
        tile.updateTile();
    }

    public static class Camera extends TileService {
        @Override public void onStartListening() { rest(this, "Live"); }
        @Override public void onClick() { open(this, "camera", null); }
    }

    public static class Clipboard extends TileService {
        @Override public void onStartListening() { rest(this, "Send"); }
        @Override public void onClick() { open(this, "share", "clipboard"); }
    }

    public static class Photo extends TileService {
        @Override public void onStartListening() { rest(this, "Send"); }
        @Override public void onClick() { open(this, "share", "photo"); }
    }

    /** Lit while the Pi screen is playing. A tap stops it; when nothing plays, a tap opens Media. */
    public static class Screen extends TileService {
        private volatile boolean playing;

        @Override public void onStartListening() { read(null); }

        @Override public void onClick() {
            if (playing) { read("stop"); return; }
            Intent i = new Intent(this, MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 9301, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        }

        /** Ask the Pi what its screen is doing, after an optional command, and show it on the tile. */
        private void read(String action) {
            String host = Prefs.assistIp(this), token = Prefs.token(this);
            if (host.isEmpty() || token.isEmpty()) { show(false, "Not set up"); return; }
            new Thread(() -> {
                try {
                    MediaClient client = new MediaClient(host, token);
                    if (action != null) client.post("/v1/player/pi/immediate", new JSONObject().put("action", action));
                    JSONObject state = client.get("/v1/player/pi");
                    String status = state.optString("state");
                    boolean active = status.equals("playing") || status.equals("paused")
                        || status.equals("loading") || status.equals("buffering");
                    // One short word: the system cuts a tile's second line after about a dozen letters.
                    show(active, status.equals("playing") ? "Playing" : status.equals("paused") ? "Paused"
                        : active ? "Loading" : "Cover");
                } catch (Exception error) {
                    show(false, "Offline");
                }
            }, "tile-screen").start();
        }

        private void show(boolean active, String words) {
            playing = active;
            Tile tile = getQsTile();
            if (tile == null) return;
            tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(words);
            tile.updateTile();
        }
    }
}
