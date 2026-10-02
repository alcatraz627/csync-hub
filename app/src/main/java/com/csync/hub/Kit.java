package com.csync.hub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * The app's shared building blocks, matched to the clickthrough mock.
 *
 * Screens build rows, capability cards, section headings, status dots and the top
 * bar through these helpers instead of styling views themselves, so one change here
 * reaches every page. Layouts live in res/layout/kit_*.xml and sizes in values/kit.xml.
 */
final class Kit {
    private Kit() {}

    /** One icon per concept, so two different things never share a glyph. */
    static final class Icon {
        static final int HOME = R.drawable.csi_home, MEDIA = R.drawable.csi_media,
            SHARE = R.drawable.csi_share, CHAT = R.drawable.csi_chat, MORE = R.drawable.csi_more,
            CAMERA = R.drawable.csi_camera, SEARCH = R.drawable.csi_search,
            DISPLAY = R.drawable.csi_screen, HISTORY = R.drawable.csi_history,
            FILES = R.drawable.csi_files, ACCESS = R.drawable.csi_access,
            TOOLS = R.drawable.csi_tools, SETTINGS = R.drawable.csi_settings,
            NOTES = R.drawable.csi_note, DEVICE = R.drawable.csi_device,
            FOLDER = R.drawable.csi_folder, FILE = R.drawable.csi_file, VIDEO = R.drawable.csi_video,
            PHOTO = R.drawable.csi_photo, VOLUME = R.drawable.csi_volume, SPEED = R.drawable.csi_speed,
            ROTATE = R.drawable.csi_rotate, LOOP = R.drawable.csi_loop, SOURCE = R.drawable.csi_source;
        private Icon() {}
    }

    enum Status { GOOD, WARN, BAD, IDLE }

    static int statusColor(Context c, Status s) {
        switch (s) {
            case GOOD: return ContextCompat.getColor(c, R.color.online);
            case WARN: return ContextCompat.getColor(c, R.color.warn);
            case BAD: return ContextCompat.getColor(c, R.color.danger);
            default: return ContextCompat.getColor(c, R.color.offline);
        }
    }

