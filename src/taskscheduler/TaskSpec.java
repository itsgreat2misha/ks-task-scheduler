// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One row of the plugin's tasks list setting, parsed defensively. */
public final class TaskSpec {
    /** Longest per-task delay before an automatic run, in seconds. */
    public static final int MAX_DELAY_SECONDS = 300;
    public final String label;
    public final boolean enabled;
    public final String executable;
    public final String args;
    public final String backend; // Auto, Root or Shizuku
    public final String schedule; // On boot, On Kiosk start, Interval, Daily, Weekly or Manual
    public final int intervalMinutes; // Interval only, 5..10080
    public final String timeOfDay; // Daily/Weekly only, "HH:MM" or "" when invalid
    public final String weekday; // Weekly only, Monday..Sunday
    public final String missedPolicy; // Catch up once or Skip
    public final int jitterMinutes; // 0..120
    public final int delaySeconds; // 0..300; automatic passes wait this long before running the task
    public final int timeoutSeconds;
    public final boolean run; // One-shot per-row run toggle; see TaskSchedulerPlugin. Not part of argv or hash.

    public TaskSpec(String label, boolean enabled, String executable, String args,
                    String backend, String schedule, int intervalMinutes, String timeOfDay,
                    String weekday, String missedPolicy, int jitterMinutes, int delaySeconds,
                    int timeoutSeconds, boolean run) {
        this.label = label;
        this.enabled = enabled;
        this.executable = executable;
        this.args = args;
        this.backend = backend;
        this.schedule = schedule;
        this.intervalMinutes = intervalMinutes;
        this.timeOfDay = timeOfDay;
        this.weekday = weekday;
        this.missedPolicy = missedPolicy;
        this.jitterMinutes = jitterMinutes;
        this.delaySeconds = delaySeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.run = run;
    }

    public static TaskSpec fromRow(Map<String, Object> row) {
        String label = string(row, "label", "Unnamed task");
        boolean enabled = !Boolean.FALSE.equals(row.get("enabled"));
        boolean run = Boolean.TRUE.equals(row.get("run"));
        String executable = string(row, "executable", "");
        String args = string(row, "args", "");
        String backend = string(row, "backend", "Auto");
        if (!"Auto".equals(backend) && !"Root".equals(backend) && !"Shizuku".equals(backend)) backend = "Auto";
        String schedule = string(row, "schedule", Schedule.ON_BOOT);
        if (!Schedule.ON_BOOT.equals(schedule) && !Schedule.ON_START.equals(schedule)
                && !Schedule.INTERVAL.equals(schedule) && !Schedule.DAILY.equals(schedule)
                && !Schedule.WEEKLY.equals(schedule) && !Schedule.MANUAL.equals(schedule)) schedule = Schedule.ON_BOOT;
        int interval = number(row, "intervalMinutes", 60);
        if (interval < Schedule.MIN_INTERVAL_MINUTES) interval = Schedule.MIN_INTERVAL_MINUTES;
        if (interval > Schedule.MAX_INTERVAL_MINUTES) interval = Schedule.MAX_INTERVAL_MINUTES;
        String timeOfDay = string(row, "timeOfDay", "");
        if (Schedule.parseTimeOfDayMinutes(timeOfDay) < 0) timeOfDay = "";
        String weekday = string(row, "weekday", "Monday");
        if (Schedule.weekdayConstant(weekday) < 0) weekday = "Monday";
        String missedPolicy = string(row, "missedPolicy", Schedule.CATCH_UP);
        if (!Schedule.SKIP.equals(missedPolicy)) missedPolicy = Schedule.CATCH_UP;
        int jitter = number(row, "jitterMinutes", 0);
        if (jitter < 0) jitter = 0;
        if (jitter > 120) jitter = 120;
        int delay = number(row, "delaySeconds", 0);
        if (delay < 0) delay = 0;
        if (delay > MAX_DELAY_SECONDS) delay = MAX_DELAY_SECONDS;
        int timeout = number(row, "timeoutSeconds", 10);
        if (timeout < 1) timeout = 1;
        if (timeout > 30) timeout = 30;
        return new TaskSpec(label, enabled, executable, args, backend, schedule, interval,
                timeOfDay, weekday, missedPolicy, jitter, delay, timeout, run);
    }

    /** Executable plus parsed arguments, as one argv list. */
    public List<String> argv() {
        List<String> argv = new ArrayList<String>();
        argv.add(executable);
        argv.addAll(ArgParser.split(args));
        return argv;
    }

    /** Identity hash: the command itself. Editing the command reruns boot tasks. */
    public String hash() { return SchedulerState.hash(executable, args); }

    /**
     * Schedule key: the command plus the schedule fields that decide when
     * it is due (schedule, interval, time, weekday). Anchors in
     * SchedulerState are stored under this key, so editing one of those
     * fields gives the task a fresh key and therefore a fresh anchor,
     * while edits to other fields (backend, timeout, jitter, delay,
     * missed policy) keep the existing anchor.
     */
    public String scheduleKey() {
        return SchedulerState.hash(executable, args + "\u0000" + schedule
                + "|" + intervalMinutes + "|" + timeOfDay + "|" + weekday);
    }

    static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.length() == 0 && fallback != null && !"args".equals(key) ? fallback : text;
    }

    static int number(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return (int) Math.round(Double.parseDouble((String) value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }
}
