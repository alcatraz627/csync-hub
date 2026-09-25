package com.csync.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Gives VLC seekable access to a Pi item without exposing the mesh token. */
public final class VlcStreamProvider extends ContentProvider {
    private HandlerThread worker;

    @Override public boolean onCreate() {
        worker = new HandlerThread("csync-vlc-stream");
        worker.start();
        return true;
    }

    @Override public String getType(Uri uri) {
        return uri.getQueryParameter("mime");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null ?
            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = uri.getQueryParameter("name");
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = length(uri);
        }
        result.addRow(row);
        return result;
    }

    private long length(Uri uri) {
        try { return Long.parseLong(uri.getQueryParameter("size")); }
        catch (Exception error) { return -1; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 ||
            !"stream".equals(uri.getPathSegments().get(0)) || length(uri) < 0)
            throw new FileNotFoundException("Invalid media request");
        String itemId = uri.getPathSegments().get(1);
        long total = length(uri);
        MediaClient client = new MediaClient(Prefs.assistIp(getContext()), Prefs.token(getContext()));
        StorageManager storage = (StorageManager) getContext().getSystemService(StorageManager.class);
        try {
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                new ProxyFileDescriptorCallback() {
                    @Override public long onGetSize() { return total; }
                    @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                        if (offset >= total) return 0;
                        int wanted = (int) Math.min(size, total - offset);
                        HttpURLConnection connection = null;
                        try {
                            connection = (HttpURLConnection) new URL(client.streamUrl(itemId)).openConnection();
                            connection.setConnectTimeout(5000);
                            connection.setReadTimeout(15000);
                            connection.setRequestProperty("X-Csync-Token", client.token());
                            connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + wanted - 1));
                            if (connection.getResponseCode() != 206) throw new Exception("Pi range unavailable");
                            int read = 0;
                            try (InputStream input = connection.getInputStream()) {
                                while (read < wanted) {
                                    int count = input.read(data, read, wanted - read);
                                    if (count < 0) throw new Exception("Pi stream ended early");
                                    read += count;
                                }
                            }
                            return read;
                        } catch (Exception error) {
                            throw new ErrnoException("Pi media read", OsConstants.EIO);
                        } finally {
                            if (connection != null) connection.disconnect();
                        }
                    }
                    @Override public void onRelease() {}
                }, new Handler(worker.getLooper()));
        } catch (Exception error) {
            throw new FileNotFoundException("Pi media proxy unavailable: " + error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
