package com.csync.hub;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The Reminders page: one box to add a reminder by typing it, and four ways to look at the same
 * reminders, chosen with a switch that is remembered. Timeline is a week strip and the chosen day
 * as a line with a now mark; Day is hour lines to add into; Week is parts of the day across seven
 * days; Month is dots on days with the chosen day listed below.
 */
final class RemindersScreen {
    private static final String[] VIEWS = {"timeline", "day", "week", "month"};
    private static final String[] VIEW_WORDS = {"Timeline", "Day", "Week", "Month"};
    private static final int[] VIEW_ICONS = {R.drawable.csi_view_list, R.drawable.csi_view_day, R.drawable.csi_view_week, R.drawable.csi_view_month};
    private static final String[][] BANDS = {{"Morning", "5", "12"}, {"Afternoon", "12", "17"}, {"Evening", "17", "21"}, {"Night", "21", "29"}};
    private static final int[] BAND_ICONS = {R.drawable.csi_sun, R.drawable.csi_dusk, R.drawable.csi_moon, R.drawable.csi_night};
    private static final long DAY = 86_400_000L;
    // Wide enough for a twelve-hour time such as "11:59 PM" on one line.
    private static final int TIME_COLUMN = 68;

    private final Activity a;
    private final LinearLayout host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::render;
    private boolean showing;

    private String view;
    private long day;
    private int band = -1;
    private long bandDay;
    private String typed = "";
    private EditText ask;
    private final List<Runnable> live = new ArrayList<>();

    RemindersScreen(Activity a, LinearLayout host) {
        this.a = a;
        this.host = host;
        view = a.getSharedPreferences("reminders_view", Activity.MODE_PRIVATE).getString("view", "timeline");
        day = Reminders.startOfDay(System.currentTimeMillis());
    }

    void show(boolean focusAsk) {
        if (!showing) { Reminders.listen(changed); showing = true; }
        Reminders.tidy(a);
        render();
        tick();
        if (focusAsk && ask != null) {
            ask.requestFocus();
            ask.post(() -> ((android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .showSoftInput(ask, 0));
        }
    }

    void hide() {
        if (showing) Reminders.unlisten(changed);
        showing = false;
        ui.removeCallbacksAndMessages(null);
    }

    // Once a minute the "in 20 min" words and the now marks move.
    private void tick() {
        if (!showing) return;
        for (Runnable r : live) r.run();
        ui.postDelayed(this::tick, 30_000);
    }

    void render() {
        boolean hadFocus = ask != null && ask.hasFocus();
        host.removeAllViews();
        live.clear();
        askBox();
        if (hadFocus) ask.requestFocus();
        LinearLayout seg = new LinearLayout(a);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.topMargin = Kit.dp(a, 14);
        host.addView(seg, segLp);
        int chosen = java.util.Arrays.asList(VIEWS).indexOf(view);
        Kit.segmented(seg, VIEW_ICONS, VIEW_WORDS, Math.max(0, chosen), i -> {
            view = VIEWS[i];
            a.getSharedPreferences("reminders_view", Activity.MODE_PRIVATE).edit().putString("view", view).apply();
            render();
        });
        switch (view) {
            case "day": dayView(); break;
            case "week": weekView(); break;
            case "month": monthView(); break;
            default: timeline();
        }
    }

    // ---- adding ----

    private void askBox() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ask = new EditText(a);
        ask.setHint("Call home at 18:30");
        ask.setSingleLine(true);
        ask.setTextSize(15);
        ask.setTextColor(ContextCompat.getColor(a, R.color.text));
        ask.setHintTextColor(ContextCompat.getColor(a, R.color.dim));
        ask.setBackgroundResource(R.drawable.card_bg);
        ask.setPadding(Kit.dp(a, 14), Kit.dp(a, 12), Kit.dp(a, 14), Kit.dp(a, 12));
        ask.setMinHeight(Kit.dp(a, 48));
        ask.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ask.setImeOptions(EditorInfo.IME_ACTION_DONE);
        ask.setContentDescription("New reminder");
        ask.setText(typed);
        ask.setSelection(typed.length());
        row.addView(ask, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView send = new ImageView(a);
        send.setImageResource(R.drawable.csi_send);
        send.setImageTintList(ColorStateList.valueOf(Kit.accentText(a)));
        send.setPadding(Kit.dp(a, 12), Kit.dp(a, 12), Kit.dp(a, 12), Kit.dp(a, 12));
        GradientDrawable well = new GradientDrawable();
        well.setShape(GradientDrawable.OVAL);
        well.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.surface), Kit.accentText(a), 0.16f));
        send.setBackground(well);
        send.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
        send.setContentDescription("Add the reminder");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(Kit.dp(a, 48), Kit.dp(a, 48));
        sendLp.setMarginStart(Kit.dp(a, 8));
        row.addView(send, sendLp);
        host.addView(row, new LinearLayout.LayoutParams(-1, -2));

