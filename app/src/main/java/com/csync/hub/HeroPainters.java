package com.csync.hub;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.core.graphics.ColorUtils;

import java.util.List;

/**
 * The Home banner's looks. Each one tells the same story, the owner's devices reaching each other
 * through the Pi, in its own picture: online devices in the accent and moving, offline ones dim and
 * still. They draw in a 360 by 200 design space anchored to the right, so the greeting keeps the
 * left side whatever the banner's width. The designs and their motion notes are in
 * csync/.claude/output/20261002-hero-logo/notes.md.
 */
final class HeroPainters {

    /** A banner on offer: its stable id (stored in Prefs), the name Settings shows, and how it draws. */
    static final class Variant {
        final String id, name;
        final Painter painter;
        Variant(String id, String name, Painter painter) { this.id = id; this.name = name; this.painter = painter; }
    }

    interface Painter { void draw(Frame f); }

    static final Variant[] ALL = {
        new Variant("orbit", "Orbit", HeroPainters::orbit),
        new Variant("radar", "Radar sweep", HeroPainters::radar),
        new Variant("atom", "Atom orbits", HeroPainters::atom),
        new Variant("desk", "Desk", HeroPainters::desk),
        new Variant("circuit", "Circuit board", HeroPainters::circuit),
        new Variant("stars", "Star chart", HeroPainters::stars),
        new Variant("heartbeat", "Heartbeat lanes", HeroPainters::heartbeat),
        new Variant("tide", "Tide and buoys", HeroPainters::tide),
        new Variant("pixel", "Pixel shelf", HeroPainters::pixel),
        new Variant("subway", "Subway map", HeroPainters::subway),
        new Variant("dusk", "Dusk ridge", HeroPainters::dusk),
    };

    static Variant find(String id) {
        for (Variant v : ALL) if (v.id.equals(id)) return v;
        return ALL[0];
    }

    private HeroPainters() {}

    /** Everything one draw needs, with the design-space mapping. */
    static final class Frame {
        Canvas canvas;
        float w, h, s, ox, oy, t;
        int accent, dim, surface, white = 0xFFFFFFFF;
        List<HeroView.Node> nodes;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        void set(Canvas c, float w, float h, float t) {
            canvas = c; this.w = w; this.h = h; this.t = t;
            s = Math.min(w / 360f, h / 200f);
            ox = w - 360f * s;
            oy = (h - 200f * s) / 2f;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            line.setShader(null);
        }
        float x(float v) { return ox + v * s; }
        float y(float v) { return oy + v * s; }
        int count() { return nodes.size(); }
        boolean on(int i) { return nodes.get(i).online; }
        int tone(int i) { return on(i) ? accent : dim; }
        int alpha(int color, int a) { return ColorUtils.setAlphaComponent(color, Math.max(0, Math.min(255, a))); }

        void dot(float cx, float cy, float r, int color) {
            fill.setColor(color);
            canvas.drawCircle(x(cx), y(cy), r * s, fill);
        }
        void stroke(int color, float width) { line.setColor(color); line.setStrokeWidth(width * s); }
        void seg(float x1, float y1, float x2, float y2) { canvas.drawLine(x(x1), y(y1), x(x2), y(y2), line); }
        void ring(float cx, float cy, float r) { canvas.drawCircle(x(cx), y(cy), r * s, line); }
        /** An online device: a solid accent dot with a soft halo. */
        void lit(float cx, float cy, float r, float halo) {
            dot(cx, cy, r * 2.1f, alpha(accent, (int) (50 + 40 * halo)));
            dot(cx, cy, r, accent);
        }
        /** The Pi: layered glow, the accent disc, a white core. */
        void pi(float cx, float cy, float r, float breathe) {
            dot(cx, cy, r * 2.2f, alpha(accent, (int) (30 + 30 * breathe)));
            dot(cx, cy, r * 1.5f, alpha(accent, 70));
            dot(cx, cy, r, accent);
            dot(cx, cy, r * 0.36f, white);
        }
    }

    // Deterministic noise, so field stars and placements stay put between frames.
    private static float rnd(int i) {
        double v = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private static float breathe(float t, float period) {
        return 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2 / period);
    }

