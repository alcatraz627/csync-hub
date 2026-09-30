package com.csync.hub;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

final class Appearance {
    private Appearance() {}

    static Context wrap(Context base) {
        String size = Prefs.textSize(base);
        float factor = "sm".equals(size) ? 0.9f : "lg".equals(size) ? 1.15f : 1f;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale *= factor;
        return base.createConfigurationContext(config);
    }

    static void apply(Activity activity) {
        String mode = Prefs.themeMode(activity);
        AppCompatDelegate.setDefaultNightMode("system".equals(mode) ?
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : "dark".equals(mode) ?
            AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        switch (Prefs.accent(activity)) {
            case "teal": activity.setTheme(R.style.Theme_Csync_Teal); break;
            case "violet": activity.setTheme(R.style.Theme_Csync_Violet); break;
            case "rust": activity.setTheme(R.style.Theme_Csync_Rust); break;
            case "blue": activity.setTheme(R.style.Theme_Csync_Blue); break;
            case "leaf": activity.setTheme(R.style.Theme_Csync_Leaf); break;
            case "rose": activity.setTheme(R.style.Theme_Csync_Rose); break;
            case "custom":
                activity.setTheme(R.style.Theme_Csync_Coral);
                DynamicColors.applyToActivityIfAvailable(activity,
                    new DynamicColorsOptions.Builder()
                        .setContentBasedSource(Prefs.customAccent(activity)).build());
                break;
            default: activity.setTheme(R.style.Theme_Csync_Coral); break;
        }
    }

    static int systemBarFlags(Activity activity) {
        return Prefs.darkTheme(activity) ? 0 :
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    }

    static void applySystemBars(Activity activity) {
        activity.getWindow().getDecorView().setSystemUiVisibility(systemBarFlags(activity));
    }

    /**
     * Draw the page behind the status and navigation bars, keeping its content clear of them.
     *
     * The page's root takes the top inset as padding, so the top bar sits under a status bar
     * of the page's own colour. A bottom bar pads itself for the navigation bar (Material's
     * bar does this on its own). While the keyboard is up the bottom bar steps aside and the
     * root takes the keyboard's height instead, so nothing rides above the keys. Call after
     * setContentView, with the page's bottom bar or null.
     */
    // On a wide screen a page of rows stays one readable column in the middle; wider than this it is padded.
    private static final int COLUMN_DP = 640;

    /**
     * Keep a page's lists one column on a wide screen (landscape, a tablet): each given view is
     * padded at the sides so its content is at most {@link #COLUMN_DP} wide and centred. The
     * player page and a conversation are not given, since they use the width.
     */
    static void column(Activity activity, View... contents) {
        View root = ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        if (root == null) return;
        // Each view keeps the inset it was drawn with; the column's share is added on top of it.
        int[][] own = new int[contents.length][];
        for (int i = 0; i < contents.length; i++)
            own[i] = contents[i] == null ? null : new int[]{contents[i].getPaddingLeft(), contents[i].getPaddingRight()};
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l - v.getPaddingLeft() - v.getPaddingRight();
            int cap = Math.round(COLUMN_DP * activity.getResources().getDisplayMetrics().density);
            int side = Math.max(0, (width - cap) / 2);
            for (int i = 0; i < contents.length; i++) {
                View content = contents[i];
                if (content != null && content.getPaddingLeft() != side + own[i][0])
                    content.setPadding(side + own[i][0], content.getPaddingTop(), side + own[i][1], content.getPaddingBottom());
            }
        });
    }

    static void edgeToEdge(Activity activity, View bottomBar) {
        android.view.Window window = activity.getWindow();
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(activity.getColor(R.color.bg)));
        applySystemBars(activity);
        View root = ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        if (root == null) return;
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            boolean typing = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            int keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            if (bottomBar != null) bottomBar.setVisibility(typing ? View.GONE : View.VISIBLE);
            // With no bottom bar the root itself keeps clear of the navigation bar.
            v.setPadding(bars.left, bars.top, bars.right, typing ? keyboard : bottomBar == null ? bars.bottom : 0);
            return insets;
        });
    }
}
