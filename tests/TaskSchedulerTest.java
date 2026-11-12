// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import me.jxl.kiosk.plugins.PluginHost;

/** Plain-main tests for the pure-Java parts. Run with: java -ea -cp <classes> taskscheduler.TaskSchedulerTest */
public final class TaskSchedulerTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    public static void main(String[] args) {
        argParserTests();
        rootRunnerTests();
        pidTests();
        schedulerStateTests();
        taskSpecTests();
        scheduleTests();
        configTests();
        loggingTests();
        System.out.println("TaskSchedulerTest: all checks passed");
    }

    private static void argParserTests() {
        List<String> split = ArgParser.split("/system/bin/am start -n 'com.example/.Main' \"a b\" c\\ d");
        check(split.size() == 6, "split size, got " + split);
        check(split.get(3).equals("com.example/.Main"), "single quotes");
        check(split.get(4).equals("a b"), "double quotes");
        check(split.get(5).equals("c d"), "escaped space");
        check(ArgParser.split("").isEmpty(), "empty split");
        boolean threw = false;
        try { ArgParser.split("unterminated 'quote"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "unterminated quote throws");

        List<String> argv = Arrays.asList("/system/bin/am", "start", "-n", "com.example/.Main", "a b", "it's");
        String joined = ArgParser.joinShell(argv);
        check(ArgParser.split(joined).equals(argv), "quote round-trip: " + joined);
        check(ArgParser.shellQuote("simple").equals("simple"), "safe arg unquoted");
        check(ArgParser.shellQuote("").equals("''"), "empty arg quoted");
    }

    private static void rootRunnerTests() {
        List<String> su0 = RootRunner.su0Command(Arrays.asList("/system/bin/id"));
        check(su0.equals(Arrays.asList("su", "0", "/system/bin/id")), "su 0 command: " + su0);
        List<String> suShell = RootRunner.suShellCommand(Arrays.asList("/system/bin/am", "start", "a b"));
        check(suShell.size() == 3 && suShell.get(0).equals("su") && suShell.get(1).equals("-c"), "su -c command: " + suShell);
        check(ArgParser.split(suShell.get(2)).equals(Arrays.asList("/system/bin/am", "start", "a b")), "su -c round-trip");
        rootRunnerCacheTests();
    }

    /** Programmable RootRunner command seam: counts calls, returns the installed result. */
    private static final class FakeSu implements RootRunner.CommandRunner {
        int calls;
        Result result = Result.failure("no su");
        public Result run(List<String> command, int timeoutSeconds) {
            calls++;
            return result;
        }
    }

    /** Mutable clock for RootRunner's negative-probe retry window. */
    private static final class FakeTicker implements RootRunner.Ticker {
        long now = 1000000L;
        public long nowMs() { return now; }
    }

    private static void rootRunnerCacheTests() {
        FakeSu su = new FakeSu();
        FakeTicker ticker = new FakeTicker();
        RootRunner.setCommandRunnerForTests(su);
        RootRunner.setTickerForTests(ticker);
        try {
            RootRunner.resetCacheForTests();

            // A failed probe tries both su forms, and the negative
            // result stands for the retry window without re-spawning.
            check(!RootRunner.probe(), "probe fails when su never reports uid 0");
            check(su.calls == 2, "failed probe tried both forms, got " + su.calls);
            check(!RootRunner.probe(), "negative probe result stands inside the window");
            check(su.calls == 2, "no re-probe inside the window, got " + su.calls);
            ticker.now += RootRunner.NEGATIVE_RETRY_MS - 1;
            check(!RootRunner.probe(), "negative probe result still stands just before the window ends");
            check(su.calls == 2, "no re-probe just before the window ends, got " + su.calls);

            // After the window the probe retries; a success is then
            // cached, and later probes spawn nothing.
            ticker.now += 1;
            su.result = new Result(0, false, false, "uid=0(root) gid=0(root)", null);
            check(RootRunner.probe(), "probe retries after the window and finds root");
            check(su.calls == 3, "retry after the window tried the first working form only, got " + su.calls);
            check(RootRunner.probe(), "cached working form reports root available");
            check(su.calls == 3, "cached working form spawns no new probe, got " + su.calls);

            // A root execution that fails at the spawn level drops
            // the cached form, so the next probe runs again.
            su.result = Result.failure("Root is unavailable: su could not be started");
            Result spawnFailure = RootRunner.run(Arrays.asList("/system/bin/id"), 5);
            check(!spawnFailure.succeeded(), "spawn-level root failure is returned as a failure");
            su.result = new Result(0, false, false, "uid=0(root) gid=0(root)", null);
            check(RootRunner.probe(), "probe runs again after a spawn-level execution failure");
            check(su.calls == 5, "execution failure dropped the cached form, got " + su.calls);

            // A command that runs and merely exits nonzero keeps the
            // cached form: the form works, the command failed.
            su.result = new Result(1, false, false, "permission denied", null);
            Result exitFailure = RootRunner.run(Arrays.asList("/system/bin/id"), 5);
            check(!exitFailure.succeeded() && exitFailure.exitCode == 1, "nonzero exit is returned as-is");
            check(RootRunner.probe(), "cached form survives a nonzero command exit");
            check(su.calls == 6, "nonzero exit did not drop the cached form, got " + su.calls);
        } finally {
            RootRunner.setCommandRunnerForTests(null);
            RootRunner.setTickerForTests(null);
            RootRunner.resetCacheForTests();
        }
    }

    private static void pidTests() {
        Integer pid = Pid.current();
        check(pid != null && pid.intValue() > 0, "pid current: " + pid);
    }

    private static void schedulerStateTests() {
        // Boot record: one boot id, hashes only, fits the 512-char setting limit.
        SchedulerState empty = SchedulerState.parse("", "");
        check(!empty.isBootCompleted("abcdef12"), "empty boot state");
        SchedulerState one = empty.withBootCompleted(SchedulerState.hash("/system/bin/id", ""));
        String encoded = one.encodeBoot("11111111-2222-3333-4444-555555555555");
        check(encoded != null && encoded.length() < 100, "boot encoded length: " + encoded);
        SchedulerState parsed = SchedulerState.parse(encoded, "");
        check("11111111-2222-3333-4444-555555555555".equals(parsed.bootId()), "boot id round-trip");
        check(parsed.isBootCompleted(SchedulerState.hash("/system/bin/id", "")), "hash round-trip");
        check(!parsed.isBootCompleted(SchedulerState.hash("/system/bin/id", "changed")), "edited task changes hash");
        check(SchedulerState.parse("garbage", "").bootId() == null, "malformed boot state ignored");
        check("old-boot".equals(SchedulerState.parse("v1|old-boot|abcdef12", "").bootId()), "old boot id parsed");

        // Anchor record: round-trip at minute resolution.
        long anchorMs = at(UTC, 2026, 1, 15, 10, 30) + 45000L; // 45 s past the minute
        SchedulerState anchored = SchedulerState.empty().withAnchor("abcd1234", anchorMs);
        Set<String> live = new HashSet<String>(Arrays.asList("abcd1234"));
        String encodedAnchors = anchored.encodeAnchors(live);
        check(encodedAnchors != null, "anchor encoding fits");
        SchedulerState reparsed = SchedulerState.parse("", encodedAnchors);
        check(reparsed.anchorMillis("abcd1234") != null
                && reparsed.anchorMillis("abcd1234").longValue() == anchorMs - 45000L,
                "anchor round-trips at minute resolution, got " + reparsed.anchorMillis("abcd1234"));
        check(reparsed.anchorMillis("missing") == null, "missing anchor is null");
        check(SchedulerState.parse("", "garbage").anchorMillis("abcd1234") == null, "malformed anchors ignored");

        // Dead keys are dropped at encode time; live keys survive.
        SchedulerState two = anchored.withAnchor("deadbeef", anchorMs);
        String onlyLive = two.encodeAnchors(live);
        check(onlyLive != null && !onlyLive.contains("deadbeef"), "dead anchor key dropped: " + onlyLive);

        // Worst case: 32 anchors must fit in one 512-char setting.
        SchedulerState full = SchedulerState.empty();
        Set<String> allKeys = new HashSet<String>();
        for (int i = 0; i < 32; i++) {
            String key = SchedulerState.hash("/system/bin/task" + i, "args " + i);
            allKeys.add(key);
            full = full.withAnchor(key, anchorMs);
        }
        String worst = full.encodeAnchors(allKeys);
        check(worst != null && worst.length() <= SchedulerState.MAX_LENGTH,
                "32 anchors fit the setting limit, length " + (worst == null ? -1 : worst.length()));
        SchedulerState fullParsed = SchedulerState.parse("", worst);
        for (String key : allKeys) {
            check(fullParsed.anchorMillis(key) != null, "worst-case anchor survives: " + key);
        }
    }

    private static void taskSpecTests() {
        // Parsing, including number-as-double (how KS JSON numbers arrive).
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("label", "Rotation");
        row.put("enabled", Boolean.TRUE);
        row.put("executable", "/system/bin/settings");
        row.put("args", "put system user_rotation 1");
        row.put("backend", "Shizuku");
        row.put("schedule", "Weekly");
        row.put("intervalMinutes", Double.valueOf(45.0));
        row.put("timeOfDay", "07:05");
        row.put("weekday", "Friday");
        row.put("missedPolicy", "Skip");
        row.put("jitterMinutes", Double.valueOf(15.0));
        row.put("delaySeconds", Double.valueOf(45.0));
        row.put("timeoutSeconds", Double.valueOf(10.0));
        TaskSpec spec = TaskSpec.fromRow(row);
        check(spec.argv().size() == 5, "argv size");
        check(spec.argv().get(1).equals("put"), "argv put");
        check(spec.timeoutSeconds == 10, "timeout as double");
        check("Weekly".equals(spec.schedule), "schedule parses");
        check(spec.intervalMinutes == 45, "interval as double");
        check("07:05".equals(spec.timeOfDay), "time of day parses");
        check("Friday".equals(spec.weekday), "weekday parses");
        check("Skip".equals(spec.missedPolicy), "missed policy parses");
        check(spec.jitterMinutes == 15, "jitter as double");
        check(spec.delaySeconds == 45, "delay as double");

        // Invalid values fall back or clamp, in the existing style.
        Map<String, Object> bad = new HashMap<String, Object>();
        bad.put("executable", "/system/bin/id");
        bad.put("backend", "Nope");
        bad.put("schedule", "Hourly");
        bad.put("intervalMinutes", Double.valueOf(0.0));
        bad.put("timeOfDay", "25:00");
        bad.put("weekday", "Funday");
        bad.put("missedPolicy", "Whatever");
        bad.put("jitterMinutes", Integer.valueOf(999));
        bad.put("timeoutSeconds", Integer.valueOf(99));
        TaskSpec badSpec = TaskSpec.fromRow(bad);
        check(badSpec.backend.equals("Auto"), "unknown backend becomes Auto");
        check(badSpec.schedule.equals(Schedule.ON_BOOT), "unknown schedule becomes On boot");
        check(badSpec.intervalMinutes == Schedule.MIN_INTERVAL_MINUTES, "interval clamped to minimum");
        Map<String, Object> bigInterval = new HashMap<String, Object>();
        bigInterval.put("executable", "/system/bin/id");
        bigInterval.put("intervalMinutes", Double.valueOf(99999.0));
        check(TaskSpec.fromRow(bigInterval).intervalMinutes == Schedule.MAX_INTERVAL_MINUTES,
                "interval clamped to maximum");
        check(badSpec.timeOfDay.equals(""), "invalid time of day becomes empty");
        check(badSpec.weekday.equals("Monday"), "unknown weekday becomes Monday");
        check(badSpec.missedPolicy.equals(Schedule.CATCH_UP), "unknown missed policy becomes Catch up once");
        check(badSpec.jitterMinutes == 120, "jitter clamped to 120");
        check(badSpec.timeoutSeconds == 30, "timeout clamped to 30");
        check(badSpec.enabled, "enabled defaults to true when absent");
        check(!badSpec.run, "run toggle defaults to false when absent");

        Map<String, Object> runRow = new HashMap<String, Object>();
        runRow.put("executable", "/system/bin/id");
        runRow.put("run", Boolean.TRUE);
        check(TaskSpec.fromRow(runRow).run, "run toggle parses true");
        check(TaskSpec.fromRow(runRow).hash().equals(SchedulerState.hash("/system/bin/id", "")),
                "run toggle does not change task hash");

        // The pre-1.4 stored value "Default" is not a schedule or a
        // backend any more: parsed on its own it falls back like any
        // other unknown value.
        Map<String, Object> defaultRow = new HashMap<String, Object>();
        defaultRow.put("executable", "/system/bin/id");
        defaultRow.put("schedule", "Default");
        defaultRow.put("backend", "Default");
        TaskSpec defaultSpec = TaskSpec.fromRow(defaultRow);
        check(defaultSpec.schedule.equals(Schedule.ON_BOOT), "stored Default schedule falls back to On boot");
        check(defaultSpec.backend.equals("Auto"), "stored Default backend falls back to Auto");

        // Manual is a real schedule value now.
        Map<String, Object> manualRow = new HashMap<String, Object>();
        manualRow.put("executable", "/system/bin/id");
        manualRow.put("schedule", "Manual");
        check(TaskSpec.fromRow(manualRow).schedule.equals(Schedule.MANUAL), "Manual schedule parses");

        // Delay: parsed like the other numbers, clamped to 0..300,
        // absent means 0.
        check(TaskSpec.fromRow(runRow).delaySeconds == 0, "delay defaults to 0 when absent");
        Map<String, Object> delayRow = new HashMap<String, Object>(runRow);
        delayRow.put("delaySeconds", "20");
        check(TaskSpec.fromRow(delayRow).delaySeconds == 20, "delay parses from a string");
        delayRow.put("delaySeconds", Integer.valueOf(999));
        check(TaskSpec.fromRow(delayRow).delaySeconds == TaskSpec.MAX_DELAY_SECONDS, "delay clamped to 300");
        delayRow.put("delaySeconds", Integer.valueOf(-5));
        check(TaskSpec.fromRow(delayRow).delaySeconds == 0, "delay clamped to 0");

        // A delay edit must not move the task's anchor or identity:
        // like backend and timeout, it stays out of the hash and the
        // schedule key.
        Map<String, Object> delayEdit = new HashMap<String, Object>(row);
        delayEdit.put("delaySeconds", Integer.valueOf(120));
        TaskSpec delayed = TaskSpec.fromRow(delayEdit);
        check(delayed.scheduleKey().equals(spec.scheduleKey()), "delay edit keeps the schedule key");
        check(delayed.hash().equals(spec.hash()), "delay edit keeps the task hash");

        // Schedule keys: a schedule edit changes the key (fresh anchor),
        // an unrelated edit does not.
        String baseKey = spec.scheduleKey();
        Map<String, Object> backendEdit = new HashMap<String, Object>(row);
        backendEdit.put("backend", "Root");
        check(TaskSpec.fromRow(backendEdit).scheduleKey().equals(baseKey),
                "backend edit keeps the schedule key");
        Map<String, Object> timeEdit = new HashMap<String, Object>(row);
        timeEdit.put("timeOfDay", "08:05");
        check(!TaskSpec.fromRow(timeEdit).scheduleKey().equals(baseKey),
                "time-of-day edit changes the schedule key");
        Map<String, Object> intervalEdit = new HashMap<String, Object>(row);
        intervalEdit.put("intervalMinutes", Double.valueOf(90.0));
        check(!TaskSpec.fromRow(intervalEdit).scheduleKey().equals(baseKey),
                "interval edit changes the schedule key");
    }

    private static void scheduleTests() {
        long t0 = at(UTC, 2026, 1, 15, 10, 0); // a Thursday

        // Interval: anchored on the last run.
        TaskSpec hourly = task("Interval", 60, "", "Monday", Schedule.CATCH_UP, 0);
        String key = hourly.scheduleKey();
        check(Schedule.nextDueMs(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 59 * 60000L, UTC) == t0 + 60 * 60000L,
                "interval next due is anchor + period");
        check(!Schedule.isDue(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 59 * 60000L, UTC), "interval not due early");
        check(Schedule.isDue(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 60 * 60000L, UTC), "interval due at anchor + period");
        check(Schedule.isDue(hourly, Schedule.INTERVAL, key, null, t0, UTC), "interval with no anchor is due immediately");

        // Interval jitter: within [0, jitter], and stable for one occurrence.
        TaskSpec jittery = task("Interval", 60, "", "Monday", Schedule.CATCH_UP, 30);
        String jitterKey = jittery.scheduleKey();
        long jitterDue = Schedule.nextDueMs(jittery, Schedule.INTERVAL, jitterKey, Long.valueOf(t0), t0, UTC);
        check(jitterDue >= t0 + 60 * 60000L && jitterDue <= t0 + 90 * 60000L,
                "jittered due within [base, base+jitter], got offset " + (jitterDue - t0 - 3600000L) / 60000L + " min");
        check(Schedule.nextDueMs(jittery, Schedule.INTERVAL, jitterKey, Long.valueOf(t0), t0 + 12345L, UTC) == jitterDue,
                "jitter offset is stable across evaluations");

        // Interval Skip: a whole period late abandons the occurrence; the
        // following occurrence becomes next due (the plugin resyncs).
        TaskSpec skipHourly = task("Interval", 60, "", "Monday", Schedule.SKIP, 0);
        String skipKey = skipHourly.scheduleKey();
        check(Schedule.nextDueMs(skipHourly, Schedule.INTERVAL, skipKey, Long.valueOf(t0), t0 + 130 * 60000L, UTC) == t0 + 120 * 60000L,
                "skip interval reports the following occurrence");
        check(Schedule.isDue(skipHourly, Schedule.INTERVAL, skipKey, Long.valueOf(t0), t0 + 90 * 60000L, UTC),
                "skip interval still due when less than a period late");

        // Daily: before and after the time of day.
        TaskSpec daily = task("Daily", 60, "02:30", "Monday", Schedule.CATCH_UP, 0);
        String dailyKey = daily.scheduleKey();
        long jan14run = at(UTC, 2026, 1, 14, 2, 30);
        check(Schedule.nextDueMs(daily, Schedule.DAILY, dailyKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 1, 0), UTC)
                == at(UTC, 2026, 1, 15, 2, 30), "daily next due is today's time");
        check(Schedule.isDue(daily, Schedule.DAILY, dailyKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC),
                "daily catch-up is due after the time");
        check(Schedule.nextDueMs(daily, Schedule.DAILY, dailyKey, Long.valueOf(at(UTC, 2026, 1, 15, 3, 0)), at(UTC, 2026, 1, 15, 3, 0), UTC)
                == at(UTC, 2026, 1, 16, 2, 30), "daily after a run is due tomorrow");

        // Daily Skip: only inside the skip window.
        TaskSpec dailySkip = task("Daily", 60, "02:30", "Monday", Schedule.SKIP, 0);
        String dailySkipKey = dailySkip.scheduleKey();
        check(Schedule.isDue(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 2, 40), UTC),
                "daily skip due inside the window");
        check(!Schedule.isDue(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC),
                "daily skip not due after the window");
        check(Schedule.nextDueMs(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC)
                == at(UTC, 2026, 1, 16, 2, 30), "daily skip next due is tomorrow");

        // Daily with no valid time never fires.
        TaskSpec dailyNoTime = task("Daily", 60, "", "Monday", Schedule.CATCH_UP, 0);
        check(Schedule.nextDueMs(dailyNoTime, Schedule.DAILY, dailyNoTime.scheduleKey(), null, t0, UTC) == -1,
                "daily without a time never fires");

        // Manual never fires on its own: not scheduled, never due,
        // no next due time — with or without an anchor.
        TaskSpec manual = task("Manual", 60, "02:30", "Monday", Schedule.CATCH_UP, 0);
        check(!Schedule.isScheduled(Schedule.MANUAL), "Manual is not a scheduled type");
        check(Schedule.nextDueMs(manual, Schedule.MANUAL, manual.scheduleKey(), null, t0, UTC) == -1,
                "Manual has no next due time");
        check(Schedule.nextDueMs(manual, Schedule.MANUAL, manual.scheduleKey(), Long.valueOf(t0), t0 + 86400000L, UTC) == -1,
                "Manual has no next due time even with an anchor");
        check(!Schedule.isDue(manual, Schedule.MANUAL, manual.scheduleKey(), null, t0, UTC),
                "Manual is never due");

        // Weekly: same weekday, and the wrap to next week after a run.
        // 2026-01-14 is a Wednesday.
        TaskSpec weekly = task("Weekly", 60, "09:00", "Wednesday", Schedule.CATCH_UP, 0);
        String weeklyKey = weekly.scheduleKey();
        long jan7run = at(UTC, 2026, 1, 7, 9, 0);
        check(Schedule.nextDueMs(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(jan7run), at(UTC, 2026, 1, 14, 8, 0), UTC)
                == at(UTC, 2026, 1, 14, 9, 0), "weekly next due is this week's weekday");
        check(Schedule.isDue(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(jan7run), at(UTC, 2026, 1, 16, 12, 0), UTC),
                "weekly catch-up is due later in the week");
        check(Schedule.nextDueMs(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(at(UTC, 2026, 1, 14, 9, 5)), at(UTC, 2026, 1, 14, 10, 0), UTC)
                == at(UTC, 2026, 1, 21, 9, 0), "weekly wraps to next week after a run");

        // DST spring forward (2026-03-08, America/New_York): 02:30 does
        // not exist; Calendar resolves it forward to 03:30 EDT.
        TaskSpec dstDaily = task("Daily", 60, "02:30", "Monday", Schedule.CATCH_UP, 0);
        String dstKey = dstDaily.scheduleKey();
        long dstDue = Schedule.nextDueMs(dstDaily, Schedule.DAILY, dstKey,
                Long.valueOf(at(NEW_YORK, 2026, 3, 7, 2, 30)), at(NEW_YORK, 2026, 3, 7, 12, 0), NEW_YORK);
        Calendar dstCal = Calendar.getInstance(NEW_YORK);
        dstCal.setTimeInMillis(dstDue);
        check(dstCal.get(Calendar.DAY_OF_MONTH) == 8 && dstCal.get(Calendar.HOUR_OF_DAY) == 3
                && dstCal.get(Calendar.MINUTE) == 30,
                "spring-forward daily resolves to Mar 8 03:30, got " + dstCal.getTime());

        // DST fall back (2026-11-01): 01:30 happens twice; Calendar
        // resolves the ambiguous time to the second (standard-time,
        // EST, UTC-5) occurrence, which is the documented behaviour.
        TaskSpec fallDaily = task("Daily", 60, "01:30", "Monday", Schedule.CATCH_UP, 0);
        String fallKey = fallDaily.scheduleKey();
        long fallDue = Schedule.nextDueMs(fallDaily, Schedule.DAILY, fallKey,
                Long.valueOf(at(NEW_YORK, 2026, 10, 31, 1, 30)), at(NEW_YORK, 2026, 10, 31, 12, 0), NEW_YORK);
        Calendar fallCal = Calendar.getInstance(NEW_YORK);
        fallCal.setTimeInMillis(fallDue);
        check(fallCal.get(Calendar.DAY_OF_MONTH) == 1 && fallCal.get(Calendar.HOUR_OF_DAY) == 1
                && fallCal.get(Calendar.MINUTE) == 30 && NEW_YORK.getOffset(fallDue) == -5L * 3600L * 1000L,
                "fall-back daily picks the standard-time 01:30 (EST), got " + fallCal.getTime());
    }

    private static void configTests() {
        // Logging level parsing: only the exact "Verbose" option is
        // verbose; a missing key, Informational itself, an unknown
        // value and an empty value all coerce to Informational.
        Map<String, Object> settings = new HashMap<String, Object>();
        check(!TaskSchedulerPlugin.Config.parse(settings).verbose, "missing logging level is Informational");
        settings.put("loggingLevel", "Informational");
        check(!TaskSchedulerPlugin.Config.parse(settings).verbose, "Informational is not verbose");
        settings.put("loggingLevel", "Verbose");
        check(TaskSchedulerPlugin.Config.parse(settings).verbose, "Verbose parses as verbose");
        settings.put("loggingLevel", "Chatty");
        check(!TaskSchedulerPlugin.Config.parse(settings).verbose, "unknown logging level coerces to Informational");
        settings.put("loggingLevel", "");
        check(!TaskSchedulerPlugin.Config.parse(settings).verbose, "empty logging level coerces to Informational");
        check(!TaskSchedulerPlugin.Config.empty().verbose, "empty config is Informational");

        // Task rows load straight from the stored list: rows with an
        // executable parse, rows without one are dropped, and
        // non-map entries are ignored.
        Map<String, Object> rowA = new HashMap<String, Object>();
        rowA.put("label", "A");
        rowA.put("executable", "/system/bin/id");
        Map<String, Object> rowB = new HashMap<String, Object>();
        rowB.put("label", "No executable");
        Map<String, Object> withRows = new HashMap<String, Object>();
        List<Object> stored = new ArrayList<Object>();
        stored.add(rowA);
        stored.add(rowB);
        stored.add("not a row");
        withRows.put("tasks", stored);
        TaskSchedulerPlugin.Config parsed = TaskSchedulerPlugin.Config.parse(withRows);
        check(parsed.tasks.size() == 1, "config keeps only executable rows, got " + parsed.tasks.size());
        check("A".equals(parsed.tasks.get(0).label), "config parses the stored row");
        check(TaskSchedulerPlugin.Config.parse(new HashMap<String, Object>()).tasks.isEmpty(),
                "missing tasks setting parses as no tasks");
    }

    /** Records log lines; every other host method keeps its interface default. */
    private static final class RecordingHost implements PluginHost {
        final List<String> logs = new ArrayList<String>();
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) { logs.add(message); }
    }

    private static void loggingTests() {
        // The verbose gate: lines reach the log channel only at
        // Verbose, and a missing host drops the line quietly.
        RecordingHost host = new RecordingHost();
        TaskSchedulerPlugin.logVerboseTo(host, false, "quiet");
        check(host.logs.isEmpty(), "verbose line suppressed at Informational");
        TaskSchedulerPlugin.logVerboseTo(host, true, "loud");
        check(host.logs.size() == 1 && host.logs.get(0).equals("loud"), "verbose line forwarded at Verbose");
        TaskSchedulerPlugin.logVerboseTo(null, true, "nowhere");
        check(host.logs.size() == 1, "verbose line with no host is dropped, not thrown");
    }

    private static TaskSpec task(String schedule, int interval, String timeOfDay, String weekday, String missedPolicy, int jitter) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("label", "Task");
        row.put("executable", "/system/bin/id");
        row.put("args", "");
        row.put("schedule", schedule);
        row.put("intervalMinutes", Integer.valueOf(interval));
        row.put("timeOfDay", timeOfDay);
        row.put("weekday", weekday);
        row.put("missedPolicy", missedPolicy);
        row.put("jitterMinutes", Integer.valueOf(jitter));
        return TaskSpec.fromRow(row);
    }

    /** Epoch millis for a local date-time in a time zone, via Calendar. */
    private static long at(TimeZone tz, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(tz);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("TaskSchedulerTest failed: " + message);
    }
}
