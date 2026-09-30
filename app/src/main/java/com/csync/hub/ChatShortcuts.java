package com.csync.hub;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Collections;

/**
 * Puts the conversations you used most recently where Android can offer them: under the app
 * icon when it is pressed and held, and in other apps' share menus as places to send to.
 */
final class ChatShortcuts {
    private ChatShortcuts() {}

    static final String CATEGORY = "com.csync.hub.category.CONVERSATION";
    private static final String PREFIX = "conversation-";

    /** Note that a conversation was just used. Android keeps the newest few and drops the oldest. */
    static void used(Context c, String id, String title) {
        if (id == null || title == null || title.trim().isEmpty()) return;
        Intent open = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .putExtra("destination", "chat").putExtra("open_conversation", id);
        try {
            ShortcutManagerCompat.pushDynamicShortcut(c, new ShortcutInfoCompat.Builder(c, PREFIX + id)
                .setShortLabel(title.length() > 24 ? cut(title, 24) : title)
                .setLongLabel(title)
                .setIcon(IconCompat.createWithResource(c, R.drawable.sc_chat))
                .setIntent(open)
                .setLongLived(true)
                .setCategories(Collections.singleton(CATEGORY))
                .build());
        } catch (RuntimeException launcherRefused) { }
    }

    /** A conversation was deleted, so it stops being offered. */
    static void gone(Context c, String id) {
        try { ShortcutManagerCompat.removeLongLivedShortcuts(c, Collections.singletonList(PREFIX + id)); }
        catch (RuntimeException launcherRefused) { }
    }

    /** The conversation a share was aimed at, when the person picked one from the share menu. */
    static String picked(Intent share) {
        String shortcut = share.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        return shortcut != null && shortcut.startsWith(PREFIX) ? shortcut.substring(PREFIX.length()) : null;
    }

    /** Cut a title at a whole word so it fits under an icon, with no ellipsis. */
    private static String cut(String title, int most) {
        int space = title.lastIndexOf(' ', most);
        return title.substring(0, space > most / 2 ? space : most);
    }
}
