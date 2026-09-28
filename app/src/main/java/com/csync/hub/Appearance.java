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
}
