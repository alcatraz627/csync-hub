package com.csync.hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.core.content.ContextCompat;

/** A small progress ring: a track with an arc for the share still to run, as timer cards and the dock show. */
final class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float fraction;

    RingView(Context c, int strokeDp) {
        super(c);
        float stroke = Kit.dp(c, strokeDp);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(ContextCompat.getColor(c, R.color.surface2));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(float fraction, int colour) {
        if (Math.abs(this.fraction - fraction) < 0.001f && arc.getColor() == colour) return;
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        arc.setColor(colour);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float half = arc.getStrokeWidth() / 2 + 1;
        box.set(half, half, getWidth() - half, getHeight() - half);
        canvas.drawOval(box, track);
        // A ring with nothing left draws no arc, since a zero-length round cap still shows as a dot.
        if (fraction > 0.004f) canvas.drawArc(box, -90, 360f * fraction, false, arc);
    }
}
