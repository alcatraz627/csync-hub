package com.csync.hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

/**
 * The timer ring: turn it like a kitchen timer to set a length, one turn an hour, up to three.
 *
 * In setting mode the arc shows the minutes chosen on an hour face, with a thin ring inside for
 * each full hour. While a timer runs the arc shows the share still to run and counts down; turning
 * it then sets how much is left. A drag is taken only when it starts on the ring, so the page
 * still scrolls when a finger lands anywhere else.
 */
final class DialView extends View {
    interface Turned { void minutes(int minutes, boolean finished); }

    static final int MAX_MINUTES = 180;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
        tick = new Paint(Paint.ANTI_ALIAS_FLAG), knobFill = new Paint(Paint.ANTI_ALIAS_FLAG),
        knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG), lap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int colour;
    private float minutes = 15;
    // While a timer runs and is not being turned, the share left to show.
    private float showFraction = -1f;
    private boolean dragging;
    private double lastAngle;
    private int lastWhole;
    private Turned turned;

    DialView(Context c) {
        super(c);
        float stroke = Kit.dp(c, 20);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(ContextCompat.getColor(c, R.color.surface2));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(Kit.dp(c, 2));
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setColor(ContextCompat.getColor(c, R.color.faint));
        knobFill.setColor(ContextCompat.getColor(c, R.color.surface));
        knobEdge.setStyle(Paint.Style.STROKE);
        knobEdge.setStrokeWidth(Kit.dp(c, 3));
        lap.setStyle(Paint.Style.STROKE);
        lap.setStrokeWidth(Kit.dp(c, 3));
        setColour(ContextCompat.getColor(c, R.color.coral));
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void onTurned(Turned t) { turned = t; }

    void setColour(int c) {
        colour = c;
        arc.setColor(c);
        knobEdge.setColor(c);
        lap.setColor((c & 0x00FFFFFF) | 0x59000000);
        invalidate();
    }

    void setMinutes(int m) {
        if (dragging) return;
        minutes = Math.max(1, Math.min(MAX_MINUTES, m));
        lastWhole = Math.round(minutes);
        updateDescription();
        invalidate();
    }

    int minutes() { return Math.round(minutes); }

    /** Show a running timer's share left, or pass a negative number to show the chosen minutes. */
    void showFraction(float f) {
        if (dragging) return;
        showFraction = f;
        invalidate();
    }

    boolean dragging() { return dragging; }

    @Override protected void onMeasure(int w, int h) {
        int side = Math.min(MeasureSpec.getSize(w), Kit.dp(getContext(), 260));
        setMeasuredDimension(side, side);
    }

    @Override protected void onDraw(Canvas canvas) {
        float half = arc.getStrokeWidth() / 2 + Kit.dp(getContext(), 14);
        box.set(half, half, getWidth() - half, getHeight() - half);
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = box.width() / 2;
        canvas.drawOval(box, track);
        boolean counting = showFraction >= 0 && !dragging;
        float fraction = counting ? showFraction : faceFraction();
        // Inner rings, one for each full hour already set.
        if (!counting) {
            int laps = (int) ((Math.round(minutes) - 1) / 60);
            for (int k = 0; k < laps; k++)
                canvas.drawCircle(cx, cy, r - Kit.dp(getContext(), 34) - k * Kit.dp(getContext(), 7), lap);
        }
        for (int k = 0; k < 12; k++) {
            double a = k / 12.0 * 2 * Math.PI - Math.PI / 2;
            float r1 = r - Kit.dp(getContext(), 20), r2 = r - Kit.dp(getContext(), k % 3 == 0 ? 29 : 25);
            canvas.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, tick);
        }
        if (fraction > 0.004f) canvas.drawArc(box, -90, 360f * fraction, false, arc);
        double end = fraction * 2 * Math.PI - Math.PI / 2;
        float kx = cx + (float) Math.cos(end) * r, ky = cy + (float) Math.sin(end) * r;
        float knob = Kit.dp(getContext(), dragging ? 17 : 15);
        canvas.drawCircle(kx, ky, knob, knobFill);
        canvas.drawCircle(kx, ky, knob, knobEdge);
    }

    // The minutes as a share of the hour face; a whole hour fills the ring.
    private float faceFraction() {
        float onFace = minutes % 60f;
        return onFace < 0.5f ? 1f : onFace / 60f;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dx = e.getX() - cx, dy = e.getY() - cy;
        double angle = Math.atan2(dy, dx);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float distance = (float) Math.hypot(dx, dy), r = box.width() / 2;
                // Only a touch on the ring band turns it; the centre and corners leave the page to scroll.
                if (Math.abs(distance - r) > Kit.dp(getContext(), 34)) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                lastAngle = angle;
                // Jump the knob to the touched point on the current hour, keeping full hours already set.
                int hours = (int) ((Math.round(minutes) - 1) / 60);
                float onFace = (float) (((angle + Math.PI / 2) / (2 * Math.PI) + 1) % 1) * 60f;
                minutes = clamp(hours * 60 + Math.max(1, onFace));
                step(false);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                double delta = angle - lastAngle;
                if (delta > Math.PI) delta -= 2 * Math.PI;
                if (delta < -Math.PI) delta += 2 * Math.PI;
                lastAngle = angle;
                minutes = clamp(minutes + (float) (delta / (2 * Math.PI) * 60));
                step(false);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                minutes = Math.round(minutes);
                step(true);
                invalidate();
                return true;
        }
        return false;
    }

    private static float clamp(float m) { return Math.max(1, Math.min(MAX_MINUTES, m)); }

    // Tell the screen at each new whole minute, with a light tick under the finger.
    private void step(boolean finished) {
        int whole = Math.round(minutes);
        if (whole != lastWhole) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            lastWhole = whole;
        }
        updateDescription();
        if (turned != null) turned.minutes(whole, finished);
    }

    private void updateDescription() {
        setContentDescription(Timers.lengthWords(Math.round(minutes)) + ". Turn the ring to change it");
    }

    // Volume keys and screen readers change it a minute at a time.
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.setClassName("android.widget.SeekBar");
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            minutes = clamp(Math.round(minutes) + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1));
            step(true);
            invalidate();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
