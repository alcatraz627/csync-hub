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
            player.playbackState().toUpperCase(java.util.Locale.ROOT), player.position(), player.duration());
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
                    pi.volume = state.optInt("volume", pi.volume);
                    pi.speed = state.optDouble("speed", pi.speed);
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
        if (pi.pendingAction != null && (!action.equals("stop") || pi.pendingAction.equals("stop"))) return;
        pi.pendingAction = action;
        pi.failureAction = null;
        pi.render();
        new Thread(() -> {
            try {
                if (action.equals("resume")) {
                    JSONObject state = client.get("/v1/player/pi");
                    client.post("/v1/player/pi/commands", new JSONObject().put("action", "resume")
                        .put("expectedRevision", state.getInt("revision")));
                } else {
                    client.post("/v1/player/pi/immediate", new JSONObject().put("action", action));
                }
                ui.post(() -> {
                    if (!action.equals(pi.pendingAction)) return;
                    pi.pendingAction = null;
                    updatePi();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (!action.equals(pi.pendingAction)) return;
                    pi.pendingAction = null;
                    pi.failureAction = action;
                    pi.render();
                    Toast.makeText(activity, "Pi " + action + " failed. Try again.", Toast.LENGTH_LONG).show();
                });
            }
        }, "mini-pi-command").start();
    }

    /** Volume, speed and seek. Pi volume applies at once; speed and seek go through the revision-checked queue. */
    private void setting(String target, String action, double value) {
        if (target.equals("phone")) {
            PhonePlaybackService player = PhonePlaybackService.current;
            if (player != null) player.setting(action, value);
            return;
        }
        new Thread(() -> {
            try {
                if (action.equals("volume")) {
                    client.post("/v1/player/pi/immediate", new JSONObject().put("action", "volume").put("value", value));
                } else {
                    JSONObject state = client.get("/v1/player/pi");
                    JSONObject body = new JSONObject().put("action", action).put("expectedRevision", state.getInt("revision"));
                    if (action.equals("seek")) body.put("positionMs", (long) value); else body.put("value", value);
                    client.post("/v1/player/pi/commands", body);
                }
                ui.post(this::updatePi);
            } catch (Exception error) {
                ui.post(() -> Toast.makeText(activity, "Pi " + action + " failed. Try again.", Toast.LENGTH_LONG).show());
            }
        }, "mini-pi-setting").start();
    }

    private final class Row {
        final LinearLayout root;
        final TextView target, title;
        final android.widget.ImageView pause, stop;
        final ProgressBar progress;
        final String output;
        String state = "", name = "", pendingAction, failureAction;
        int position, duration, volume;
        double speed = 1;
        // The open half-height panel, if any, refreshed on every tick.
        com.google.android.material.bottomsheet.BottomSheetDialog panel;
        TextView panelTitle, panelState;
        android.widget.SeekBar panelSeek;
        android.widget.ImageView panelPause;

        Row(LinearLayout root, String output) {
            this.root = root;
            this.output = output;
            target = root.findViewById(R.id.mini_target);
            title = root.findViewById(R.id.mini_title);
            pause = root.findViewById(R.id.mini_pause);
            stop = root.findViewById(R.id.mini_stop);
            progress = root.findViewById(R.id.mini_progress);
            root.findViewById(R.id.mini_open).setOnClickListener(v -> openPanel());
            pause.setOnClickListener(v -> togglePause());
            stop.setOnClickListener(v -> command(output, "stop"));
            root.findViewById(R.id.mini_volume).setOnClickListener(v -> chooseVolume());
            root.findViewById(R.id.mini_speed).setOnClickListener(v -> chooseSpeed());
        }

        private void togglePause() { command(output, state.equals("PAUSED") ? "resume" : "pause"); }

        private void chooseVolume() {
            Kit.sliderSheet(activity, "Volume", 0f, 100f, 5f, volume, v -> Math.round(v) + "%",
                v -> { volume = Math.round(v); setting(output, "volume", Math.round(v)); });
        }

        private void chooseSpeed() {
            Kit.sliderSheet(activity, "Speed", 0.5f, 2f, 0.25f, (float) speed, v -> v + "×",
                v -> { speed = v; setting(output, "speed", v); });
        }

        private void openFullPlayer() {
            if (panel != null) panel.dismiss();
            activity.startActivity(new Intent(activity, MediaActivity.class).putExtra("player_target", output));
        }

        /**
         * The half-height panel floats over the app with the main controls. Dragging it
         * to full height opens the full player; dragging it down returns to the row.
         */
        private void openPanel() {
            panel = new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
            View body = activity.getLayoutInflater().inflate(R.layout.kit_sheet, null, false);
            panelTitle = body.findViewById(R.id.kit_title);
            panelState = body.findViewById(R.id.kit_sub);
            LinearLayout rows = body.findViewById(R.id.kit_rows);
            panelSeek = new android.widget.SeekBar(activity);
            panelSeek.setMax(1000);
            panelSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {}
                public void onStartTrackingTouch(android.widget.SeekBar s) {}
                public void onStopTrackingTouch(android.widget.SeekBar s) {
                    if (duration > 0) setting(output, "seek", (double) duration * s.getProgress() / 1000);
                }
            });
            rows.addView(panelSeek);
            LinearLayout transport = new LinearLayout(activity);
            transport.setGravity(android.view.Gravity.CENTER);
            transport.setPadding(0, Kit.dp(activity, 10), 0, Kit.dp(activity, 6));
            addControl(transport, R.drawable.csi_rewind, "Rewind 10 seconds",
                () -> setting(output, "seek", Math.max(0, position - 10000)));
            panelPause = addControl(transport, R.drawable.csi_pause, "Pause", this::togglePause);
            addControl(transport, R.drawable.csi_fastforward, "Forward 10 seconds",
                () -> setting(output, "seek", Math.min(Math.max(duration, 0), position + 10000)));
            addControl(transport, R.drawable.csi_stop, "Stop", () -> { command(output, "stop"); panel.dismiss(); });
            rows.addView(transport);
            LinearLayout group = Kit.group(rows);
            View vol = Kit.addRow(group);
            Kit.bindRow(vol, R.drawable.csi_volume, "Volume", volume + "%", null, true);
            vol.setOnClickListener(v -> chooseVolume());
            View spd = Kit.addRow(group);
            Kit.bindRow(spd, R.drawable.csi_speed, "Speed", speed + "×", null, true);
            spd.setOnClickListener(v -> chooseSpeed());
            View full = Kit.addRow(group);
            Kit.bindRow(full, R.drawable.csi_expand, "Full player", "Or drag this panel up", null, true);
            full.setOnClickListener(v -> openFullPlayer());
            panel.setContentView(body);
            com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.FrameLayout> behavior = panel.getBehavior();
            behavior.setPeekHeight(activity.getResources().getDisplayMetrics().heightPixels / 2);
            behavior.addBottomSheetCallback(new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(View sheet, int newState) {
                    if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) openFullPlayer();
                }
                @Override public void onSlide(View sheet, float offset) {}
            });
            panel.setOnDismissListener(d -> panel = null);
            renderPanel();
            panel.show();
        }

        private android.widget.ImageView addControl(LinearLayout row, int icon, String label, Runnable click) {
            android.widget.ImageView control = new android.widget.ImageView(activity);
            control.setImageResource(icon);
            control.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text)));
            control.setBackgroundResource(R.drawable.kit_action_bg);
            int pad = Kit.dp(activity, 13);
            control.setPadding(pad, pad, pad, pad);
            control.setContentDescription(label);
            control.setOnClickListener(v -> click.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(activity, 52), Kit.dp(activity, 52));
            lp.setMargins(Kit.dp(activity, 6), 0, Kit.dp(activity, 6), 0);
            row.addView(control, lp);
            return control;
        }

        private void renderPanel() {
            if (panel == null) return;
            panelTitle.setText(name);
            panelState.setText(target.getText());
            if (duration > 0) panelSeek.setProgress((int) Math.min(1000L, 1000L * position / duration));
            boolean paused = state.equals("PAUSED");
            panelPause.setImageResource(paused ? R.drawable.csi_play : R.drawable.csi_pause);
            panelPause.setContentDescription(paused ? "Resume" : "Pause");
        }

        void show(String name, String state, int position, int duration) {
            root.setVisibility(View.VISIBLE);
            this.state = state;
            this.name = name;
            this.position = position;
            this.duration = duration;
            if (failureAction != null &&
                ((failureAction.equals("pause") && state.equals("PAUSED")) ||
                 (failureAction.equals("resume") && state.equals("PLAYING")))) failureAction = null;
            title.setText(name);
            progress.setProgress(duration > 0 ? (int) Math.min(1000L, 1000L * position / duration) : 0);
            render();
        }

        void render() {
            String label = output.equals("pi") ? "PI SCREEN" : "THIS PHONE";
            if (pendingAction != null) label += " · " + pendingAction.toUpperCase(java.util.Locale.ROOT) + " pending";
            else if (failureAction != null) label += " · " + failureAction.toUpperCase(java.util.Locale.ROOT) + " FAILED · TRY AGAIN";
            else label += " · " + state;
            target.setText(label);
            boolean paused = state.equals("PAUSED");
            pause.setImageResource(paused ? R.drawable.csi_play : R.drawable.csi_pause);
            pause.setContentDescription(paused ? "Resume" : "Pause");
            pause.setEnabled(pendingAction == null && (paused || state.equals("PLAYING")));
            pause.setVisibility(state.equals("FINISHED") ? View.GONE : View.VISIBLE);
            stop.setEnabled(!"stop".equals(pendingAction));
            renderPanel();
        }
    }
}
