package com.csync.hub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A card the assistant composes inside a reply: a title, a few facts and buttons, drawn with the
 * app's own parts so it looks like the rest of the app. The assistant writes it as a fenced block
 * in the language {@code csync-card} holding one JSON object.
 *
 * Buttons can only do safe things: open an https link, send words as the owner's next message, or
 * go to a place in the app. Nothing on a card runs a command or changes anything by itself.
 */
final class ChatCard {

    interface Ask { void send(String words); }

    private ChatCard() {}

    /** The card for a fenced block, or null when the block is not a well-formed card. */
    static View from(Activity a, String fenced, Ask ask) {
        String body = fenced.startsWith("csync-card") ? fenced.substring("csync-card".length()) : null;
        if (body == null) return null;
        JSONObject card;
        try { card = new JSONObject(body.trim()); } catch (Exception malformed) { return null; }
        String title = card.optString("title");
        if (title.isEmpty()) return null;

        LinearLayout host = new LinearLayout(a);
        host.setOrientation(LinearLayout.VERTICAL);
        LinearLayout group = Kit.group(host);
        Kit.bindRow(Kit.addRow(group), icon(card.optString("icon")), title, emptyToNull(card.optString("sub")), null, false)
            .setClickable(false);

        JSONArray rows = card.optJSONArray("rows");
        if (rows != null && rows.length() > 0) {
            List<String[]> pairs = new ArrayList<>();
            for (int i = 0; i < rows.length() && i < 12; i++) {
                JSONArray pair = rows.optJSONArray(i);
                if (pair != null && pair.length() >= 2) pairs.add(new String[]{pair.optString(0), pair.optString(1)});
            }
            if (!pairs.isEmpty()) Kit.facts(host, pairs);
        }

        JSONArray buttons = card.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length() && i < 4; i++) {
            JSONObject b = buttons.optJSONObject(i);
            if (b == null || b.optString("label").isEmpty()) continue;
            Runnable run = action(a, b, ask);
            if (run == null) continue;
            int glyph = b.has("link") ? R.drawable.csi_link : b.has("ask") ? Kit.Icon.CHAT : R.drawable.csi_forward;
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.topMargin = Kit.dp(a, 8);
            host.addView(Kit.button(a, glyph, b.optString("label"), R.color.text, run), gap);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Kit.dp(a, 6);
        lp.bottomMargin = Kit.dp(a, 6);
        host.setLayoutParams(lp);
        return host;
    }

    private static Runnable action(Activity a, JSONObject b, Ask ask) {
        if (b.has("link")) {
            Uri link = Uri.parse(b.optString("link"));
            if (!"https".equals(link.getScheme()) || link.getHost() == null) return null;
            return () -> {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, link)); }
                catch (Exception none) { android.widget.Toast.makeText(a, "No app can open that link", android.widget.Toast.LENGTH_SHORT).show(); }
            };
        }
        if (b.has("ask")) {
            String words = b.optString("ask").trim();
            return words.isEmpty() ? null : () -> ask.send(words);
        }
        if (b.has("open")) {
            String place = b.optString("open");
            if (Places.of(place) == null) return null;
            return () -> {
                try { RailActions.run(a, new JSONObject().put("id", "help".equals(place) ? "about" : place)); }
                catch (Exception ignored) { }
            };
        }
        return null;
    }

    private static int icon(String name) {
        switch (name) {
            case "media": return Kit.Icon.MEDIA;
            case "camera": return Kit.Icon.CAMERA;
            case "screen": return Kit.Icon.DISPLAY;
            case "notes": return Kit.Icon.NOTES;
            case "file": return Kit.Icon.FILE;
            case "photo": return Kit.Icon.PHOTO;
            case "video": return Kit.Icon.VIDEO;
            case "chat": return Kit.Icon.CHAT;
            case "tools": return Kit.Icon.TOOLS;
            case "alert": return R.drawable.csi_alert;
            case "info": return R.drawable.csi_info;
            default: return Kit.Icon.DEVICE;
        }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
}
