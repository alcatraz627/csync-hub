package com.csync.hub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;

/**
 * The client half of the mesh contract: send text and files to a peer's /send,
 * and read a peer's /peers roster. All calls block, so run them off the UI thread.
 */
public final class MeshClient {

    static final int PORT = 8790;
    static final int ASSIST_PORT = 8791;

    interface Attempt<T> {
        T run() throws Exception;
    }

    /**
     * A mobile Tailscale link goes cold between uses, so the first request after
     * an idle stretch times out while the path re-establishes, then works. Retry
     * a few times so that cold start is invisible; only a genuinely-down peer or
     * a disconnected Tailscale reaches the final, plain-language error.
     */
    static <T> T withRetry(String peer, Attempt<T> a) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return a.run();
            } catch (java.io.IOException e) {
                last = e;
                try { Thread.sleep(1200); } catch (InterruptedException ignore) {}
            }
        }
        throw new Exception("Can't reach " + peer + " over Tailscale. Check that Tailscale is"
                + " connected on this phone and that the device is on. ("
                + (last != null ? last.getMessage() : "no response") + ")");
    }

    /** Wake the path to a peer so the first real request is warm. Errors ignored. */
    static void warmUp(String ip, int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/whoami").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignore) {
        }
    }

    /** Send a chat message to the assistant peer and return its reply text. */
    static String chat(String ip, String token, String session, String message) throws Exception {
        return withRetry(ip, () -> chatOnce(ip, token, session, message));
    }

    /** Send a chat message and return the structured turns (thinking, tool_call, text). */
    static JSONArray chatTurns(String ip, String token, String session, String message) throws Exception {
        return withRetry(ip, () -> {
            String resp = chatRaw(ip, token, session, message);
            JSONObject o = new JSONObject(resp);
            JSONArray turns = o.optJSONArray("turns");
            if (turns == null) { // older assist: wrap the flat reply as one text turn
                turns = new JSONArray();
                JSONObject t = new JSONObject();
                t.put("type", "text"); t.put("text", o.optString("reply", "(no reply)"));
                turns.put(t);
            }
            return turns;
        });
    }

    private static String chatRaw(String ip, String token, String session, String message) throws Exception {
        URL url = new URL("http://" + ip + ":" + ASSIST_PORT + "/chat");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Csync-Token", token);
        JSONObject req = new JSONObject();
        req.put("session", session);
        req.put("message", message);
        byte[] body = req.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream out = c.getOutputStream();
        out.write(body);
        out.close();
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            throw new Exception("assistant error (" + code + "): " + resp);
        }
        return resp;
    }

    private static String chatOnce(String ip, String token, String session, String message) throws Exception {
        URL url = new URL("http://" + ip + ":" + ASSIST_PORT + "/chat");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Csync-Token", token);
        JSONObject req = new JSONObject();
        req.put("session", session);
        req.put("message", message);
        byte[] body = req.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream out = c.getOutputStream();
        out.write(body);
        out.close();
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            throw new Exception("assistant error (" + code + "): " + resp);
        }
        return new JSONObject(resp).optString("reply", "(no reply)");
    }

    /** Clear the assistant's memory for a session. */
    static void chatReset(String ip, String token, String session) throws Exception {
        URL url = new URL("http://" + ip + ":" + ASSIST_PORT + "/reset");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Csync-Token", token);
        byte[] body = ("{\"session\":\"" + session + "\"}").getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream out = c.getOutputStream();
        out.write(body);
        out.close();
        c.getResponseCode();
        c.disconnect();
    }

    /** Post one payload to a peer's /send. Returns the receipt body on success. */
    static String send(String ip, String token, String from, String kind,
                       String name, byte[] body) throws Exception {
        return withRetry(ip, () -> sendOnce(ip, token, from, kind, name, body));
    }

    private static String sendOnce(String ip, String token, String from, String kind,
                                   String name, byte[] body) throws Exception {
        URL url = new URL("http://" + ip + ":" + PORT + "/send");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Csync-Token", token);
        c.setRequestProperty("X-Csync-From", from);
        c.setRequestProperty("X-Csync-Kind", kind);
        c.setRequestProperty("X-Csync-Name", name);
        OutputStream out = c.getOutputStream();
        out.write(body);
        out.close();
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            throw new Exception("peer refused (" + code + "): " + resp);
        }
        return resp;
    }

    /** Read a peer's tailnet roster (that peer runs `tailscale`, this device need not). */
    static JSONArray peers(String ip, String token) throws Exception {
        return withRetry(ip, () -> peersOnce(ip, token));
    }

    private static JSONArray peersOnce(String ip, String token) throws Exception {
        URL url = new URL("http://" + ip + ":" + PORT + "/peers");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("X-Csync-Token", token);
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            throw new Exception("scan failed (" + code + "): " + resp);
        }
        return new JSONArray(resp);
    }

    /** The assistant's advertised tools: a list of {name, description}. */
    static JSONArray capabilities(String assistIp, String token) throws Exception {
        return withRetry(assistIp, () -> {
            URL url = new URL("http://" + assistIp + ":" + ASSIST_PORT + "/capabilities");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("X-Csync-Token", token);
            int code = c.getResponseCode();
            String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            if (code >= 400) throw new Exception("capabilities failed (" + code + "): " + resp);
            JSONArray tools = new JSONObject(resp).optJSONArray("tools");
            return tools == null ? new JSONArray() : tools;
        });
    }

    /**
     * A quick liveness probe of a device by name, resolved via MagicDNS. Hits
     * /whoami with a short timeout and no retry; true means the device answered.
     * Used for the Home status dots, where a slow no is as good as a fast no.
     */
    static boolean reachable(String host, int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/whoami").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * This device's tailnet IPv4, if Tailscale is up. Tailscale hands out
     * addresses in the 100.64.0.0/10 CGNAT range, so that is what we look for.
     */
    static String tailnetIP() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && ip.indexOf(':') < 0 && inCGNAT(ip)) {
                        return ip;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static boolean inCGNAT(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private MeshClient() {}
}
