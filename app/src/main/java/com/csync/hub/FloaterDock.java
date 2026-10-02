package com.csync.hub;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strip above the bottom bar where things that keep going are shown: what plays on the Pi
 * screen or this phone, running timers, and anything added later.
 *
 * Only one card shows at a time, the next one peeking at the edge, and a sideways swipe moves
 * between them with dots underneath saying how many there are. A source hands over its cards
 * under a group name and replaces them whenever they change; the card being looked at stays put.
 */
final class FloaterDock {

    /** One card in the dock. {@code order} places it: lower comes first. */
    static final class Floater {
        final String key;
        final View view;
        final int order;
        Floater(String key, View view, int order) { this.key = key; this.view = view; this.order = order; }
    }

    private final Activity a;
    private final ViewPager2 pager;
    private final LinearLayout dots;
    private final View root;
    private final Adapter adapter = new Adapter();
    private final Map<String, List<Floater>> groups = new LinkedHashMap<>();
    private final java.util.Set<String> hidden = new java.util.HashSet<>();
    private List<Floater> shown = new ArrayList<>();

    FloaterDock(Activity a, FrameLayout host) {
        this.a = a;
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setVisibility(View.GONE);
        root = column;
        pager = new ViewPager2(a);
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        RecyclerView list = (RecyclerView) pager.getChildAt(0);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // A card is 64dp inside a 4dp margin: the media row's height, which every floater shares.
        column.addView(pager, new LinearLayout.LayoutParams(-1, Kit.dp(a, 72)));
        dots = new LinearLayout(a);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, Kit.dp(a, 12));
        column.addView(dots, dotsLp);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { drawDots(); }
        });
        host.addView(column, new FrameLayout.LayoutParams(-1, -2));
        // While the keyboard is up the dock steps aside, so typing keeps the room.
        host.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(host);
            boolean up = insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            if (up != typing) { typing = up; showOrHide(); }
        });
    }

    private boolean typing;

    private void showOrHide() {
        root.setVisibility(shown.isEmpty() || typing ? View.GONE : View.VISIBLE);
    }

    /** Replace one source's cards. An empty list removes them. */
    void set(String group, List<Floater> floaters) {
        groups.put(group, floaters == null ? new ArrayList<>() : floaters);
        publish();
    }

    /** Hide or show one source's cards, as the Timers page hides timers it already shows. */
    void hide(String group, boolean hide) {
        if (hide ? hidden.add(group) : hidden.remove(group)) publish();
    }

    private void publish() {
        List<Floater> next = new ArrayList<>();
        for (Map.Entry<String, List<Floater>> e : groups.entrySet())
            if (!hidden.contains(e.getKey())) next.addAll(e.getValue());
        next.sort(Comparator.comparingInt(f -> f.order));
        if (sameAs(next)) return;
        String looking = shown.isEmpty() ? null : shown.get(Math.min(pager.getCurrentItem(), shown.size() - 1)).key;
        shown = next;
        adapter.notifyDataSetChanged();
        int keep = 0;
        for (int i = 0; i < next.size(); i++) if (next.get(i).key.equals(looking)) keep = i;
        pager.setCurrentItem(keep, false);
        // With one card there is nothing to peek at, so it takes the full width.
        RecyclerView list = (RecyclerView) pager.getChildAt(0);
        int side = next.size() > 1 ? Kit.dp(a, 18) : Kit.dp(a, 8);
        list.setPadding(side, 0, side, 0);
        showOrHide();
        dots.setVisibility(next.size() > 1 ? View.VISIBLE : View.GONE);
        drawDots();
    }

    private boolean sameAs(List<Floater> next) {
        if (next.size() != shown.size()) return false;
        for (int i = 0; i < next.size(); i++) if (next.get(i).view != shown.get(i).view) return false;
        return true;
    }

    private void drawDots() {
        dots.removeAllViews();
        if (shown.size() < 2) return;
        int on = pager.getCurrentItem();
        for (int i = 0; i < shown.size(); i++) {
            View dot = new View(a);
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(Kit.dp(a, 3));
            fill.setColor(i == on ? Kit.accentText(a) : ContextCompat.getColor(a, R.color.border));
            dot.setBackground(fill);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(a, i == on ? 14 : 6), Kit.dp(a, 6));
            lp.setMargins(Kit.dp(a, 3), 0, Kit.dp(a, 3), 0);
            dots.addView(dot, lp);
        }
        dots.setContentDescription((on + 1) + " of " + shown.size() + ". Swipe sideways for the others");
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        Adapter() { setHasStableIds(true); }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(a);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Kit.dp(a, 16));
            shape.setColor(ContextCompat.getColor(a, R.color.surface));
            shape.setStroke(Kit.dp(a, 1), ContextCompat.getColor(a, R.color.border));
            card.setBackground(shape);
            card.setClipToOutline(true);
            card.setElevation(Kit.dp(a, 3));
            FrameLayout page = new FrameLayout(a);
            page.setPadding(Kit.dp(a, 4), Kit.dp(a, 4), Kit.dp(a, 4), Kit.dp(a, 4));
            page.addView(card, new FrameLayout.LayoutParams(-1, -1));
            page.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            return new Holder(page, card);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            View v = shown.get(position).view;
            if (v.getParent() == h.card) return;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            h.card.removeAllViews();
            v.setBackground(null);
            h.card.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_VERTICAL));
        }

        @Override public int getItemCount() { return shown.size(); }

        @Override public long getItemId(int position) { return shown.get(position).key.hashCode(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        Holder(View page, FrameLayout card) { super(page); this.card = card; }
    }
}
