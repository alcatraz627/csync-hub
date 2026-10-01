package com.csync.hub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.util.HashSet;
import java.util.Set;

/**
 * Saving files where the phone's own apps find them: photos and videos in the gallery
 * (Pictures/csync and Movies/csync), anything else in Downloads/csync.
 *
 * A file is written hidden and shown only once it is whole, so a stopped or failed save
 * never leaves half a picture in the gallery.
 */
final class Gallery {
    private Gallery() {}

    /** Make a hidden entry for a file about to be written. Needs Android 10 or newer. */
    static Uri begin(Context c, String name, String mime) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new Exception("Saving needs Android 10 or newer");
        boolean picture = mime != null && mime.startsWith("image/");
        boolean video = mime != null && mime.startsWith("video/");
        Uri collection = picture ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            : video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String folder = picture ? Environment.DIRECTORY_PICTURES : video ? Environment.DIRECTORY_MOVIES
            : Environment.DIRECTORY_DOWNLOADS;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/csync");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri entry = c.getContentResolver().insert(collection, values);
        if (entry == null) throw new Exception("The phone's storage is not available");
        return entry;
    }

    /** Show a finished file in the gallery. */
    static void done(Context c, Uri entry) {
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        c.getContentResolver().update(entry, ready, null, null);
    }

    /** Drop an entry that was never finished. */
    static void discard(Context c, Uri entry) {
        if (entry == null) return;
        try { c.getContentResolver().delete(entry, null, null); } catch (Exception ignored) { }
    }

    /** The names already saved by csync that start with {@code prefix}, to tell what is saved already. */
    static Set<String> saved(Context c, String prefix) {
        Set<String> names = new HashSet<>();
        if (android.os.Build.VERSION.SDK_INT < 29) return names;
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor cursor = c.getContentResolver().query(files,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " + MediaStore.MediaColumns.IS_PENDING + "=0",
                new String[]{prefix + "%"}, null)) {
            while (cursor != null && cursor.moveToNext()) names.add(cursor.getString(0));
        } catch (Exception ignored) { }
        return names;
    }
}
