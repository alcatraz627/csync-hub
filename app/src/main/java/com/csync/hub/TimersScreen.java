package com.csync.hub;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The Timers page: one ring to set and run a timer, cards when two to four run at once, the
 * running timer's buttons, and saved tiles that start a timer in one tap.
 *
 * It draws itself into a host the page gives it and redraws whenever a timer changes, from here
 * or from a notification, the dock or a widget. Between changes a light tick moves the numbers.
 */
final class TimersScreen {
    private final Activity a;
    private final LinearLayout host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::render;

    // What a new timer will be, kept while the page is open.
    private int draftMinutes = 15;
    private String draftLabel = "", draftColour = "coral";
    private long focus = -1;
    private boolean adding, editingTiles;

    // Views the tick updates without redrawing the page.
    private final List<Runnable> live = new ArrayList<>();
    private DialView dial;
    private boolean showing;

    TimersScreen(Activity a, LinearLayout host) {
        this.a = a;
        this.host = host;
    }

    void show(long openTimer) {
        if (openTimer > 0 && Timers.find(a, openTimer) != null) { focus = openTimer; adding = false; }
        if (!showing) { Timers.listen(changed); showing = true; }
        render();
        tick();
    }

    void hide() {
        if (showing) Timers.unlisten(changed);
        showing = false;
        ui.removeCallbacksAndMessages(null);
    }

    boolean onBack() {
        if (adding && !Timers.all(a).isEmpty()) { adding = false; render(); return true; }
        if (focus > 0 && running().size() > 1) { focus = -1; render(); return true; }
        return false;
    }

    private void tick() {
        if (!showing) return;
        for (Runnable r : live) r.run();
        ui.postDelayed(this::tick, 250);
    }

    private List<Timers.Timer> running() { return Timers.all(a); }

    // ---- the page ----

