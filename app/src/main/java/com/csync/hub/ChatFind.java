package com.csync.hub;

import android.graphics.Rect;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find a word in the open conversation. Every place it appears in your messages, the answers and
 * their code is marked, a count says which one you are on, and the arrows walk through them.
 *
 * Only text tagged with {@link #mark(TextView)} is searched, so labels such as the work strip's
 * summary never count as a match. Text folded away inside the work strip is not searched.
 */
final class ChatFind {

    private static final int EVERY = 0x66FFC107, CURRENT = 0xFFFFC107, CURRENT_TEXT = 0xFF1B1B1B;

    private static final class Mark extends BackgroundColorSpan { Mark(int color) { super(color); } }
    private static final class MarkText extends ForegroundColorSpan { MarkText() { super(CURRENT_TEXT); } }

    private static final class Hit {
        final TextView view; final int start, end;
        Hit(TextView view, int start, int end) { this.view = view; this.start = start; this.end = end; }
    }

    private final ViewGroup messages;
    private final ScrollView scroll;
    private final View bar, composer;
    private final EditText input;
    private final TextView count;
    private final List<Hit> hits = new ArrayList<>();
    private int at;

    ChatFind(View page, ViewGroup messages, ScrollView scroll) {
        this.messages = messages;
        this.scroll = scroll;
        bar = page.findViewById(R.id.chat_find);
        input = page.findViewById(R.id.chat_find_input);
        count = page.findViewById(R.id.chat_find_count);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { search(true); }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            step(1);
            return true;
        });
        page.findViewById(R.id.chat_find_prev).setOnClickListener(v -> step(-1));
        page.findViewById(R.id.chat_find_next).setOnClickListener(v -> step(1));
        page.findViewById(R.id.chat_find_close).setOnClickListener(v -> close());
        composer = page.findViewById(R.id.chat_composer);
    }

    /** Make a message's text searchable. Call it on each text view a message is drawn with. */
    static void mark(TextView text) { text.setTag(R.id.chat_find, Boolean.TRUE); }

    boolean isOpen() { return bar.getVisibility() == View.VISIBLE; }

    void open() {
        bar.setVisibility(View.VISIBLE);
        // Finding is reading, not writing: the message box steps aside so more of the conversation shows.
        composer.setVisibility(View.GONE);
        input.requestFocus();
        keyboard().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        search(true);
    }

    void close() {
        if (!isOpen()) return;
        clear();
        hits.clear();
        input.setText("");
        bar.setVisibility(View.GONE);
        composer.setVisibility(View.VISIBLE);
        keyboard().hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** The conversation was redrawn or grew: mark the new text too, keeping your place. */
    void refresh() { if (isOpen()) search(false); }

    private InputMethodManager keyboard() {
        return (InputMethodManager) bar.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    }

    private void search(boolean jump) {
        clear();
        hits.clear();
        String wanted = input.getText().toString().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) { count.setText(""); return; }
        collect(messages, wanted);
        if (hits.isEmpty()) { count.setText("No matches"); return; }
        at = jump ? hits.size() - 1 : Math.min(at, hits.size() - 1);
        paint();
        if (jump) reveal();
    }

    private void collect(ViewGroup group, String wanted) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof TextView && child.getTag(R.id.chat_find) != null) {
                TextView text = (TextView) child;
                String shown = text.getText().toString().toLowerCase(Locale.ROOT);
                for (int from = shown.indexOf(wanted); from >= 0; from = shown.indexOf(wanted, from + wanted.length()))
                    hits.add(new Hit(text, from, from + wanted.length()));
            } else if (child instanceof ViewGroup) {
                collect((ViewGroup) child, wanted);
            }
        }
    }

    private void step(int by) {
        if (hits.isEmpty()) return;
        at = (at + by + hits.size()) % hits.size();
        clear();
        paint();
        reveal();
    }

    private void paint() {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            Spannable text = spannable(hit.view);
            if (hit.end > text.length()) continue;
            text.setSpan(new Mark(i == at ? CURRENT : EVERY), hit.start, hit.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == at) text.setSpan(new MarkText(), hit.start, hit.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        count.setText((at + 1) + " of " + hits.size());
    }

    private void clear() {
        for (Hit hit : hits) {
            if (!(hit.view.getText() instanceof Spannable)) continue;
            Spannable text = (Spannable) hit.view.getText();
            for (Object span : text.getSpans(0, text.length(), Mark.class)) text.removeSpan(span);
            for (Object span : text.getSpans(0, text.length(), MarkText.class)) text.removeSpan(span);
        }
    }

    private static Spannable spannable(TextView view) {
        if (!(view.getText() instanceof Spannable)) view.setText(view.getText(), TextView.BufferType.SPANNABLE);
        return (Spannable) view.getText();
    }

    /** Bring the current match a third of the way down the screen, so its surroundings can be read. */
    private void reveal() {
        final Hit hit = hits.get(at);
        scroll.post(() -> {
            Rect where = new Rect(0, 0, hit.view.getWidth(), hit.view.getHeight());
            if (hit.view.getLayout() != null) {
                int line = hit.view.getLayout().getLineForOffset(hit.start);
                where.top = hit.view.getLayout().getLineTop(line);
                where.bottom = hit.view.getLayout().getLineBottom(line);
            }
            ((ViewGroup) scroll.getChildAt(0)).offsetDescendantRectToMyCoords(hit.view, where);
            scroll.smoothScrollTo(0, Math.max(0, where.top - scroll.getHeight() / 3));
        });
    }
}
