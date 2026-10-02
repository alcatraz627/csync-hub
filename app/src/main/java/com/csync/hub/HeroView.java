package com.csync.hub;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's hero: the owner's devices reaching each other through the Pi, drawn in the banner the
 * owner chose under Settings > Appearance. A device that is online glows in the accent and moves;
 * one that is offline sits dim. It is decoration with meaning, not a status readout.
 *
 * The same view draws Settings' preview and its thumbnails: a thumbnail is still, so ten of them
 * cost one frame each rather than an animation each.
 */
public final class HeroView extends View {
    static final class Node { final String name; final boolean online; Node(String n, boolean o) { name = n; online = o; } }

    /** Devices to show where the real list is not known yet, as in Settings. */
    static List<Node> sample() {
        List<Node> s = new ArrayList<>();
        s.add(new Node("Mac", true));
        s.add(new Node("Phone", true));
        s.add(new Node("Laptop", false));
        s.add(new Node("Tablet", true));
        s.add(new Node("Watch", false));
        return s;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final HeroPainters.Frame frame = new HeroPainters.Frame();
    private ValueAnimator clock;
    private long started = System.currentTimeMillis();
    private String fixedVariant;
    private boolean still;

    public HeroView(Context c) { this(c, null); }

    public HeroView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The devices to draw, each named and online or not. */
    void setNodes(List<Node> devices) {
        nodes.clear();
        nodes.addAll(devices);
        invalidate();
    }

    /** Draw this banner instead of the saved choice; a still one does not animate. */
    void show(String variantId, boolean still) {
        fixedVariant = variantId;
        if (this.still != still) {
            this.still = still;
            if (isAttachedToWindow()) { stopClock(); if (!still) startClock(); }
        }
        invalidate();
    }

    /** Pick up a new saved choice, as Home does when it comes back from Settings. */
    void refresh() { invalidate(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!still) startClock();
    }

    @Override protected void onDetachedFromWindow() {
        stopClock();
        super.onDetachedFromWindow();
    }

    private void startClock() {
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(60000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> invalidate());
        clock.start();
    }

    private void stopClock() {
        if (clock != null) clock.cancel();
        clock = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        int accent = Kit.accentText(c);
        int surface = ContextCompat.getColor(c, R.color.surface);
        float w = getWidth(), h = getHeight();
        float radius = Kit.dp(c, still ? 10 : 18);
        bounds.set(0, 0, w, h);
        card.setShader(new LinearGradient(0, 0, w, h,
            ColorUtils.blendARGB(surface, accent, 0.22f), surface, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, card);
        clip.reset();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        // A still banner is caught a few seconds in, when every motion is under way.
        float t = still ? 2.4f : (System.currentTimeMillis() - started) / 1000f;
        frame.set(canvas, w, h, t);
        frame.accent = accent;
        frame.surface = surface;
        frame.dim = ContextCompat.getColor(c, R.color.dim);
        frame.nodes = nodes;
        String id = fixedVariant != null ? fixedVariant : Prefs.heroVariant(c);
        HeroPainters.find(id).painter.draw(frame);
        canvas.restore();
    }
}
