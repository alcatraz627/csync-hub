package com.csync.hub;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

final class MediaMiniPlayer {
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MediaClient client;
    private final Row pi;
    private final Row phone;
    private final AtomicBoolean piInFlight = new AtomicBoolean();
    private boolean running;

    MediaMiniPlayer(Activity activity) {
        this.activity = activity;
        client = new MediaClient(Prefs.assistIp(activity), Prefs.token(activity));
        pi = new Row(activity.findViewById(R.id.mini_pi), "pi");
        phone = new Row(activity.findViewById(R.id.mini_phone), "phone");
    }

    void start() {
        if (running) return;
        running = true;
        tick();
    }

    void stop() {
        running = false;
        ui.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        updatePhone();
        updatePi();
        ui.postDelayed(this::tick, 2000);
    }

    private void updatePhone() {
        PhonePlaybackService player = PhonePlaybackService.current;
        if (player == null || !player.hasPlayer()) {
            phone.root.setVisibility(View.GONE);
            return;
        }
        JSONObject item = player.item();
        phone.show(item == null ? "Phone media" : item.optString("name", "Phone media"),
            player.playing() ? "PLAYING" : "PAUSED", player.position(), player.duration());
    }

    private void updatePi() {
        if (Prefs.assistIp(activity).isEmpty() || Prefs.token(activity).isEmpty()) {
            pi.root.setVisibility(View.GONE);
            return;
        }
        if (!piInFlight.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject state = client.get("/v1/player/pi");
                ui.post(() -> {
                    if (!running) return;
                    String status = state.optString("state");
                    if (status.equals("playing") || status.equals("paused") || status.equals("loading") || status.equals("buffering"))
                        pi.show(state.optString("name", "Pi media"), status.toUpperCase(java.util.Locale.ROOT),
                            state.optInt("positionMs"), state.optInt("durationMs"));
                    else pi.root.setVisibility(View.GONE);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (running && pi.root.getVisibility() == View.VISIBLE) {
                        pi.target.setText("PI SCREEN · CONNECTION LOST");
                        pi.pause.setEnabled(false);
                    }
                });
            } finally { piInFlight.set(false); }
        }, "mini-pi-state").start();
    }

    private void command(String target, String action) {
        if (target.equals("phone")) {
            PhonePlaybackService player = PhonePlaybackService.current;
            if (player != null) player.control(action);
            ui.postDelayed(this::updatePhone, 200);
            return;
        }
        new Thread(() -> {
            try {
                if (action.equals("resume")) {
                    JSONObject state = client.get("/v1/player/pi");
                    client.post("/v1/player/pi/commands", new JSONObject().put("action", "resume")
                        .put("expectedRevision", state.getInt("revision")));
                } else {
                    client.post("/v1/player/pi/immediate", new JSONObject().put("action", action));
                }
                ui.post(this::updatePi);
            } catch (Exception error) {
                ui.post(() -> Toast.makeText(activity, "Pi " + action + " failed: " + error.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }, "mini-pi-command").start();
    }

    private final class Row {
        final LinearLayout root;
        final TextView target, title, pause;
        final ProgressBar progress;
        final String output;

        Row(LinearLayout root, String output) {
            this.root = root;
            this.output = output;
            target = root.findViewById(R.id.mini_target);
            title = root.findViewById(R.id.mini_title);
            pause = root.findViewById(R.id.mini_pause);
            progress = root.findViewById(R.id.mini_progress);
            root.findViewById(R.id.mini_open).setOnClickListener(v ->
                activity.startActivity(new Intent(activity, MediaActivity.class)));
            pause.setOnClickListener(v -> command(output, pause.getText().toString().equals("Resume") ? "resume" : "pause"));
            root.findViewById(R.id.mini_stop).setOnClickListener(v -> command(output, "stop"));
        }

        void show(String name, String state, int position, int duration) {
            root.setVisibility(View.VISIBLE);
            target.setText((output.equals("pi") ? "PI SCREEN" : "THIS PHONE") + " · " + state);
            title.setText(name + "  ›");
            boolean paused = state.equals("PAUSED");
            pause.setText(paused ? "Resume" : "Pause");
            pause.setEnabled(paused || state.equals("PLAYING"));
            progress.setProgress(duration > 0 ? (int) Math.min(1000L, 1000L * position / duration) : 0);
        }
    }
}