    void render() {
        if (dial != null && dial.dragging()) return;
        host.removeAllViews();
        live.clear();
        dial = null;
        List<Timers.Timer> runs = running();
        if (focus > 0 && Timers.find(a, focus) == null) focus = -1;
        if (adding && runs.size() >= Timers.MAX) adding = false;

        if (adding && !runs.isEmpty()) {
            host.addView(backRow("Back to running timers", () -> { adding = false; render(); }));
            dialFor(null);
            composer();
        } else if (runs.isEmpty() || runs.size() == 1 || focus > 0) {
            Timers.Timer t = focus > 0 ? Timers.find(a, focus) : runs.isEmpty() ? null : runs.get(0);
            if (focus > 0 && runs.size() > 1)
                host.addView(backRow("All " + runs.size() + " timers", () -> { focus = -1; render(); }));
            if (t != null) host.addView(title(t));
            dialFor(t);
            if (t != null) controls(t); else composer();
            if (t != null && runs.size() < Timers.MAX) {
                View another = Kit.button(a, R.drawable.csi_plus, "Another timer", R.color.text, () -> { adding = true; focus = -1; render(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Kit.dp(a, 16);
                host.addView(another, lp);
            }
        } else cards(runs);

        tiles(runs.size() >= Timers.MAX);
        for (Runnable r : live) r.run();
    }

    private View backRow(String words, Runnable go) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Kit.dp(a, 48));
        row.setBackgroundResource(Kit.outValue(a));
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.csi_back);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.dim)));
        row.addView(icon, new LinearLayout.LayoutParams(Kit.dp(a, 20), Kit.dp(a, 20)));
        TextView text = new TextView(a);
        text.setText(words);
        text.setTextAppearance(R.style.Kit_Text_RowSub);
        text.setTextSize(14);
        text.setPadding(Kit.dp(a, 8), 0, 0, 0);
        row.addView(text);
        row.setOnClickListener(v -> go.run());
        return row;
    }

    private View title(Timers.Timer t) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Kit.dp(a, 4), 0, 0);
        row.addView(dot(Timers.colour(a, t.colour), 10));
        TextView name = new TextView(a);
        name.setText(t.name());
        name.setTextAppearance(R.style.Kit_Text_RowTitle);
        name.setTextSize(16);
        name.setPadding(Kit.dp(a, 8), 0, 0, 0);
        row.addView(name);
        row.setOnClickListener(v -> timerSheet(t.id));
        row.setMinimumHeight(Kit.dp(a, 40));
        return row;
    }

    // ---- the ring ----

    private void dialFor(Timers.Timer t) {
        FrameLayout box = new FrameLayout(a);
        DialView ring = new DialView(a);
        dial = ring;
        box.addView(ring, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout centre = new LinearLayout(a);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        TextView time = new TextView(a);
        time.setTextColor(ContextCompat.getColor(a, R.color.text));
        time.setTextSize(40);
        time.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        time.setGravity(Gravity.CENTER);
        time.setSingleLine(true);
        centre.addView(time);
        box.addView(centre, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        if (t == null) {
            ring.setColour(Timers.colour(a, draftColour));
            ring.setMinutes(draftMinutes);
            time.setText(draftClock(draftMinutes));
            ImageView play = new ImageView(a);
            play.setImageResource(R.drawable.csi_play);
            play.setImageTintList(ColorStateList.valueOf(Timers.colour(a, draftColour)));
            play.setPadding(Kit.dp(a, 16), Kit.dp(a, 16), Kit.dp(a, 16), Kit.dp(a, 16));
            GradientDrawable well = new GradientDrawable();
            well.setShape(GradientDrawable.OVAL);
            well.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.surface),
                Timers.colour(a, draftColour), 0.16f));
            play.setBackground(well);
            play.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
            play.setContentDescription("Start a " + Timers.lengthWords(draftMinutes) + " timer");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(a, 60), Kit.dp(a, 60));
            lp.topMargin = Kit.dp(a, 8);
            centre.addView(play, lp);
            play.setOnClickListener(v -> startDraft());
            ring.onTurned((m, finished) -> {
                draftMinutes = m;
                time.setText(draftClock(m));
                play.setContentDescription("Start a " + Timers.lengthWords(m) + " timer");
            });
        } else {
            TextView under = new TextView(a);
            under.setTextAppearance(R.style.Kit_Text_RowSub);
            under.setGravity(Gravity.CENTER);
            centre.addView(under);
            ring.setColour(t.done ? Kit.statusColor(a, Kit.Status.GOOD) : Timers.colour(a, t.colour));
            long id = t.id;
            ring.setMinutes((int) Math.max(1, Math.ceil(t.left(System.currentTimeMillis()) / 60000.0)));
            ring.onTurned((m, finished) -> {
                time.setText(draftClock(m));
                if (finished) Timers.setLeft(a, id, m * Timers.MINUTE);
            });
            live.add(() -> {
                if (ring.dragging()) return;
                Timers.Timer now = Timers.find(a, id);
                if (now == null) return;
                long at = System.currentTimeMillis();
                time.setText(now.done ? "00:00" : Timers.clock(now.left(at)));
                under.setText(now.done ? "Done" : now.paused ? "Paused"
                    : "Rings at " + android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date(now.end)));
                ring.showFraction(now.fraction(at));
                ring.setMinutes((int) Math.max(1, Math.ceil(now.left(at) / 60000.0)));
            });
        }
        host.addView(box, new LinearLayout.LayoutParams(-1, -2));
        TextView help = new TextView(a);
        help.setTextAppearance(R.style.Kit_Text_RowSub);
        help.setGravity(Gravity.CENTER);
        help.setText(t == null ? "Turn the ring to set the length, up to three hours"
            : t.done ? "Add time to run it again, or stop it" : "Turn the ring to change the time left");
        help.setPadding(0, Kit.dp(a, 6), 0, 0);
        host.addView(help);
    }

    private static String draftClock(int minutes) {
        return minutes >= 60 ? String.format(java.util.Locale.ROOT, "%d:%02d:00", minutes / 60, minutes % 60)
            : String.format(java.util.Locale.ROOT, "%02d:00", minutes);
    }

    private void startDraft() {
        Timers.Timer t = Timers.start(a, draftMinutes, draftLabel, draftColour);
        if (t == null) { Toast.makeText(a, "Four timers is the most at once", Toast.LENGTH_SHORT).show(); return; }
        adding = false;
        focus = -1;
        draftLabel = "";
        Toast.makeText(a, t.name() + " started, " + Timers.lengthWords(draftMinutes), Toast.LENGTH_SHORT).show();
    }

    // ---- a new timer's label and colour ----

    private void composer() {
        EditText label = field("Label, such as Tea");
        label.setText(draftLabel);
        label.addTextChangedListener(new SimpleWatcher(s -> draftLabel = s));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Kit.dp(a, 14);
        host.addView(label, lp);
        host.addView(swatches(draftColour, c -> { draftColour = c; render(); }));
    }

    interface Picked { void colour(String name); }

    private View swatches(String chosen, Picked picked) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Kit.dp(a, 6), 0, 0);
        for (String name : Timers.COLOURS) {
            FrameLayout touch = new FrameLayout(a);
            View swatch = new View(a);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(Timers.colour(a, name));
            if (name.equals(chosen)) fill.setStroke(Kit.dp(a, 3), ContextCompat.getColor(a, R.color.text));
            swatch.setBackground(fill);
            touch.addView(swatch, new FrameLayout.LayoutParams(Kit.dp(a, name.equals(chosen) ? 32 : 28),
                Kit.dp(a, name.equals(chosen) ? 32 : 28), Gravity.CENTER));
            touch.setContentDescription(Timers.colourName(name) + (name.equals(chosen) ? ", chosen" : ""));
            touch.setBackgroundResource(Kit.outValue(a));
            touch.setOnClickListener(v -> picked.colour(name));
            row.addView(touch, new LinearLayout.LayoutParams(0, Kit.dp(a, 48), 1));
        }
        return row;
    }

    private EditText field(String hint) {
        EditText field = new EditText(a);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(ContextCompat.getColor(a, R.color.text));
        field.setHintTextColor(ContextCompat.getColor(a, R.color.dim));
        field.setBackgroundResource(R.drawable.card_bg);
        field.setPadding(Kit.dp(a, 14), Kit.dp(a, 12), Kit.dp(a, 14), Kit.dp(a, 12));
        field.setMinHeight(Kit.dp(a, 48));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return field;
    }

    // ---- one running timer's buttons ----

    private void controls(Timers.Timer t) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Kit.dp(a, 14), 0, 0);
        long id = t.id;
        View minus = iconButton(R.drawable.csi_minus_one, "Take a minute off", R.color.text, () -> Timers.adjust(a, id, -1));
        minus.setEnabled(!t.done);
        minus.setAlpha(t.done ? 0.4f : 1f);
        row.addView(minus);
        View pause = iconButton(t.paused ? R.drawable.csi_play : R.drawable.csi_pause, t.paused ? "Resume" : "Pause",
            R.color.text, () -> Timers.pauseToggle(a, id));
        pause.setEnabled(!t.done);
        pause.setAlpha(t.done ? 0.4f : 1f);
        row.addView(pause);
        View plus = iconButton(R.drawable.csi_plus_one, "Add a minute. Hold for more", R.color.text, () -> Timers.adjust(a, id, 1));
        plus.setOnLongClickListener(v -> { timerSheet(id); return true; });
        row.addView(plus);
        row.addView(iconButton(R.drawable.csi_stop, "Stop", R.color.danger, () -> Timers.stop(a, id)));
        row.addView(iconButton(R.drawable.csi_more, "More for this timer", R.color.text, () -> timerSheet(id)));
        host.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View iconButton(int icon, String spoken, int colour, Runnable click) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, colour)));
        int pad = Kit.dp(a, 13);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Kit.dp(a, 1), ContextCompat.getColor(a, R.color.border));
        b.setBackground(ring);
        b.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
        b.setClipToOutline(true);
        b.setContentDescription(spoken);
        b.setOnClickListener(v -> { Kit.tick(v); click.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(a, 50), Kit.dp(a, 50));
        lp.setMargins(Kit.dp(a, 5), 0, Kit.dp(a, 5), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---- two to four timers as cards ----

    private void cards(List<Timers.Timer> runs) {
        TextView head = Kit.label(host, "Timers");
        head.setText("Timers · " + runs.size() + " of " + Timers.MAX);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(2);
        host.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        int gap = Kit.dp(a, 10);
        for (Timers.Timer t : runs) grid.addView(card(t), cell(gap));
        if (runs.size() < Timers.MAX) {
            LinearLayout add = new LinearLayout(a);
            add.setOrientation(LinearLayout.VERTICAL);
            add.setGravity(Gravity.CENTER);
            add.setMinimumHeight(Kit.dp(a, 196));
            GradientDrawable dashed = new GradientDrawable();
            dashed.setCornerRadius(Kit.dp(a, 18));
            dashed.setStroke(Kit.dp(a, 1), ContextCompat.getColor(a, R.color.border), Kit.dp(a, 6), Kit.dp(a, 4));
            add.setBackground(dashed);
            add.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
            ImageView plus = new ImageView(a);
            plus.setImageResource(R.drawable.csi_plus);
            plus.setImageTintList(ColorStateList.valueOf(Kit.accentText(a)));
            add.addView(plus, new LinearLayout.LayoutParams(Kit.dp(a, 24), Kit.dp(a, 24)));
            TextView words = new TextView(a);
            words.setText("Another timer");
            words.setTextColor(Kit.accentText(a));
            words.setGravity(Gravity.CENTER);
            words.setPadding(0, Kit.dp(a, 6), 0, 0);
            add.addView(words, new LinearLayout.LayoutParams(-1, -2));
            add.setOnClickListener(v -> { adding = true; render(); });
            grid.addView(add, cell(gap));
        }
    }

    private GridLayout.LayoutParams cell(int gap) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
        return lp;
    }

    private View card(Timers.Timer t) {
        long id = t.id;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Kit.dp(a, 8), Kit.dp(a, 14), Kit.dp(a, 8), Kit.dp(a, 6));
        card.setBackgroundResource(R.drawable.card_bg);
        LinearLayout open = new LinearLayout(a);
        open.setOrientation(LinearLayout.VERTICAL);
        open.setGravity(Gravity.CENTER_HORIZONTAL);
        open.setBackgroundResource(Kit.outValue(a));
        FrameLayout ringBox = new FrameLayout(a);
        RingView ring = new RingView(a, 8);
        ringBox.addView(ring, new FrameLayout.LayoutParams(Kit.dp(a, 100), Kit.dp(a, 100)));
        TextView time = new TextView(a);
        time.setTextColor(ContextCompat.getColor(a, R.color.text));
        time.setTextSize(18);
        time.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        ringBox.addView(time, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        open.addView(ringBox, new LinearLayout.LayoutParams(Kit.dp(a, 100), Kit.dp(a, 100)));
        TextView name = new TextView(a);
        name.setText(t.name());
        name.setTextAppearance(R.style.Kit_Text_RowTitle);
        name.setTextSize(14);
        name.setSingleLine();
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Kit.dp(a, 6), 0, 0);
        open.addView(name, new LinearLayout.LayoutParams(-1, -2));
        open.setContentDescription("Open " + t.name());
        open.setOnClickListener(v -> { focus = id; render(); });
        card.addView(open, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER);
        if (t.done) {
            buttons.addView(smallButton(R.drawable.csi_plus_one, "Add a minute", R.color.text, () -> Timers.adjust(a, id, 1)));
            buttons.addView(smallButton(R.drawable.csi_restart, "Restart", R.color.text, () -> Timers.restart(a, id)));
            buttons.addView(smallButton(R.drawable.csi_close, "Dismiss", R.color.text, () -> Timers.stop(a, id)));
        } else {
            buttons.addView(smallButton(t.paused ? R.drawable.csi_play : R.drawable.csi_pause, t.paused ? "Resume" : "Pause",
                R.color.text, () -> Timers.pauseToggle(a, id)));
            View plus = smallButton(R.drawable.csi_plus_one, "Add a minute. Hold for more", R.color.text, () -> Timers.adjust(a, id, 1));
            plus.setOnLongClickListener(v -> { timerSheet(id); return true; });
            buttons.addView(plus);
            buttons.addView(smallButton(R.drawable.csi_stop, "Stop", R.color.danger, () -> Timers.stop(a, id)));
        }
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        live.add(() -> {
            Timers.Timer now = Timers.find(a, id);
            if (now == null) return;
            long at = System.currentTimeMillis();
            time.setText(now.done ? "Done" : Timers.clock(now.left(at)));
            ring.set(now.fraction(at), now.done ? Kit.statusColor(a, Kit.Status.GOOD) : Timers.colour(a, now.colour));
        });
        return card;
    }

    private View smallButton(int icon, String spoken, int colour, Runnable click) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, colour)));
        int pad = Kit.dp(a, 13);
        b.setPadding(pad, pad, pad, pad);
        b.setBackgroundResource(borderless());
        b.setContentDescription(spoken);
        b.setOnClickListener(v -> { Kit.tick(v); click.run(); });
        b.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(a, 48), Kit.dp(a, 48)));
        return b;
    }

    private int borderless() {
        android.util.TypedValue value = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId;
    }

    private View dot(int colour, int sizeDp) {
        View dot = new View(a);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(colour);
        dot.setBackground(fill);
        dot.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(a, sizeDp), Kit.dp(a, sizeDp)));
        return dot;
    }

    // ---- saved tiles ----

    private void tiles(boolean full) {
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Kit.label(head, "Quick timers");
        ((LinearLayout.LayoutParams) label.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) label.getLayoutParams()).weight = 1;
        View edit = Kit.compactButton(a, editingTiles ? R.drawable.csi_check : R.drawable.csi_edit,
            editingTiles ? "Done" : "Edit", false, () -> { editingTiles = !editingTiles; render(); });
        head.addView(edit);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.topMargin = Kit.dp(a, 12);
        host.addView(head, headLp);

        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(3);
        host.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        int gap = Kit.dp(a, 8);
        for (Timers.Tile q : Timers.tiles(a)) grid.addView(tile(q, full), cell(gap));
        if (editingTiles) {
            LinearLayout add = new LinearLayout(a);
            add.setOrientation(LinearLayout.VERTICAL);
            add.setGravity(Gravity.CENTER);
            add.setMinimumHeight(Kit.dp(a, 76));
            GradientDrawable dashed = new GradientDrawable();
            dashed.setCornerRadius(Kit.dp(a, 16));
            dashed.setStroke(Kit.dp(a, 1), ContextCompat.getColor(a, R.color.border), Kit.dp(a, 6), Kit.dp(a, 4));
            add.setBackground(dashed);
            add.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
            TextView words = new TextView(a);
            words.setText("New tile");
            words.setTextColor(Kit.accentText(a));
            add.addView(words);
            add.setContentDescription("New tile");
            add.setOnClickListener(v -> tileSheet(null, draftMinutes, draftLabel, draftColour));
            grid.addView(add, cell(gap));
        }
        if (full) {
            TextView help = new TextView(a);
            help.setTextAppearance(R.style.Kit_Text_RowSub);
            help.setText("Four timers are running, the most at once. Stop one to start another.");
            help.setPadding(0, Kit.dp(a, 6), 0, 0);
            host.addView(help);
        }
    }

    private View tile(Timers.Tile q, boolean full) {
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(Kit.dp(a, 12), Kit.dp(a, 10), Kit.dp(a, 10), Kit.dp(a, 10));
        tile.setMinimumHeight(Kit.dp(a, 76));
        tile.setBackgroundResource(R.drawable.card_bg);
        tile.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(dot(Timers.colour(a, q.colour), 8));
        TextView name = new TextView(a);
        name.setText(q.label);
        name.setTextAppearance(R.style.Kit_Text_RowSub);
        name.setTextSize(12.5f);
        name.setSingleLine();
        name.setPadding(Kit.dp(a, 6), 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (editingTiles) {
            ImageView pen = new ImageView(a);
            pen.setImageResource(R.drawable.csi_edit);
            pen.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.dim)));
            top.addView(pen, new LinearLayout.LayoutParams(Kit.dp(a, 14), Kit.dp(a, 14)));
        }
        tile.addView(top);
        TextView length = new TextView(a);
        boolean hours = q.minutes >= 60 && q.minutes % 60 == 0;
        android.text.SpannableString words = new android.text.SpannableString(
            (hours ? q.minutes / 60 : q.minutes) + (hours ? " h" : " min"));
        int split = String.valueOf(hours ? q.minutes / 60 : q.minutes).length();
        words.setSpan(new android.text.style.RelativeSizeSpan(0.55f), split, words.length(), 0);
        length.setText(words);
        length.setTextColor(ContextCompat.getColor(a, R.color.text));
        length.setTextSize(24);
        length.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        length.setPadding(0, Kit.dp(a, 4), 0, 0);
        tile.addView(length);
        String spoken = (q.label.equals("Quick") ? "" : q.label + ", ") + Timers.lengthWords(q.minutes);
        if (editingTiles) {
            tile.setContentDescription("Edit " + spoken);
            tile.setOnClickListener(v -> tileSheet(q, q.minutes, q.label, q.colour));
        } else {
            tile.setContentDescription("Start " + spoken + ". Hold to edit");
            tile.setAlpha(full ? 0.45f : 1f);
            tile.setOnClickListener(v -> {
                if (full) { Toast.makeText(a, "Four timers is the most at once", Toast.LENGTH_SHORT).show(); return; }
                Kit.tick(v);
                Timers.Timer t = Timers.startTile(a, q.id);
                if (t != null) Toast.makeText(a, t.name() + " started, " + Timers.lengthWords(q.minutes), Toast.LENGTH_SHORT).show();
            });
            tile.setOnLongClickListener(v -> { tileSheet(q, q.minutes, q.label, q.colour); return true; });
        }
        return tile;
    }

    // ---- drawers ----

    /** Everything for one timer: extend, label, colour, restart, keep as a tile, stop. */
    void timerSheet(long id) {
        Timers.Timer t = Timers.find(a, id);
        if (t == null) return;
        long at = System.currentTimeMillis();
        int length = (int) Math.max(1, Math.round(t.total / 60000.0));
        Kit.Sheet sheet = new Kit.Sheet(a, t.name(), t.done ? "Done" : Timers.clock(t.left(at)) + " left of " + Timers.lengthWords(length));
        Kit.label(sheet.rows, t.done ? "Run it again for" : "Extend");
        LinearLayout extend = new LinearLayout(a);
        extend.setOrientation(LinearLayout.HORIZONTAL);
        for (int minutes : new int[]{1, 5, 10, 15}) {
            View b = Kit.compactButton(a, R.drawable.csi_plus, minutes + " min", false, () -> {
                Timers.adjust(a, id, minutes);
                sheet.dialog.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Kit.dp(a, 8));
            extend.addView(b, lp);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(extend);
        sheet.rows.addView(scroll);

        Kit.label(sheet.rows, "This timer");
        EditText label = field("Label");
        label.setText(t.label);
        sheet.rows.addView(label, new LinearLayout.LayoutParams(-1, -2));
        String[] colour = {t.colour};
        LinearLayout swatchHost = new LinearLayout(a);
        sheet.rows.addView(swatchHost, new LinearLayout.LayoutParams(-1, -2));
        Runnable[] drawSwatches = new Runnable[1];
        drawSwatches[0] = () -> {
            swatchHost.removeAllViews();
            swatchHost.addView(swatches(colour[0], c -> { colour[0] = c; Timers.recolour(a, id, c); drawSwatches[0].run(); }),
                new LinearLayout.LayoutParams(-1, -2));
        };
        drawSwatches[0].run();

        LinearLayout group = Kit.group(sheet.rows);
        ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = Kit.dp(a, 12);
        View restart = Kit.addRow(group);
        Kit.bindRow(restart, R.drawable.csi_restart, "Restart", "From " + Timers.lengthWords(length) + " again", null, false);
        restart.setOnClickListener(v -> { Timers.restart(a, id); sheet.dialog.dismiss(); });
        View keep = Kit.addRow(group);
        Kit.bindRow(keep, R.drawable.csi_bookmark, "Save as a tile", "Start it again in one tap", null, false);
        keep.setOnClickListener(v -> {
            String name = label.getText().toString().trim();
            Timers.addTile(a, name, length, colour[0]);
            Toast.makeText(a, (name.isEmpty() ? "Timer" : name) + " saved as a tile", Toast.LENGTH_SHORT).show();
            sheet.dialog.dismiss();
        });
        View stop = Kit.addRow(group);
        Kit.bindRow(stop, R.drawable.csi_stop, t.done ? "Dismiss" : "Stop", null, null, false);
        stop.setOnClickListener(v -> { Timers.stop(a, id); sheet.dialog.dismiss(); });
        sheet.dialog.setOnDismissListener(d -> {
            String typed = label.getText().toString().trim();
            Timers.Timer still = Timers.find(a, id);
            if (still != null && !typed.equals(still.label)) Timers.rename(a, id, typed);
        });
        sheet.show();
    }

    /** Make or change a saved tile: its label, length and colour, pin it, or delete it. */
    private void tileSheet(Timers.Tile q, int minutes, String name, String colour) {
        Kit.Sheet sheet = new Kit.Sheet(a, q == null ? "New tile" : "Edit tile", "A timer you start with one tap");
        EditText label = field("Label");
        label.setText(q == null ? name : q.label.equals("Quick") ? "" : q.label);
        sheet.rows.addView(label, new LinearLayout.LayoutParams(-1, -2));
        int[] length = {minutes};
        String[] picked = {colour};

        LinearLayout stepper = new LinearLayout(a);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.setPadding(0, Kit.dp(a, 10), 0, 0);
        TextView value = new TextView(a);
        value.setTextColor(ContextCompat.getColor(a, R.color.text));
        value.setTextSize(20);
        value.setGravity(Gravity.CENTER);
        value.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        Runnable showLength = () -> value.setText(Timers.lengthShort(length[0]));
        stepper.addView(iconButton(R.drawable.csi_minus_one, "A minute less", R.color.text,
            () -> { length[0] = Math.max(1, length[0] - 1); showLength.run(); }));
        stepper.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        stepper.addView(iconButton(R.drawable.csi_plus_one, "A minute more", R.color.text,
            () -> { length[0] = Math.min(180, length[0] + 1); showLength.run(); }));
        showLength.run();
        sheet.rows.addView(stepper, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout jumps = new LinearLayout(a);
        for (int m : new int[]{5, 10, 15, 30, 60}) {
            View b = Kit.compactButton(a, 0, Timers.lengthShort(m), false, () -> { length[0] = m; showLength.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Kit.dp(a, 6));
            jumps.addView(b, lp);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(jumps);
        sheet.rows.addView(scroll);

        LinearLayout swatchHost = new LinearLayout(a);
        sheet.rows.addView(swatchHost, new LinearLayout.LayoutParams(-1, -2));
        Runnable[] drawSwatches = new Runnable[1];
        drawSwatches[0] = () -> {
            swatchHost.removeAllViews();
            swatchHost.addView(swatches(picked[0], c -> { picked[0] = c; drawSwatches[0].run(); }), new LinearLayout.LayoutParams(-1, -2));
        };
        drawSwatches[0].run();

        View save = Kit.tonalButton(a, R.drawable.csi_check, q == null ? "Add tile" : "Save", () -> {
            if (q == null) Timers.addTile(a, label.getText().toString(), length[0], picked[0]);
            else Timers.editTile(a, q.id, label.getText().toString(), length[0], picked[0]);
            sheet.dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Kit.dp(a, 12);
        sheet.rows.addView(save, lp);
        if (q != null) {
            LinearLayout group = Kit.group(sheet.rows);
            ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = Kit.dp(a, 12);
            View pin = Kit.addRow(group);
            Kit.bindRow(pin, R.drawable.ic_pin, "Pin to the home screen", "An icon that starts this timer", null, false);
            pin.setOnClickListener(v -> {
                if (!TimerShortcuts.pin(a, q)) Toast.makeText(a, "This launcher cannot pin icons", Toast.LENGTH_SHORT).show();
                sheet.dialog.dismiss();
            });
            View delete = Kit.addRow(group);
            Kit.bindRow(delete, R.drawable.csi_trash, "Delete tile", null, null, false);
            delete.setOnClickListener(v -> { Timers.deleteTile(a, q.id); sheet.dialog.dismiss(); });
        }
        sheet.show();
    }

    /** A text watcher that only cares about the new text. */
    static final class SimpleWatcher implements android.text.TextWatcher {
        interface Typed { void text(String s); }
        private final Typed typed;
        SimpleWatcher(Typed typed) { this.typed = typed; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(android.text.Editable s) { typed.text(s.toString()); }
    }
}
