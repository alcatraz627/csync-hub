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
        setOptional(row.findViewById(R.id.kit_end), end);
        row.findViewById(R.id.kit_chevron).setVisibility(opens ? View.VISIBLE : View.GONE);
        row.setContentDescription(sub == null ? title : title + ", " + sub);
        return row;
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
        row.setContentDescription(((TextView) row.findViewById(R.id.kit_title)).getText() + ", " + words);
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

    /** Open a tall drawer at its full height, so its last rows are not hidden behind a half-open edge. */
    private static void openFully(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Ask before something is lost or replaced. The drawer has two buttons: one keeps things
     * as they are, the other goes ahead and is named for what it does.
     */
    static void confirm(Context c, CharSequence title, CharSequence sub, int icon, String go, Runnable run) {
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
        label.setTextColor(color);
        label.setTextSize(14.5f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(dp(c, 8), 0, 0, 0);
        button.addView(label);
        button.setContentDescription(words);
        button.setOnClickListener(v -> click.run());
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

    interface Pick { void at(int index); }

    /**
     * Views of one place, side by side, with a line under the chosen one. The strip is drawn
     * into {@code host}, replacing what was there, so a caller redraws it by calling again.
     */
    static void tabs(LinearLayout host, int[] icons, String[] labels, int selected, Pick pick) {
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
            label.setTextColor(chosen ? accent : dim);
            label.setTypeface(null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            label.setPadding(dp(c, 7), 0, 0, 0);
            tab.addView(label);
            if (chosen) tab.setBackground(underline(c, accent));
            else tab.setBackgroundResource(outValue(c));
            tab.setContentDescription(labels[i] + (chosen ? ", selected" : ""));
            int index = i;
            tab.setOnClickListener(v -> { if (index != selected) pick.at(index); });
            strip.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        host.addView(strip, new LinearLayout.LayoutParams(-1, -2));
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
    static void sheet(Context c, CharSequence title, CharSequence message) {
        sheet(c, title, message, new Action[0]);
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
        java.util.List<Places.Place> path = Places.path(placeId);
        java.util.List<Crumb> crumbs = new java.util.ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Places.Place place = path.get(i);
            boolean current = deeper.length == 0 && i == path.size() - 1;
            crumbs.add(new Crumb(place.icon, place.label, current ? null : () -> open.place(place.id)));
        }
        crumbs.addAll(java.util.Arrays.asList(deeper));
        Runnable up = crumbs.size() == 1 ? null : crumbs.get(crumbs.size() - 2).open;
        pageTop(top, up, crumbs.toArray(new Crumb[0]));
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

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static int outValue(Context c) {
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
