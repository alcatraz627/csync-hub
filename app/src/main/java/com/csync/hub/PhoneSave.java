package com.csync.hub;

import android.content.Context;
import android.net.Uri;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Saving things onto this phone: one file or many, from any page, the same way every time.
 *
 * Photos land in the gallery (Pictures/csync), videos in Movies/csync, everything else in
 * Downloads/csync, or in a named folder inside it for a folder saved whole. The save runs as
 * background work with "3 of 12" progress and Stop, and a file only appears once it is whole.
 */
final class PhoneSave {
    private PhoneSave() {}

    /** Where one file's bytes come from when the save reaches it. */
    interface Opener { InputStream open() throws Exception; }

    /** One file to save: its name, its type, and how to read it. */
    static final class Entry {
        final String name, mime; final Opener opener;
        Entry(String name, String mime, Opener opener) { this.name = name; this.mime = mime; this.opener = opener; }
    }

    /** A file already on this phone (a content or file URI). */
    static Entry of(Context c, String name, String mime, Uri uri) {
        return new Entry(name, mime, () -> c.getContentResolver().openInputStream(uri));
    }

    /** Words, saved as a text or Markdown file. */
    static Entry text(String name, String words) {
        return new Entry(name, name.endsWith(".md") ? "text/markdown" : "text/plain",
            () -> new java.io.ByteArrayInputStream(words.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Save the entries as one piece of background work. {@code folder} names a folder inside
     * Downloads/csync for files that are not photos or videos, or null for Downloads/csync itself.
     */
    static void save(Context c, String what, String folder, List<Entry> entries) {
        if (entries.isEmpty()) return;
        String title = entries.size() == 1 ? "Saving " + entries.get(0).name : "Saving " + what;
        WorkService.start(c, title, R.drawable.csi_download, true, work -> {
            int saved = 0;
            String last = null;
            for (int n = 0; n < entries.size(); n++) {
                if (work.stopped()) break;
                Entry entry = entries.get(n);
                work.progress(n * 100 / entries.size(), entries.size() == 1 ? entry.name : (n + 1) + " of " + entries.size());
                Uri target = null;
                try (InputStream in = entry.opener.open()) {
                    if (in == null) throw new Exception("It could not be read");
                    target = Gallery.begin(c, entry.name, entry.mime, folder);
                    try (OutputStream out = c.getContentResolver().openOutputStream(target)) {
                        if (out == null) throw new Exception("The phone's storage is not available");
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (work.stopped()) throw new InterruptedException();
                            out.write(buffer, 0, count);
                        }
                    }
                    Gallery.done(c, target);
                    saved++;
                } catch (Exception failed) {
                    Gallery.discard(c, target);
                    if (work.stopped()) break;
                    last = failed.getMessage();
                }
            }
            if (work.stopped()) return saved == 0 ? "Stopped, nothing saved" : "Stopped after saving " + saved;
            if (saved == 0) throw new Exception(last == null ? "Nothing was saved" : "Nothing was saved. " + last);
            if (saved < entries.size()) return "Saved " + saved + " of " + entries.size();
            return entries.size() == 1 ? "Saved " + entries.get(0).name : "Saved all " + saved;
        });
    }
}
