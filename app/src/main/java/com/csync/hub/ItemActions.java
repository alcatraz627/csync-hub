package com.csync.hub;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * What one item can do, the same wherever the item appears.
 *
 * A received file, a camera capture, a note, a pin, a file on a drive and something shared
 * from another app all get their choices from here, so the same thing is offered under the
 * same words in the same order everywhere. The table is in docs/android-app-model.md, 7a.
 *
 * A place hands over an {@link Item} and supplies only what is special to it, through
 * {@code own}. Anything it does not supply is done the common way: the item is passed to
 * the share page with the destination already chosen, which does the sending and says so
 * when it fails.
 */
final class ItemActions {
    private ItemActions() {}

    enum Kind { VIDEO, AUDIO, IMAGE, DOCUMENT, TEXT, LINK, VIDEO_LINK, FILES }

    enum Act { PLAY_PI, PLAY_PHONE, SHOW_PI, OPEN, VLC, COVER, COPY, DEVICE, CHAT, NOTE, PIN, SAVE, SHARE_OUT }

    /** Hands over a file that can be read, fetching it first when it lives on the Pi. */
    interface Source { void open(Got got); }
    interface Got { void file(Uri uri, String mime); }

    static final class Item {
        final Kind kind;
        final String title;
        // The line under the title: where the item is, or what it is.
        String sub;
        // The words or the link, for an item that is not a file.
        String text;
        // The web address alone, when the words carry more than the link.
        String link;
        // The file name the words travel under when they are sent to a device.
        String textName;
        Source file;
        // The file's type when the place knows it, so a PDF is known as one even under an odd name.
        String mime;
        // From another app: it is already on this phone, so keeping or opening it here is not offered.
        boolean incoming;
        boolean inNote, isPin;
        // What this place does itself instead of the common way.
        final EnumMap<Act, Runnable> own = new EnumMap<>(Act.class);
        // A line under one choice, such as how loud playback starts.
        final EnumMap<Act, String> notes = new EnumMap<>(Act.class);
        // Choices only this place has, such as File details or Delete, shown last.
        final List<Kit.Action> more = new ArrayList<>();

        Item(Kind kind, String title) { this.kind = kind; this.title = title; }
    }

    /** The kind a file is, read from its type. */
    static Kind kindOf(String mime) {
        String type = mime == null ? "" : mime;
        return type.startsWith("video/") ? Kind.VIDEO : type.startsWith("audio/") ? Kind.AUDIO
            : type.startsWith("image/") ? Kind.IMAGE : Kind.DOCUMENT;
    }

