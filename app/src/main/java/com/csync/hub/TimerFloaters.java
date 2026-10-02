package com.csync.hub;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Each running timer as a card in the floater dock while the Timers page is not open: its ring,
 * name and time left, with Pause and Stop, or Add a minute and Dismiss once it has rung.
 * Tapping the card opens that timer on the Timers page.
 */
final class TimerFloaters {
    interface Open { void timer(long id); }

    private final Activity a;
    private final FloaterDock dock;
    private final Open open;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::rebuild;
    private final Map<Long, Card> cards = new HashMap<>();
    private boolean running;

    TimerFloaters(Activity a, FloaterDock dock, Open open) {
        this.a = a;
        this.dock = dock;
        this.open = open;
    }

    void start() {
        if (running) return;
        running = true;
        Timers.listen(changed);
        rebuild();
        tick();
    }

    void stop() {
        running = false;
        Timers.unlisten(changed);
        ui.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        long now = System.currentTimeMillis();
        for (Timers.Timer t : Timers.all(a)) {
            Card c = cards.get(t.id);
            if (c != null) c.update(t, now);
        }
        ui.postDelayed(this::tick, 500);
    }

    private void rebuild() {
        List<Timers.Timer> all = Timers.all(a);
        List<FloaterDock.Floater> out = new ArrayList<>();
        Map<Long, Card> kept = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Timers.Timer t : all) {
            Card c = cards.get(t.id);
            // A card is rebuilt when its buttons change, since done and running timers offer different ones.
            if (c == null || c.done != t.done || c.paused != t.paused) c = new Card(t);
            c.update(t, now);
            kept.put(t.id, c);
            // A timer that has rung comes first; the rest follow media, soonest first.
            int order = t.done ? 0 : 20 + (int) Math.min(1000, t.left(now) / 60_000);
            out.add(new FloaterDock.Floater("timer-" + t.id, c.view, order));
        }
        cards.clear();
        cards.putAll(kept);
        dock.set("timers", out);
    }

    private final class Card {
        final View view;
        final boolean done, paused;
        final RingView ring;
        final TextView name, line;

        Card(Timers.Timer t) {
            done = t.done;
            paused = t.paused;
            long id = t.id;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Kit.dp(a, 10), 0, Kit.dp(a, 4), 0);
            LinearLayout openArea = new LinearLayout(a);
            openArea.setGravity(Gravity.CENTER_VERTICAL);
            openArea.setBackgroundResource(Kit.outValue(a));
            FrameLayout ringBox = new FrameLayout(a);
            ring = new RingView(a, 4);
            ringBox.addView(ring, new FrameLayout.LayoutParams(Kit.dp(a, 40), Kit.dp(a, 40)));
            ImageView mark = new ImageView(a);
            mark.setImageResource(t.done ? R.drawable.csi_check : R.drawable.csi_timer);
            mark.setImageTintList(ColorStateList.valueOf(t.done ? Kit.statusColor(a, Kit.Status.GOOD) : Timers.colour(a, t.colour)));
            ringBox.addView(mark, new FrameLayout.LayoutParams(Kit.dp(a, 18), Kit.dp(a, 18), Gravity.CENTER));
            openArea.addView(ringBox, new LinearLayout.LayoutParams(Kit.dp(a, 40), Kit.dp(a, 40)));
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(Kit.dp(a, 10), 0, Kit.dp(a, 4), 0);
            name = new TextView(a);
            name.setTextAppearance(R.style.Kit_Text_RowTitle);
            name.setTextSize(14);
            name.setSingleLine();
            words.addView(name);
            line = new TextView(a);
            line.setTextAppearance(R.style.Kit_Text_Meta);
            line.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
            line.setSingleLine();
            words.addView(line);
            openArea.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            openArea.setOnClickListener(v -> open.timer(id));
            row.addView(openArea, new LinearLayout.LayoutParams(0, -1, 1));
            if (t.done) {
                row.addView(button(R.drawable.csi_plus_one, "Add a minute", R.color.text, () -> Timers.adjust(a, id, 1)));
                row.addView(button(R.drawable.csi_close, "Dismiss", R.color.text, () -> Timers.stop(a, id)));
            } else {
                row.addView(button(t.paused ? R.drawable.csi_play : R.drawable.csi_pause, t.paused ? "Resume" : "Pause",
                    R.color.text, () -> Timers.pauseToggle(a, id)));
                row.addView(button(R.drawable.csi_stop, "Stop", R.color.danger, () -> Timers.stop(a, id)));
            }
            view = row;
        }

        void update(Timers.Timer t, long now) {
            name.setText(t.name() + (t.done ? " is done" : ""));
            line.setText(t.done ? "Rang at " + android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date(t.end))
                : Timers.clock(t.left(now)) + (t.paused ? ", paused" : " left"));
            ring.set(t.done ? 1f : t.fraction(now), t.done ? Kit.statusColor(a, Kit.Status.GOOD) : Timers.colour(a, t.colour));
            view.setContentDescription(t.name() + ", " + line.getText());
        }

        private View button(int icon, String spoken, int colour, Runnable click) {
            ImageView b = new ImageView(a);
            b.setImageResource(icon);
            b.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, colour)));
            int pad = Kit.dp(a, 14);
            b.setPadding(pad, pad, pad, pad);
            android.util.TypedValue value = new android.util.TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
            b.setBackgroundResource(value.resourceId);
            b.setContentDescription(spoken);
            b.setOnClickListener(v -> { Kit.tick(v); click.run(); });
            b.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(a, 48), Kit.dp(a, 48)));
            return b;
        }
    }
}
