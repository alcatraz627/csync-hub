package com.csync.hub;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Looking at one item on the whole screen before deciding what to do with it.
 *
 * A picture fills the screen on black and can be pinched, double-tapped and dragged, and a
 * downward swipe closes it. Words from a text file are shown to read. Any other file is shown
 * as a card naming what it is. The item's choices come from {@link ItemActions}: the main few
 * along the bottom, and every choice behind the top bar's action button.
 */
final class Viewer {
    private Viewer() {}

    // Text files larger than this are opened in another app rather than read here.
    private static final long MAX_TEXT = 512 * 1024;

    /** Whether the item can be looked at here rather than only acted on. */
    static boolean shows(ItemActions.Item item) {
        if (item.file == null) return item.kind == ItemActions.Kind.TEXT && item.text != null;
        return item.kind == ItemActions.Kind.IMAGE || isText(item.mime) || item.kind == ItemActions.Kind.DOCUMENT;
    }

    static boolean isText(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.equals("application/json"));
    }

    static void show(Activity a, ItemActions.Item item) {
        boolean picture = item.kind == ItemActions.Kind.IMAGE;
        Dialog dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int ground = picture ? Color.BLACK : ContextCompat.getColor(a, R.color.bg);
        int ink = picture ? Color.WHITE : ContextCompat.getColor(a, R.color.text);
        int dim = picture ? 0xB3FFFFFF : ContextCompat.getColor(a, R.color.dim);

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(ground);
        FrameLayout stage = new FrameLayout(a);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = topBar(a, item, ink, dim, picture, dialog);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout bottom = bottomBar(a, item, ink, picture, dialog);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // The bars sit inside the system bars; a picture still runs beneath them.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getSystemWindowInsetTop(), bottomInset = insets.getSystemWindowInsetBottom();
            top.setPadding(top.getPaddingLeft(), topInset + Kit.dp(a, 4), top.getPaddingRight(), Kit.dp(a, 10));
            bottom.setPadding(bottom.getPaddingLeft(), Kit.dp(a, 8), bottom.getPaddingRight(), bottomInset + Kit.dp(a, 10));
            if (!picture) stage.setPadding(0, 0, 0, 0);
            return insets.consumeSystemWindowInsets();
        });

        ZoomView[] zoom = new ZoomView[1];
        // With the bars showing, a picture sits between them, so a screenshot's own bars never
        // collide with the viewer's; with them hidden it takes the whole screen.
        Runnable fitRoom = () -> {
            if (zoom[0] == null) return;
            boolean barsUp = top.getVisibility() == View.VISIBLE && top.getAlpha() > 0.5f;
            zoom[0].setRoom(barsUp ? top.getHeight() : 0,
                barsUp && bottom.getVisibility() != View.GONE ? bottom.getHeight() : 0);
        };
        Runnable toggleBars = () -> {
            float to = top.getAlpha() > 0.5f ? 0f : 1f;
            for (View bar : new View[]{top, bottom}) {
                if (bar.getVisibility() == View.GONE) continue;
                if (to == 1f) bar.setVisibility(View.VISIBLE);
                bar.animate().alpha(to).setDuration(160).withEndAction(() -> { if (to == 0f) bar.setVisibility(View.INVISIBLE); }).start();
            }
            top.postDelayed(fitRoom, 170);
        };

        if (picture) {
            zoom[0] = showPicture(a, item, stage, toggleBars, dialog, root);
            top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitRoom.run());
            bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitRoom.run());
        }
        else if (item.file == null) showWords(a, stage, item.text, top, bottom);
        else if (isText(item.mime)) showTextFile(a, item, stage, top, bottom);
        else showCard(a, item, stage);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ground));
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | (picture || isNight(a) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        dialog.show();
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ---- bars ----

    private static LinearLayout topBar(Activity a, ItemActions.Item item, int ink, int dim, boolean picture, Dialog dialog) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Kit.dp(a, 4), 0, Kit.dp(a, 4), 0);
        if (picture) bar.setBackgroundColor(Color.BLACK);
        bar.addView(iconButton(a, R.drawable.csi_back, "Close", ink, dialog::dismiss));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(Kit.dp(a, 4), 0, Kit.dp(a, 4), 0);
        TextView title = new TextView(a);
        title.setText(item.title);
        title.setTextColor(ink);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        fade(title);
        words.addView(title);
        if (item.sub != null && !item.sub.isEmpty()) {
            TextView sub = new TextView(a);
            sub.setText(item.sub);
            sub.setTextColor(dim);
            sub.setTextSize(12.5f);
            fade(sub);
            words.addView(sub);
        }
        bar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(iconButton(a, R.drawable.ic_item_actions, "Everything you can do with it", ink,
            () -> ItemActions.sheet(a, item)));
        return bar;
    }

    /** The main choices, as icon-over-word buttons; the rest are behind the top bar's button. */
    private static LinearLayout bottomBar(Activity a, ItemActions.Item item, int ink, boolean picture, Dialog dialog) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(Kit.dp(a, 8), 0, Kit.dp(a, 8), 0);
        if (picture) bar.setBackgroundColor(Color.BLACK);
        else {
            GradientDrawable line = new GradientDrawable();
            line.setColor(ContextCompat.getColor(a, R.color.bg));
            bar.setBackground(line);
        }
        List<Kit.Action> main = mainActions(a, item);
        for (Kit.Action action : main) {
            LinearLayout button = new LinearLayout(a);
            button.setOrientation(LinearLayout.VERTICAL);
            button.setGravity(Gravity.CENTER);
            button.setMinimumHeight(Kit.dp(a, 56));
            button.setBackgroundResource(borderless(a));
            ImageView icon = new ImageView(a);
            icon.setImageResource(action.icon);
            icon.setImageTintList(ColorStateList.valueOf(ink));
            button.addView(icon, new LinearLayout.LayoutParams(Kit.dp(a, 22), Kit.dp(a, 22)));
            TextView word = new TextView(a);
            word.setText(shortWord(action));
            word.setTextColor(ink);
            word.setTextSize(12);
            word.setSingleLine();
            word.setGravity(Gravity.CENTER);
            word.setPadding(0, Kit.dp(a, 4), 0, 0);
            button.addView(word);
            button.setContentDescription(action.label);
            button.setOnClickListener(v -> {
                // Choices that leave for another page close the viewer; looking again is one tap.
                if (action.opens) dialog.dismiss();
                action.run.run();
            });
            bar.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (main.isEmpty()) bar.setVisibility(View.GONE);
        return bar;
    }

    // The choices worth one tap while looking, in this order; the rest are behind the top bar.
    private static final ItemActions.Act[] MAIN = {ItemActions.Act.SHOW_PI, ItemActions.Act.PLAY_PI,
        ItemActions.Act.COPY, ItemActions.Act.DEVICE, ItemActions.Act.SHARE_OUT, ItemActions.Act.SAVE};

    /** Up to four of the item's choices, found by what they do. */
    private static List<Kit.Action> mainActions(Activity a, ItemActions.Item item) {
        List<Kit.Action> all = new ArrayList<>();
        for (Kit.Section section : ItemActions.sections(a, item)) all.addAll(section.actions);
        List<Kit.Action> picked = new ArrayList<>();
        for (ItemActions.Act act : MAIN)
            for (Kit.Action action : all)
                if (action.key == act && picked.size() < 4) picked.add(action);
        return picked;
    }

    // The word under each icon, short enough for four to sit side by side.
    private static String shortWord(Kit.Action action) {
        if (!(action.key instanceof ItemActions.Act)) return action.label;
        switch ((ItemActions.Act) action.key) {
            case SHOW_PI: case PLAY_PI: return "Pi screen";
            case COPY: return "Copy";
            case DEVICE: return "Send";
            case SHARE_OUT: return "Share";
            case SAVE: return "Save";
            default: return action.label;
        }
    }

    private static ImageView iconButton(Context c, int icon, String spoken, int ink, Runnable click) {
        ImageView button = new ImageView(c);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(ink));
        button.setPadding(Kit.dp(c, 13), Kit.dp(c, 13), Kit.dp(c, 13), Kit.dp(c, 13));
        button.setBackgroundResource(borderless(c));
        button.setContentDescription(spoken);
        button.setOnClickListener(v -> click.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(c, 48), Kit.dp(c, 48)));
        return button;
    }

    private static int borderless(Context c) {
        android.util.TypedValue value = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId;
    }

    // A long name runs to the edge and fades rather than ending in dots.
    private static void fade(TextView text) {
        text.setSingleLine();
        text.setEllipsize(null);
        text.setHorizontallyScrolling(true);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setFadingEdgeLength(Kit.dp(text.getContext(), 24));
    }

    // ---- what is shown ----

    private static ZoomView showPicture(Activity a, ItemActions.Item item, FrameLayout stage, Runnable toggleBars,
                                        Dialog dialog, View root) {
        ZoomView zoom = new ZoomView(a);
        zoom.setContentDescription(item.title);
        zoom.onTap = toggleBars;
        zoom.onPull = fraction -> root.setAlpha(1f - Math.min(0.6f, fraction));
        zoom.onDismiss = dialog::dismiss;
        stage.addView(zoom, new FrameLayout.LayoutParams(-1, -1));
        android.widget.ProgressBar waiting = new android.widget.ProgressBar(a);
        waiting.setIndeterminateTintList(ColorStateList.valueOf(0x99FFFFFF));
        stage.addView(waiting, new FrameLayout.LayoutParams(Kit.dp(a, 36), Kit.dp(a, 36), Gravity.CENTER));
        Handler main = new Handler(Looper.getMainLooper());
        item.file.open((uri, mime) -> new Thread(() -> {
            Bitmap picture = decode(a, uri);
            main.post(() -> {
                stage.removeView(waiting);
                if (picture == null) { gone(a, stage, "This picture could not be opened", Color.WHITE); return; }
                zoom.setPicture(picture);
            });
        }, "viewer-picture").start());
        return zoom;
    }

    /** Read a picture scaled to the screen, so a large photo does not run the phone out of memory. */
    private static Bitmap decode(Context c, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            int longest = Math.max(c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels) * 2;
            BitmapFactory.Options scaled = new BitmapFactory.Options();
            scaled.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / scaled.inSampleSize > Math.max(2048, longest))
                scaled.inSampleSize *= 2;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, scaled); }
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static void showTextFile(Activity a, ItemActions.Item item, FrameLayout stage, View top, View bottom) {
        Handler main = new Handler(Looper.getMainLooper());
        item.file.open((uri, mime) -> new Thread(() -> {
            String words = null;
            try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream read = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1 && read.size() <= MAX_TEXT) read.write(buffer, 0, count);
                if (read.size() <= MAX_TEXT) words = read.toString("UTF-8");
            } catch (Exception unreadable) { words = null; }
            String said = words;
            main.post(() -> {
                if (said == null) showCard(a, item, stage);
                else showWords(a, stage, said, top, bottom);
            });
        }, "viewer-text").start());
    }

    private static void showWords(Activity a, FrameLayout stage, String text, View top, View bottom) {
        ScrollView scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        TextView words = new TextView(a);
        words.setText(text);
        words.setTextIsSelectable(true);
        words.setTextColor(ContextCompat.getColor(a, R.color.text));
        words.setTextSize(15);
        words.setLineSpacing(0, 1.3f);
        words.setPadding(Kit.dp(a, 20), 0, Kit.dp(a, 20), 0);
        scroll.addView(words);
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // The words start under the top bar and end above the bottom one, whatever their heights.
        Runnable fit = () -> scroll.setPadding(0, top.getHeight() + Kit.dp(a, 8), 0, bottom.getHeight() + Kit.dp(a, 8));
        top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit.run());
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit.run());
        scroll.post(fit);
    }

    /** A file this phone cannot show here: a card naming it, with a way to open it elsewhere. */
    private static void showCard(Activity a, ItemActions.Item item, FrameLayout stage) {
        stage.removeAllViews();
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Kit.dp(a, 24), Kit.dp(a, 28), Kit.dp(a, 24), Kit.dp(a, 24));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(a, R.color.surface));
        shape.setStroke(Kit.dp(a, 1), ContextCompat.getColor(a, R.color.border));
        shape.setCornerRadius(Kit.dp(a, 20));
        card.setBackground(shape);

        FrameLayout badge = new FrameLayout(a);
        GradientDrawable well = new GradientDrawable();
        well.setColor(androidx.core.graphics.ColorUtils.blendARGB(
            ContextCompat.getColor(a, R.color.surface), Kit.accentText(a), 0.14f));
        well.setCornerRadius(Kit.dp(a, 18));
        badge.setBackground(well);
        ImageView icon = new ImageView(a);
        icon.setImageResource(ItemActions.icon(item.kind));
        icon.setImageTintList(ColorStateList.valueOf(Kit.accentText(a)));
        badge.addView(icon, new FrameLayout.LayoutParams(Kit.dp(a, 32), Kit.dp(a, 32), Gravity.CENTER));
        card.addView(badge, new LinearLayout.LayoutParams(Kit.dp(a, 72), Kit.dp(a, 72)));

        TextView name = new TextView(a);
        name.setText(item.title);
        name.setTextColor(ContextCompat.getColor(a, R.color.text));
        name.setTextSize(17);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setPadding(0, Kit.dp(a, 16), 0, 0);
        card.addView(name);
        TextView what = new TextView(a);
        what.setText(describe(item));
        what.setTextColor(ContextCompat.getColor(a, R.color.dim));
        what.setTextSize(13);
        what.setGravity(Gravity.CENTER);
        what.setPadding(0, Kit.dp(a, 4), 0, Kit.dp(a, 20));
        card.addView(what);
        card.addView(Kit.tonalButton(a, R.drawable.csi_expand, "Open in another app",
            () -> ItemActions.openElsewhere(a, item)), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams centred = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        centred.leftMargin = centred.rightMargin = Kit.dp(a, 24);
        stage.addView(card, centred);
    }

    private static String describe(ItemActions.Item item) {
        String type = item.mime == null ? "" : item.mime;
        String kind = ItemActions.isPdf(item.title, item.mime) ? "PDF document"
            : type.startsWith("audio/") ? "Sound" : type.startsWith("video/") ? "Video"
            : type.contains("zip") || type.contains("compressed") ? "Archive" : "File";
        int dot = item.title.lastIndexOf('.');
        if (dot > 0 && dot < item.title.length() - 1 && kind.equals("File"))
            kind = item.title.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) + " file";
        return kind;
    }

    private static void gone(Context c, FrameLayout stage, String words, int ink) {
        TextView said = new TextView(c);
        said.setText(words);
        said.setTextColor(ink);
        said.setGravity(Gravity.CENTER);
        stage.addView(said, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    /**
     * A picture that can be pinched, double-tapped and dragged. At its fitted size a vertical
     * drag pulls it away, and letting go far enough closes the viewer.
     */
    static final class ZoomView extends View {
        interface Pull { void moved(float fraction); }

        Runnable onTap, onDismiss;
        Pull onPull;
        private Bitmap picture;
        private final Matrix matrix = new Matrix();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private float fit = 1f, scale = 1f, dx, dy, pull;
        private boolean pulling;
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;

        ZoomView(Context c) {
            super(c);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float next = Math.max(1f, Math.min(6f, scale * d.getScaleFactor()));
                    float f = next / scale;
                    dx = d.getFocusX() - (d.getFocusX() - dx) * f;
                    dy = d.getFocusY() - (d.getFocusY() - dy) * f;
                    scale = next;
                    clamp();
                    invalidate();
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    float target = scale > 1.05f ? 1f : 2.5f;
                    animateTo(target, e.getX(), e.getY());
                    return true;
                }
                @Override public boolean onScroll(MotionEvent first, MotionEvent e, float x, float y) {
                    if (pinch.isInProgress()) return false;
                    if (scale <= 1.01f && (pulling || Math.abs(y) > Math.abs(x))) {
                        pulling = true;
                        pull -= y;
                        if (onPull != null) onPull.moved(Math.abs(pull) / Math.max(1, getHeight()));
                        invalidate();
                        return true;
                    }
                    dx -= x;
                    dy -= y;
                    clamp();
                    invalidate();
                    return true;
                }
            });
        }

        void setPicture(Bitmap b) {
            picture = b;
            scale = 1f;
            dx = dy = 0;
            requestLayout();
            fitToView();
            setAlpha(0f);
            animate().alpha(1f).setDuration(180).start();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { fitToView(); }

        /** Fit the picture between {@code top} and {@code bottom} pixels, as the bars leave room. */
        void setRoom(int top, int bottom) {
            if (top == getPaddingTop() && bottom == getPaddingBottom()) return;
            setPadding(0, top, 0, bottom);
            scale = 1f;
            dx = dy = 0;
            fitToView();
        }

        private float roomHeight() { return getHeight() - getPaddingTop() - getPaddingBottom(); }

        private float baseY() { return getPaddingTop() + (roomHeight() - picture.getHeight() * fit) / 2f; }

        private void fitToView() {
            if (picture == null || getWidth() == 0) return;
            fit = Math.min(getWidth() / (float) picture.getWidth(), roomHeight() / (float) picture.getHeight());
            clamp();
            invalidate();
        }

        // Keep the picture centred in its room when it is smaller and its edges on screen when larger.
        private void clamp() {
            if (picture == null) return;
            float w = picture.getWidth() * fit * scale, h = picture.getHeight() * fit * scale;
            float baseX = (getWidth() - picture.getWidth() * fit) / 2f, baseY = baseY();
            if (w <= getWidth()) dx = (getWidth() - w) / 2f - baseX * scale;
            else dx = Math.min(-baseX * scale, Math.max(getWidth() - w - baseX * scale, dx));
            if (h <= roomHeight()) dy = getPaddingTop() + (roomHeight() - h) / 2f - baseY * scale;
            else dy = Math.min(-baseY * scale, Math.max(getHeight() - h - baseY * scale, dy));
        }

        private void animateTo(float target, float fx, float fy) {
            float from = scale;
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            float startDx = dx, startDy = dy;
            anim.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                float next = from + (target - from) * t;
                float f = next / from;
                dx = fx - (fx - startDx) * f;
                dy = fy - (fy - startDy) * f;
                scale = next;
                clamp();
                invalidate();
            });
            anim.start();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (picture == null) return true;
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (pulling) {
                    pulling = false;
                    if (Math.abs(pull) > getHeight() * 0.18f && onDismiss != null) { onDismiss.run(); return true; }
                    float from = pull;
                    android.animation.ValueAnimator back = android.animation.ValueAnimator.ofFloat(from, 0f);
                    back.setDuration(180);
                    back.addUpdateListener(v -> {
                        pull = (float) v.getAnimatedValue();
                        if (onPull != null) onPull.moved(Math.abs(pull) / Math.max(1, getHeight()));
                        invalidate();
                    });
                    back.start();
                }
            }
            return true;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (picture == null) return;
            matrix.reset();
            matrix.postScale(fit, fit);
            matrix.postTranslate((getWidth() - picture.getWidth() * fit) / 2f, baseY());
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy + pull);
            canvas.drawBitmap(picture, matrix, paint);
        }
    }
}