    /** The one kind of document the Pi screen can draw, told by the file's type or its name. */
    static boolean isPdf(String name, String mime) {
        return "application/pdf".equalsIgnoreCase(mime) ||
            (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"));
    }

    static int icon(Kind kind) {
        switch (kind) {
            case VIDEO: case AUDIO: case VIDEO_LINK: return Kit.Icon.VIDEO;
            case IMAGE: return Kit.Icon.PHOTO;
            case LINK: return R.drawable.csi_link;
            case TEXT: return R.drawable.csi_text;
            case FILES: return Kit.Icon.FILES;
            default: return Kit.Icon.FILE;
        }
    }

    /** A file already on this phone. */
    static Source local(Activity a, File file, String mime) {
        return got -> got.file(FileProvider.getUriForFile(a, a.getPackageName() + ".share", file), mime);
    }

    static void sheet(Activity a, Item item) {
        Kit.sheet(a, item.title, item.sub, sections(a, item));
    }

    /** The choices for an item, as the set that acts now and the set that keeps or sends. */
    static List<Kit.Section> sections(Activity a, Item item) {
        Kind kind = item.kind;
        boolean plays = kind == Kind.VIDEO || kind == Kind.AUDIO || kind == Kind.VIDEO_LINK;
        boolean shows = kind == Kind.IMAGE || kind == Kind.TEXT || (kind == Kind.DOCUMENT && isPdf(item.title, item.mime));
        boolean words = item.file == null;
        boolean here = !item.incoming;

        List<Kit.Action> first = new ArrayList<>();
        if (plays) {
            add(first, a, item, Act.PLAY_PI, Kit.Icon.DISPLAY, "Play on Pi screen", false);
            if (here) add(first, a, item, Act.PLAY_PHONE, Kit.Icon.DEVICE, "Play on this phone", false);
        }
        if (shows) add(first, a, item, Act.SHOW_PI, Kit.Icon.DISPLAY, "Show on Pi screen", false);
        if (here && (kind == Kind.IMAGE || kind == Kind.DOCUMENT))
            add(first, a, item, Act.OPEN, R.drawable.csi_expand, "Open", false);
        if (here && kind == Kind.LINK) add(first, a, item, Act.OPEN, R.drawable.csi_link, "Open the link", false);
        if (here && kind == Kind.VIDEO) add(first, a, item, Act.VLC, R.drawable.csi_play, "Open in VLC", false);
        if (kind == Kind.IMAGE) add(first, a, item, Act.COVER, R.drawable.csi_image, "Set as the Pi cover", true);
        if (kind == Kind.TEXT) add(first, a, item, Act.COPY, R.drawable.csi_copy, "Copy the text", false);

        List<Kit.Action> rest = new ArrayList<>();
        add(rest, a, item, Act.DEVICE, Kit.Icon.DEVICE, "Send to a device", true);
        if (kind != Kind.FILES) {
            add(rest, a, item, Act.CHAT, Kit.Icon.CHAT, "Send to a conversation", false);
            if (!item.inNote) add(rest, a, item, Act.NOTE, Kit.Icon.NOTES, "Add to a note", false);
            if (!item.isPin) add(rest, a, item, Act.PIN, R.drawable.ic_pin, "Save as a pin", false);
        }
        // Anything but a bare link can be kept on this phone, including what another app shared in
        // and words such as a note (owner ruling D3).
        boolean savable = kind != Kind.LINK && kind != Kind.VIDEO_LINK && (!words || item.text != null);
        if (savable && (Build.VERSION.SDK_INT >= 29 || item.own.containsKey(Act.SAVE)))
            add(rest, a, item, Act.SAVE, R.drawable.csi_download, "Save on this phone", false);
        if (here) add(rest, a, item, Act.SHARE_OUT, Kit.Icon.SHARE, "Share with another app", true);
        rest.addAll(item.more);

        List<Kit.Section> sections = new ArrayList<>();
        sections.add(new Kit.Section(plays ? "Play" : shows ? "Show" : null, first));
        sections.add(new Kit.Section(first.isEmpty() ? null : item.incoming ? "Keep or send" : "Also", rest));
        return sections;
    }

    private static void add(List<Kit.Action> list, Activity a, Item item, Act act, int icon, String label, boolean opens) {
        Runnable own = item.own.get(act);
        Kit.Action action = new Kit.Action(icon, label, item.notes.get(act), own != null ? own : () -> common(a, item, act), opens);
        action.key = act;
        list.add(action);
    }

    /**
     * Look at an item on the whole screen when it can be shown here, otherwise list its choices.
     * This is what tapping an item does wherever it is listed.
     */
    static void open(Activity a, Item item) {
        if (Viewer.shows(item)) Viewer.show(a, item);
        else sheet(a, item);
    }

    /** Hand the item to whichever app on this phone opens its kind of file. */
    static void openElsewhere(Activity a, Item item) {
        if (item.file == null) return;
        item.file.open((uri, mime) -> start(a, view(a, uri, mime == null ? "application/octet-stream" : mime, item.title),
            "No app on this phone can open this file"));
    }

    // ---- the common way ----

    private static String place(Act act) {
        switch (act) {
            case PLAY_PI: case SHOW_PI: return "pi";
            case COVER: return "cover";
            case DEVICE: return "device";
            case CHAT: return "chat";
            case NOTE: return "note";
            default: return "pin";
        }
    }

    private static void common(Activity a, Item item, Act act) {
        if (act == Act.COPY) {
            ((android.content.ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText(item.title, item.text));
            toast(a, "Copied");
            return;
        }
        if (item.file == null) {
            if (act == Act.SAVE) {
                String name = item.textName != null ? item.textName : item.title + ".txt";
                PhoneSave.save(a, name, null, java.util.Collections.singletonList(PhoneSave.text(name, item.text)));
                return;
            }
            if (act == Act.PLAY_PHONE || act == Act.OPEN) {
                String address = item.link == null ? item.text.trim() : item.link;
                start(a, new Intent(Intent.ACTION_VIEW, Uri.parse(address)), "No app on this phone can open this link");
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text);
            if (act == Act.SHARE_OUT) { start(a, Intent.createChooser(send, "Share with another app"), null); return; }
            send.setClass(a, ShareActivity.class).putExtra(ShareActivity.PLACE, place(act)).putExtra(ShareActivity.RETURN, true);
            if (item.textName != null) send.putExtra(ShareActivity.TEXT_NAME, item.textName);
            start(a, send, null);
            return;
        }
        item.file.open((uri, mime) -> {
            String type = mime == null ? "application/octet-stream" : mime;
            switch (act) {
                case OPEN:
                    if (item.kind == Kind.IMAGE || Viewer.isText(type)) { Viewer.show(a, item); return; }
                    start(a, view(a, uri, type, item.title), "No app on this phone can open this file");
                    return;
                case PLAY_PHONE:
                    start(a, view(a, uri, type, item.title), "No app on this phone can play this file");
                    return;
                case VLC:
                    start(a, view(a, uri, type, item.title).setPackage("org.videolan.vlc"), "VLC is not on this phone");
                    return;
                case SAVE:
                    PhoneSave.save(a, item.title, null,
                        java.util.Collections.singletonList(PhoneSave.of(a, item.title, type, uri)));
                    return;
                default:
                    Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri(item.title, uri));
                    if (act == Act.SHARE_OUT) {
                        Intent chooser = Intent.createChooser(send, "Share with another app");
                        chooser.setClipData(send.getClipData());
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        start(a, chooser, null);
                    } else start(a, send.setClass(a, ShareActivity.class).putExtra(ShareActivity.PLACE, place(act))
                        .putExtra(ShareActivity.RETURN, true), null);
            }
        });
    }

    private static Intent view(Activity a, Uri uri, String type, String title) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(title, uri));
        return view;
    }

    private static void start(Activity a, Intent intent, String whenNothingCan) {
        try { a.startActivity(intent); }
        catch (Exception nothing) { toast(a, whenNothingCan == null ? "It could not be opened" : whenNothingCan); }
    }

    private static void toast(Activity a, String words) {
        Toast.makeText(a, words, Toast.LENGTH_SHORT).show();
    }
}
