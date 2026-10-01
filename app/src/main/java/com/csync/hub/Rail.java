package com.csync.hub;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.json.JSONObject;

import java.util.List;

/**
 * Two floating buttons at the top corners of every page, translucent over the top bar.
 *
 * Bar 1, on the left, rests as a plus. A tap opens it into a chain of every action the owner
 * picked in Settings, and so does a drag downwards, where letting go on one runs it. The right
 * button holds one action, the light and dark switch unless another was chosen. Both live
 * in a 48dp band the top bar leaves free at each side.
 */
final class Rail {
    private Rail() {}

    static final int BUTTON_DP = 48;
    private static final int CHIP_DP = 48;
    private static final int OPEN_DRAG_DP = 28;

    // How the Pi was at the last check, shown as a dot on the left button on every page. Null until checked.
    private static Kit.Status hub;

    /** Record how the Pi is and repaint the dot on this screen's button. */
    static void status(Activity a, Kit.Status now) {
        hub = now;
        View dot = a.findViewById(R.id.rail_hub_dot);
        if (dot != null) paintDot(dot);
    }

    private static void paintDot(View dot) {
        dot.setVisibility(hub == null ? View.GONE : View.VISIBLE);
        if (hub == null) return;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Kit.statusColor(dot.getContext(), hub));
        shape.setStroke(Kit.dp(dot.getContext(), 2), ContextCompat.getColor(dot.getContext(), R.color.bg));
        dot.setBackground(shape);
        dot.setContentDescription(hub == Kit.Status.GOOD ? "The Pi is online" : hub == Kit.Status.BAD ? "The Pi cannot be reached" : "The Pi needs a look");
    }

    /** Draw the buttons over the page; called on create and on resume, so Settings' picks show at once. */
    static void attach(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        if (root == null) return;
        View old = content.findViewById(R.id.rail_overlay);
        if (old != null) content.removeView(old);
        FrameLayout overlay = new FrameLayout(a);
        overlay.setId(R.id.rail_overlay);
        overlay.setClipChildren(false);
        content.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        // The band sits on the top bar's row, which starts under the status bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(overlay, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            int barHeight = a.getResources().getDimensionPixelSize(R.dimen.kit_top_height);
            int inset = top + (barHeight - Kit.dp(a, BUTTON_DP)) / 2;
            v.setPadding(Kit.dp(a, 4), inset, Kit.dp(a, 4), 0);
            return insets;
        });
        overlay.addView(bar1(a, overlay), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        overlay.addView(slot(a), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        // Added after the window's insets went round, the overlay asks for them itself.
        androidx.core.view.ViewCompat.requestApplyInsets(overlay);
    }

    /** A translucent round button the page shows through. */
    private static ImageView button(Context c, int icon, String label) {
        ImageView button = new ImageView(c);
        int size = Kit.dp(c, BUTTON_DP);
        button.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(c, R.color.text)));
        button.setPadding(Kit.dp(c, 13), Kit.dp(c, 13), Kit.dp(c, 13), Kit.dp(c, 13));
        button.setBackground(glass(c));
        button.setContentDescription(label);
        button.setElevation(Kit.dp(c, 2));
        return button;
    }

    private static GradientDrawable glass(Context c) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(c, R.color.surface2), 200));
        shape.setStroke(Kit.dp(c, 1), ColorUtils.setAlphaComponent(ContextCompat.getColor(c, R.color.border), 160));
        return shape;
    }

    private static View slot(Activity a) {
        JSONObject action = RailActions.slot(a);
        boolean theme = "theme".equals(action.optString("id"));
        ImageView button = button(a, theme ? (Prefs.darkTheme(a) ? R.drawable.csi_sun : R.drawable.csi_moon)
            : RailActions.icon(action.optString("id")), action.optString("label"));
        button.setOnClickListener(v -> { Kit.tick(v); RailActions.run(a, action); });
        return button;
    }

    /** Bar 1 and its chain. The chain is drawn under the button and grows as the finger pulls it. */
    private static View bar1(Activity a, FrameLayout overlay) {
        List<JSONObject> items = RailActions.rail(a);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        ImageView button = button(a, R.drawable.csi_plus, "Quick rail");
        // The Pi's status rides on this button, the one part of the frame every page has.
        FrameLayout holder = new FrameLayout(a);
        holder.setClipChildren(false);
        holder.addView(button);
        View dot = new View(a);
        dot.setId(R.id.rail_hub_dot);
        holder.addView(dot, new FrameLayout.LayoutParams(Kit.dp(a, 12), Kit.dp(a, 12), Gravity.TOP | Gravity.END));
        paintDot(dot);
        column.addView(holder);
        LinearLayout chain = new LinearLayout(a);
        chain.setOrientation(LinearLayout.VERTICAL);
        chain.setVisibility(View.GONE);
        for (JSONObject item : items) chain.addView(chip(a, item));
        // An empty rail still opens: its one link says where the picks are made.
        if (items.isEmpty()) chain.addView(chip(a, RailActions.setup()));
        LinearLayout.LayoutParams chainParams = new LinearLayout.LayoutParams(-2, -2);
        chainParams.topMargin = Kit.dp(a, 6);
        column.addView(chain, chainParams);
        final boolean[] open = {false};
        int scrim = ColorUtils.setAlphaComponent(ContextCompat.getColor(a, R.color.bg), 150);
        Runnable close = () -> {
            open[0] = false;
            button.animate().rotation(0f).setDuration(160).start();
            chain.animate().alpha(0f).translationY(-Kit.dp(a, 12)).setDuration(120)
                .withEndAction(() -> chain.setVisibility(View.GONE)).start();
            overlay.setBackgroundColor(0);
            overlay.setOnTouchListener(null);
            overlay.setClickable(false);
        };
        Runnable show = () -> {
            open[0] = true;
            // The plus turns to a cross while the chain is open, so the same button closes it.
            button.animate().rotation(45f).setDuration(160).start();
            overlay.setBackgroundColor(scrim);
            chain.setVisibility(View.VISIBLE);
            chain.setAlpha(0f);
            chain.setTranslationY(-Kit.dp(a, 12));
            chain.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            for (int i = 0; i < chain.getChildCount(); i++) {
                View chip = chain.getChildAt(i);
                chip.setAlpha(0f);
                chip.animate().alpha(1f).setStartDelay(40L * i).setDuration(120).start();
            }
            // A tap anywhere else closes the chain.
            overlay.setClickable(true);
            overlay.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_DOWN) close.run(); return true; });
        };
        button.setOnTouchListener(new View.OnTouchListener() {
            float downY; boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); dragging = false;
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && e.getRawY() - downY > Kit.dp(a, OPEN_DRAG_DP)) {
                            dragging = true;
                            Kit.tick(v);
                            if (!open[0]) show.run();
                        }
                        if (dragging) highlight(chain, e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        if (dragging) {
                            View picked = under(chain, e.getRawY());
                            if (picked != null) { close.run(); picked.performClick(); }
                            else highlight(chain, -1);
                        } else if (open[0]) close.run();
                        else { Kit.tick(v); show.run(); }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        return true;
                    default: return false;
                }
            }
        });
        return column;
    }

    /** One link of the chain: a translucent pill with the action's icon and words. */
    private static View chip(Activity a, JSONObject item) {
        LinearLayout chip = new LinearLayout(a);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(Kit.dp(a, CHIP_DP));
        chip.setPadding(Kit.dp(a, 10), 0, Kit.dp(a, 14), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(a, R.color.surface2), 225));
        pill.setStroke(Kit.dp(a, 1), ColorUtils.setAlphaComponent(ContextCompat.getColor(a, R.color.border), 160));
        pill.setCornerRadius(Kit.dp(a, 22));
        chip.setBackground(pill);
        chip.setElevation(Kit.dp(a, 2));
        ImageView icon = new ImageView(a);
        icon.setImageResource(RailActions.icon(item.optString("id")));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(a, R.color.text)));
        chip.addView(icon, new LinearLayout.LayoutParams(Kit.dp(a, 18), Kit.dp(a, 18)));
        TextView label = new TextView(a);
        label.setText(item.optString("label"));
        label.setSingleLine();
        label.setTextSize(13.5f);
        label.setTextColor(ContextCompat.getColor(a, R.color.text));
        label.setPadding(Kit.dp(a, 8), 0, 0, 0);
        chip.addView(label);
        chip.setContentDescription(item.optString("label"));
        chip.setOnClickListener(v -> {
            Kit.tick(v);
            View overlay = a.findViewById(R.id.rail_overlay);
            if (overlay != null) overlay.setBackgroundColor(0);
            RailActions.run(a, item);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = Kit.dp(a, 6);
        chip.setLayoutParams(params);
        return chip;
    }

    private static View under(LinearLayout chain, float rawY) {
        int[] at = new int[2];
        for (int i = 0; i < chain.getChildCount(); i++) {
            View chip = chain.getChildAt(i);
            chip.getLocationOnScreen(at);
            if (rawY >= at[1] && rawY <= at[1] + chip.getHeight()) return chip;
        }
        return null;
    }

    /** The chip under the finger stands out a little, so letting go is never a guess. */
    private static void highlight(LinearLayout chain, float rawY) {
        View picked = rawY < 0 ? null : under(chain, rawY);
        for (int i = 0; i < chain.getChildCount(); i++) {
            View chip = chain.getChildAt(i);
            float scale = chip == picked ? 1.06f : 1f;
            chip.setScaleX(scale);
            chip.setScaleY(scale);
            chip.setTranslationX(chip == picked ? Kit.dp(chain.getContext(), 4) : 0);
        }
    }
}
