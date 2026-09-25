package com.csync.hub;

import android.content.Context;
import android.util.AttributeSet;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public final class SixTabNavigationView extends BottomNavigationView {
    public SixTabNavigationView(Context context) { super(context); }
    public SixTabNavigationView(Context context, AttributeSet attrs) { super(context, attrs); }
    public SixTabNavigationView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }
    @Override public int getMaxItemCount() { return 6; }
}
