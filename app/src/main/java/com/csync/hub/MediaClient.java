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

    JSONObject get(String path) throws Exception { return request("GET", path, null); }
    JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body); }

    JSONObject uploadWallpaper(byte[] jpeg) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url("/v1/display/wallpaper")).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", "image/jpeg");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(jpeg.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(jpeg); }
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
                result.optString("message", "Wallpaper upload failed"));
            return result;
        } finally { connection.disconnect(); }
    }

    JSONObject uploadMedia(String driveId, String name, long size, InputStream input,
                           java.util.function.LongConsumer progress) throws Exception {
        if (size < 1 || size > 16L * 1024 * 1024 * 1024)
            throw new Exception("Choose a media file under 16 GB");
        HttpURLConnection connection = (HttpURLConnection) new URL(url("/v1/cast/file?driveId=" +
            enc(driveId) + "&name=" + enc(name))).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("X-Csync-Token", token);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
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

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://" + host + ":8792" + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(path.equals("/v1/camera/record/stop") ? 120000 :
            path.equals("/v1/cast/youtube") ? 40000 :
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