        // What the typed words will become, read as they are typed.
        TextView meaning = new TextView(a);
        meaning.setTextAppearance(R.style.Kit_Text_RowSub);
        meaning.setPadding(Kit.dp(a, 4), Kit.dp(a, 6), 0, 0);
        host.addView(meaning);
        Runnable explain = () -> {
            Reminders.Parsed p = Reminders.parse(typed, System.currentTimeMillis(), day);
            meaning.setVisibility(p == null ? View.GONE : View.VISIBLE);
            if (p != null) meaning.setText(p.label + ", " + dayWords(p.at).toLowerCase() + " at " + hm(p.at));
        };
        explain.run();
        ask.addTextChangedListener(new TimersScreen.SimpleWatcher(s -> { typed = s; explain.run(); }));
        Runnable add = () -> {
            Reminders.Parsed p = Reminders.parse(typed, System.currentTimeMillis(), day);
            if (p == null) return;
            // Done typing: the keyboard goes so the new reminder can be seen in its day.
            ((android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(ask.getWindowToken(), 0);
            ask.clearFocus();
            typed = "";
            day = Reminders.startOfDay(p.at);
            Reminders.add(a, p.label, p.at);
            Toast.makeText(a, p.label + ", " + dayWords(p.at).toLowerCase() + " at " + hm(p.at), Toast.LENGTH_SHORT).show();
        };
        send.setOnClickListener(v -> { Kit.tick(v); add.run(); });
        ask.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { add.run(); return true; }
            return false;
        });

