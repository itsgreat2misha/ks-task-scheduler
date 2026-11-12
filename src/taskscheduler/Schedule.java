// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * When a scheduled task is due: pure date math over a task's schedule
 * fields and its persisted anchor, with no Android APIs, so the same
 * code runs on a host JVM in tests.
 *
 * <p>Only {@link java.util.Calendar} is used, never {@code java.time}:
 * the plugin supports Android API 24 and builds with
 * {@code --release 8}, and {@code java.time} needs API 26. Calendar
 * also gives the right local-time behaviour across DST changes for
 * daily and weekly times.
 *
 * <p>Semantics, per schedule type (each task's own schedule; there
 * is no plugin-wide default):
 *
 * <ul>
 * <li><b>On boot / On Kiosk start</b> — not handled here; the plugin
 * runs those in its start pass.
 * <li><b>Manual</b> — never due. A manual task runs only when the
 * user runs it (the run-all action/button or its per-row Run
 * toggle).
 * <li><b>Interval</b> — due every {@code intervalMinutes} after the
 * anchor (the last accounted run). With no anchor the task is due
 * immediately, so a new interval task runs soon after it is added and
 * anchors itself. Jitter shifts each occurrence by a deterministic
 * per-occurrence offset (see {@link #jitterOffsetMs}).
 * <li><b>Daily / Weekly</b> — due when the most recent calendar
 * occurrence (today or an earlier day at {@code timeOfDay}, on
 * {@code weekday} for weekly) is newer than the anchor. With the
 * <i>Catch up once</i> missed policy that is the whole rule: a missed
 * occurrence runs once at the next opportunity, never more than once.
 * With <i>Skip</i>, an occurrence counts only while it is at most
 * {@link #SKIP_WINDOW_MS} old.
 * </ul>
 *
 * <p>Timing is best-effort by construction: this math runs when the
 * plugin's scheduler thread wakes, which needs the Kiosk process
 * alive. Nothing here can wake a sleeping device.
 */
public final class Schedule {
    public static final String ON_BOOT = "On boot";
    public static final String ON_START = "On Kiosk start";
    public static final String INTERVAL = "Interval";
    public static final String DAILY = "Daily";
    public static final String WEEKLY = "Weekly";
    public static final String MANUAL = "Manual";
    public static final String CATCH_UP = "Catch up once";
    public static final String SKIP = "Skip";

    public static final int MIN_INTERVAL_MINUTES = 5;
    public static final int MAX_INTERVAL_MINUTES = 10080; // one week
    public static final long SKIP_WINDOW_MS = 15L * 60L * 1000L;

    private Schedule() {}

    public static boolean isScheduled(String schedule) {
        return INTERVAL.equals(schedule) || DAILY.equals(schedule) || WEEKLY.equals(schedule);
    }

    /** Minutes since midnight for "HH:MM" (H:MM also accepted), or -1 when invalid. */
    public static int parseTimeOfDayMinutes(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon != trimmed.lastIndexOf(':')) return -1;
        try {
            int hour = Integer.parseInt(trimmed.substring(0, colon));
            int minute = Integer.parseInt(trimmed.substring(colon + 1));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Calendar weekday constant for a weekday name, or -1 when invalid. */
    public static int weekdayConstant(String weekday) {
        if ("Sunday".equals(weekday)) return Calendar.SUNDAY;
        if ("Monday".equals(weekday)) return Calendar.MONDAY;
        if ("Tuesday".equals(weekday)) return Calendar.TUESDAY;
        if ("Wednesday".equals(weekday)) return Calendar.WEDNESDAY;
        if ("Thursday".equals(weekday)) return Calendar.THURSDAY;
        if ("Friday".equals(weekday)) return Calendar.FRIDAY;
        if ("Saturday".equals(weekday)) return Calendar.SATURDAY;
        return -1;
    }

    /**
     * Deterministic jitter for one occurrence: a value in
     * [0, jitterMinutes] minutes derived from the task's schedule key
     * and the un-jittered due time, so every evaluation of the same
     * occurrence agrees on the same offset and the due time does not
     * flap between scheduler ticks.
     */
    public static long jitterOffsetMs(TaskSpec spec, String scheduleKey, long dueBaseMs) {
        if (spec.jitterMinutes <= 0) return 0;
        String seed = scheduleKey + "@" + (dueBaseMs / 60000L);
        int hash = seed.hashCode();
        if (hash == Integer.MIN_VALUE) hash = 0;
        long offsetMinutes = Math.abs(hash) % (spec.jitterMinutes + 1L);
        return offsetMinutes * 60000L;
    }

    /**
     * The next due time for a scheduled task, in epoch millis: at or
     * before {@code nowMs} when the task is due now, later when it is
     * not, or -1 when the schedule can never fire (for example a daily
     * task with no valid time of day). The anchor is the last accounted
     * run, or null when the task has never run.
     */
    public static long nextDueMs(TaskSpec spec, String schedule, String scheduleKey, Long anchorMs, long nowMs, TimeZone tz) {
        if (INTERVAL.equals(schedule)) return intervalDueMs(spec, scheduleKey, anchorMs, nowMs);
        if (DAILY.equals(schedule) || WEEKLY.equals(schedule)) {
            return calendarNextDueMs(spec, schedule, anchorMs, nowMs, tz);
        }
        return -1;
    }

    /** True when the task should run at {@code nowMs}. */
    public static boolean isDue(TaskSpec spec, String schedule, String scheduleKey, Long anchorMs, long nowMs, TimeZone tz) {
        long due = nextDueMs(spec, schedule, scheduleKey, anchorMs, nowMs, tz);
        return due >= 0 && due <= nowMs;
    }

    private static long intervalDueMs(TaskSpec spec, String scheduleKey, Long anchorMs, long nowMs) {
        long periodMs = spec.intervalMinutes * 60000L;
        if (anchorMs == null) return nowMs;
        long base = anchorMs.longValue() + periodMs;
        long due = base + jitterOffsetMs(spec, scheduleKey, base);
        if (SKIP.equals(spec.missedPolicy) && nowMs >= due + periodMs) {
            // A whole period late or more: Skip means this occurrence is
            // abandoned. Report the following occurrence as next due;
            // the caller resyncs the anchor when it sees this.
            long nextBase = base + periodMs;
            return nextBase + jitterOffsetMs(spec, scheduleKey, nextBase);
        }
        return due;
    }

    private static long calendarNextDueMs(TaskSpec spec, String schedule, Long anchorMs, long nowMs, TimeZone tz) {
        int minutes = parseTimeOfDayMinutes(spec.timeOfDay);
        if (minutes < 0) return -1;
        long latest = latestOccurrenceMs(spec, schedule, minutes, nowMs, tz);
        if (latest >= 0 && (anchorMs == null || anchorMs.longValue() < latest)) {
            if (CATCH_UP.equals(spec.missedPolicy) || nowMs - latest <= SKIP_WINDOW_MS) return latest;
        }
        return nextOccurrenceMs(spec, schedule, minutes, nowMs, tz);
    }

    /** Most recent occurrence at or before {@code nowMs}, or -1 when none can be computed. */
    static long latestOccurrenceMs(TaskSpec spec, String schedule, int minutesOfDay, long nowMs, TimeZone tz) {
        int targetWeekday = WEEKLY.equals(schedule) ? weekdayConstant(spec.weekday) : -1;
        if (WEEKLY.equals(schedule) && targetWeekday < 0) return -1;
        for (int back = 0; back <= 7; back++) {
            Calendar candidate = occurrenceOnOffset(spec, schedule, minutesOfDay, nowMs, tz, -back);
            if (candidate == null) continue;
            long time = candidate.getTimeInMillis();
            if (time <= nowMs) return time;
        }
        return -1;
    }

    /** First occurrence strictly after {@code nowMs}, or -1 when none can be computed. */
    static long nextOccurrenceMs(TaskSpec spec, String schedule, int minutesOfDay, long nowMs, TimeZone tz) {
        int targetWeekday = WEEKLY.equals(schedule) ? weekdayConstant(spec.weekday) : -1;
        if (WEEKLY.equals(schedule) && targetWeekday < 0) return -1;
        for (int ahead = 0; ahead <= 7; ahead++) {
            Calendar candidate = occurrenceOnOffset(spec, schedule, minutesOfDay, nowMs, tz, ahead);
            if (candidate == null) continue;
            long time = candidate.getTimeInMillis();
            if (time > nowMs) return time;
        }
        return -1;
    }

    /**
     * The occurrence on the day {@code dayOffset} days from the day
     * containing {@code refMs}, at the task's time of day — or null for
     * a weekly task when that day is not the task's weekday. Calendar
     * resolves times inside a DST gap forward, which is the documented
     * behaviour: a 02:30 task on spring-forward day runs at 03:30. An
     * ambiguous time on fall-back day resolves to its second,
     * standard-time occurrence.
     */
    private static Calendar occurrenceOnOffset(TaskSpec spec, String schedule, int minutesOfDay, long refMs, TimeZone tz, int dayOffset) {
        Calendar calendar = Calendar.getInstance(tz);
        calendar.setTimeInMillis(refMs);
        calendar.add(Calendar.DATE, dayOffset);
        if (WEEKLY.equals(schedule) && calendar.get(Calendar.DAY_OF_WEEK) != weekdayConstant(spec.weekday)) {
            return null;
        }
        calendar.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        calendar.set(Calendar.MINUTE, minutesOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }
}
