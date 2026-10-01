package com.csync.hub;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

/** Direct client for the Pi media service. Call it on a worker thread. */
final class MediaClient {
    static final class MediaException extends Exception {
        final String code;
        MediaException(String code, String message) { super(message); this.code = code; }
    }
    private final String host;
    private final String token;

    MediaClient(String host, String token) {
        this.host = host;
        this.token = token;
    }

    String streamUrl(String itemId) {
        return "http://" + host + ":8792/v1/items/" + itemId + "/stream";
    }

    String url(String path) { return "http://" + host + ":8792" + path; }

    String token() { return token; }

    static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    /**
     * Words as the Pi screen will take them. It accepts 4000 characters, far more than fit on
     * it, so a longer text is cut after its last whole word inside that limit.
     */
    static String screenText(String text) {
        if (text.length() <= 4000) return text;
        int cut = 4000;
        while (cut > 0 && !Character.isWhitespace(text.charAt(cut))) cut--;
        return text.substring(0, cut == 0 ? 4000 : cut).trim();
    }

    JSONObject get(String path) throws Exception { return request("GET", path, null); }
    JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body); }
    JSONObject put(String path, JSONObject body) throws Exception { return request("PUT", path, body); }
    JSONObject delete(String path, JSONObject body) throws Exception { return request("DELETE", path, body); }

    /** Save an image as the Pi cover, which also shows it. */
    JSONObject uploadWallpaper(byte[] jpeg) throws Exception { return sendImage("/v1/display/wallpaper", jpeg); }

    /** Show an image on the Pi screen now, leaving the saved cover as it is. */
    JSONObject showImage(byte[] jpeg, String name) throws Exception {
        return sendImage("/v1/display/show" + (name == null || name.isEmpty() ? "" : "?name=" + enc(name)), jpeg);
    }

    /** Show a PDF on the Pi screen a page at a time; the Pi draws the pages, which takes a moment. */
    JSONObject showDocument(byte[] pdf, String name) throws Exception {
        return sendBytes("/v1/display/show" + (name == null || name.isEmpty() ? "" : "?name=" + enc(name)),
            "application/pdf", pdf, 90000);
    }

    private JSONObject sendImage(String path, byte[] jpeg) throws Exception {
        return sendBytes(path, "image/jpeg", jpeg, 15000);
    }

    private JSONObject sendBytes(String path, String contentType, byte[] body, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) != -1) {
                if (output.size() + count > 1048576) throw new Exception("Media response is too large");
                output.write(chunk, 0, count);
            }
            JSONObject result = new JSONObject(output.toString("UTF-8"));
            if (status >= 400) throw new MediaException(result.optString("code", "MEDIA_ERROR"),
                result.optString("message", "It could not be sent to the Pi"));
            return result;
        } finally { connection.disconnect(); }
    }

    JSONObject uploadMedia(String driveId, String name, long size, InputStream input,
                           java.util.function.LongConsumer progress) throws Exception {
        return uploadMedia(driveId, name, size, input, 0, progress);
    }

    /** As above, where the stream starts with the file's last tailBytes (see CastOrder). */
    JSONObject uploadMedia(String driveId, String name, long size, InputStream input, long tailBytes,
                           java.util.function.LongConsumer progress) throws Exception {
        if (size < 1 || size > 16L * 1024 * 1024 * 1024)
            throw new Exception("Choose a media file under 16 GB");
        // play=1 lets the Pi start the file once enough has arrived, instead of after the whole copy.
        HttpURLConnection connection = (HttpURLConnection) new URL(url("/v1/cast/file?driveId=" +
            enc(driveId) + "&name=" + enc(name) + "&play=1")).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        if (tailBytes > 0) connection.setRequestProperty("X-Csync-Tail-Bytes", String.valueOf(tailBytes));
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(size);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[65536];
                long sent = 0;
                while (sent < size) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, size - sent));
                    if (count < 0) throw new Exception("The selected file ended during upload");
                    out.write(buffer, 0, count);
                    sent += count;
                    progress.accept(sent);
                }
            }
            int status = connection.getResponseCode();
            InputStream response = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = response.read(chunk)) != -1) {
                if (output.size() + count > 1048576) throw new Exception("Media response is too large");
                output.write(chunk, 0, count);
            }
            JSONObject result = new JSONObject(output.toString("UTF-8"));
            if (status >= 400) throw new MediaException(result.optString("code", "MEDIA_ERROR"),
                result.optString("message", "Media upload failed"));
            return result;
        } finally { connection.disconnect(); }
    }

    byte[] getBytes(String path, int maxBytes) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("X-Csync-Token", token);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("Note image unavailable");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) throw new Exception("Note image is too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    /** Keep any file of up to 20 MB beside a note. */
    JSONObject uploadNoteFile(String noteId, String name, String mime, byte[] data) throws Exception {
        return uploadFile("/v1/notes/" + enc(noteId) + "/files?name=" + enc(name), mime, data);
    }

    /** Keep one file of up to 20 MB as a pin of its own. */
    JSONObject uploadPinFile(String name, String mime, byte[] data) throws Exception {
        return uploadFile("/v1/pins/file?name=" + enc(name), mime, data);
    }

    private JSONObject uploadFile(String route, String mime, byte[] data) throws Exception {
        if (data.length < 1 || data.length > 20 * 1024 * 1024) throw new Exception("Choose a file under 20 MB");
        HttpURLConnection connection = (HttpURLConnection) new URL(url(route)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", mime == null ? "application/octet-stream" : mime);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(data); }
            int status = connection.getResponseCode();
            InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject result = new JSONObject(output.toString("UTF-8"));
            if (status >= 400) throw new MediaException(result.optString("code", "MEDIA_ERROR"),
                result.optString("message", "The file could not be added"));
            return result;
        } finally { connection.disconnect(); }
    }

    /** Fetch something from the Pi into a file on this phone. */
    void download(String path, java.io.File to) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("X-Csync-Token", token);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new Exception(status == 404 ? "It is no longer on the Pi" : "The Pi answered " + status);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new java.io.FileOutputStream(to)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        } finally { connection.disconnect(); }
    }

    /** How far a stream has come: bytes so far and the total, or -1 when the Pi did not say. */
    interface Progress { void at(long done, long total); }

    /** True when whoever asked for the stream no longer wants it. */
    interface Stop { boolean now(); }

    /**
     * Copy a file from the Pi into {@code to} as it arrives, saying how far it has come.
     * Returns the type the Pi named. A Pi refusal arrives as a MediaException with its code.
     */
    String stream(String path, OutputStream to, int readTimeoutMs, Progress progress, Stop stop) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("X-Csync-Token", token);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                JSONObject error;
                try (InputStream input = connection.getErrorStream()) {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while (input != null && (count = input.read(buffer)) != -1 && body.size() < 65536) body.write(buffer, 0, count);
                    error = new JSONObject(body.size() == 0 ? "{}" : body.toString("UTF-8"));
                } catch (Exception unreadable) { error = new JSONObject(); }
                throw new MediaException(error.optString("code", "MEDIA_ERROR"),
                    error.optString("message", "The Pi answered " + status));
            }
            long total = connection.getContentLengthLong(), done = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (stop != null && stop.now()) throw new Exception("Stopped");
                    to.write(buffer, 0, count);
                    done += count;
                    if (progress != null) progress.at(done, total);
                }
            }
            return connection.getContentType();
        } finally { connection.disconnect(); }
    }

    JSONObject uploadNoteImage(String path, byte[] png) throws Exception {
        if (png.length < 8 || png.length > 1024 * 1024) throw new Exception("Choose a PNG under 1 MB");
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", "image/png");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(png.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(png); }
            int status = connection.getResponseCode();
            InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject result = new JSONObject(output.toString("UTF-8"));
            if (status >= 400) throw new MediaException(result.optString("code", "IMAGE_ERROR"),
                result.optString("message", "Could not add image"));
            return result;
        } finally { connection.disconnect(); }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://" + host + ":8792" + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(path.equals("/v1/camera/record/stop") ? 120000 :
            path.equals("/v1/cast/youtube") ? 40000 :
            path.equals("/v1/instagram/inspect") ? 150000 :
            path.equals("/v1/player/pi/commands") ? 40000 :
            (path.equals("/v1/player/pi") || path.equals("/v1/player/pi/immediate")) ? 5000 : 15000);
        connection.setRequestProperty("X-Csync-Token", token);
        if (body != null) {
            byte[] data = body.toString().getBytes("UTF-8");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
        }
        try {
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) != -1) {
                if (output.size() + count > 1048576) throw new Exception("Media response is too large");
                output.write(chunk, 0, count);
            }
            JSONObject result = new JSONObject(output.toString("UTF-8"));
            if (status >= 400) throw new MediaException(result.optString("code", "MEDIA_ERROR"),
                result.optString("message", result.optString("code", "Media error")));
            return result;
        } finally {
            connection.disconnect();
        }
    }
}