        LinearLayout chips = new LinearLayout(a);
        chips.setPadding(0, Kit.dp(a, 4), 0, 0);
        String[][] quick = {{"In an hour", "in 1 hour"}, {"This evening", "at 19:00"}, {"Tomorrow morning", "tomorrow at 9:00"}};
        int[] icons = {R.drawable.csi_clock, R.drawable.csi_moon, R.drawable.csi_sun};
        for (int i = 0; i < quick.length; i++) {
            String words = quick[i][1];
            View chip = Kit.compactButton(a, icons[i], quick[i][0], false, () -> fill(words));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Kit.dp(a, 6));
            chips.addView(chip, lp);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        host.addView(scroll);
    }

    /** Put words into the box after what is already typed, ready to add or finish. */
    private void fill(String words) {
        String now = typed.trim();
        typed = now.isEmpty() ? words : now + " " + words;
        if (ask != null) {
            ask.setText(typed);
            ask.setSelection(now.isEmpty() ? 0 : typed.length());
            ask.requestFocus();
            ((android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(ask, 0);
        }
    }

    // ---- timeline ----

    private void timeline() {
        long today = Reminders.startOfDay(System.currentTimeMillis());
        LinearLayout strip = new LinearLayout(a);
        strip.setPadding(0, Kit.dp(a, 12), 0, 0);
        for (int k = 0; k < 7; k++) {
            long d = addDays(today, k);
            strip.addView(dayButton(d, d == day, d == today, !on(d).isEmpty(), () -> { day = d; render(); }),
                new LinearLayout.LayoutParams(0, Kit.dp(a, 64), 1));
        }
        host.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        Kit.label(host, dayWords(day));
        List<Reminders.Item> list = on(day);
        Reminders.Item next = Reminders.next(a);
        long now = System.currentTimeMillis();
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.VERTICAL);
        host.addView(line, new LinearLayout.LayoutParams(-1, -2));
        boolean nowShown = day != today;
        for (Reminders.Item r : list) {
            if (!nowShown && r.at > now) { line.addView(nowMark()); nowShown = true; }
            line.addView(event(r, next != null && next.id == r.id));
        }
        if (!nowShown) line.addView(nowMark());
        if (list.isEmpty()) quiet(day == today ? "Nothing else today." : "Nothing on " + dayWords(day) + ".");
    }

    private View dayButton(long d, boolean chosen, boolean today, boolean has, Runnable pick) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        if (chosen) {
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(Kit.dp(a, 14));
            fill.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.bg), Kit.accentText(a), 0.16f));
            b.setBackground(fill);
        }
        b.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(d);
        TextView name = new TextView(a);
        name.setText(new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.getTime()));
        name.setTextSize(11.5f);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(chosen ? Kit.accentText(a) : ContextCompat.getColor(a, R.color.dim));
        b.addView(name);
        TextView date = new TextView(a);
        date.setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
        date.setTextSize(16);
        date.setTypeface(android.graphics.Typeface.create("sans-serif-medium", today ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        date.setTextColor(chosen ? Kit.accentText(a) : ContextCompat.getColor(a, R.color.text));
        date.setGravity(Gravity.CENTER);
        b.addView(date);
        View dot = new View(a);
        GradientDrawable mark = new GradientDrawable();
        mark.setShape(GradientDrawable.OVAL);
        mark.setColor(has ? Kit.accentText(a) : 0);
        dot.setBackground(mark);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Kit.dp(a, 5), Kit.dp(a, 5));
        dotLp.topMargin = Kit.dp(a, 3);
        b.addView(dot, dotLp);
        b.setContentDescription(dayWords(d) + (has ? ", has reminders" : ""));
        b.setOnClickListener(v -> pick.run());
        return b;
    }

    private View nowMark() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Kit.dp(a, 6), 0, Kit.dp(a, 6));
        TextView time = new TextView(a);
        time.setTextColor(ContextCompat.getColor(a, R.color.danger));
        time.setTextSize(11.5f);
        time.setSingleLine(true);
        time.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        row.addView(time, new LinearLayout.LayoutParams(Kit.dp(a, TIME_COLUMN), -2));
        View rule = new View(a);
        rule.setBackgroundColor(ContextCompat.getColor(a, R.color.danger));
        row.addView(rule, new LinearLayout.LayoutParams(0, Kit.dp(a, 2), 1));
        Runnable show = () -> time.setText(hm(System.currentTimeMillis()));
        show.run();
        live.add(show);
        return row;
    }

    private View event(Reminders.Item r, boolean next) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Kit.dp(a, 4), 0, Kit.dp(a, 4));
        row.setAlpha(r.rang ? 0.55f : 1f);
        TextView when = new TextView(a);
        when.setText(hm(r.at));
        when.setTextSize(11.5f);
        when.setSingleLine(true);
        when.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
        when.setTextColor(ContextCompat.getColor(a, next ? R.color.text : R.color.dim));
        row.addView(when, new LinearLayout.LayoutParams(Kit.dp(a, TIME_COLUMN), -2));
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Kit.dp(a, 14), Kit.dp(a, 4), Kit.dp(a, 2), Kit.dp(a, 4));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(Kit.dp(a, 14));
        shape.setColor(ContextCompat.getColor(a, R.color.surface));
        shape.setStroke(Kit.dp(a, next ? 2 : 1), next ? Kit.accentText(a) : ContextCompat.getColor(a, R.color.border));
        card.setBackground(shape);
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setMinimumHeight(Kit.dp(a, 48));
        words.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(a);
        label.setText(r.label);
        label.setTextAppearance(R.style.Kit_Text_RowTitle);
        words.addView(label);
        TextView sub = new TextView(a);
        sub.setTextAppearance(R.style.Kit_Text_RowSub);
        words.addView(sub);
        Runnable show = () -> sub.setText(r.rang ? "Rang" : until(r.at));
        show.run();
        live.add(show);
        words.setOnClickListener(v -> sheet(r.id));
        words.setBackgroundResource(Kit.outValue(a));
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(clear(r));
        row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View clear(Reminders.Item r) {
        ImageView x = new ImageView(a);
        x.setImageResource(R.drawable.csi_close);
        x.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.dim)));
        x.setPadding(Kit.dp(a, 14), Kit.dp(a, 14), Kit.dp(a, 14), Kit.dp(a, 14));
        x.setBackgroundResource(borderless());
        x.setContentDescription((r.rang ? "Clear " : "Cancel ") + r.label);
        x.setOnClickListener(v -> Reminders.cancel(a, r.id));
        x.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(a, 48), Kit.dp(a, 48)));
        return x;
    }

    // ---- day ----

    private void dayView() {
        host.addView(stepper(dayWords(day), "Day before", "Day after", step -> { day = addDays(day, step); render(); }));
        int startHour = 6, hours = 18, rowDp = 52;
        FrameLayout grid = new FrameLayout(a);
        LinearLayout lines = new LinearLayout(a);
        lines.setOrientation(LinearLayout.VERTICAL);
        for (int k = 0; k < hours; k++) {
            int hour = (startHour + k) % 24;
            LinearLayout line = new LinearLayout(a);
            line.setGravity(Gravity.TOP);
            line.setBackgroundResource(Kit.outValue(a));
            TextView label = new TextView(a);
            label.setText(String.format(java.util.Locale.ROOT, "%02d:00", hour));
            label.setTextSize(11.5f);
            label.setTextColor(ContextCompat.getColor(a, R.color.dim));
            label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(a, R.font.mono));
            line.addView(label, new LinearLayout.LayoutParams(Kit.dp(a, 52), -2));
            View rule = new View(a);
            rule.setBackgroundColor(ContextCompat.getColor(a, R.color.border));
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, Kit.dp(a, 1), 1);
            ruleLp.topMargin = Kit.dp(a, 8);
            line.addView(rule, ruleLp);
            line.setContentDescription("Add a reminder at " + label.getText());
            // A bare time lands on the day being looked at, so no day words are needed.
            String at = "at " + label.getText();
            line.setOnClickListener(v -> fillAt(at));
            lines.addView(line, new LinearLayout.LayoutParams(-1, Kit.dp(a, rowDp)));
        }
        grid.addView(lines, new FrameLayout.LayoutParams(-1, -2));
        for (Reminders.Item r : on(day)) {
            float h = (r.at - day) / 3_600_000f;
            if (h < startHour || h > startHour + hours) continue;
            LinearLayout block = new LinearLayout(a);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(Kit.dp(a, 10), Kit.dp(a, 4), Kit.dp(a, 8), Kit.dp(a, 4));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Kit.dp(a, 10));
            shape.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.surface), Kit.accentText(a), r.rang ? 0.08f : 0.2f));
            block.setBackground(shape);
            block.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
            TextView label = new TextView(a);
            label.setText(r.label + "  ·  " + hm(r.at));
            label.setTextColor(ContextCompat.getColor(a, R.color.text));
            label.setTextSize(13);
            label.setSingleLine();
            block.addView(label);
            block.setAlpha(r.rang ? 0.6f : 1f);
            block.setContentDescription(r.label + " at " + hm(r.at));
            block.setOnClickListener(v -> sheet(r.id));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Kit.dp(a, rowDp - 8));
            lp.leftMargin = Kit.dp(a, 56);
            lp.topMargin = (int) ((h - startHour) * Kit.dp(a, rowDp)) + Kit.dp(a, 2);
            grid.addView(block, lp);
        }
        if (day == Reminders.startOfDay(System.currentTimeMillis())) {
            View now = new View(a);
            now.setBackgroundColor(ContextCompat.getColor(a, R.color.danger));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Kit.dp(a, 2));
            lp.leftMargin = Kit.dp(a, 52);
            grid.addView(now, lp);
            Runnable place = () -> {
                float h = (System.currentTimeMillis() - day) / 3_600_000f;
                now.setVisibility(h < startHour || h > startHour + hours ? View.GONE : View.VISIBLE);
                now.setTranslationY((h - startHour) * Kit.dp(a, rowDp) + Kit.dp(a, 8));
            };
            place.run();
            live.add(place);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = Kit.dp(a, 6);
        host.addView(grid, gridLp);
        help("Tap an empty hour to add a reminder there.");
    }

    private void fillAt(String when) {
        typed = typed.replaceAll("(?i)\\s*at \\d{1,2}(:\\d{2})?$", "").trim();
        fill(when);
    }

    // ---- week ----

    private void weekView() {
        long today = Reminders.startOfDay(System.currentTimeMillis());
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(8);
        // The first column holds the part-of-day names, so it is wider than a day.
        grid.addView(new View(a), cell(0, 1.5f));
        for (int k = 0; k < 7; k++) {
            long d = addDays(today, k);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(d);
            TextView head = new TextView(a);
            head.setGravity(Gravity.CENTER);
            head.setText(new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.getTime()) + "\n" + cal.get(Calendar.DAY_OF_MONTH));
            head.setTextSize(12);
            head.setTextColor(d == today ? Kit.accentText(a) : ContextCompat.getColor(a, R.color.dim));
            head.setBackgroundResource(Kit.outValue(a));
            head.setMinHeight(Kit.dp(a, 48));
            head.setContentDescription("Open " + dayWords(d));
            head.setOnClickListener(v -> openInTimeline(d));
            grid.addView(head, cell(0, 1f));
        }
        for (int b = 0; b < BANDS.length; b++) {
            LinearLayout label = new LinearLayout(a);
            label.setOrientation(LinearLayout.VERTICAL);
            label.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(a);
            icon.setImageResource(BAND_ICONS[b]);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.dim)));
            label.addView(icon, new LinearLayout.LayoutParams(Kit.dp(a, 16), Kit.dp(a, 16)));
            TextView words = new TextView(a);
            words.setText(BANDS[b][0]);
            words.setTextSize(9.5f);
            words.setSingleLine(true);
            words.setGravity(Gravity.CENTER);
            words.setTextColor(ContextCompat.getColor(a, R.color.dim));
            label.addView(words);
            grid.addView(label, cell(Kit.dp(a, 56), 1.5f));
            for (int k = 0; k < 7; k++) {
                long d = addDays(today, k);
                int bandIndex = b;
                List<Reminders.Item> in = inBand(d, b);
                LinearLayout c = new LinearLayout(a);
                c.setGravity(Gravity.CENTER);
                c.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(Kit.dp(a, 8));
                boolean picked = band == b && bandDay == d;
                shape.setColor(picked ? androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.surface), Kit.accentText(a), 0.2f)
                    : ContextCompat.getColor(a, d == today ? R.color.surface : R.color.surface2));
                c.setBackground(shape);
                c.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
                for (int n = 0; n < Math.min(3, in.size()); n++) {
                    View dot = new View(a);
                    GradientDrawable mark = new GradientDrawable();
                    mark.setShape(GradientDrawable.OVAL);
                    mark.setColor(in.get(n).rang ? ContextCompat.getColor(a, R.color.faint) : Kit.accentText(a));
                    dot.setBackground(mark);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(a, 6), Kit.dp(a, 6));
                    lp.setMargins(0, Kit.dp(a, 1), 0, Kit.dp(a, 1));
                    c.addView(dot, lp);
                }
                c.setContentDescription(dayWords(d) + " " + BANDS[b][0].toLowerCase() + ", " + in.size());
                c.setOnClickListener(v -> { band = bandIndex; bandDay = d; render(); });
                GridLayout.LayoutParams lp = cell(Kit.dp(a, 56), 1f);
                lp.setMargins(Kit.dp(a, 2), Kit.dp(a, 2), Kit.dp(a, 2), Kit.dp(a, 2));
                grid.addView(c, lp);
            }
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = Kit.dp(a, 10);
        host.addView(grid, gridLp);
        if (band < 0) { help("Tap a cell to see it, or a day to open it."); return; }
        Kit.label(host, dayWords(bandDay) + " " + BANDS[band][0].toLowerCase());
        List<Reminders.Item> chosen = inBand(bandDay, band);
        if (chosen.isEmpty()) quiet("Nothing then. Use the box above to add one.");
        else rows(chosen);
    }

    private GridLayout.LayoutParams cell(int heightPx, float weight) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, weight));
        lp.width = 0;
        if (heightPx > 0) lp.height = heightPx;
        return lp;
    }

    private List<Reminders.Item> inBand(long d, int b) {
        List<Reminders.Item> out = new ArrayList<>();
        int from = Integer.parseInt(BANDS[b][1]), to = Integer.parseInt(BANDS[b][2]);
        for (Reminders.Item r : Reminders.all(a)) {
            // Night runs past midnight, so the small hours count as the evening before's night.
            long owner = Reminders.startOfDay(r.at);
            float h = (r.at - owner) / 3_600_000f;
            if (h < 5) { h += 24; owner = addDays(owner, -1); }
            if (owner == d && h >= from && h < to) out.add(r);
        }
        return out;
    }

    // ---- month ----

    private void monthView() {
        Calendar sel = Calendar.getInstance();
        sel.setTimeInMillis(day);
        String title = new java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(sel.getTime());
        host.addView(stepper(title, "Month before", "Month after", step -> {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(day);
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, step);
            day = Reminders.startOfDay(c.getTimeInMillis());
            render();
        }));
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(7);
        String[] heads = {"M", "T", "W", "T", "F", "S", "S"};
        for (String h : heads) {
            TextView t = new TextView(a);
            t.setText(h);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            t.setTextColor(ContextCompat.getColor(a, R.color.dim));
            grid.addView(t, cell(Kit.dp(a, 24), 1f));
        }
        Calendar first = (Calendar) sel.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int startCol = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int month = sel.get(Calendar.MONTH);
        long today = Reminders.startOfDay(System.currentTimeMillis());
        for (int k = 0; k < 42; k++) {
            Calendar c = (Calendar) first.clone();
            c.add(Calendar.DAY_OF_MONTH, k - startCol);
            long d = Reminders.startOfDay(c.getTimeInMillis());
            boolean out = c.get(Calendar.MONTH) != month;
            LinearLayout b = new LinearLayout(a);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            if (d == day) {
                GradientDrawable fill = new GradientDrawable();
                fill.setShape(GradientDrawable.OVAL);
                fill.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(a, R.color.bg), Kit.accentText(a), 0.2f));
                b.setBackground(fill);
            }
            b.setForeground(ContextCompat.getDrawable(a, Kit.outValue(a)));
            TextView n = new TextView(a);
            n.setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
            n.setTextSize(14);
            n.setTypeface(android.graphics.Typeface.create("sans-serif-medium", d == today ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            n.setTextColor(d == today ? Kit.accentText(a) : ContextCompat.getColor(a, out ? R.color.faint : R.color.text));
            n.setGravity(Gravity.CENTER);
            b.addView(n);
            boolean has = !on(d).isEmpty();
            View dot = new View(a);
            GradientDrawable mark = new GradientDrawable();
            mark.setShape(GradientDrawable.OVAL);
            mark.setColor(has ? Kit.accentText(a) : 0);
            dot.setBackground(mark);
            b.addView(dot, new LinearLayout.LayoutParams(Kit.dp(a, 5), Kit.dp(a, 5)));
            b.setContentDescription(dayWords(d) + (has ? ", has reminders" : ""));
            b.setOnClickListener(v -> { day = d; render(); });
            grid.addView(b, cell(Kit.dp(a, 48), 1f));
        }
        host.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Kit.label(head, dayWords(day));
        ((LinearLayout.LayoutParams) label.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) label.getLayoutParams()).weight = 1;
        head.addView(Kit.compactButton(a, R.drawable.csi_view_list, "Timeline", false, () -> openInTimeline(day)));
        host.addView(head, new LinearLayout.LayoutParams(-1, -2));
        List<Reminders.Item> list = on(day);
        if (list.isEmpty()) quiet("Nothing on this day.");
        else rows(list);
    }

    private void openInTimeline(long d) {
        day = d;
        view = "timeline";
        a.getSharedPreferences("reminders_view", Activity.MODE_PRIVATE).edit().putString("view", view).apply();
        render();
    }

    // ---- shared pieces ----

    private void rows(List<Reminders.Item> list) {
        LinearLayout group = Kit.group(host);
        for (Reminders.Item r : list) {
            View row = Kit.addRow(group);
            Kit.bindRow(row, R.drawable.csi_bell, r.label, hm(r.at) + (r.rang ? ", rang" : ""),
                r.rang ? null : until(r.at).replace("in ", ""), false);
            row.setAlpha(r.rang ? 0.55f : 1f);
            row.setOnClickListener(v -> sheet(r.id));
            Kit.rowAction(row, R.drawable.csi_close, (r.rang ? "Clear " : "Cancel ") + r.label, v -> Reminders.cancel(a, r.id));
        }
    }

    interface Step { void by(int step); }

    private View stepper(String title, String before, String after, Step step) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Kit.dp(a, 8), 0, 0);
        row.addView(arrow(R.drawable.csi_back, before, () -> step.by(-1)));
        TextView t = new TextView(a);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        t.setTextAppearance(R.style.Kit_Text_RowTitle);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        View fwd = arrow(R.drawable.csi_back, after, () -> step.by(1));
        fwd.setRotation(180);
        row.addView(fwd);
        return row;
    }

    private View arrow(int icon, String spoken, Runnable go) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(a, R.color.text)));
        b.setPadding(Kit.dp(a, 13), Kit.dp(a, 13), Kit.dp(a, 13), Kit.dp(a, 13));
        b.setBackgroundResource(borderless());
        b.setContentDescription(spoken);
        b.setOnClickListener(v -> go.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(a, 48), Kit.dp(a, 48)));
        return b;
    }

    private void quiet(String words) {
        TextView t = new TextView(a);
        t.setText(words);
        t.setTextAppearance(R.style.Kit_Text_RowSub);
        t.setPadding(0, Kit.dp(a, 8), 0, 0);
        host.addView(t);
    }

    private void help(String words) { quiet(words); }

    private int borderless() {
        android.util.TypedValue value = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId;
    }

    /** Change one reminder: what, which day and time, snooze it, or delete it. */
    private void sheet(long id) {
        Reminders.Item r = Reminders.find(a, id);
        if (r == null) return;
        Kit.Sheet sheet = new Kit.Sheet(a, r.label, dayWords(r.at) + " at " + hm(r.at) + (r.rang ? ", rang" : ""));
        EditText label = new EditText(a);
        label.setText(r.label);
        label.setSingleLine(true);
        label.setTextSize(15);
        label.setTextColor(ContextCompat.getColor(a, R.color.text));
        label.setBackgroundResource(R.drawable.card_bg);
        label.setPadding(Kit.dp(a, 14), Kit.dp(a, 12), Kit.dp(a, 14), Kit.dp(a, 12));
        label.setMinHeight(Kit.dp(a, 48));
        label.setContentDescription("What to remember");
        sheet.rows.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout group = Kit.group(sheet.rows);
        ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = Kit.dp(a, 12);
        View date = Kit.addRow(group);
        Kit.bindRow(date, R.drawable.csi_view_month, "Day", dayWords(r.at), null, true);
        date.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(r.at);
            new android.app.DatePickerDialog(a, (p, y, m, d) -> {
                Calendar n = Calendar.getInstance();
                n.setTimeInMillis(r.at);
                n.set(y, m, d);
                Reminders.edit(a, id, label.getText().toString(), n.getTimeInMillis());
                sheet.dialog.dismiss();
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        View time = Kit.addRow(group);
        Kit.bindRow(time, R.drawable.csi_clock, "Time", hm(r.at), null, true);
        time.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(r.at);
            new android.app.TimePickerDialog(a, (p, h, m) -> {
                Calendar n = Calendar.getInstance();
                n.setTimeInMillis(r.at);
                n.set(Calendar.HOUR_OF_DAY, h);
                n.set(Calendar.MINUTE, m);
                n.set(Calendar.SECOND, 0);
                Reminders.edit(a, id, label.getText().toString(), n.getTimeInMillis());
                sheet.dialog.dismiss();
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(a)).show();
        });
        Kit.label(sheet.rows, "Again");
        LinearLayout again = new LinearLayout(a);
        int[][] snoozes = {{10}, {60}};
        String[] words = {"In 10 minutes", "In an hour"};
        int[] icons = {R.drawable.csi_snooze, R.drawable.csi_clock};
        for (int i = 0; i < 2; i++) {
            int minutes = snoozes[i][0];
            View b = Kit.compactButton(a, icons[i], words[i], false, () -> {
                Reminders.snooze(a, id, minutes);
                sheet.dialog.dismiss();
                Reminders.Item moved = Reminders.find(a, id);
                if (moved != null) Toast.makeText(a, moved.label + " again at " + hm(moved.at), Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Kit.dp(a, 8));
            again.addView(b, lp);
        }
        sheet.rows.addView(again);
        LinearLayout danger = Kit.group(sheet.rows);
        ((LinearLayout.LayoutParams) danger.getLayoutParams()).topMargin = Kit.dp(a, 12);
        View delete = Kit.addRow(danger);
        Kit.bindRow(delete, R.drawable.csi_trash, "Delete", null, null, false);
        delete.setOnClickListener(v -> { Reminders.cancel(a, id); sheet.dialog.dismiss(); });
        sheet.dialog.setOnDismissListener(d -> {
            Reminders.Item still = Reminders.find(a, id);
            String now = label.getText().toString().trim();
            if (still != null && !now.isEmpty() && !now.equals(still.label)) Reminders.edit(a, id, now, 0);
        });
        sheet.show();
    }

    // ---- words for times ----

    private List<Reminders.Item> on(long d) {
        List<Reminders.Item> out = new ArrayList<>();
        for (Reminders.Item r : Reminders.all(a)) if (Reminders.startOfDay(r.at) == d) out.add(r);
        return out;
    }

    private static long addDays(long d, int n) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(d);
        c.add(Calendar.DAY_OF_MONTH, n);
        return Reminders.startOfDay(c.getTimeInMillis());
    }

    private String hm(long t) { return android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date(t)); }

    static String dayWords(long t) {
        long today = Reminders.startOfDay(System.currentTimeMillis()), d = Reminders.startOfDay(t);
        long diff = Math.round((d - today) / (double) DAY);
        if (diff == 0) return "Today";
        if (diff == 1) return "Tomorrow";
        if (diff == -1) return "Yesterday";
        return new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(new java.util.Date(t));
    }

    private static String until(long t) {
        long m = Math.max(0, Math.round((t - System.currentTimeMillis()) / 60_000.0));
        if (m < 60) return "in " + m + " min";
        long h = m / 60;
        if (h >= 24) return "in " + (h / 24) + (h / 24 == 1 ? " day" : " days");
        return "in " + h + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min");
    }
}
