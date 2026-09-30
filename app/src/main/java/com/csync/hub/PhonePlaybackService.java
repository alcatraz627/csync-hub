package com.csync.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns phone playback and agent control after the browser closes. */
public final class PhonePlaybackService extends Service {
    interface Observer { void changed(String status); }
    static PhonePlaybackService current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService progressQueue = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "phone-media-progress");
        thread.setDaemon(true);
        return thread;
    });
    private MediaClient client;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private JSONObject item, session;
    private Observer observer;
    private SurfaceHolder surface;
    private DisplayManager displays;
    private Presentation presentation;
    private SurfaceView externalVideo;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int displayId) { ui.post(PhonePlaybackService.this::updateOutput); }
        public void onDisplayRemoved(int displayId) { ui.post(PhonePlaybackService.this::updateOutput); }
        public void onDisplayChanged(int displayId) { ui.post(PhonePlaybackService.this::updateOutput); }
    };
    private int sequence, volume = 100, requestVersion;
    private float speed = 1f;
    private boolean completed, prepared;
    private String status = "Phone player stopped";

    static void ensure(Context context) {
        Intent intent = new Intent(context, PhonePlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        client = new MediaClient(Prefs.assistIp(this), Prefs.token(this));
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displays.registerDisplayListener(displayListener, ui);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("csync_media", "Media playback",
            NotificationManager.IMPORTANCE_LOW));
        mediaSession = new MediaSession(this, "csync-phone");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { control("resume"); }
            @Override public void onPause() { control("pause"); }
            @Override public void onStop() { control("stop"); }
            @Override public void onSeekTo(long position) { seek((int) position); }
        });
        mediaSession.setActive(true);
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(61, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(61, notification);
        ui.postDelayed(this::tick, 2000);
    }

    /** Pause, Resume and Stop from the notification arrive here as the intent's action. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) control(intent.getAction());
        return START_STICKY;
    }

    /**
     * Android's media notification for what this phone is playing. It carries the
     * media session, so the lock screen, the shade, headsets and the volume panel
     * all control the same playback as the app's own player.
     */
    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MediaActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = playing();
        String words = player == null ? "Stopped" : !prepared ? "Loading" : playing ? "Playing on this phone" : "Paused on this phone";
        return new Notification.Builder(this, "csync_media")
            .setSmallIcon(R.drawable.csi_media)
            .setContentTitle(item == null ? "csync" : item.optString("name", "Media"))
            .setContentText(words).setContentIntent(open).setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(notificationAction(playing ? R.drawable.csi_pause : R.drawable.csi_play,
                playing ? "Pause" : "Resume", playing ? "pause" : "resume"))
            .addAction(notificationAction(R.drawable.csi_stop, "Stop", "stop"))
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1))
            .build();
    }

    private Notification.Action notificationAction(int icon, String label, String command) {
        PendingIntent send = PendingIntent.getService(this, command.hashCode(),
            new Intent(this, PhonePlaybackService.class).setAction(command),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), label, send).build();
    }

    /** Tell Android what is playing and how far in, and redraw the notification to match. */
    private void publish() {
        if (mediaSession == null) return;
        int state = player == null ? PlaybackState.STATE_STOPPED : !prepared ? PlaybackState.STATE_BUFFERING
            : playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
            .setState(state, position(), speed).build());
        mediaSession.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item == null ? "csync" : item.optString("name", "Media"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "On this phone")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(61, buildNotification());
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    void attach(Observer next, SurfaceHolder holder) {
        observer = next;
        setSurface(holder);
        changed(status);
    }

    void detach(Observer leaving) {
        if (observer == leaving) observer = null;
        setSurface(null);
    }

    void setSurface(SurfaceHolder holder) {
        surface = holder;
        updateOutput();
    }

    private void updateOutput() {
        if (item == null || item.optString("mime").startsWith("audio/")) {
            closePresentation();
            return;
        }
        Display[] external = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display chosen = external.length == 0 ? null : external[0];
        if (chosen == null || !chosen.isValid()) closePresentation();
        else if (presentation == null || presentation.getDisplay().getDisplayId() != chosen.getDisplayId()) {
            closePresentation();
            try {
                presentation = new Presentation(this, chosen);
                FrameLayout frame = new FrameLayout(presentation.getContext());
                frame.setBackgroundColor(Color.BLACK);
                externalVideo = new SurfaceView(presentation.getContext());
                externalVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
                    public void surfaceCreated(SurfaceHolder holder) { updateOutput(); }
                    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
                    public void surfaceDestroyed(SurfaceHolder holder) { if (player != null) player.setDisplay(null); }
                });
                frame.addView(externalVideo, new FrameLayout.LayoutParams(-1, -1));
                presentation.setContentView(frame);
                presentation.show();
            } catch (Exception error) {
                closePresentation();
                changed("External display unavailable: " + error.getMessage());
            }
        }
        SurfaceHolder output = presentation != null && externalVideo != null &&
            externalVideo.getHolder().getSurface().isValid() ? externalVideo.getHolder() :
            presentation == null && surface != null && surface.getSurface().isValid() ? surface : null;
        if (player != null) player.setDisplay(output);
    }

    private void closePresentation() {
        if (presentation != null) presentation.dismiss();
        presentation = null;
        externalVideo = null;
    }

    private void changed(String message) {
        status = message;
        publish();
        if (observer != null) observer.changed(message);
    }

    JSONObject item() { return item; }
    boolean hasPlayer() { return player != null; }
    String playbackState() {
        if (player == null) return "stopped";
        if (!prepared) return "loading";
        if (completed) return "finished";
        return playing() ? "playing" : "paused";
    }
    int position() { try { return player == null || !prepared ? 0 : player.getCurrentPosition(); } catch (Exception e) { return 0; } }
    int duration() { try { return player == null || !prepared ? 0 : player.getDuration(); } catch (Exception e) { return 0; } }
    int volume() { return volume; }
    float speed() { return speed; }
    boolean playing() { try { return player != null && prepared && player.isPlaying(); } catch (Exception e) { return false; } }

    void play(JSONObject next, int resumeMs) {
        releasePlayer();
        item = next;
        updateOutput();
        session = null;
        completed = false;
        volume = 100;
        speed = 1f;
        int version = ++requestVersion;
        changed("Opening stream");
        progressQueue.execute(() -> {
            try {
                JSONObject registered = client.post("/v1/phone/sessions",
                    new JSONObject().put("itemId", next.getString("id")));
                ui.post(() -> {
                    if (version != requestVersion) return;
                    session = registered;
                    sequence = 0;
                    try {
                        MediaPlayer opened = new MediaPlayer();
                        player = opened;
                        opened.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                        updateOutput();
                        Map<String, String> headers = new HashMap<>();
                        headers.put("X-Csync-Token", client.token());
                        opened.setDataSource(this, Uri.parse(client.streamUrl(next.getString("id"))), headers);
                        opened.setOnPreparedListener(p -> {
                            if (version != requestVersion) return;
                            prepared = true;
                            if (resumeMs > 0) p.seekTo(resumeMs);
                            p.start();
                            changed("Playing on phone");
                        });
                        opened.setOnErrorListener((p, what, extra) -> {
                            if (version != requestVersion) return true;
                            changed("Phone could not decode or stream this file (" + what + ")");
                            releasePlayer();
                            session = null;
                            stopSelf();
                            return true;
                        });
                        opened.setOnCompletionListener(p -> {
                            if (version != requestVersion) return;
                            completed = true;
                            saveProgress(true);
                            changed("Finished");
                        });
                        opened.prepareAsync();
                    } catch (Exception error) { changed("Phone stream failed: " + error.getMessage()); releasePlayer(); }
                });
            } catch (Exception error) {
                ui.post(() -> { if (version == requestVersion) changed("Phone stream failed: " + error.getMessage()); });
            }
        });
    }

    private void releasePlayer() {
        if (player != null) {
            if (prepared && !completed) saveProgress(false);
            prepared = false;
            player.release();
            player = null;
        }
    }

    void control(String action) {
        if (action.equals("stop")) {
            ++requestVersion;
            releasePlayer();
            item = null;
            session = null;
            closePresentation();
            changed("Phone stopped");
            stopSelf();
            return;
        }
        if (player == null) { changed("Phone player is stopped"); return; }
        if (!prepared) { changed("Phone stream is still opening; Stop is available"); return; }
        try {
            if (action.equals("pause")) player.pause();
            else if (action.equals("resume")) player.start();
            else return;
            saveProgress(false);
            changed("Phone " + action);
        } catch (Exception error) { changed("Phone control failed: " + error.getMessage()); }
    }

    void seek(int value) {
        if (player == null) { changed("Phone player is stopped"); return; }
        if (!prepared) { changed("Phone stream is still opening; Stop is available"); return; }
        try { player.seekTo(value); saveProgress(false); }
        catch (Exception error) { changed("Phone seek failed: " + error.getMessage()); }
    }

    boolean setting(String action, double value) {
        if (player == null) { changed("Phone player is stopped"); return false; }
        if (!prepared) { changed("Phone stream is still opening; Stop is available"); return false; }
        try {
            if (action.equals("volume")) {
                player.setVolume((float) value / 100f, (float) value / 100f);
                volume = (int) value;
            } else {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed((float) value));
                speed = (float) value;
            }
            changed("Phone " + action + " " + value);
            return true;
        } catch (Exception error) { changed("Phone setting failed: " + error.getMessage()); return false; }
    }

    private void saveProgress(boolean finished) {
        if (player == null || session == null || item == null) return;
        try {
            JSONObject body = new JSONObject().put("itemId", item.getString("id")).put("target", "phone")
                .put("sessionId", session.getString("id")).put("generation", session.getInt("generation"))
                .put("sequence", ++sequence).put("positionMs", position()).put("completed", finished)
                .put("name", item.optString("name", "Saved media"))
                .put("mime", item.optString("mime", "application/octet-stream"))
                .put("driveLabel", item.optString("driveId", "Pi drive"))
                .put("updatedAt", System.currentTimeMillis() / 1000);
            String key = item.getString("id"), saved = body.toString();
            SharedPreferences pending = getSharedPreferences("media_progress", MODE_PRIVATE);
            pending.edit().putString(key, saved).apply();
            getSharedPreferences("media_history", MODE_PRIVATE).edit().putString(key + "|phone", saved).apply();
            progressQueue.execute(() -> {
                try {
                    client.post("/v1/progress", body);
                    if (saved.equals(pending.getString(key, null))) pending.edit().remove(key).apply();
                } catch (Exception ignored) { }
            });
        } catch (Exception error) { changed("Could not save phone position"); }
    }

    private void tick() {
        if (current != this) return;
        if (player != null && session != null) {
            try {
                JSONObject observedSession = session;
                JSONObject heartbeat = new JSONObject().put("sessionId", observedSession.getString("id"))
                    .put("generation", observedSession.getInt("generation"))
                    .put("sequence", ++sequence).put("state", !prepared ? "loading" : completed ? "finished" : playing() ? "playing" : "paused")
                    .put("positionMs", position()).put("durationMs", duration())
                    .put("volume", volume).put("speed", speed);
                new Thread(() -> {
                    try {
                        client.post("/v1/phone/heartbeat", heartbeat);
                        JSONObject response = client.get("/v1/phone/commands?sessionId=" +
                            MediaClient.enc(observedSession.getString("id")) + "&generation=" +
                            observedSession.getInt("generation"));
                        ui.post(() -> {
                            if (session != observedSession || player == null) return;
                            JSONArray commands = response.optJSONArray("commands");
                            if (commands == null) return;
                            for (int i = 0; i < commands.length(); i++)
                                try { applyCommand(commands.getJSONObject(i)); } catch (Exception ignored) { }
                        });
                    } catch (Exception ignored) { }
                }, "phone-media-heartbeat").start();
                if (prepared && !completed && sequence % 5 == 0) saveProgress(false);
                if (observer != null) observer.changed(status);
            } catch (Exception ignored) { }
        }
        if (current == this) ui.postDelayed(this::tick, 2000);
    }

    private void applyCommand(JSONObject command) throws Exception {
        if (session == null || player == null ||
                !session.optString("id").equals(command.optString("sessionId")) ||
                session.optInt("generation") != command.optInt("generation")) return;
        String action = command.optString("action"), result = "applied";
        if (command.optDouble("expiresAt", 0) <= System.currentTimeMillis() / 1000.0) {
            acknowledge(command, "rejected", playing() ? "playing" : "paused", position());
            return;
        }
        if (!prepared && !action.equals("stop")) {
            acknowledge(command, "rejected", "loading", 0);
            return;
        }
        try {
            if (action.equals("pause")) player.pause();
            else if (action.equals("resume")) player.start();
            else if (action.equals("seek")) {
                player.setOnSeekCompleteListener(p -> acknowledge(command, "applied", p.isPlaying() ? "playing" : "paused", p.getCurrentPosition()));
                player.seekTo(command.getInt("value"));
                return;
            } else if (action.equals("volume") || action.equals("speed")) {
                if (!setting(action, command.getDouble("value"))) result = "rejected";
            }
            else if (action.equals("stop")) { releasePlayer(); }
            else result = "rejected";
        } catch (Exception error) { result = "rejected"; }
        acknowledge(command, result, player == null ? "idle" : playing() ? "playing" : "paused", position());
        if (action.equals("stop") && "applied".equals(result)) { session = null; stopSelf(); }
    }

    private void acknowledge(JSONObject command, String result, String state, int position) {
        JSONObject observed = session;
        if (observed == null) return;
        try {
            JSONObject body = new JSONObject().put("sessionId", observed.getString("id"))
                .put("generation", observed.getInt("generation")).put("status", result)
                .put("state", state).put("positionMs", position)
                .put("volume", volume).put("speed", speed);
            new Thread(() -> {
                try { client.post("/v1/phone/commands/" +
                    MediaClient.enc(command.getString("commandId")) + "/result", body); }
                catch (Exception ignored) { }
            }, "phone-media-ack").start();
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        ++requestVersion;
        releasePlayer();
        progressQueue.shutdown();
        displays.unregisterDisplayListener(displayListener);
        closePresentation();
        mediaSession.release();
        mediaSession = null;
        current = null;
        super.onDestroy();
    }
}