    static void setStatus(View dot, Status s) {
        dot.setBackgroundTintList(ColorStateList.valueOf(statusColor(dot.getContext(), s)));
        // A live thing breathes; everything else holds still.
        dot.animate().cancel();
        if (s == Status.GOOD) {
            android.animation.ObjectAnimator breath = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f);
            breath.setDuration(1400);
            breath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            breath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            breath.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            dot.setTag(R.id.kit_dot, breath);
            breath.start();
        } else {
            Object held = dot.getTag(R.id.kit_dot);
            if (held instanceof android.animation.Animator) ((android.animation.Animator) held).cancel();
            dot.setAlpha(1f);
        }
    }

    // ---- rows ----

    static View row(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.kit_row, parent, false);
    }

    /** Fill a row. A null subtitle or end label hides it; the chevron shows when the row opens something. */
    static View bindRow(View row, int icon, CharSequence title, CharSequence sub, CharSequence end,
                        boolean opens) {
        ((ImageView) row.findViewById(R.id.kit_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.kit_title)).setText(title);
        setOptional(row.findViewById(R.id.kit_sub), sub);
        TextView reading = row.findViewById(R.id.kit_end);
        // The mono face is set on the view itself: named in the layout's style it was not taken up.
        reading.setTextAppearance(R.style.Kit_Text_Meta);
        reading.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(row.getContext(), R.font.mono));
        setOptional(reading, end);
        row.findViewById(R.id.kit_chevron).setVisibility(opens ? View.VISIBLE : View.GONE);
        // The row is spoken as one thing, so everything written on it goes into what is said.
        row.setContentDescription(spokenRow(title, sub, end));
        return row;
    }

    private static String spokenRow(CharSequence title, CharSequence sub, CharSequence end) {
        StringBuilder said = new StringBuilder(title);
        if (sub != null && sub.length() > 0) said.append(", ").append(sub);
        if (end != null && end.length() > 0) said.append(", ").append(end);
        return said.toString();
    }

    /** Show a quiet icon button at the row's end, for a row that has a second action of its own. */
    static void rowAction(View row, int icon, String label, View.OnClickListener click) {
        ImageView action = row.findViewById(R.id.kit_action);
        action.setImageResource(icon);
        action.setContentDescription(label);
        action.setOnClickListener(click);
        action.setVisibility(View.VISIBLE);
    }

    /** Put a status at the row's end: a dot in the status colour, then the words. */
    static void rowStatus(View row, Status status, CharSequence words) {
        TextView end = row.findViewById(R.id.kit_end);
        Context c = row.getContext();
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(statusColor(c, status));
        dot.setBounds(0, 0, dp(c, 8), dp(c, 8));
        end.setCompoundDrawablesRelative(dot, null, null, null);
        end.setCompoundDrawablePadding(dp(c, 6));
        end.setText(words);
        end.setVisibility(View.VISIBLE);
        TextView sub = row.findViewById(R.id.kit_sub);
        row.setContentDescription(spokenRow(((TextView) row.findViewById(R.id.kit_title)).getText(),
            sub.getVisibility() == View.VISIBLE ? sub.getText() : null, words));
    }

    interface Flip { void to(boolean on); }

    /** Put a switch at the row's end for a setting that is on or off. A tap anywhere on the row flips it. */
    static void rowToggle(View row, boolean on, Flip flip) {
        Context c = row.getContext();
        row.findViewById(R.id.kit_chevron).setVisibility(View.GONE);
        com.google.android.material.materialswitch.MaterialSwitch toggle =
            new com.google.android.material.materialswitch.MaterialSwitch(c);
        toggle.setChecked(on);
        // The row takes the tap, so the switch and the words beside it act as one control.
        toggle.setClickable(false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((LinearLayout) row).addView(toggle);
        CharSequence title = ((TextView) row.findViewById(R.id.kit_title)).getText();
        row.setContentDescription(title + (on ? ", on" : ", off"));
        row.setOnClickListener(v -> {
            toggle.setChecked(!toggle.isChecked());
            row.setContentDescription(title + (toggle.isChecked() ? ", on" : ", off"));
            tick(row);
            flip.to(toggle.isChecked());
        });
    }

    /** The line under a tab that marks it as the chosen one. */
    static android.graphics.drawable.Drawable underline(Context c, int color) {
        android.graphics.drawable.LayerDrawable line = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(color)});
        line.setLayerGravity(0, android.view.Gravity.BOTTOM);
        line.setLayerHeight(0, dp(c, 2));
        return line;
    }

    /** A card that holds rows separated by thin dividers, like every list in the mock. */
    static LinearLayout group(ViewGroup parent) {
        Context c = parent.getContext();
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.card_bg);
        group.setClipToOutline(true);
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    /** Add a fresh row to a group, with a divider above it when it is not the first. */
    static View addRow(LinearLayout group) {
        if (group.getChildCount() > 0) {
            View divider = new View(group.getContext());
            divider.setBackgroundColor(ContextCompat.getColor(group.getContext(), R.color.border));
            group.addView(divider, new LinearLayout.LayoutParams(-1, dp(group.getContext(), 1)));
        }
        View row = row(group);
        group.addView(row);
        return row;
    }

    /** A section label between groups, such as FOLDERS or FILES. */
    static TextView label(ViewGroup parent, CharSequence text) {
        Context c = parent.getContext();
        TextView label = new TextView(c);
        label.setTextAppearance(R.style.Kit_Text_Section);
        label.setText(text);
        label.setTag("kit-title");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(c, 16);
        params.bottomMargin = dp(c, 8);
        parent.addView(label, params);
        return label;
    }

    // ---- bottom drawers ----

    /** One choice in a drawer. */
    static final class Action {
        final int icon; final String label; final String sub; final Runnable run;
        // True when the choice leads to another question, which the row shows with a chevron.
        final boolean opens;
        // What the choice is set to now, written at the row's end. Null leaves the end empty.
        String value;
        Action(int icon, String label, String sub, Runnable run) { this(icon, label, sub, run, false); }
        Action(int icon, String label, String sub, Runnable run, boolean opens) {
            this.icon = icon; this.label = label; this.sub = sub; this.run = run; this.opens = opens;
        }
        Action value(String now) { value = now; return this; }
    }

    /** A labelled set of choices. A null label draws the set without a heading. */
    static final class Section {
        final String label; final java.util.List<Action> actions;
        Section(String label, java.util.List<Action> actions) { this.label = label; this.actions = actions; }
    }

    /**
     * Draw labelled sets of choices as groups of rows, on a page or inside a drawer.
     * {@code before} runs ahead of whichever choice is tapped; a drawer uses it to close itself.
     */
    static void sections(ViewGroup parent, java.util.List<Section> sections, Runnable before) {
        boolean first = true;
        for (Section section : sections) {
            if (section.actions.isEmpty()) continue;
            if (section.label != null) label(parent, section.label);
            else if (!first) parent.addView(new View(parent.getContext()),
                new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 16)));
            first = false;
            LinearLayout rows = group(parent);
            for (Action action : section.actions) {
                View row = addRow(rows);
                bindRow(row, action.icon, action.label, action.sub, action.value, action.opens);
                row.setOnClickListener(v -> { if (before != null) before.run(); action.run.run(); });
            }
        }
    }

    /** Open a bottom drawer whose choices come in labelled sets. */
    static com.google.android.material.bottomsheet.BottomSheetDialog sheet(
            Context c, CharSequence title, CharSequence sub, java.util.List<Section> sections) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        sections(body.findViewById(R.id.kit_rows), sections, dialog::dismiss);
        dialog.setContentView(scrolling(body));
        openFully(dialog);
        dialog.show();
        return dialog;
    }

    /**
     * A drawer of choices that are switched on and off, like a checklist. A tap changes the
     * choice and redraws the rows where they are, so the list stays open and does not jump to
     * its top. A choice that leads to another question still closes the drawer first.
     */
    static com.google.android.material.bottomsheet.BottomSheetDialog toggleSheet(
            Context c, CharSequence title, CharSequence sub,
            java.util.function.Supplier<java.util.List<Section>> build) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        LinearLayout rows = body.findViewById(R.id.kit_rows);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            rows.removeAllViews();
            boolean first = true;
            for (Section section : build.get()) {
                if (section.actions.isEmpty()) continue;
                if (section.label != null) label(rows, section.label);
                else if (!first) rows.addView(new View(c), new LinearLayout.LayoutParams(-1, dp(c, 16)));
                first = false;
                LinearLayout group = group(rows);
                for (Action action : section.actions) {
                    View row = addRow(group);
                    bindRow(row, action.icon, action.label, action.sub, action.value, action.opens);
                    row.setOnClickListener(v -> {
                        if (action.opens) { dialog.dismiss(); action.run.run(); return; }
                        action.run.run();
                        draw[0].run();
                    });
                }
            }
        };
        draw[0].run();
        dialog.setContentView(scrolling(body));
        openFully(dialog);
        dialog.show();
        return dialog;
    }

    /** Open a tall drawer at its full height, so its last rows are not hidden behind a half-open edge. */
    private static void openFully(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Ask before something is lost or replaced. The drawer has two buttons: one keeps things
     * as they are, the other goes ahead and is named for what it does.
     */
    static com.google.android.material.bottomsheet.BottomSheetDialog confirm(
            Context c, CharSequence title, CharSequence sub, int icon, String go, Runnable run) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        buttons.addView(button(c, R.drawable.csi_back, "Keep it", R.color.text, dialog::dismiss), half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, -2, 1);
        other.setMarginStart(dp(c, 8));
        buttons.addView(button(c, icon, go, R.color.danger, () -> { dialog.dismiss(); run.run(); }), other);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.topMargin = dp(c, 6);
        ((LinearLayout) body.findViewById(R.id.kit_rows)).addView(buttons, row);
        dialog.setContentView(scrolling(body));
        dialog.show();
        return dialog;
    }

    /** An outlined button with an icon and words in one colour, as the mock draws every plain button. */
    static View button(Context c, int icon, String words, int colorRes, Runnable click) {
        return button(c, icon, words, click, ContextCompat.getColor(c, colorRes));
    }

    /** The button that confirms a choice inside a drawer: tinted with the accent, never filled. */
    static View tonalButton(Context c, int icon, String words, Runnable click) {
        View button = button(c, icon, words, click, accentText(c));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(androidx.core.graphics.ColorUtils.blendARGB(
            ContextCompat.getColor(c, R.color.surface), accentText(c), 0.16f));
        shape.setCornerRadius(dp(c, 14));
        button.setBackground(shape);
        return button;
    }

    private static View button(Context c, int icon, String words, Runnable click, int color) {
        LinearLayout button = new LinearLayout(c);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(android.view.Gravity.CENTER);
        button.setMinimumHeight(dp(c, 48));
        button.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(ContextCompat.getColor(c, R.color.surface));
        shape.setStroke(dp(c, 1), ContextCompat.getColor(c, R.color.border));
        shape.setCornerRadius(dp(c, 14));
        button.setBackground(shape);
        button.setForeground(ContextCompat.getDrawable(c, outValue(c)));
        button.setClipToOutline(true);
        ImageView symbol = new ImageView(c);
        symbol.setImageResource(icon);
        symbol.setImageTintList(ColorStateList.valueOf(color));
        button.addView(symbol, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        TextView label = new TextView(c);
        label.setText(words);
        label.setSingleLine();
        label.setTextColor(color);
        label.setTextSize(14.5f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(dp(c, 8), 0, 0, 0);
        button.addView(label);
        button.setContentDescription(words);
        button.setOnClickListener(v -> click.run());
        button.setTag("kit-action");
        return button;
    }

    /** The one filled button a page may carry, for Send, Save, Install or Create. */
    static View primaryButton(Context c, int icon, String words, Runnable click) {
        View button = button(c, icon, words, R.color.onAccent, click);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(accentFill(c));
        shape.setCornerRadius(dp(c, 14));
        button.setBackground(shape);
        return button;
    }

    /**
     * A small pill for an action that sits beside other content, such as Use under a colour
     * or Save under a picker. It looks compact but its touch area stays 48dp tall. Filled with
     * the accent when it is the page's main action, tinted otherwise.
     */
    static View compactButton(Context c, int icon, String words, boolean main, Runnable click) {
        android.widget.FrameLayout touch = new android.widget.FrameLayout(c);
        touch.setMinimumHeight(dp(c, 48));
        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setPadding(dp(c, 12), 0, dp(c, 14), 0);
        int ink = main ? ContextCompat.getColor(c, R.color.onAccent) : accentText(c);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(main ? accentFill(c) : androidx.core.graphics.ColorUtils.blendARGB(
            ContextCompat.getColor(c, R.color.surface), accentText(c), 0.16f));
        shape.setCornerRadius(dp(c, 18));
        pill.setBackground(shape);
        pill.setForeground(ContextCompat.getDrawable(c, outValue(c)));
        pill.setClipToOutline(true);
        if (icon != 0) {
            ImageView symbol = new ImageView(c);
            symbol.setImageResource(icon);
            symbol.setImageTintList(ColorStateList.valueOf(ink));
            pill.addView(symbol, new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));
        }
        TextView label = new TextView(c);
        label.setText(words);
        label.setSingleLine();
        label.setTextColor(ink);
        label.setTextSize(13.5f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(icon != 0 ? dp(c, 6) : 0, 0, 0, 0);
        pill.addView(label);
        touch.addView(pill, new android.widget.FrameLayout.LayoutParams(-2, dp(c, 36), android.view.Gravity.CENTER_VERTICAL));
        touch.setContentDescription(words);
        touch.setOnClickListener(v -> click.run());
        touch.setTag("kit-action");
        return touch;
    }

    /** Change the words on a compact button made by {@link #compactButton}. */
    static void compactButtonText(View button, String words) {
        LinearLayout pill = (LinearLayout) ((ViewGroup) button).getChildAt(0);
        ((TextView) pill.getChildAt(pill.getChildCount() - 1)).setText(words);
        button.setContentDescription(words);
    }

    interface Pick { void at(int index); }

    /**
     * Views of one place, side by side, with a line under the chosen one. The strip is drawn
     * into {@code host}, replacing what was there, so a caller redraws it by calling again.
     */
    static void tabs(LinearLayout host, int[] icons, String[] labels, int selected, Pick pick) {
        tabs(host, icons, labels, selected, -1, pick);
    }

    /** The same, with a green dot after the name of the tab at {@code marked}, the one holding the current choice. */
    static void tabs(LinearLayout host, int[] icons, String[] labels, int selected, int marked, Pick pick) {
        Context c = host.getContext();
        host.removeAllViews();
        host.setOrientation(LinearLayout.VERTICAL);
        LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int accent = accentText(c), dim = ContextCompat.getColor(c, R.color.dim);
        for (int i = 0; i < labels.length; i++) {
            boolean chosen = i == selected;
            LinearLayout tab = new LinearLayout(c);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setMinimumHeight(dp(c, 48));
            ImageView symbol = new ImageView(c);
            symbol.setImageResource(icons[i]);
            symbol.setImageTintList(ColorStateList.valueOf(chosen ? accent : dim));
            tab.addView(symbol, new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));
            TextView label = new TextView(c);
            label.setText(labels[i]);
            label.setTextSize(13);
            label.setSingleLine();
            label.setTextColor(chosen ? accent : dim);
            label.setTypeface(null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            label.setPadding(dp(c, 7), 0, 0, 0);
            tab.addView(label);
            if (i == marked) {
                View dot = new View(c);
                dot.setBackgroundResource(R.drawable.kit_dot);
                dot.setBackgroundTintList(ColorStateList.valueOf(statusColor(c, Status.GOOD)));
                LinearLayout.LayoutParams after = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 7));
                after.setMarginStart(dp(c, 6));
                tab.addView(dot, after);
            }
            tab.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            if (chosen) tab.setBackground(underline(c, accent));
            else tab.setBackgroundResource(outValue(c));
            tab.setContentDescription(labels[i] + (chosen ? ", selected" : "") + (i == marked ? ", holds your choice" : ""));
            int index = i;
            tab.setOnClickListener(v -> { if (index != selected) pick.at(index); });
            strip.addView(tab, new LinearLayout.LayoutParams(-2, -2, 1));
        }
        // The tabs share the width while they fit; at a large text size the strip scrolls sideways instead of wrapping a word.
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(c);
        scroller.setFillViewport(true);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setTag("kit-action");
        scroller.addView(strip, new ViewGroup.LayoutParams(-2, -2));
        host.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(c);
        line.setBackgroundColor(ContextCompat.getColor(c, R.color.border));
        host.addView(line, new LinearLayout.LayoutParams(-1, dp(c, 1)));
    }

    /** What a page shows when it has nothing: a symbol, one line that says so, one that says why, and a way forward. */
    static void empty(ViewGroup parent, int icon, String title, String text, View action) {
        Context c = parent.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 16), dp(c, 40), dp(c, 16), dp(c, 16));
        ImageView symbol = new ImageView(c);
        symbol.setImageResource(icon);
        symbol.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.dim)));
        box.addView(symbol, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
        TextView heading = new TextView(c);
        heading.setTextAppearance(R.style.Kit_Text_RowTitle);
        heading.setText(title);
        heading.setGravity(android.view.Gravity.CENTER);
        heading.setPadding(0, dp(c, 12), 0, 0);
        box.addView(heading);
        if (text != null && !text.isEmpty()) {
            TextView words = new TextView(c);
            words.setTextAppearance(R.style.Kit_Text_RowSub);
            words.setText(text);
            words.setGravity(android.view.Gravity.CENTER);
            words.setPadding(0, dp(c, 4), 0, 0);
            box.addView(words);
        }
        if (action != null) {
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-2, -2);
            below.topMargin = dp(c, 16);
            box.addView(action, below);
        }
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A drawer that shows one picture, for looking at an image without leaving the page. */
    static void pictureSheet(Context c, CharSequence title, android.graphics.Bitmap picture) {
        pictureSheet(c, title, null, picture, 0, null, null);
    }

    /**
     * A drawer that shows one picture with one thing to do about it under it. A null picture
     * leaves the picture out, and a null {@code go} leaves the button out.
     */
    static void pictureSheet(Context c, CharSequence title, CharSequence sub, android.graphics.Bitmap picture,
                             int icon, String go, Runnable run) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        LinearLayout rows = body.findViewById(R.id.kit_rows);
        if (picture != null) {
            ImageView image = new ImageView(c);
            image.setImageBitmap(picture);
            image.setAdjustViewBounds(true);
            image.setContentDescription(title);
            image.setBackgroundResource(R.drawable.card_bg);
            image.setClipToOutline(true);
            rows.addView(image, new LinearLayout.LayoutParams(-1, -2));
        }
        if (go != null) {
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
            below.topMargin = dp(c, picture == null ? 0 : 12);
            rows.addView(button(c, icon, go, R.color.text, () -> { dialog.dismiss(); run.run(); }), below);
        }
        dialog.setContentView(scrolling(body));
        openFully(dialog);
        dialog.show();
    }

    /** A drawer a page fills itself: add views to {@code rows}, then {@code show()}. */
    static final class Sheet {
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog;
        final LinearLayout rows;
        private final View body;
        Sheet(Context c, CharSequence title, CharSequence sub) {
            dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(c);
            body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
            ((TextView) body.findViewById(R.id.kit_title)).setText(title);
            setOptional(body.findViewById(R.id.kit_sub), sub);
            rows = body.findViewById(R.id.kit_rows);
        }
        void show() {
            dialog.setContentView(scrolling(body));
            openFully(dialog);
            dialog.show();
        }
    }

    /**
     * A choice among a few options in one control, drawn as a row of pills with the chosen one
     * filled. Drawn into {@code host}, replacing what was there, so a caller redraws it by calling again.
     */
    static void segmented(LinearLayout host, int[] icons, String[] labels, int selected, Pick pick) {
        Context c = host.getContext();
        host.removeAllViews();
        host.setOrientation(LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(ContextCompat.getColor(c, R.color.surface2));
        track.setCornerRadius(dp(c, 14));
        host.setBackground(track);
        host.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        int accent = accentText(c), dim = ContextCompat.getColor(c, R.color.dim);
        for (int i = 0; i < labels.length; i++) {
            boolean chosen = i == selected;
            LinearLayout option = new LinearLayout(c);
            option.setOrientation(LinearLayout.HORIZONTAL);
            option.setGravity(android.view.Gravity.CENTER);
            option.setMinimumHeight(dp(c, 42));
            if (chosen) {
                android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
                fill.setColor(androidx.core.graphics.ColorUtils.blendARGB(ContextCompat.getColor(c, R.color.surface), accent, 0.16f));
                fill.setCornerRadius(dp(c, 11));
                option.setBackground(fill);
            } else option.setBackgroundResource(outValue(c));
            if (icons != null && icons[i] != 0) {
                ImageView symbol = new ImageView(c);
                symbol.setImageResource(icons[i]);
                symbol.setImageTintList(ColorStateList.valueOf(chosen ? accent : dim));
                option.addView(symbol, new LinearLayout.LayoutParams(dp(c, 15), dp(c, 15)));
            }
            TextView label = new TextView(c);
            label.setText(labels[i]);
            label.setTextSize(13);
            label.setTextColor(chosen ? accent : dim);
            label.setTypeface(null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            label.setPadding(dp(c, 6), 0, 0, 0);
            option.addView(label);
            option.setContentDescription(labels[i] + (chosen ? ", selected" : ""));
            int index = i;
            option.setOnClickListener(v -> { if (index != selected) pick.at(index); });
            host.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    interface Typed { void text(String words); }

    /**
     * A drawer with one box to type or paste into and one button that acts on it. The button
     * stays quiet until something is typed. {@code note} is a line of help under the box.
     */
    static void fieldSheet(Context c, CharSequence title, CharSequence sub, String hint, String note,
                           int icon, String go, Typed typed) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        LinearLayout rows = body.findViewById(R.id.kit_rows);
        android.widget.EditText field = new android.widget.EditText(c);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(ContextCompat.getColor(c, R.color.text));
        field.setHintTextColor(ContextCompat.getColor(c, R.color.dim));
        field.setBackgroundResource(R.drawable.card_bg);
        field.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        field.setMinHeight(dp(c, 48));
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        rows.addView(field, new LinearLayout.LayoutParams(-1, -2));
        if (note != null) {
            TextView help = new TextView(c);
            help.setTextAppearance(R.style.Kit_Text_RowSub);
            help.setText(note);
            help.setPadding(dp(c, 2), dp(c, 8), dp(c, 2), 0);
            rows.addView(help);
        }
        View button = tonalButton(c, icon, go, () -> {
            String words = field.getText().toString().trim();
            if (words.isEmpty()) return;
            dialog.dismiss();
            typed.text(words);
        });
        button.setAlpha(0.45f);
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) {
                button.setAlpha(s.toString().trim().isEmpty() ? 0.45f : 1f);
            }
        });
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
        below.topMargin = dp(c, 12);
        rows.addView(button, below);
        dialog.setContentView(scrolling(body));
        openFully(dialog);
        dialog.show();
    }

    /**
     * Open a bottom drawer of choices. It closes by dragging down, tapping outside, or
     * picking a choice; there is no close button, by the owner's rule.
     */
    static com.google.android.material.bottomsheet.BottomSheetDialog sheet(
            Context c, CharSequence title, CharSequence sub, Action... actions) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        ((TextView) body.findViewById(R.id.kit_title)).setText(title);
        setOptional(body.findViewById(R.id.kit_sub), sub);
        LinearLayout rows = actions.length == 0 ? null : group(body.findViewById(R.id.kit_rows));
        for (Action action : actions) {
            View row = addRow(rows);
            bindRow(row, action.icon, action.label, action.sub, action.value, action.opens);
            row.setOnClickListener(v -> { dialog.dismiss(); action.run.run(); });
        }
        dialog.setContentView(scrolling(body));
        dialog.show();
        return dialog;
    }

    /** An information drawer: a title and a message, closed by dragging down or tapping outside. */
    static com.google.android.material.bottomsheet.BottomSheetDialog sheet(Context c, CharSequence title, CharSequence message) {
        return sheet(c, title, message, new Action[0]);
    }

    /**
     * Say that something did not work, without covering the page. A short bar rises above the
     * bottom bar and leaves by itself; when the step can be tried again it carries Retry.
     * A page that cannot show anything at all says so in place instead (see {@link #notice}).
     */
    static void failed(android.app.Activity a, CharSequence what, Runnable retry) {
        if (a == null || a.isFinishing()) return;
        View root = a.findViewById(android.R.id.content);
        com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(
            root, what, retry == null ? com.google.android.material.snackbar.Snackbar.LENGTH_LONG : 6000);
        View nav = find(root, com.google.android.material.bottomnavigation.BottomNavigationView.class);
        if (nav != null && nav.isShown()) bar.setAnchorView(nav);
        if (retry != null) bar.setAction("Retry", v -> retry.run());
        // Drawn in the app's own colours, a raised card with the accent on Retry, not Material's default.
        bar.setBackgroundTint(ContextCompat.getColor(a, R.color.surface2));
        bar.setTextColor(ContextCompat.getColor(a, R.color.text));
        bar.setActionTextColor(com.google.android.material.color.MaterialColors.getColor(a,
            com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(a, R.color.coral)));
        bar.show();
    }

    /**
     * A notice that sits at the top of what a page is showing: why it is empty or out of date,
     * and the one thing to do about it. It replaces the page's rows, so it never stacks.
     */
    static void notice(ViewGroup host, int icon, String title, String why, String go, Runnable action) {
        host.removeAllViews();
        empty(host, icon, title, why,
            action == null ? null : button(host.getContext(), R.drawable.csi_refresh, go, R.color.text, action));
    }

    private static View find(View v, Class<?> kind) {
        if (kind.isInstance(v)) return v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = find(g.getChildAt(i), kind);
            if (hit != null) return hit;
        }
        return null;
    }

    interface Format { String of(float value); }
    interface Change { void to(float value); }

    /** A drawer with one slider. The value applies when the finger lifts, so dragging does not flood the Pi. */
    static void sliderSheet(Context c, CharSequence title, float from, float to, float step,
                            float value, Format format, Change change) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        View body = LayoutInflater.from(c).inflate(R.layout.kit_sheet, null, false);
        TextView heading = body.findViewById(R.id.kit_title);
        TextView reading = body.findViewById(R.id.kit_sub);
        heading.setText(title);
        com.google.android.material.slider.Slider slider = new com.google.android.material.slider.Slider(c);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setValue(Math.max(from, Math.min(to, Math.round(value / step) * step)));
        slider.setLabelFormatter(format::of);
        reading.setText(format.of(slider.getValue()));
        slider.addOnChangeListener((s, v, fromUser) -> reading.setText(format.of(v)));
        slider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(com.google.android.material.slider.Slider s) {}
            @Override public void onStopTrackingTouch(com.google.android.material.slider.Slider s) { change.to(s.getValue()); }
        });
        ((LinearLayout) body.findViewById(R.id.kit_rows)).addView(slider,
            new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(scrolling(body));
        dialog.show();
    }

    /** Long drawers (many devices, many models) scroll inside the sheet instead of running off screen. */
    private static View scrolling(View body) {
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(body.getContext());
        scroll.addView(body);
        return scroll;
    }

    // ---- readings: stat cards and meters ----

    /** The colour a share of something reads in: calm to 60 percent, amber to 85, red past it. */
    static int meterColor(Context c, double percent) {
        return statusColor(c, percent < 60 ? Status.GOOD : percent < 85 ? Status.WARN : Status.BAD);
    }

    /** A bar of fixed width filled to {@code percent} in its meter colour. */
    static View meter(Context c, double percent, int widthDp, int heightDp) {
        android.widget.FrameLayout track = new android.widget.FrameLayout(c);
        android.graphics.drawable.GradientDrawable ground = new android.graphics.drawable.GradientDrawable();
        ground.setColor(ContextCompat.getColor(c, R.color.surface2));
        ground.setCornerRadius(dp(c, heightDp));
        track.setBackground(ground);
        View fill = new View(c);
        android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
        bar.setColor(meterColor(c, percent));
        bar.setCornerRadius(dp(c, heightDp));
        fill.setBackground(bar);
        int width = dp(c, widthDp);
        track.addView(fill, new android.widget.FrameLayout.LayoutParams(
            Math.max(dp(c, heightDp), (int) Math.round(width * Math.min(100, Math.max(0, percent)) / 100.0)), -1));
        track.setLayoutParams(new LinearLayout.LayoutParams(width, dp(c, heightDp)));
        return track;
    }

    /**
     * Short choices as small cards, two to a row: an icon in a disc and a one-word name, the
     * same look as Home's secondary cards. For a handful of sends or destinations, not long lists.
     */
    static void tileGrid(ViewGroup parent, java.util.List<Action> actions) {
        Context c = parent.getContext();
        LinearLayout row = null;
        for (int i = 0; i < actions.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowAt = new LinearLayout.LayoutParams(-1, -2);
                rowAt.setMargins(-dp(c, 4), 0, -dp(c, 4), 0);
                parent.addView(row, rowAt);
            }
            Action action = actions.get(i);
            LinearLayout card = new LinearLayout(c);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dp(c, 14), dp(c, 10), dp(c, 12), dp(c, 10));
            card.setMinimumHeight(dp(c, 56));
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(ContextCompat.getColor(c, R.color.surface));
            shape.setCornerRadius(dp(c, 16));
            shape.setStroke(dp(c, 1), ContextCompat.getColor(c, R.color.border));
            card.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(accentText(c), 40)), shape, null));
            ImageView icon = new ImageView(c);
            icon.setImageResource(action.icon);
            icon.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
            android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
            disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            disc.setColor(ContextCompat.getColor(c, R.color.surface2));
            icon.setBackground(disc);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.dim)));
            card.addView(icon, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
            TextView title = new TextView(c);
            title.setTextAppearance(R.style.Kit_Text_RowTitle);
            title.setTextSize(14.5f);
            title.setText(action.label);
            title.setPadding(dp(c, 10), 0, 0, 0);
            card.addView(title);
            card.setContentDescription(action.label);
            card.setOnClickListener(v -> { tick(v); action.run.run(); });
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -1, 1);
            at.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            row.addView(card, at);
        }
        if (row != null && row.getChildCount() == 1) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
    }

    /**
     * Facts to read, not act on: a quiet name on the left and its value on the right, with no
     * card around them, so they never look like something to tap.
     */
    static void facts(ViewGroup parent, java.util.List<String[]> pairs) {
        Context c = parent.getContext();
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), 0);
        for (String[] pair : pairs) {
            LinearLayout line = new LinearLayout(c);
            line.setPadding(0, dp(c, 7), 0, dp(c, 7));
            TextView name = new TextView(c);
            name.setTextAppearance(R.style.Kit_Text_RowSub);
            name.setText(pair[0]);
            line.addView(name, new LinearLayout.LayoutParams(dp(c, 104), -2));
            TextView value = new TextView(c);
            value.setTextAppearance(R.style.Kit_Text_RowSub);
            value.setTextColor(ContextCompat.getColor(c, R.color.text));
            value.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.mono));
            value.setText(pair[1]);
            line.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            line.setContentDescription(pair[0] + ", " + pair[1]);
            list.addView(line);
        }
        parent.addView(list, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One headline reading: a small label, the value large, and a meter when it is a share. */
    static final class Stat {
        final int icon; final String label, value; final double percent;
        Stat(int icon, String label, String value, double percent) {
            this.icon = icon; this.label = label; this.value = value; this.percent = percent;
        }
    }

    /** Headline readings as cards, three to a row, so a dense page leads with what matters. */
    static void statCards(ViewGroup parent, java.util.List<Stat> stats) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowAt = new LinearLayout.LayoutParams(-1, -2);
        rowAt.setMargins(-dp(c, 4), 0, -dp(c, 4), 0);
        parent.addView(row, rowAt);
        for (Stat stat : stats) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
            card.setBackgroundResource(R.drawable.card_bg);
            LinearLayout head = new LinearLayout(c);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ImageView symbol = new ImageView(c);
            symbol.setImageResource(stat.icon);
            symbol.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.dim)));
            head.addView(symbol, new LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)));
            TextView label = new TextView(c);
            label.setTextAppearance(R.style.Kit_Text_Section);
            label.setAllCaps(true);
            label.setText(stat.label);
            label.setPadding(dp(c, 6), 0, 0, 0);
            head.addView(label);
            card.addView(head);
            TextView value = new TextView(c);
            value.setTextAppearance(R.style.Kit_Text_RowTitle);
            value.setTextSize(22);
            value.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.mono));
            value.setText(stat.value);
            value.setPadding(0, dp(c, 4), 0, dp(c, 8));
            card.addView(value);
            if (stat.percent >= 0) {
                View bar = meter(c, stat.percent, 1, 6);
                bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 6)));
                // A card's meter spans the card, so it is sized after the card is laid out.
                card.addView(bar);
                bar.post(() -> {
                    View fill = ((ViewGroup) bar).getChildAt(0);
                    fill.getLayoutParams().width = Math.max(dp(c, 6), (int) (bar.getWidth() * Math.min(100, stat.percent) / 100));
                    fill.requestLayout();
                });
            }
            card.setContentDescription(stat.label + ", " + stat.value);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -1, 1);
            at.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            row.addView(card, at);
        }
    }

    /**
     * One process: its name, then two short fixed-width meters stacked at the end, processor over
     * memory, each filled and coloured by its percent with the number beside it.
     */
    static View meterRow(LinearLayout group, int icon, String name, double cpu, double memory) {
        View row = addRow(group);
        bindRow(row, icon, name, null, null, false);
        Context c = row.getContext();
        LinearLayout meters = new LinearLayout(c);
        meters.setOrientation(LinearLayout.VERTICAL);
        meters.setGravity(android.view.Gravity.END);
        meters.setTag("kit-action");
        String[] words = {"CPU", "MEM"};
        double[] values = {cpu, memory};
        for (int i = 0; i < 2; i++) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(c);
            label.setTextAppearance(R.style.Kit_Text_Meta);
            label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.mono));
            label.setTextSize(11);
            label.setText(words[i] + " " + (values[i] >= 10 ? Math.round(values[i]) : Math.round(values[i] * 10) / 10.0) + "%");
            label.setMinWidth(dp(c, 74));
            line.addView(label);
            line.addView(meter(c, values[i], 64, 6));
            LinearLayout.LayoutParams lineAt = new LinearLayout.LayoutParams(-2, -2);
            if (i == 1) lineAt.topMargin = dp(c, 4);
            meters.addView(line, lineAt);
        }
        ((LinearLayout) row).addView(meters, ((LinearLayout) row).indexOfChild(row.findViewById(R.id.kit_chevron)));
        row.setContentDescription(name + ", processor " + Math.round(cpu) + " percent, memory " + Math.round(memory) + " percent");
        return row;
    }

    // ---- capability cards ----

    static View bindArea(View card, int icon, CharSequence title, Status status, CharSequence sub) {
        ((ImageView) card.findViewById(R.id.kit_icon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.kit_title)).setText(title);
        ((TextView) card.findViewById(R.id.kit_sub)).setText(sub);
        setStatus(card.findViewById(R.id.kit_dot), status);
        card.setContentDescription(title + ", " + sub);
        return card;
    }

    /** Fill a large action tile: its icon, its one word, and what it says to a screen reader. */
    static View bindAction(View tile, int icon, CharSequence word, CharSequence spoken, View.OnClickListener click) {
        // The primary actions are the one place the accent may wash a surface: a diagonal tint
        // that fades into the card, under an accent hairline.
        Context c = tile.getContext();
        int accent = com.google.android.material.color.MaterialColors.getColor(c,
            com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(c, R.color.coral));
        int surface = ContextCompat.getColor(c, R.color.surface);
        android.graphics.drawable.GradientDrawable wash = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{androidx.core.graphics.ColorUtils.blendARGB(surface, accent, 0.16f), surface});
        wash.setCornerRadius(dp(c, 14));
        wash.setStroke(dp(c, 1), androidx.core.graphics.ColorUtils.blendARGB(surface, accent, 0.35f));
        tile.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 40)), wash, null));
        ((ImageView) tile.findViewById(R.id.kit_icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.kit_title)).setText(word);
        tile.setContentDescription(spoken);
        tile.setOnClickListener(click);
        return tile;
    }

    // ---- section headings ----

    /**
     * Fill a section heading. With content to collapse, tapping the heading shows or
     * hides it and turns the caret; without, the caret is hidden.
     */
    static void bindSection(View head, int icon, CharSequence title, View collapses) {
        ImageView symbol = head.findViewById(R.id.kit_icon);
        symbol.setVisibility(icon == 0 ? View.GONE : View.VISIBLE);
        if (icon != 0) symbol.setImageResource(icon);
        ((TextView) head.findViewById(R.id.kit_title)).setText(title);
        View caret = head.findViewById(R.id.kit_chevron);
        if (collapses == null) {
            caret.setVisibility(View.GONE);
            head.setClickable(false);
            head.setBackground(null);
            return;
        }
        head.setContentDescription("Collapse " + title);
        head.setOnClickListener(v -> {
            boolean open = collapses.getVisibility() != View.VISIBLE;
            collapses.setVisibility(open ? View.VISIBLE : View.GONE);
            caret.animate().rotation(open ? 0f : -90f).setDuration(160).start();
            head.setContentDescription((open ? "Collapse " : "Expand ") + title);
        });
    }

    // ---- top bar ----

    /** One breadcrumb step. A null target marks the current page, which is not tappable. */
    static final class Crumb {
        final int icon; final String label; final Runnable open;
        Crumb(int icon, String label, Runnable open) { this.icon = icon; this.label = label; this.open = open; }
    }

    /** Opens a place by its id in the map. Each activity supplies how. */
    interface Open { void place(String id); }

    /**
     * Fill the top bar for a place in the map. A bar place shows its name and no Back.
     * A child place shows the path from its bar place, and Back goes one level up.
     * {@code deeper} adds steps below the place for a view that is not in the map.
     */
    static void pageTop(View top, String placeId, Open open, Crumb... deeper) {
        pageTop(top, placeId, open, null, deeper);
    }

    /**
     * The same, for a page reached sideways from an item on another page (Play on Pi screen,
     * Send to a conversation). The crumbs still climb this page's own path, but Back is
     * {@code back}: it returns to the page the item was on.
     */
    static void pageTop(View top, String placeId, Open open, Runnable back, Crumb... deeper) {
        java.util.List<Places.Place> path = Places.path(placeId);
        java.util.List<Crumb> crumbs = new java.util.ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Places.Place place = path.get(i);
            boolean current = deeper.length == 0 && i == path.size() - 1;
            crumbs.add(new Crumb(place.icon, place.label, current ? null : () -> open.place(place.id)));
        }
        crumbs.addAll(java.util.Arrays.asList(deeper));
        Runnable up = back != null ? back : crumbs.size() == 1 ? null : crumbs.get(crumbs.size() - 2).open;
        pageTop(top, up, crumbs.toArray(new Crumb[0]));
        // The page's own name opens what sits beside it and inside it.
        if (deeper.length == 0 && top.getContext() instanceof android.app.Activity) {
            LinearLayout row = top.findViewById(R.id.kit_crumbs);
            View current = row.getChildAt(row.getChildCount() - 1);
            current.setBackgroundResource(outValue(top.getContext()));
            current.setContentDescription(Places.of(placeId).label + ", show the places beside it");
            current.setOnClickListener(v -> adjacent((android.app.Activity) top.getContext(), placeId));
        }
    }

    // Places the sheet can open from any screen; the rest live inside a page and are reached there.
    private static final java.util.Set<String> REACHABLE = new java.util.HashSet<>(java.util.Arrays.asList(
        "home", "search", "pi", "camera", "notes", "media", "pi-screen", "share", "received", "chat", "more",
        "process", "widgets", "settings", "connection", "guide", "help", "showcase"));

    /** A sheet of the places next to this one and inside it, each with its icon and what it is for. */
    static void adjacent(android.app.Activity a, String placeId) {
        Places.Place here = Places.of(placeId);
        if (here == null) return;
        java.util.List<Section> sections = new java.util.ArrayList<>();
        java.util.List<Action> inside = new java.util.ArrayList<>(), beside = new java.util.ArrayList<>();
        for (Places.Place child : Places.children(placeId)) if (REACHABLE.contains(child.id)) inside.add(placeAction(a, child));
        // A bar place's neighbours are the bar itself, so only places under a parent list theirs.
        if (here.parent != null)
            for (Places.Place sibling : Places.children(here.parent))
                if (!sibling.id.equals(placeId) && REACHABLE.contains(sibling.id)) beside.add(placeAction(a, sibling));
        if (!inside.isEmpty()) sections.add(new Section("In " + here.label, inside));
        if (!beside.isEmpty()) sections.add(new Section("Next to " + here.label, beside));
        if (sections.isEmpty()) return;
        sheet(a, here.label, Places.purpose(placeId), sections);
    }

    private static Action placeAction(android.app.Activity a, Places.Place place) {
        String rail = "help".equals(place.id) ? "about" : place.id;
        return new Action(place.icon, place.label, Places.purpose(place.id), () -> {
            try { RailActions.run(a, new org.json.JSONObject().put("id", rail)); } catch (Exception ignored) { }
        }, true);
    }

    /**
     * Fill the fixed top bar. Back goes exactly one level up via {@code up}; pass null
     * on a top-level page to hide it. Each crumb always shows its own icon.
     */
    static void pageTop(View top, Runnable up, Crumb... crumbs) {
        Context c = top.getContext();
        boolean alone = crumbs.length == 1;
        View back = top.findViewById(R.id.kit_back);
        back.setVisibility(up == null ? View.GONE : View.VISIBLE);
        if (up != null) back.setOnClickListener(v -> up.run());
        LinearLayout row = top.findViewById(R.id.kit_crumbs);
        row.removeAllViews();
        if (up == null) row.setPadding(dp(c, 8), 0, 0, 0);
        for (int i = 0; i < crumbs.length; i++) {
            Crumb crumb = crumbs[i];
            boolean current = i == crumbs.length - 1;
            if (i > 0) {
                TextView slash = new TextView(c);
                slash.setTextAppearance(R.style.Kit_Text_Crumb);
                slash.setText("/");
                slash.setPadding(dp(c, 6), 0, dp(c, 6), 0);
                slash.setAlpha(0.55f);
                row.addView(slash);
            }
            LinearLayout step = new LinearLayout(c);
            step.setOrientation(LinearLayout.HORIZONTAL);
            step.setGravity(android.view.Gravity.CENTER_VERTICAL);
            step.setPadding(dp(c, 2), dp(c, 6), dp(c, 2), dp(c, 6));
            step.setMinimumHeight(dp(c, 48));
            step.setMinimumWidth(dp(c, 48));
            ImageView symbol = new ImageView(c);
            symbol.setImageResource(crumb.icon);
            symbol.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c,
                current ? R.color.text : R.color.dim)));
            int glyph = alone ? 19 : 14;
            step.addView(symbol, new LinearLayout.LayoutParams(dp(c, glyph), dp(c, glyph)));
            TextView label = new TextView(c);
            label.setTextAppearance(current ? R.style.Kit_Text_CrumbCurrent : R.style.Kit_Text_Crumb);
            if (alone) label.setTextSize(17);
            label.setText(crumb.label);
            label.setSingleLine(true);
            label.setPadding(dp(c, alone ? 9 : 5), 0, 0, 0);
            step.addView(label);
            if (!current && crumb.open != null) {
                step.setBackgroundResource(outValue(c));
                step.setOnClickListener(v -> crumb.open.run());
                step.setContentDescription("Go to " + crumb.label);
            }
            row.addView(step);
        }
        // A long path rests at its end, so the current page's name is the part that stays in view.
        android.widget.HorizontalScrollView strip = top.findViewById(R.id.kit_crumbs_scroll);
        strip.post(() -> strip.fullScroll(View.FOCUS_RIGHT));
        ((LinearLayout) top.findViewById(R.id.kit_actions)).removeAllViews();
    }

    /** Add an icon-only action to the right of the top bar. */
    static ImageView topAction(View top, int icon, String label, View.OnClickListener click) {
        Context c = top.getContext();
        ImageView action = new ImageView(c);
        action.setImageResource(icon);
        action.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.text)));
        action.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        action.setBackgroundResource(outValueBorderless(c));
        action.setContentDescription(label);
        action.setOnClickListener(click);
        ((LinearLayout) top.findViewById(R.id.kit_actions)).addView(action,
            new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return action;
    }

    // ---- helpers ----

    /**
     * The accent in a shade that small text can be read in. Coral, teal and violet are too pale
     * for words on the light background, so the shade is deepened (or, on the dark background,
     * lifted) until it stands out at the ratio the accessibility guidelines ask for. Icons and
     * filled buttons keep the accent itself.
     */
    static int accentText(Context c) {
        int shade = com.google.android.material.color.MaterialColors.getColor(c,
            com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(c, R.color.coral));
        int ground = ContextCompat.getColor(c, R.color.bg);
        int toward = androidx.core.graphics.ColorUtils.calculateLuminance(ground) > 0.5
            ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        for (int step = 0; step < 12 && androidx.core.graphics.ColorUtils.calculateContrast(shade, ground) < 4.5; step++)
            shade = androidx.core.graphics.ColorUtils.blendARGB(shade, toward, 0.08f);
        return shade;
    }

    /** The accent deepened just enough for white words on top of it to be read, for a filled area that holds text. */
    static int accentFill(Context c) {
        int shade = com.google.android.material.color.MaterialColors.getColor(c,
            com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(c, R.color.coral));
        int words = ContextCompat.getColor(c, R.color.onAccent);
        for (int step = 0; step < 12 && androidx.core.graphics.ColorUtils.calculateContrast(words, shade) < 4.5; step++)
            shade = androidx.core.graphics.ColorUtils.blendARGB(shade, android.graphics.Color.BLACK, 0.06f);
        return shade;
    }

    private static void setOptional(TextView view, CharSequence text) {
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        view.setText(text);
    }

    // ---- motion and touch ----

    // ---- moving between pages (the motion guidebook is in docs/android-ui-system.md) ----

    /** The place on screen last, so the next page can tell how it was reached. */
    static String lastPlace = "home";

    /**
     * Animate a page arriving from {@code from}. A step to a direct child, or back to the parent,
     * slides along one horizontal axis; every other move fades through. Then the page's parts
     * arrive in four steps: the surface, then titles, then actions, then status and decoration.
     */
    static void move(View page, String from, String to) {
        if (page == null || to == null) return;
        Places.Place target = Places.of(to), origin = from == null ? null : Places.of(from);
        boolean down = target != null && from != null && from.equals(target.parent);
        boolean up = origin != null && to.equals(origin.parent);
        Context c = page.getContext();
        androidx.interpolator.view.animation.FastOutSlowInInterpolator ease =
            new androidx.interpolator.view.animation.FastOutSlowInInterpolator();
        page.animate().cancel();
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setTranslationY(0f);
        if (down || up) {
            // Shared axis: forward comes in from the right, back from the left.
            page.setAlpha(0f);
            page.setTranslationX(dp(c, down ? 36 : -36));
            page.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(ease).start();
        } else {
            // Fade through: the new page grows a touch into place as it fades in.
            page.setTranslationX(0f);
            page.setAlpha(0f);
            page.setScaleX(0.96f);
            page.setScaleY(0.96f);
            page.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setStartDelay(40).setInterpolator(ease).start();
        }
        stagger(page);
        lastPlace = to;
    }

    /**
     * A screen of its own (Media, Notes, Search) arriving or coming back into view. It moves by the
     * same rule as a page, measured from the place that was on screen last; staying put is no move.
     */
    static void arrive(android.app.Activity a, String place) {
        if (place == null || place.equals(lastPlace)) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);
        String from = lastPlace;
        // The bottom bar is the app's frame, the same on every page, so it holds still while the page moves.
        if (root instanceof ViewGroup) {
            ViewGroup parts = (ViewGroup) root;
            for (int i = 0; i < parts.getChildCount(); i++) {
                View part = parts.getChildAt(i);
                if (!(part instanceof com.google.android.material.navigation.NavigationBarView)) move(part, from, place);
            }
        } else move(root, from, place);
    }

    /** A page arriving on its own, with no move to measure against: it fades through. */
    static void fadeThrough(View page) { move(page, null, lastPlace); }

    private static final long STEP_MS = 70;

    /**
     * Bring a page's parts in by tier. Titles (the crumbs, section labels) come second, actions
     * (buttons, tabs, the top bar's icons) third, and status words, dots, chevrons and row icons
     * last. A part keeps whatever see-through level it had, so a dimmed part stays dimmed.
     */
    static void stagger(View page) {
        Object held = page.getTag(R.id.kit_motion_parts);
        if (held instanceof java.util.List) {
            for (Object old : (java.util.List<?>) held) {
                View part = (View) old;
                part.animate().cancel();
                Object rest = part.getTag(R.id.kit_motion_alpha);
                part.setAlpha(rest instanceof Float ? (Float) rest : 1f);
                part.setTranslationY(0f);
            }
        }
        java.util.List<View> moved = new java.util.ArrayList<>();
        tiers(page, moved);
        page.setTag(R.id.kit_motion_parts, moved);
    }

    private static void tiers(View view, java.util.List<View> moved) {
        if (view.getVisibility() != View.VISIBLE) return;
        int tier = tierOf(view);
        if (tier > 1) {
            Float rest = view.getAlpha();
            view.setTag(R.id.kit_motion_alpha, rest);
            view.setAlpha(0f);
            view.setTranslationY(tier == 4 ? 0f : dp(view.getContext(), 6));
            view.animate().alpha(rest).translationY(0f).setStartDelay(STEP_MS * (tier - 1)).setDuration(180).start();
            moved.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tiers(group.getChildAt(i), moved);
        }
    }

    /** Which step a part arrives in: 2 titles, 3 actions, 4 status and decoration, 1 for the rest. */
    private static int tierOf(View view) {
        int id = view.getId();
        Object tag = view.getTag();
        if (id == R.id.kit_crumbs || "kit-title".equals(tag)) return 2;
        if (id == R.id.kit_actions || id == R.id.kit_back || "kit-action".equals(tag)) return 3;
        if (id == R.id.kit_end || id == R.id.kit_chevron || id == R.id.kit_icon || id == R.id.kit_dot
            || id == R.id.kit_action) return 4;
        return 1;
    }

    /** Draw a page the way Android's back preview does: it shrinks a little as the gesture goes on, and springs back at 0. */
    static void peekBack(View page, float progress) {
        if (page == null) return;
        page.animate().cancel();
        float scale = 1f - 0.08f * progress;
        if (progress == 0f) {
            page.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
            return;
        }
        page.setScaleX(scale);
        page.setScaleY(scale);
        page.setAlpha(1f - 0.25f * progress);
    }

    /** Pull down on a page whose list is the page to read it again; the caller ends the spinner when the list is back. */
    static void pullToRefresh(androidx.swiperefreshlayout.widget.SwipeRefreshLayout host, Runnable refresh) {
        Context c = host.getContext();
        host.setColorSchemeColors(accentText(c));
        host.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(c, R.color.surface));
        host.setOnRefreshListener(refresh::run);
    }

    /** A light tick on a commit: Send, Save, a toggle, a transport button. */
    static void tick(View view) {
        if (view == null) return;
        view.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
            ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static int outValue(Context c) {
        android.util.TypedValue v = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, v, true);
        return v.resourceId;
    }

    private static int outValueBorderless(Context c) {
        android.util.TypedValue v = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, v, true);
        return v.resourceId;
    }
}
