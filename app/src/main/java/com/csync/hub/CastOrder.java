package com.csync.hub;

import android.content.Context;
import android.net.Uri;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * The order a video is sent to the Pi in, so the Pi can start playing it before it has all arrived.
 *
 * A phone camera writes an MP4's index (its moov box) after the picture and sound, and a player
 * cannot start one until it has the index. When that is so, the index and everything after it
 * are sent first, and the Pi puts them back at the end of the file.
 */
final class CastOrder {
    private CastOrder() {}

    /** How many bytes at the end of the file to send first: the index onwards, or 0 when no reordering helps. */
    static long tailBytes(Context c, Uri uri, String name, long size) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (!(lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov"))) return 0;
        try (FileInputStream file = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                c.getContentResolver().openFileDescriptor(uri, "r"))) {
            FileChannel channel = file.getChannel();
            ByteBuffer header = ByteBuffer.allocate(16);
            long at = 0;
            boolean mediaSeen = false;
            while (at + 8 <= size) {
                header.clear();
                header.limit(16);
                if (channel.read(header, at) < 8) return 0;
                long box = header.getInt(0) & 0xFFFFFFFFL;
                String kind = new String(header.array(), 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (box == 1) box = header.getLong(8);
                else if (box == 0) box = size - at;
                if (box < 8) return 0;
                if (kind.equals("mdat")) mediaSeen = true;
                // The index after the media is the case that needs it sent first.
                if (kind.equals("moov")) return mediaSeen ? size - at : 0;
                at += box;
            }
        } catch (Exception unreadable) { }
        return 0;
    }

    /** The file as one stream in send order: its last tailBytes, then everything before them. */
    static InputStream stream(Context c, Uri uri, long size, long tailBytes) throws Exception {
        if (tailBytes <= 0) return c.getContentResolver().openInputStream(uri);
        FileInputStream tail = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            c.getContentResolver().openFileDescriptor(uri, "r"));
        tail.getChannel().position(size - tailBytes);
        InputStream head = new java.io.FilterInputStream(c.getContentResolver().openInputStream(uri)) {
            long left = size - tailBytes;
            @Override public int read() throws java.io.IOException {
                if (left <= 0) return -1;
                int b = super.read();
                if (b >= 0) left--;
                return b;
            }
            @Override public int read(byte[] buffer, int offset, int count) throws java.io.IOException {
                if (left <= 0) return -1;
                int n = super.read(buffer, offset, (int) Math.min(count, left));
                if (n > 0) left -= n;
                return n;
            }
        };
        return new SequenceInputStream(tail, head);
    }
}
