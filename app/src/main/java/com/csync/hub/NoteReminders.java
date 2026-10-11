package com.csync.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the phone's reminders in step with the notes that ask for one here. A note can say "remind me at
 * this time, on the phone" or "on the Mac"; this makes sure the phone holds exactly one reminder for each
 * note that says phone, and none for a note that moved to the Mac, lost its reminder or was deleted.
 *
 * A time the phone has already acted on for a note is remembered, so a reminder the person dismissed in the
 * Reminders list does not come back the next time the notes are read. A note whose time passed more than an
 * hour ago, and does not repeat, is let go quietly rather than ringing late.
 */
final class NoteReminders {
    private static final String FILE = "note_reminders";
    /** How late a one-off reminder may still ring once, as the phone finds out about it. */
    static final long GRACE_MS = 60 * 60_000L;

    private NoteReminders() { }

    private static SharedPreferences seen(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** True when this note's reminder is meant for this phone. */
    static boolean forPhone(JSONObject note) {
        return "phone".equals(note.optString("remindOn")) && note.optLong("remindAt") > 0;
    }

    /**
     * Bring the phone's reminders in line with a full read of the notes (not a search result, which
     * would look like the other notes were gone). Safe to run again and again.
     */
    static void reconcile(Context c, JSONArray notes) {
        long now = System.currentTimeMillis();
        Set<String> wanted = new HashSet<>();
        SharedPreferences.Editor remember = seen(c).edit();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null || !forPhone(note)) continue;
            String id = note.optString("id");
            long asked = note.optLong("remindAt") * 1000;
            String repeat = note.optString("remindRepeat", "never");
            String title = note.optString("title");
            String label = title.isEmpty() ? "Note" : title;
            wanted.add(id);
            Reminders.Item current = Reminders.forNote(c, id);
            if (seen(c).getLong(id, 0) == asked) {
                if (current != null && !label.equals(current.label)) Reminders.edit(c, current.id, label, 0);
                continue;
            }
            long at = asked;
            if (asked <= now) {
                long next = Reminders.nextOccurrence(asked, repeat, now);
                if (next > 0) at = next;
                else if (now - asked > GRACE_MS) { remember.putLong(id, asked); continue; }
            }
            Reminders.setForNote(c, id, label, at, asked, repeat);
            remember.putLong(id, asked);
        }
        for (Reminders.Item item : Reminders.all(c)) {
            if (item.note.isEmpty() || wanted.contains(item.note)) continue;
            Reminders.cancel(c, item.id);
            remember.remove(item.note);
        }
        remember.apply();
    }

    /** The phone sets a reminder from a note's sheet: keep it here at once, whatever the Pi says. */
    static void setHere(Context c, String noteId, String title, long atMs, String repeat) {
        Reminders.setForNote(c, noteId, title.isEmpty() ? "Note" : title, atMs, atMs, repeat);
        seen(c).edit().putLong(noteId, atMs).apply();
    }

    /** The reminder for this note goes elsewhere or away: drop the phone's copy. */
    static void dropHere(Context c, String noteId) {
        Reminders.dropForNote(c, noteId);
        seen(c).edit().remove(noteId).apply();
    }
}
