package com.csync.hub;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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
 * Home's hero: the owner's devices as a small slowly turning constellation around the Pi.
 *
 * Each device is a dot on an orbit, joined to the Pi by a line. A device that is online
 * glows in the accent and a spark travels along its line; one that is offline sits dim.
 * It is decoration with meaning, not a status readout: nothing here needs reading.
 */
public final class HeroView extends View {
    static final class Node { final String name; final boolean online; Node(String n, boolean o) { name = n; online = o; } }

    private final List<Node> nodes = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private ValueAnimator clock;
    private float time;

    public HeroView(Context c) { this(c, null); }

    public HeroView(Context c, AttributeSet attrs) {
        super(c, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Kit.dp(c, 1));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The devices to draw, each named and online or not. */
    void setNodes(List<Node> devices) {
        nodes.clear();
        nodes.addAll(devices);
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // One full turn a minute: slow enough to be calm, fast enough to be noticed.
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(60000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> { time = (float) a.getAnimatedValue(); invalidate(); });
        clock.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        int accent = Kit.accentText(c);
        int surface = ContextCompat.getColor(c, R.color.surface);
        int dim = ContextCompat.getColor(c, R.color.dim);
        float w = getWidth(), h = getHeight(), radius = Kit.dp(c, 18);
        card.set(0, 0, w, h);
        fill.setShader(new LinearGradient(0, 0, w, h,
            ColorUtils.blendARGB(surface, accent, 0.22f), surface, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, radius, radius, fill);
        fill.setShader(null);

        // The Pi sits right of centre, leaving the left for the greeting.
        float cx = w * 0.77f, cy = h * 0.5f;
        float orbitX = Math.min(w * 0.18f, Kit.dp(c, 110)), orbitY = h * 0.36f;
        int count = Math.max(nodes.size(), 1);
        double turn = time * Math.PI * 2;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            double angle = turn + i * Math.PI * 2 / count;
            float x = cx + (float) Math.cos(angle) * orbitX, y = cy + (float) Math.sin(angle) * orbitY;
            int color = node.online ? accent : dim;
            line.setColor(ColorUtils.setAlphaComponent(color, node.online ? 110 : 50));
            canvas.drawLine(cx, cy, x, y, line);
            if (node.online) {
                // A spark runs out along the line and starts again, a little out of step per device.
                float t = (time * 12f + i * 0.37f) % 1f;
                glow.setColor(ColorUtils.setAlphaComponent(accent, (int) (200 * (1 - t))));
                canvas.drawCircle(cx + (x - cx) * t, cy + (y - cy) * t, Kit.dp(c, 2), glow);
                glow.setColor(ColorUtils.setAlphaComponent(accent, 60));
                canvas.drawCircle(x, y, Kit.dp(c, 9), glow);
            }
            fill.setColor(color);
            canvas.drawCircle(x, y, Kit.dp(c, node.online ? 5 : 4), fill);
        }
        float breathe = 0.5f + 0.5f * (float) Math.sin(time * Math.PI * 2 * 15);
        glow.setColor(ColorUtils.setAlphaComponent(accent, (int) (40 + 50 * breathe)));
        canvas.drawCircle(cx, cy, Kit.dp(c, 16), glow);
        fill.setColor(accent);
        canvas.drawCircle(cx, cy, Kit.dp(c, 8), fill);
    }
}
