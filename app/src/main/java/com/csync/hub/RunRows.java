package com.csync.hub;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;

/**
 * The "On the Mac" part of the Timers and Reminders pages: what the Mac is running or has been asked to run,
 * as the phone last heard it. Drawn quieter than the phone's own runs. A run waiting for the Mac says so
 * plainly and never as a problem; a Mac not heard from for a while shows how long ago, and fades.
 */
final class RunRows {
    private RunRows() { }

    /** The runs this section would show for a kind, so a page can skip the heading when there are none. */
    static List<RunSync.Row> rows(Activity a, boolean timers) {
        List<RunSync.Row> all = RunSync.macRows(a);
        all.removeIf(r -> timers == r.kind.equals("reminder"));
        return all;
    }

    /** What the section shows, as one string, so a page redraws only when it changed. */
    static String signature(Activity a, boolean timers) {
        StringBuilder s = new StringBuilder();
        for (RunSync.Row r : rows(a, timers))
            s.append(r.id).append(r.status).append(r.label).append(r.endMs).append(r.stateNote).append(r.stopRequested)
                .append(r.staleSeconds >= RunSync.DIM_AFTER_S ? 2 : r.staleSeconds >= RunSync.STALE_AFTER_S ? 1 : 0).append(';');
        return s.toString();
    }

    static void section(Activity a, LinearLayout host, boolean timers) {
        List<RunSync.Row> rows = rows(a, timers);
        if (rows.isEmpty()) return;
        Kit.label(host, "On the Mac");
        LinearLayout group = Kit.group(host);
        long now = System.currentTimeMillis();
        for (RunSync.Row r : rows) {
            boolean waiting = r.status.equals("waiting");
            int icon = waiting ? R.drawable.csi_clock : timers ? R.drawable.csi_timer : R.drawable.csi_bell;
            String name = r.label.isEmpty() ? (timers ? "Timer" : "Reminder") : r.label;
            String old = r.staleSeconds >= RunSync.STALE_AFTER_S ? "as of " + ago(r.staleSeconds * 1000L) + " ago" : null;
            View row = Kit.bindRow(Kit.addRow(group), icon, name, wording(a, r, now) + (old == null ? "" : " · " + old), null, false);
            row.setAlpha(r.staleSeconds >= RunSync.DIM_AFTER_S ? 0.45f : 0.75f);
            row.setOnClickListener(v -> sheet(a, r, name));
        }
    }

    private static void sheet(Activity a, RunSync.Row r, String name) {
        if (r.fromMacFile) {
            Kit.sheet(a, name, "Started on the Mac. Stop it there.", new Kit.Action(R.drawable.csi_info, "Stop it from the Mac", null, () -> { }));
            return;
        }
        Kit.sheet(a, name, null, new Kit.Action(R.drawable.csi_stop, "Stop on the Mac",
            r.stopRequested ? "Asked already; the Mac stops it when it next checks" : "The Mac stops it when it next checks",
            () -> RunSync.stop(a, r.id)));
    }

    /** The words under a Mac run's name: what it is doing, in calm terms. */
    static String wording(Activity a, RunSync.Row r, long now) {
        switch (r.status) {
            case "waiting": return "Waiting for Mac" + (r.createdAt > 0 ? " · " + ago(now - r.createdAt) : "");
            case "paused": return "Paused";
            case "ringing": return r.stateNote.isEmpty() ? "Ringing now" : "Ringing, " + r.stateNote;
            case "rejected": return r.stateNote.isEmpty() ? "The Mac could not start it" : r.stateNote;
            case "missed": return "Came after its time";
            default: {
                long at = r.endMs > 0 ? r.endMs : r.fireAt;
                return at > 0 ? (r.stopRequested ? "Stopping, rings at " : "Rings at ")
                    + android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date(at)) : "Running";
            }
        }
    }

    /** "just now", "3 min", "1 h 5 min": how long, in words short enough for one line. */
    static String ago(long ms) {
        long minutes = Math.max(0, ms / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