    /** Point a fraction along a polyline of design-space points, for pulses and trains. */
    private static float[] along(float[] pts, float frac) {
        float total = 0;
        for (int i = 2; i < pts.length; i += 2) total += (float) Math.hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]);
        float want = total * frac;
        for (int i = 2; i < pts.length; i += 2) {
            float d = (float) Math.hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]);
            if (want <= d || i == pts.length - 2) {
                float k = d == 0 ? 0 : Math.min(1, want / d);
                return new float[]{pts[i - 2] + (pts[i] - pts[i - 2]) * k, pts[i - 1] + (pts[i + 1] - pts[i - 1]) * k};
            }
            want -= d;
        }
        return new float[]{pts[0], pts[1]};
    }

    private static void poly(Frame f, float[] pts) {
        f.path.reset();
        f.path.moveTo(f.x(pts[0]), f.y(pts[1]));
        for (int i = 2; i < pts.length; i += 2) f.path.lineTo(f.x(pts[i]), f.y(pts[i + 1]));
        f.canvas.drawPath(f.path, f.line);
    }

    // ---- the banners ----

    /** Devices on one slowly turning orbit, each joined to the Pi by a line a spark runs along. */
    static void orbit(Frame f) {
        float cx = 280, cy = 100, rx = 64, ry = 72;
        double turn = f.t / 60.0 * Math.PI * 2;
        int n = Math.max(f.count(), 1);
        for (int i = 0; i < f.count(); i++) {
            double a = turn + i * Math.PI * 2 / n;
            float px = cx + (float) Math.cos(a) * rx, py = cy + (float) Math.sin(a) * ry;
            f.stroke(f.alpha(f.tone(i), f.on(i) ? 110 : 50), 1);
            f.seg(cx, cy, px, py);
            if (f.on(i)) {
                float k = (f.t / 5f + i * 0.37f) % 1f;
                f.dot(cx + (px - cx) * k, cy + (py - cy) * k, 2, f.alpha(f.accent, (int) (200 * (1 - k))));
                f.lit(px, py, 5, 0.5f);
            } else f.dot(px, py, 4, f.dim);
        }
        f.pi(cx, cy, 8, breathe(f.t, 4));
    }

    /** Range rings and a sweep; a device flashes as the sweep passes it. */
    static void radar(Frame f) {
        float cx = 258, cy = 100;
        f.stroke(f.alpha(f.dim, 110), 1);
        for (int r = 28; r <= 84; r += 28) f.ring(cx, cy, r);
        f.seg(cx - 84, cy, cx + 84, cy);
        f.seg(cx, cy - 84, cx, cy + 84);
        float sweep = (f.t / 6f % 1f) * 360f;
        RectF box = new RectF(f.x(cx - 84), f.y(cy - 84), f.x(cx + 84), f.y(cy + 84));
        for (int k = 0; k < 12; k++) {
            f.fill.setColor(f.alpha(f.accent, 70 - k * 6));
            f.canvas.drawArc(box, sweep - 270 - (k + 1) * 5, 5, true, f.fill);
        }
        double lead = Math.toRadians(sweep - 270);
        f.stroke(f.accent, 2);
        f.seg(cx, cy, cx + (float) Math.cos(lead) * 84, cy + (float) Math.sin(lead) * 84);
        for (int i = 0; i < f.count(); i++) {
            double a = i * 2.399963 + 0.6;
            float r = 30 + (i * 23 % 52);
            float px = cx + (float) Math.cos(a) * r, py = cy + (float) Math.sin(a) * r;
            if (!f.on(i)) { f.dot(px, py, 3, f.dim); continue; }
            double behind = (lead - a) % (Math.PI * 2);
            if (behind < 0) behind += Math.PI * 2;
            float flash = behind < 0.6 ? (float) (1 - behind / 0.6) : 0;
            f.lit(px, py, 4, flash);
        }
        f.pi(cx, cy, 6, breathe(f.t, 3));
    }

    /** Three tilted rings around the Pi, devices riding them at their own speeds. */
    static void atom(Frame f) {
        float cx = 258, cy = 100, rx = 80, ry = 28;
        float[] tilt = {20, 80, 140};
        f.stroke(f.alpha(f.dim, 120), 1);
        for (float deg : tilt) {
            f.canvas.save();
            f.canvas.rotate(deg, f.x(cx), f.y(cy));
            f.canvas.drawOval(new RectF(f.x(cx - rx), f.y(cy - ry), f.x(cx + rx), f.y(cy + ry)), f.line);
            f.canvas.restore();
        }
        for (int i = 0; i < f.count(); i++) {
            int ring = i % 3;
            double th = f.t * (0.35 + ring * 0.12) + i * 2.1;
            double rad = Math.toRadians(tilt[ring]);
            float ex = (float) Math.cos(th) * rx, ey = (float) Math.sin(th) * ry;
            float px = cx + ex * (float) Math.cos(rad) - ey * (float) Math.sin(rad);
            float py = cy + ex * (float) Math.sin(rad) + ey * (float) Math.cos(rad);
            if (f.on(i)) f.lit(px, py, 5, breathe(f.t + i, 3)); else f.dot(px, py, 3, f.dim);
        }
        f.pi(cx, cy, 10, breathe(f.t, 4));
    }

    /** An isometric desk: screens light for devices that are online, cables carry pulses from the Pi. */
    static void desk(Frame f) {
        final float cx = 262, cy = 104, k = 50;
        int slab = ColorUtils.blendARGB(f.surface, 0xFF000000, 0.15f);
        int side = ColorUtils.blendARGB(f.surface, 0xFF000000, 0.45f);
        int screenOff = ColorUtils.blendARGB(f.surface, f.dim, 0.25f);
        // Slab: top face, then the two visible sides.
        quad(f, iso(cx, cy, k, -1.6f, -1, 0), iso(cx, cy, k, 1.6f, -1, 0), iso(cx, cy, k, 1.6f, 1, 0), iso(cx, cy, k, -1.6f, 1, 0), slab);
        quad(f, iso(cx, cy, k, -1.6f, 1, 0), iso(cx, cy, k, 1.6f, 1, 0), iso(cx, cy, k, 1.6f, 1, -0.22f), iso(cx, cy, k, -1.6f, 1, -0.22f), side);
        quad(f, iso(cx, cy, k, 1.6f, -1, 0), iso(cx, cy, k, 1.6f, 1, 0), iso(cx, cy, k, 1.6f, 1, -0.22f), iso(cx, cy, k, 1.6f, -1, -0.22f), ColorUtils.blendARGB(side, f.surface, 0.3f));
        boolean[] lit = new boolean[3];
        for (int i = 0; i < 3; i++) lit[i] = i < f.count() && f.on(i);
        // Monitor at the back right, its text lines typing in while online.
        float[] m0 = iso(cx, cy, k, 0.3f, -0.85f, 0.15f), m1 = iso(cx, cy, k, 1.3f, -0.85f, 0.15f);
        float[] m2 = iso(cx, cy, k, 1.3f, -0.85f, 1.05f), m3 = iso(cx, cy, k, 0.3f, -0.85f, 1.05f);
        quad(f, m0, m1, m2, m3, side);
        float[] s0 = lerp(m0, m2, 0.08f), s2 = lerp(m0, m2, 0.92f);
        float[] s1 = lerp(m1, m3, 0.08f), s3 = lerp(m1, m3, 0.92f);
        quad(f, s0, s1, s2, s3, lit[0] ? f.accent : screenOff);
        if (lit[0]) {
            f.stroke(f.alpha(f.white, 170), 1.2f);
            float typed = f.t / 3f % 1f;
            for (int l = 0; l < 3; l++) {
                float len = Math.max(0, Math.min(1, typed * 3 - l)) * (l == 1 ? 0.5f : 0.7f);
                float[] a = lerp(lerp(s3, s0, 0.25f + l * 0.22f), lerp(s2, s1, 0.25f + l * 0.22f), 0.1f);
                float[] b = lerp(lerp(s3, s0, 0.25f + l * 0.22f), lerp(s2, s1, 0.25f + l * 0.22f), 0.1f + len);
                f.seg(a[0], a[1], b[0], b[1]);
            }
        }
        // Laptop at the back left: base on the desk, screen standing.
        quad(f, iso(cx, cy, k, -1.3f, -0.4f, 0.02f), iso(cx, cy, k, -0.4f, -0.4f, 0.02f), iso(cx, cy, k, -0.4f, 0.25f, 0.02f), iso(cx, cy, k, -1.3f, 0.25f, 0.02f), side);
        quad(f, iso(cx, cy, k, -1.3f, -0.4f, 0.02f), iso(cx, cy, k, -0.4f, -0.4f, 0.02f), iso(cx, cy, k, -0.4f, -0.4f, 0.7f), iso(cx, cy, k, -1.3f, -0.4f, 0.7f), side);
        quad(f, iso(cx, cy, k, -1.22f, -0.4f, 0.08f), iso(cx, cy, k, -0.48f, -0.4f, 0.08f), iso(cx, cy, k, -0.48f, -0.4f, 0.64f), iso(cx, cy, k, -1.22f, -0.4f, 0.64f), lit[1] ? ColorUtils.blendARGB(f.accent, f.surface, 0.25f) : screenOff);
        // Phone lying at the front right.
        quad(f, iso(cx, cy, k, 0.55f, 0.35f, 0.02f), iso(cx, cy, k, 0.95f, 0.35f, 0.02f), iso(cx, cy, k, 0.95f, 0.85f, 0.02f), iso(cx, cy, k, 0.55f, 0.85f, 0.02f), lit[2] ? f.accent : screenOff);
        // Cables from the Pi, with a pulse on each live one.
        float[] pi = iso(cx, cy, k, 0, 0, 0.1f);
        float[][] ends = {iso(cx, cy, k, 0.8f, -0.85f, 0.15f), iso(cx, cy, k, -0.4f, -0.1f, 0.02f), iso(cx, cy, k, 0.55f, 0.6f, 0.02f)};
        for (int i = 0; i < 3; i++) {
            f.stroke(lit[i] ? f.alpha(f.accent, 200) : f.alpha(f.dim, 90), 1.4f);
            f.seg(pi[0], pi[1], ends[i][0], ends[i][1]);
            if (lit[i]) {
                float[] p = lerp(pi, ends[i], (f.t / 1.6f + i * 0.33f) % 1f);
                f.dot(p[0], p[1], 2.2f, f.white);
            }
        }
        // The Pi board: an accent box with a blinking LED.
        quad(f, iso(cx, cy, k, -0.3f, -0.25f, 0.16f), iso(cx, cy, k, 0.3f, -0.25f, 0.16f), iso(cx, cy, k, 0.3f, 0.25f, 0.16f), iso(cx, cy, k, -0.3f, 0.25f, 0.16f), f.accent);
        quad(f, iso(cx, cy, k, -0.3f, 0.25f, 0.16f), iso(cx, cy, k, 0.3f, 0.25f, 0.16f), iso(cx, cy, k, 0.3f, 0.25f, 0), iso(cx, cy, k, -0.3f, 0.25f, 0), ColorUtils.blendARGB(f.accent, 0xFF000000, 0.3f));
        quad(f, iso(cx, cy, k, -0.08f, -0.1f, 0.17f), iso(cx, cy, k, 0.12f, -0.1f, 0.17f), iso(cx, cy, k, 0.12f, 0.1f, 0.17f), iso(cx, cy, k, -0.08f, 0.1f, 0.17f), ColorUtils.blendARGB(f.accent, 0xFF000000, 0.4f));
        float[] led = iso(cx, cy, k, -0.2f, -0.15f, 0.17f);
        if ((int) (f.t * 1.5f) % 2 == 0) f.dot(led[0], led[1], 1.8f, f.white);
    }

    private static float[] iso(float cx, float cy, float k, float u, float v, float z) {
        return new float[]{cx + (u - v) * 0.866f * k, cy + (u + v) * 0.5f * k - z * k};
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private static void quad(Frame f, float[] a, float[] b, float[] c, float[] d, int color) {
        f.path.reset();
        f.path.moveTo(f.x(a[0]), f.y(a[1]));
        f.path.lineTo(f.x(b[0]), f.y(b[1]));
        f.path.lineTo(f.x(c[0]), f.y(c[1]));
        f.path.lineTo(f.x(d[0]), f.y(d[1]));
        f.path.close();
        f.fill.setColor(color);
        f.canvas.drawPath(f.path, f.fill);
    }

    // Trace routes from the chip's edge to a pad, in design space, for up to eight devices.
    private static final float[][] TRACES = {
        {258, 80, 258, 60, 272, 42, 302, 42},
        {278, 92, 296, 92, 304, 76, 338, 76},
        {278, 108, 298, 108, 312, 124, 342, 124},
        {238, 92, 214, 92, 200, 78, 178, 78},
        {238, 108, 220, 108, 196, 124, 178, 124},
        {278, 100, 344, 100},
        {258, 120, 258, 150, 244, 164, 212, 164},
        {248, 80, 248, 66, 234, 50, 202, 50},
    };

    /** The Pi as a chip; each device a pad at the end of a trace, pulses running on live traces. */
    static void circuit(Frame f) {
        int quiet = ColorUtils.blendARGB(f.surface, f.dim, 0.3f);
        f.stroke(quiet, 2);
        poly(f, new float[]{214, 22, 240, 22, 252, 34, 252, 52});
        poly(f, new float[]{304, 18, 316, 30, 340, 30});
        poly(f, new float[]{288, 184, 288, 162, 300, 150, 330, 150});
        int n = Math.min(f.count(), TRACES.length);
        for (int i = 0; i < n; i++) {
            float[] tr = TRACES[i];
            f.stroke(f.on(i) ? f.accent : f.alpha(f.dim, 160), 2);
            poly(f, tr);
            float px = tr[tr.length - 2], py = tr[tr.length - 1];
            f.stroke(f.on(i) ? f.accent : f.dim, 2);
            f.ring(px, py, 5.5f);
            f.dot(px, py, 2, f.on(i) ? f.accent : f.dim);
            if (f.on(i)) {
                float[] p = along(tr, (f.t / 1.4f + i * 0.29f) % 1f);
                f.dot(p[0], p[1], 2.4f, f.white);
            }
        }
        // Pins on four sides, then the chip itself.
        f.stroke(f.alpha(f.dim, 180), 1.6f);
        for (int p = -2; p <= 2; p++) {
            float o = p * 6;
            f.seg(258 + o, 74, 258 + o, 80); f.seg(258 + o, 120, 258 + o, 126);
            f.seg(232, 100 + o, 238, 100 + o); f.seg(278, 100 + o, 284, 100 + o);
        }
        RectF chip = new RectF(f.x(238), f.y(80), f.x(278), f.y(120));
        f.fill.setColor(ColorUtils.blendARGB(f.surface, 0xFF000000, 0.35f));
        f.canvas.drawRoundRect(chip, 7 * f.s, 7 * f.s, f.fill);
        f.stroke(f.accent, 2);
        f.canvas.drawRoundRect(chip, 7 * f.s, 7 * f.s, f.line);
        float b = breathe(f.t, 3);
        RectF core = new RectF(f.x(249), f.y(91), f.x(267), f.y(109));
        f.fill.setColor(f.alpha(f.accent, (int) (200 + 55 * b)));
        f.canvas.drawRoundRect(core, 3 * f.s, 3 * f.s, f.fill);
        f.dot(258, 100, 3, f.white);
    }

    /** A constellation: the Pi the brightest star, devices the others, the field twinkling. */
    static void stars(Frame f) {
        for (int i = 0; i < 46; i++) {
            float sx = 150 + rnd(i) * 210, sy = 8 + rnd(i + 100) * 184;
            float tw = 0.5f + 0.5f * (float) Math.sin(f.t * (1 + rnd(i + 200) * 2) + i);
            f.dot(sx, sy, 0.6f + rnd(i + 300) * 0.9f, f.alpha(f.white, (int) (40 + 110 * tw)));
        }
        // A shooting star crosses every nine seconds.
        float shot = f.t % 9f;
        if (shot < 0.8f) {
            float k = shot / 0.8f;
            float hx = 200 + k * 90, hy = 20 + k * 30;
            f.stroke(f.alpha(f.white, (int) (180 * (1 - k))), 1);
            f.seg(hx - 18, hy - 6, hx, hy);
        }
        float cx = 262, cy = 96;
        int n = f.count();
        float[][] at = new float[n][];
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 0.7 + i * Math.PI * 2 / Math.max(n, 1);
            at[i] = new float[]{cx + (float) Math.cos(a) * 76, cy + (float) Math.sin(a) * 68};
        }
        f.stroke(f.alpha(f.dim, 130), 1);
        for (int i = 0; i + 1 < n; i++) f.seg(at[i][0], at[i][1], at[i + 1][0], at[i + 1][1]);
        for (int i = 0; i < n; i++) if (f.on(i)) {
            f.stroke(f.alpha(f.accent, 190), 1.4f);
            f.seg(cx, cy, at[i][0], at[i][1]);
        }
        for (int i = 0; i < n; i++) {
            float shimmer = f.on(i) ? breathe(f.t + i * 0.7f, 2) : 0;
            if (f.on(i)) f.dot(at[i][0], at[i][1], 11, f.alpha(f.accent, (int) (40 + 40 * shimmer)));
            spark(f, at[i][0], at[i][1], f.on(i) ? 9 : 6, f.tone(i));
            // The device's name as a short row of dots, one per few letters.
            int dots = 2 + nameLength(f, i) % 3;
            for (int d = 0; d < dots; d++) f.dot(at[i][0] + 12 + d * 4, at[i][1], 1.2f, f.alpha(f.tone(i), 200));
        }
        f.dot(cx, cy, 20, f.alpha(f.accent, 50));
        spark(f, cx, cy, 18, f.accent);
        f.dot(cx, cy, 3.5f, f.white);
    }

    private static int nameLength(Frame f, int i) {
        String name = f.nodes.get(i).name;
        return name == null ? 0 : name.length();
    }

    private static void spark(Frame f, float cx, float cy, float r, int color) {
        f.path.reset();
        float in = r * 0.22f;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 - Math.PI / 2;
            float rr = k % 2 == 0 ? r : in;
            float px = f.x(cx + (float) Math.cos(a) * rr), py = f.y(cy + (float) Math.sin(a) * rr);
            if (k == 0) f.path.moveTo(px, py); else f.path.lineTo(px, py);
        }
        f.path.close();
        f.fill.setColor(color);
        f.canvas.drawPath(f.path, f.fill);
    }

    /** One lane per device into a bus; live lanes carry a heartbeat, the Pi pulses as beats arrive. */
    static void heartbeat(Frame f) {
        int n = Math.max(1, Math.min(f.count(), 7));
        float top = 100 - (n - 1) * 11, left = 176, right = 296;
        f.stroke(f.alpha(f.dim, 130), 1.4f);
        f.seg(right, top, right, top + (n - 1) * 22);
        f.seg(right, 100, 326, 100);
        float speed = 40, beat = (f.t * speed) % 60;
        for (int i = 0; i < n && i < f.count(); i++) {
            float ly = top + i * 22;
            boolean live = f.on(i);
            if (live) {
                f.stroke(f.accent, 1.8f);
                f.path.reset();
                for (float lx = left + 8; lx <= right; lx += 1.5f) {
                    float yy = ly - ecg(((lx - left) - f.t * speed + i * 17) / 60f) * 13;
                    if (lx == left + 8) f.path.moveTo(f.x(lx), f.y(yy)); else f.path.lineTo(f.x(lx), f.y(yy));
                }
                f.canvas.drawPath(f.path, f.line);
                f.lit(left, ly, 4, 0.5f);
            } else {
                f.stroke(f.alpha(f.dim, 110), 1.4f);
                f.seg(left + 8, ly, right, ly);
                f.dot(left, ly, 3, f.dim);
            }
            f.dot(right, ly, 2, live ? f.accent : f.dim);
        }
        float arrive = beat < 12 ? 1 - beat / 12 : 0;
        f.pi(328, 100, 8, arrive);
    }

    /** A heartbeat shape for one beat period: flat, a small bump, the spike, a dip, a recovery. */
    private static float ecg(float p) {
        p = p - (float) Math.floor(p);
        if (p < 0.55f) return 0;
        if (p < 0.6f) return (p - 0.55f) / 0.05f * 0.25f;
        if (p < 0.63f) return 0.25f - (p - 0.6f) / 0.03f * 0.45f;
        if (p < 0.67f) return -0.2f + (p - 0.63f) / 0.04f * 1.2f;
        if (p < 0.71f) return 1f - (p - 0.67f) / 0.04f * 1.5f;
        if (p < 0.75f) return -0.5f + (p - 0.71f) / 0.04f * 0.5f;
        if (p < 0.82f) return (float) Math.sin((p - 0.75f) / 0.07f * Math.PI) * 0.18f;
        return 0;
    }

    /** Layered waves under a low Pi sun; devices are buoys bobbing on the crest. */
    static void tide(Frame f) {
        f.stroke(f.alpha(f.dim, 70), 1);
        for (int l = 0; l < 3; l++) {
            f.path.reset();
            for (float lx = 150; lx <= 360; lx += 3) {
                float yy = 42 + l * 12 + (float) Math.sin(lx / 22 + f.t * 0.4 + l) * 3;
                if (lx == 150) f.path.moveTo(f.x(lx), f.y(yy)); else f.path.lineTo(f.x(lx), f.y(yy));
            }
            f.canvas.drawPath(f.path, f.line);
        }
        float sunX = 264, sunY = 76;
        float[] crest = new float[f.count() * 2];
        for (int i = 0; i < f.count(); i++) {
            float bx = 180 + i * 170f / Math.max(1, f.count() - 1);
            if (f.count() == 1) bx = 300;
            crest[i * 2] = bx;
            crest[i * 2 + 1] = wave(bx, f.t, 0) - 8;
        }
        for (int i = 0; i < f.count(); i++) if (f.on(i)) {
            f.stroke(f.alpha(f.accent, 110), 1);
            f.seg(sunX, sunY, crest[i * 2], crest[i * 2 + 1]);
        }
        f.pi(sunX, sunY, 13, breathe(f.t, 5));
        int[] tones = {ColorUtils.blendARGB(f.surface, f.accent, 0.12f), ColorUtils.blendARGB(f.surface, f.accent, 0.22f),
            ColorUtils.blendARGB(f.surface, f.accent, 0.42f)};
        for (int l = 0; l < 3; l++) {
            f.path.reset();
            f.path.moveTo(0, f.h);
            for (float px = 0; px <= f.w + 4; px += 4) {
                float dx = (px - f.ox) / f.s;
                f.path.lineTo(px, f.y(wave(dx, f.t, l + 1)));
            }
            f.path.lineTo(f.w, f.h);
            f.path.close();
            f.fill.setColor(tones[l]);
            f.canvas.drawPath(f.path, f.fill);
        }
        f.stroke(f.alpha(f.accent, 170), 1.4f);
        f.path.reset();
        for (float px = 0; px <= f.w + 4; px += 4) {
            float dx = (px - f.ox) / f.s;
            if (px == 0) f.path.moveTo(px, f.y(wave(dx, f.t, 0))); else f.path.lineTo(px, f.y(wave(dx, f.t, 0)));
        }
        f.canvas.drawPath(f.path, f.line);
        for (int i = 0; i < f.count(); i++) {
            float bx = crest[i * 2], by = crest[i * 2 + 1];
            f.stroke(f.on(i) ? f.alpha(f.white, 160) : f.alpha(f.dim, 200), 1.4f);
            f.seg(bx, by, bx, by + 10);
            if (f.on(i)) f.lit(bx, by, 4, 0.5f); else f.dot(bx, by, 3, f.dim);
        }
    }

    private static float wave(float x, float t, int layer) {
        float base = 142 + layer * 13;
        return base + (float) Math.sin(x / (26 + layer * 6) + t * (0.6 + layer * 0.15) + layer * 1.7) * (5 - layer * 0.6f);
    }

    /** A pixel-art shelf of devices wired along the bottom, pulses stepping in whole pixels. */
    static void pixel(Frame f) {
        final float px = 4;
        int dark = ColorUtils.blendARGB(f.surface, 0xFF000000, 0.15f);
        int frame = ColorUtils.blendARGB(f.surface, f.dim, 0.35f);
        int screenOff = ColorUtils.blendARGB(f.surface, f.dim, 0.18f);
        // Data pixels drift up from the Pi.
        for (int i = 0; i < 14; i++) {
            float life = (f.t * 0.5f + rnd(i)) % 1f;
            float dx = 236 + snap(rnd(i + 40) * 52, px), dy = snap(116 - life * 100, px);
            pixRect(f, dx, dy, px, px, f.alpha(i % 4 == 0 ? f.accent : f.white, (int) (180 * (1 - life))));
        }
        boolean[] on = new boolean[4];
        for (int i = 0; i < 4; i++) on[i] = i < f.count() && f.on(i);
        int blink = (int) (f.t * 2) % 2;
        // Phone.
        pixRect(f, 148, 120, 20, 32, frame);
        pixRect(f, 152, 124, 12, 24, on[0] ? f.accent : screenOff);
        // Laptop.
        pixRect(f, 176, 120, 44, 28, frame);
        pixRect(f, 180, 124, 36, 16, on[1] ? f.accent : screenOff);
        if (on[1] && blink == 0) pixRect(f, 184, 128, 12, 4, f.alpha(f.white, 200));
        pixRect(f, 176, 144, 44, 8, ColorUtils.blendARGB(frame, f.white, 0.3f));
        // The Pi, always in the accent.
        pixRect(f, 224, 120, 56, 32, f.accent);
        pixRect(f, 244, 124, 20, 12, dark);
        pixRect(f, 228, 124, 8, 8, f.white);
        pixRect(f, 272, 124, 4, 4, blink == 0 ? 0xFF34C759 : f.alpha(0xFF34C759, 90));
        // Monitor.
        pixRect(f, 284, 112, 44, 32, frame);
        pixRect(f, 288, 116, 36, 24, on[2] ? ColorUtils.blendARGB(f.accent, f.surface, 0.2f) : screenOff);
        pixRect(f, 302, 144, 8, 4, frame);
        pixRect(f, 292, 148, 28, 4, frame);
        // Watch.
        pixRect(f, 340, 124, 4, 4, frame);
        pixRect(f, 332, 128, 20, 16, frame);
        pixRect(f, 336, 132, 12, 8, on[3] ? f.accent : screenOff);
        pixRect(f, 340, 144, 4, 8, frame);
        // Shelf and wire.
        pixRect(f, 140, 152, 216, 4, frame);
        int wire = ColorUtils.blendARGB(f.surface, f.accent, 0.45f);
        pixRect(f, 156, 164, 188, 4, wire);
        float[] drops = {156, 196, 252, 304, 340};
        for (float d : drops) pixRect(f, d, 156, 4, 8, wire);
        float step = snap((f.t * 40) % 188, px);
        for (int k = 0; k < 3; k++) {
            float at = 156 + (step + k * 64) % 188;
            pixRect(f, snap(at, px), 164, 4, 4, f.white);
        }
    }

    private static float snap(float v, float grid) { return (float) Math.floor(v / grid) * grid; }

    private static void pixRect(Frame f, float x, float y, float w, float h, int color) {
        f.fill.setColor(color);
        f.canvas.drawRect(f.x(x), f.y(y), f.x(x + w), f.y(y + h), f.fill);
    }

    // Station slots on the map, and the accent line the train runs along.
    private static final float[][] STATIONS = {{176, 70}, {300, 100}, {262, 36}, {262, 164}, {190, 134}, {294, 166}, {348, 70}};
    private static final float[] MAIN_LINE = {176, 70, 206, 70, 236, 100, 262, 100, 300, 100, 330, 70, 348, 70};

    /** A transit map: devices are stations, the Pi the interchange, a train lighting each it passes. */
    static void subway(Frame f) {
        int grey = f.alpha(f.dim, 200);
        f.stroke(grey, 5);
        f.seg(262, 36, 262, 164);
        f.seg(190, 134, 262, 134);
        f.seg(262, 134, 294, 166);
        f.stroke(f.accent, 5);
        poly(f, MAIN_LINE);
        float k = (f.t / 7f) % 1f;
        float[] train = along(MAIN_LINE, k);
        float[] ahead = along(MAIN_LINE, Math.min(1, k + 0.05f));
        f.stroke(f.white, 6);
        f.seg(train[0], train[1], ahead[0], ahead[1]);
        int n = Math.min(f.count(), STATIONS.length);
        for (int i = 0; i < n; i++) {
            float sx = STATIONS[i][0], sy = STATIONS[i][1];
            boolean passed = Math.hypot(train[0] - sx, train[1] - sy) < 14;
            if (f.on(i)) {
                f.dot(sx, sy, 8, passed ? f.white : f.accent);
                f.dot(sx, sy, 4.5f, passed ? f.accent : f.white);
                f.dot(sx, sy, 2, f.accent);
            } else {
                f.dot(sx, sy, 8, f.dim);
                f.dot(sx, sy, 5.5f, ColorUtils.blendARGB(f.surface, 0xFF000000, 0.4f));
            }
        }
        f.dot(262, 134, 7, f.white);
        f.dot(262, 134, 4.5f, ColorUtils.blendARGB(f.surface, 0xFF000000, 0.4f));
        float b = breathe(f.t, 3);
        f.dot(262, 100, 13 + 2 * b, f.alpha(f.accent, 120));
        f.dot(262, 100, 11, f.accent);
        f.dot(262, 100, 7, f.white);
        f.dot(262, 100, 3.5f, f.accent);
    }

    /** Ridges at dusk; the Pi is a sun whose height and sky follow the real hour, beacons on the ridge. */
    static void dusk(Frame f) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        float hour = now.get(java.util.Calendar.HOUR_OF_DAY) + now.get(java.util.Calendar.MINUTE) / 60f;
        // Daylight from 6 to 19: the sun rises, peaks at midday, and sets; outside that it is night.
        float day = (hour - 6) / 13f;
        boolean night = day < 0 || day > 1;
        float height = night ? 0 : (float) Math.sin(day * Math.PI);
        float sunY = 150 - height * 90;
        int sky = night ? ColorUtils.blendARGB(f.surface, 0xFF0B1030, 0.35f)
            : ColorUtils.blendARGB(ColorUtils.blendARGB(f.surface, f.accent, 0.18f), 0xFF5A86B0, height * 0.45f);
        f.fill.setShader(new LinearGradient(0, 0, 0, f.h, f.surface, sky, Shader.TileMode.CLAMP));
        f.canvas.drawRect(0, 0, f.w, f.h, f.fill);
        f.fill.setShader(null);
        if (night || height < 0.35f) for (int i = 0; i < 24; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(f.t * 1.3 + i);
            f.dot(150 + rnd(i) * 210, 6 + rnd(i + 50) * 80, 0.8f, f.alpha(f.white, (int) ((night ? 150 : 70) * tw)));
        }
        float sunX = 262;
        if (!night) {
            f.fill.setShader(new RadialGradient(f.x(sunX), f.y(sunY), 90 * f.s,
                f.alpha(f.accent, 110), f.alpha(f.accent, 0), Shader.TileMode.CLAMP));
            f.canvas.drawCircle(f.x(sunX), f.y(sunY), 90 * f.s, f.fill);
            f.fill.setShader(null);
            f.stroke(f.alpha(f.accent, 70), 1.4f);
            for (int l = 0; l < 4; l++) f.seg(sunX - 40 + l * 6, sunY - 26 + l * 8, sunX + 40 - l * 6, sunY - 26 + l * 8);
            f.dot(sunX, sunY, 22, f.accent);
        } else f.dot(sunX, 40, 9, f.alpha(f.white, 210));
        float[][] beacons = new float[f.count()][];
        for (int i = 0; i < f.count(); i++) {
            float bx = 186 + i * 154f / Math.max(1, f.count() - 1);
            if (f.count() == 1) bx = 300;
            beacons[i] = new float[]{bx, ridge(bx, 0) - 3};
        }
        int[] tones = {ColorUtils.blendARGB(f.surface, f.dim, 0.18f), ColorUtils.blendARGB(f.surface, 0xFF000000, 0.35f),
            ColorUtils.blendARGB(f.surface, 0xFF000000, 0.6f)};
        for (int l = 0; l < 3; l++) {
            f.path.reset();
            f.path.moveTo(0, f.h);
            for (float px = 0; px <= f.w + 4; px += 4) f.path.lineTo(px, f.y(ridge((px - f.ox) / f.s, l)));
            f.path.lineTo(f.w, f.h);
            f.path.close();
            f.fill.setColor(tones[l]);
            f.canvas.drawPath(f.path, f.fill);
        }
        float anchorY = night ? 40 : sunY;
        for (int i = 0; i < f.count(); i++) {
            float bx = beacons[i][0], by = beacons[i][1];
            if (f.on(i)) {
                f.stroke(f.alpha(f.accent, 150), 1.2f);
                f.path.reset();
                f.path.moveTo(f.x(bx), f.y(by));
                f.path.quadTo(f.x((bx + sunX) / 2), f.y(Math.min(by, anchorY) - 30), f.x(sunX), f.y(anchorY));
                f.canvas.drawPath(f.path, f.line);
                f.lit(bx, by, 3.5f, breathe(f.t + i, 4));
            } else f.dot(bx, by, 3, f.alpha(f.dim, 220));
        }
    }

    private static float ridge(float x, int layer) {
        float base = 146 + layer * 18;
        return base + (float) (Math.sin(x / 34 + layer * 2.1) * 9 + Math.sin(x / 13 + layer) * 3);
    }
}
