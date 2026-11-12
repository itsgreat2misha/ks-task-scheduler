// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Task Scheduler: run user-chosen commands on boot, on Kiosk start, or
 * on a schedule (interval, daily, weekly), through root or Shizuku.
 *
 * <p>Design, in one place:
 *
 * <ul>
 * <li>Callbacks ({@code start}, {@code configure}, {@code execute},
 * {@code onEvent}) return within the SDK's three-second deadline. All
 * real work runs on one daemon worker thread, sequentially, so tasks
 * never overlap and a long task cannot stall Kiosk. A second daemon
 * thread is the scheduler clock: it sleeps until the nearest due
 * time, re-evaluating at least once a minute and on every configure,
 * and hands due tasks to the worker.
 * <li>One task is one executable plus arguments, stored that way so
 * the same task can run through either backend. The root backend
 * runs them through su, trying {@code su 0 <argv...>} first and
 * {@code su -c <shell string>} second (see {@link RootRunner}); the
 * Shizuku backend passes them verbatim to {@code host.executeShizuku},
 * which takes an absolute executable and separate arguments and does
 * no shell joining. Use {@code /system/bin/sh} with {@code -c} as the
 * executable when shell syntax is genuinely needed.
 * <li>Shizuku rules are honoured: at most one pending request, at
 * least 250 ms between requests (300 ms here), timeouts clamped to
 * 100-30000 ms, at most 32 argv elements, 4096 characters per argument
 * and 16384 total, output capped at 32768 bytes per stream. The
 * Shizuku callback only stores its result and counts a latch down;
 * it never blocks and never calls the host, so it cannot miss the
 * callback deadline. The worker waits for the latch.
 * <li>Once-per-boot tracking uses the kernel boot id (see
 * {@link BootId}) and the boot record in {@link SchedulerState}.
 * Only successful automatic runs are marked, so a failed boot task
 * retries on the next Kiosk start, and a manual run never marks
 * anything. Scheduled tasks anchor on their last accounted run in
 * the same state record; a failed scheduled task waits for its next
 * occurrence instead of retrying in a hot loop.
 * <li>Scheduling is best-effort: the scheduler thread lives in the
 * Kiosk process, and a plugin has no AlarmManager or wake-lock
 * access, so a due task can fire late (or, with the Skip missed-run
 * policy, not at all) when the device sleeps or Kiosk is not
 * running. The Catch up once policy runs a missed task once at the
 * next opportunity.
 * <li>A task's delay holds back its automatic runs: the worker
 * waits that long immediately before running it, so start tasks can
 * be staggered without a plugin-wide startup delay. Manual runs
 * ignore the delay, and Manual-schedule tasks never run
 * automatically at all — only via a manual run.
 * <li>Backend availability is checked automatically at start, on
 * settings saves and on Shizuku state changes (throttled, on a
 * probe thread) and shown in the plugin status; there is no Check
 * backends button. Execution never relies on that check: Shizuku
 * state is read live for every task, and a root probe that finds
 * no working su is retried on later passes (see
 * {@link RootRunner}), so a backend that starts late or comes back
 * after a crash is picked up without a restart.
 * <li>The Logging level setting gates verbose decision logging.
 * At Verbose, passes also log why each task ran, waited or was
 * skipped — due checks with the anchors they read, anchor saves,
 * backend probes and choices, delay waits — to the same log;
 * Informational keeps the result lines only. In the manifest the
 * setting is declared before the managed state settings, so it
 * also anchors their hidden condition and all three stay hidden.
 * <li>The task list shows each task's schedule type under its
 * name: the manifest's row subtitle is the {@code schedule} row
 * field itself, so there is nothing for the plugin to maintain.
 * <li>Failures never stop later tasks and never throw out of a
 * callback. Every result is logged, summarised in the plugin status
 * and published as text sensors.
 * </ul>
 */
public final class TaskSchedulerPlugin implements KioskPlugin {
    private static final long SHIZUKU_GAP_MS = 300;
    private static final long SCHEDULER_MAX_SLEEP_MS = 60000L;
    /** The automatic backend check runs at most this often, and never overwrites a run summary younger than this. */
    private static final long PROBE_THROTTLE_MS = 60000L;

    private volatile PluginHost host;
    private volatile boolean stopped;
    private volatile Config config = Config.empty();
    private volatile SchedulerState state = SchedulerState.empty();
    private volatile boolean needsShizukuRetry;
    private volatile String currentSetupRun;
    private volatile boolean setupCancelled;
    private volatile String lastNextRunText = "";
    private volatile long lastProbeMs;
    private volatile long lastSummaryMs;
    private final Set<String> runOnceSeen = Collections.synchronizedSet(new HashSet<String>());
    /** Schedule key -> epoch millis before which a failed scheduled task is not retried. In-memory only. */
    private final Map<String, Long> failedUntil = Collections.synchronizedMap(new HashMap<String, Long>());
    private final Object schedulerLock = new Object();

    private ExecutorService worker;
    private Thread schedulerThread;

    static final class Config {
        final List<TaskSpec> tasks;
        final String sessionPid;
        /** True when the Logging level setting is Verbose; gates the decision-level log lines. */
        final boolean verbose;

        Config(List<TaskSpec> tasks, String sessionPid, boolean verbose) {
            this.tasks = tasks;
            this.sessionPid = sessionPid;
            this.verbose = verbose;
        }

        static Config empty() {
            return new Config(Collections.<TaskSpec>emptyList(), "", false);
        }

        @SuppressWarnings("unchecked")
        static Config parse(Map<String, Object> settings) {
            List<TaskSpec> tasks = new ArrayList<TaskSpec>();
            Object raw = settings.get("tasks");
            if (raw instanceof List) {
                for (Object row : (List<?>) raw) {
                    if (!(row instanceof Map)) continue;
                    TaskSpec spec = TaskSpec.fromRow((Map<String, Object>) row);
                    if (spec.executable != null && spec.executable.length() > 0) tasks.add(spec);
                }
            }
            String pid = TaskSpec.string(settings, "sessionPid", "");
            // Logging level: anything but the exact "Verbose"
            // option — a missing or unknown value included — is
            // Informational.
            boolean verbose = "Verbose".equals(TaskSpec.string(settings, "loggingLevel", "Informational"));
            return new Config(tasks, pid, verbose);
        }
    }

    /** Shared per-pass execution context: backend probing and Shizuku pacing are per pass, not per task. */
    private static final class ExecContext {
        Boolean rootInPass;
        long lastShizukuMs;
        boolean shizukuFailure;
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.stopped = false;
        configure(settings);
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "ks-task-scheduler");
                thread.setDaemon(true);
                return thread;
            }
        });
        startSchedulerThread();
        probeBackendsAutomatically();
        publishSensors("Task Scheduler ready.", "No run yet.");
        // Automatic runs must happen on a Kiosk app start, not on a
        // plugin session restart inside the same Kiosk process. KS
        // calls start() for both: explicit enable, master-switch on,
        // and a plugin update all restart the session in the same
        // process, and saving settings does not call start() at all.
        // Distinguish the two with the Kiosk process id, persisted in
        // the hidden sessionPid setting: a first start (never started
        // before) only arms the plugin, a start in the same process as
        // the last start is a session restart and does not run the
        // start/boot pass, and a start in a new process is an app start
        // and does run. Scheduled tasks are exempt from this guard in
        // the other direction: their anchors persist, so a session
        // restart neither re-fires nor loses them, and the scheduler
        // thread simply resumes watching due times. If the pid cannot
        // be determined, fall back to running the pass, so a boot is
        // never silently skipped for that reason.
        // Known limit, stated honestly: disabling, rebooting, and
        // then enabling again looks exactly like an app start with
        // a pre-enabled plugin (new process, stored pid differs),
        // so that one sequence still runs on enable.
        Integer pid = Pid.current();
        String pidStr = pid == null ? "" : String.valueOf(pid.intValue());
        boolean firstStart = config.sessionPid == null || config.sessionPid.length() == 0;
        boolean sameProcess = !firstStart && pidStr.length() > 0 && pidStr.equals(config.sessionPid);
        boolean runAutomatic = !firstStart && !sameProcess;
        if (pidStr.length() > 0 && !pidStr.equals(config.sessionPid)) {
            try {
                Map<String, Object> values = new HashMap<String, Object>();
                values.put("sessionPid", pidStr);
                host.updateSettings(values);
            } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
        }
        if (runAutomatic) {
            statusSafe("Task Scheduler ready.", false);
            scheduleAutomatic();
        } else {
            statusSafe("Task Scheduler armed. Start and boot tasks will run on the next Kiosk start, not on enable or a plugin restart.", false);
        }
    }

    @Override
    public void configure(Map<String, Object> settings) {
        config = Config.parse(settings);
        state = SchedulerState.parse(
                TaskSpec.string(settings, "runState", ""),
                TaskSpec.string(settings, "schedulerState", ""));
        // Only when the worker already exists (a settings save while
        // the plugin is running). During start(), configure runs
        // before the worker is created, and the automatic pass, not
        // a one-shot toggle, owns that start.
        if (worker != null) {
            scheduleRunOnce();
            probeBackendsAutomatically();
            wakeScheduler();
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("runNow".equals(command)) scheduleManual(null);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("shizuku.state".equals(event)) {
            // Shizuku starting late or stopping later is exactly when
            // the informational backend check must refresh; it is
            // throttled, so a burst of state events cannot spawn a
            // burst of probes.
            probeBackendsAutomatically();
            if (Boolean.TRUE.equals(payload.get("granted")) && needsShizukuRetry) {
                needsShizukuRetry = false;
                scheduleAutomatic();
            }
            return;
        }
        if ("settings.button".equals(event) && payload != null) {
            String id = String.valueOf(payload.get("id"));
            String run = payload.get("run") == null ? null : String.valueOf(payload.get("run"));
            if ("runNow".equals(id)) {
                scheduleManual(run);
            } else if ("clearSchedulerState".equals(id)) {
                scheduleClearSchedulerState(run);
            }
            return;
        }
        if ("settings.cancel".equals(event) && payload != null) {
            String run = payload.get("run") == null ? null : String.valueOf(payload.get("run"));
            if (run != null && run.equals(currentSetupRun)) setupCancelled = true;
        }
    }

    @Override
    public void stop() {
        stopped = true;
        wakeScheduler();
        Thread thread = schedulerThread;
        schedulerThread = null;
        if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
        ExecutorService executor = worker;
        worker = null;
        host = null;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    // -- Scheduler thread -------------------------------------------------

    private void startSchedulerThread() {
        Thread thread = new Thread(new Runnable() {
            public void run() { schedulerLoop(); }
        }, "ks-task-scheduler-clock");
        thread.setDaemon(true);
        schedulerThread = thread;
        thread.start();
    }

    private void wakeScheduler() {
        synchronized (schedulerLock) { schedulerLock.notifyAll(); }
    }

    /**
     * Sleep until the nearest scheduled task is due, then hand the due
     * tasks to the worker. The sleep is capped so clock changes, missed
     * notifications and configure races cost at most a minute of delay;
     * due-ness is always recomputed from the persisted anchors, never
     * from the sleep itself.
     */
    private void schedulerLoop() {
        while (!stopped) {
            long sleepMs = SCHEDULER_MAX_SLEEP_MS;
            try {
                long now = System.currentTimeMillis();
                Long next = nearestDueMs(config, state, now);
                publishNextRun(config, state, now);
                if (next != null) {
                    if (next.longValue() <= now) {
                        scheduleDuePass();
                        sleepMs = 1000L;
                    } else {
                        sleepMs = Math.min(SCHEDULER_MAX_SLEEP_MS, next.longValue() - now);
                    }
                }
            } catch (RuntimeException e) {
                logSafe("Scheduler loop failed: " + e.getMessage());
            }
            synchronized (schedulerLock) {
                if (stopped) break;
                try { schedulerLock.wait(Math.max(50L, sleepMs)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** Nearest due time across enabled scheduled tasks, or null when there are none. Failed tasks count from their retry time. */
    private Long nearestDueMs(Config snapshot, SchedulerState snapshotState, long now) {
        Long best = null;
        for (int i = 0; i < snapshot.tasks.size(); i++) {
            TaskSpec spec = snapshot.tasks.get(i);
            if (!spec.enabled) continue;
            if (!Schedule.isScheduled(spec.schedule)) continue;
            String key = spec.scheduleKey();
            long due = Schedule.nextDueMs(spec, spec.schedule, key, snapshotState.anchorMillis(key), now, TimeZone.getDefault());
            if (due < 0) continue;
            Long retry = failedUntil.get(key);
            if (retry != null && retry.longValue() > due) due = retry.longValue();
            if (best == null || due < best.longValue()) best = Long.valueOf(due);
        }
        return best;
    }

    private void scheduleDuePass() {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runDuePass(); }
        });
    }

    /** Run every scheduled task that is due now. Runs on the worker, so it never overlaps other passes. */
    private void runDuePass() {
        Config snapshot = config;
        SchedulerState snapshotState = state;
        long now = System.currentTimeMillis();
        logVerbose("Due pass started.");
        ExecContext context = new ExecContext();
        List<String> lines = new ArrayList<String>();
        int ran = 0;
        for (int i = 0; i < snapshot.tasks.size(); i++) {
            if (stopped) break;
            TaskSpec spec = snapshot.tasks.get(i);
            if (!spec.enabled) {
                logVerbose(spec.label + ": skipped (disabled)");
                continue;
            }
            String effective = spec.schedule;
            if (!Schedule.isScheduled(effective)) continue;
            String key = spec.scheduleKey();
            Long anchor = snapshotState.anchorMillis(key);
            if (Schedule.INTERVAL.equals(effective) && Schedule.SKIP.equals(spec.missedPolicy) && anchor != null) {
                long periodMs = spec.intervalMinutes * 60000L;
                long base = anchor.longValue() + periodMs;
                long due = base + Schedule.jitterOffsetMs(spec, key, base);
                if (now >= due + periodMs) {
                    // Skip, a whole period late: abandon the missed
                    // occurrences and re-anchor without running.
                    logVerbose(spec.label + ": skipped (a whole interval late; re-anchored)");
                    snapshotState = snapshotState.withAnchor(key, now);
                    state = snapshotState;
                    persistAnchors(snapshot, snapshotState);
                    continue;
                }
            }
            if (!Schedule.isDue(spec, effective, key, anchor, now, TimeZone.getDefault())) {
                long nextDue = Schedule.nextDueMs(spec, effective, key, anchor, now, TimeZone.getDefault());
                logVerbose(spec.label + ": not due (anchor "
                        + (anchor == null ? "none" : timeText(anchor.longValue()))
                        + ", next due " + (nextDue < 0 ? "never" : timeText(nextDue)) + ")");
                continue;
            }
            Long retry = failedUntil.get(key);
            if (retry != null && now < retry.longValue()) {
                logVerbose(spec.label + ": not due (waiting to retry a failed run at " + timeText(retry.longValue()) + ")");
                continue;
            }
            logVerbose(spec.label + ": due (anchor " + (anchor == null ? "none" : timeText(anchor.longValue())) + ")");
            if (!waitForDelay(spec)) break;
            Result result = executeTask(spec, context);
            ran++;
            if (result.succeeded()) {
                failedUntil.remove(key);
                lines.add(spec.label + ": ok");
                logSafe(spec.label + ": ok (scheduled)");
                long doneAt = System.currentTimeMillis();
                snapshotState = snapshotState.withAnchor(key, doneAt);
                state = snapshotState;
                persistAnchors(snapshot, snapshotState);
            } else {
                lines.add(spec.label + ": failed (" + result.summary() + ")");
                logSafe(spec.label + ": failed (" + result.summary() + ")" + outputSuffix(result));
                // No hot loop: the next attempt is the occurrence
                // after the one that just failed.
                failedUntil.put(key, Long.valueOf(retryAfterFailureMs(spec, effective, key, anchor, now)));
            }
        }
        if (context.shizukuFailure) needsShizukuRetry = true;
        if (ran > 0) {
            String summary = "Last run: scheduled pass, " + lines.size() + " task(s).";
            publishSensors(summary, joinLines(lines));
            lastSummaryMs = System.currentTimeMillis();
            statusSafe(summary, false);
        }
        logVerbose("Due pass finished: " + ran + " task(s) ran.");
        publishNextRun(config, state, System.currentTimeMillis());
    }

    /**
     * In-memory backoff after a scheduled task fails: the epoch millis
     * of the occurrence after the one that just failed, computed from
     * the task's current anchor. Falls back to one minute from now when
     * no next occurrence can be computed. Shared by runDuePass and the
     * scheduled branch of runPass so both passes back off identically.
     */
    private long retryAfterFailureMs(TaskSpec spec, String effective, String key, Long anchor, long now) {
        long failedDue = Schedule.nextDueMs(spec, effective, key, anchor, now, TimeZone.getDefault());
        Long nextAnchor = Long.valueOf(failedDue >= 0 ? failedDue : now);
        long retryAt = Schedule.nextDueMs(spec, effective, key, nextAnchor, now, TimeZone.getDefault());
        return retryAt >= 0 ? retryAt : now + 60000L;
    }

    private void persistAnchors(Config snapshot, SchedulerState snapshotState) {
        Set<String> liveKeys = new HashSet<String>();
        for (int i = 0; i < snapshot.tasks.size(); i++) {
            TaskSpec spec = snapshot.tasks.get(i);
            liveKeys.add(spec.scheduleKey());
        }
        String encoded = snapshotState.encodeAnchors(liveKeys);
        if (encoded == null) {
            logSafe("Scheduler state would exceed the setting limit; anchors were not persisted.");
            return;
        }
        try {
            PluginHost snapshotHost = safeHost();
            if (snapshotHost != null) {
                Map<String, Object> values = new HashMap<String, Object>();
                values.put("schedulerState", encoded);
                snapshotHost.updateSettings(values);
                logVerbose("Scheduler anchors saved: " + snapshotState.anchorSnapshot().size() + " anchor(s).");
            }
        } catch (RuntimeException e) {
            logSafe("Could not persist scheduler state: " + e.getMessage());
        } catch (LinkageError e) {
            logSafe("This Kiosk Satellite version cannot persist scheduler state; scheduled tasks will re-anchor on the next start.");
        }
    }

    private void publishNextRun(Config snapshot, SchedulerState snapshotState, long now) {
        Long next = nearestDueMs(snapshot, snapshotState, now);
        String text;
        if (next == null) {
            text = "No scheduled tasks.";
        } else if (next.longValue() <= now) {
            text = "A scheduled task is due now.";
        } else {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            text = "Next scheduled run: " + format.format(new Date(next.longValue())) + ".";
        }
        if (!text.equals(lastNextRunText)) {
            lastNextRunText = text;
            publishNextRunSensor(text);
        }
    }

    // -- Passes -----------------------------------------------------------

    private void scheduleAutomatic() {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runPass(false, null); }
        });
    }

    private void scheduleManual(final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runPass(true, setupRun); }
        });
    }

    private void scheduleSingleByHash(final String hash, final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runSinglePass(hash, setupRun); }
        });
    }

    /**
     * One-shot per-row runs. A task row's Run toggle is a request,
     * not a state: when configure sees it set, schedule that task
     * once (manual semantics: ignores its schedule, never marks
     * state), then reset every Run toggle to false. The reset is
     * retried on the next configure if it fails, but a hash already
     * scheduled in this session is never scheduled again, so a
     * failed reset cannot cause a second run.
     */
    private void scheduleRunOnce() {
        ExecutorService executor = worker;
        if (executor == null || stopped) return;
        List<TaskSpec> toRun = new ArrayList<TaskSpec>();
        boolean anyRunFlag = false;
        for (int i = 0; i < config.tasks.size(); i++) {
            TaskSpec spec = config.tasks.get(i);
            if (!spec.run) continue;
            anyRunFlag = true;
            if (runOnceSeen.contains(spec.hash())) continue;
            runOnceSeen.add(spec.hash());
            toRun.add(spec);
        }
        if (anyRunFlag) resetRunToggles();
        for (int i = 0; i < toRun.size(); i++) {
            scheduleSingleByHash(toRun.get(i).hash(), null);
        }
    }

    /**
     * The tasks list rebuilt from the parsed config. The whole-list
     * write-back (the Run toggle reset below) goes through here so a
     * rewrite always carries the full parsed row, every current
     * field included.
     */
    private List<Map<String, Object>> taskRows(boolean resetRunFlags) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < config.tasks.size(); i++) {
            TaskSpec spec = config.tasks.get(i);
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("label", spec.label);
            row.put("enabled", Boolean.valueOf(spec.enabled));
            row.put("executable", spec.executable);
            row.put("args", spec.args);
            row.put("backend", spec.backend);
            row.put("schedule", spec.schedule);
            row.put("intervalMinutes", Integer.valueOf(spec.intervalMinutes));
            row.put("timeOfDay", spec.timeOfDay);
            row.put("weekday", spec.weekday);
            row.put("missedPolicy", spec.missedPolicy);
            row.put("jitterMinutes", Integer.valueOf(spec.jitterMinutes));
            row.put("delaySeconds", Integer.valueOf(spec.delaySeconds));
            row.put("timeoutSeconds", Integer.valueOf(spec.timeoutSeconds));
            row.put("run", Boolean.valueOf(!resetRunFlags && spec.run));
            rows.add(row);
        }
        return rows;
    }

    private void resetRunToggles() {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try {
            Map<String, Object> values = new HashMap<String, Object>();
            values.put("tasks", taskRows(true));
            snapshotHost.updateSettings(values);
        } catch (RuntimeException e) {
            logSafe("Could not reset Run toggles: " + e.getMessage());
        } catch (LinkageError e) {
            logSafe("Could not reset Run toggles: this Kiosk Satellite version cannot update list settings.");
        }
    }

    private void scheduleClearSchedulerState(final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { clearSchedulerStatePass(setupRun); }
        });
    }

    private void clearSchedulerStatePass(String setupRun) {
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            state = SchedulerState.empty();
            failedUntil.clear();
            PluginHost snapshotHost = safeHost();
            if (snapshotHost != null) {
                Map<String, Object> values = new HashMap<String, Object>();
                values.put("runState", "");
                values.put("schedulerState", "");
                snapshotHost.updateSettings(values);
            }
            String message = "Scheduler state cleared. Once per boot tasks will run again on the next Kiosk start, and scheduled tasks will re-anchor.";
            statusSafe(message, false);
            logSafe(message);
            reportSetup(setupRun, "success", message);
            wakeScheduler();
        } catch (RuntimeException e) {
            logSafe("Could not clear scheduler state: " + e.getMessage());
            reportSetup(setupRun, "error", "Could not clear scheduler state: " + e.getMessage());
        } catch (LinkageError e) {
            reportSetup(setupRun, "error", "This Kiosk Satellite version cannot clear scheduler state.");
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    /** Run one task by hash. Manual: ignores its schedule, never marks state. */
    private void runSinglePass(String hash, String setupRun) {
        Config snapshot = config;
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            TaskSpec found = null;
            for (int i = 0; i < snapshot.tasks.size(); i++) {
                if (snapshot.tasks.get(i).hash().equals(hash)) { found = snapshot.tasks.get(i); break; }
            }
            if (found == null) {
                String message = "That task no longer exists.";
                statusSafe(message, true);
                logSafe(message);
                reportSetup(setupRun, "error", message);
                return;
            }
            if (!found.enabled) {
                String message = found.label + ": disabled, not run.";
                statusSafe(message, true);
                logSafe(message);
                reportSetup(setupRun, "error", message);
                return;
            }
            if (stopped || setupCancelled) return;
            logVerbose("Running " + found.label + " on request (single-task run).");
            ExecContext context = new ExecContext();
            Result result = executeTask(found, context);
            if (stopped) return;
            String line = result.succeeded()
                    ? found.label + ": ok"
                    : found.label + ": failed (" + result.summary() + ")";
            publishSensors("Last run: " + line, line);
            lastSummaryMs = System.currentTimeMillis();
            statusSafe(line, !result.succeeded());
            logSafe(line + (result.succeeded() ? "" : outputSuffix(result)));
            reportSetup(setupRun, result.succeeded() ? "success" : "error", line);
        } catch (RuntimeException e) {
            logSafe("Run task failed: " + e.getMessage());
            statusSafe("Run task failed: " + e.getMessage(), true);
            reportSetup(setupRun, "error", "Run task failed: " + e.getMessage());
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    /**
     * The start/boot pass (automatic) or the run-everything pass
     * (manual). Automatic runs include start and boot tasks, plus any
     * scheduled task that is due at pass time — which is what makes
     * Catch up once work when Kiosk was not running at the due time.
     * The scheduler thread covers due times that pass while Kiosk is
     * running. Manual runs ignore schedules and state entirely.
     */
    private void runPass(boolean manual, String setupRun) {
        Config snapshot = config;
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            List<TaskSpec> tasks = snapshot.tasks;
            if (tasks.isEmpty()) {
                String message = "No tasks configured.";
                publishSensors(message, "No run yet.");
                statusSafe(message, false);
                reportSetup(setupRun, "success", message);
                return;
            }
            logVerbose((manual ? "Manual" : "Automatic") + " pass started: " + tasks.size() + " task(s) configured.");
            String bootId = BootId.current();
            SchedulerState snapshotState = state;
            boolean bootKnown = bootId != null;
            SchedulerState working = (bootKnown && bootId.equals(snapshotState.bootId()))
                    ? snapshotState
                    : new SchedulerState(null, Collections.<String>emptySet(),
                            Collections.<String, Long>emptyMap());
            // Anchors live independently of the boot record: carry them
            // over even when the boot changed.
            SchedulerState anchors = snapshotState;
            boolean bootChanged = false;
            boolean anchorsChanged = false;
            ExecContext context = new ExecContext();
            int ok = 0, skipped = 0, failed = 0;
            List<String> lines = new ArrayList<String>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < tasks.size(); i++) {
                if (stopped || setupCancelled) break;
                TaskSpec spec = tasks.get(i);
                if (!spec.enabled) {
                    logVerbose(spec.label + ": skipped (disabled)");
                    continue;
                }
                String effective = spec.schedule;
                boolean oncePerBoot = Schedule.ON_BOOT.equals(effective);
                boolean onStart = Schedule.ON_START.equals(effective);
                boolean scheduled = Schedule.isScheduled(effective);
                if (!manual && !oncePerBoot && !onStart && !scheduled) {
                    logVerbose(spec.label + ": not run automatically (Manual schedule)");
                    continue;
                }
                String key = spec.scheduleKey();
                if (!manual && scheduled) {
                    Long anchor = anchors.anchorMillis(key);
                    if (Schedule.INTERVAL.equals(effective) && Schedule.SKIP.equals(spec.missedPolicy) && anchor != null) {
                        long periodMs = spec.intervalMinutes * 60000L;
                        long base = anchor.longValue() + periodMs;
                        long dueAt = base + Schedule.jitterOffsetMs(spec, key, base);
                        if (now >= dueAt + periodMs) {
                            // Skip, a whole period late: abandon the
                            // missed occurrences and re-anchor without
                            // running (same resync as runDuePass).
                            logVerbose(spec.label + ": skipped (a whole interval late; re-anchored)");
                            anchors = anchors.withAnchor(key, now);
                            anchorsChanged = true;
                            continue;
                        }
                    }
                    Long retry = failedUntil.get(key);
                    if (!Schedule.isDue(spec, effective, key, anchor, now, TimeZone.getDefault())) {
                        long nextDue = Schedule.nextDueMs(spec, effective, key, anchor, now, TimeZone.getDefault());
                        logVerbose(spec.label + ": not due (anchor "
                                + (anchor == null ? "none" : timeText(anchor.longValue()))
                                + ", next due " + (nextDue < 0 ? "never" : timeText(nextDue)) + ")");
                        continue;
                    }
                    if (retry != null && now < retry.longValue()) {
                        logVerbose(spec.label + ": not due (waiting to retry a failed run at " + timeText(retry.longValue()) + ")");
                        continue;
                    }
                    logVerbose(spec.label + ": due (anchor " + (anchor == null ? "none" : timeText(anchor.longValue())) + ")");
                }
                if (!manual && oncePerBoot && bootKnown && working.isBootCompleted(spec.hash())) {
                    skipped++;
                    lines.add(spec.label + ": skipped (already ran this boot)");
                    logVerbose(spec.label + ": skipped (already ran this boot)");
                    continue;
                }
                if (!manual && !waitForDelay(spec)) break;
                Result result = executeTask(spec, context);
                if (stopped) break;
                if (result.succeeded()) {
                    ok++;
                    lines.add(spec.label + ": ok");
                    logSafe(spec.label + ": ok");
                    if (!manual && oncePerBoot && bootKnown) {
                        working = working.withBootCompleted(spec.hash());
                        bootChanged = true;
                    }
                    if (!manual && scheduled) {
                        anchors = anchors.withAnchor(key, System.currentTimeMillis());
                        anchorsChanged = true;
                        failedUntil.remove(key);
                    }
                } else {
                    failed++;
                    lines.add(spec.label + ": failed (" + result.summary() + ")");
                    logSafe(spec.label + ": failed (" + result.summary() + ")" + outputSuffix(result));
                    if (!manual && scheduled) {
                        // Same backoff as runDuePass: a scheduled task
                        // that fails here must not retry almost
                        // immediately in a due pass.
                        failedUntil.put(key, Long.valueOf(retryAfterFailureMs(spec, effective, key, anchors.anchorMillis(key), now)));
                    }
                }
                if (!reportSetup(setupRun, "running", lines.get(lines.size() - 1))) break;
            }
            needsShizukuRetry = context.shizukuFailure && !manual;
            String summary = "Last run: " + ok + " ok, " + skipped + " skipped, " + failed + " failed.";
            String detail = joinLines(lines);
            publishSensors(summary, detail);
            lastSummaryMs = System.currentTimeMillis();
            statusSafe(summary, failed > 0);
            logVerbose("Pass finished: " + ok + " ok, " + skipped + " skipped, " + failed + " failed.");
            if (!manual) {
                // Boot completions come from the working copy, anchors
                // from the anchors copy; both started from the same
                // parsed state.
                SchedulerState merged = mergeState(working, anchors, bootKnown, bootId);
                state = merged;
                if (bootChanged && bootKnown) {
                    String encoded = merged.encodeBoot(bootId);
                    if (encoded != null) {
                        try {
                            PluginHost snapshotHost = safeHost();
                            if (snapshotHost != null) {
                                Map<String, Object> values = new HashMap<String, Object>();
                                values.put("runState", encoded);
                                snapshotHost.updateSettings(values);
                                logVerbose("Run state saved: " + merged.bootCompletedSnapshot().size() + " boot task(s) marked for this boot.");
                            }
                        } catch (RuntimeException e) {
                            logSafe("Could not persist run state: " + e.getMessage());
                        } catch (LinkageError e) {
                            logSafe("This Kiosk Satellite version cannot persist run state; Once per boot tasks will run again on the next start.");
                        }
                    } else {
                        logSafe("Run state would exceed the setting limit; Once per boot tasks will run again on the next start.");
                    }
                }
                if (anchorsChanged) persistAnchors(snapshot, merged);
                publishNextRun(config, state, System.currentTimeMillis());
            }
            reportSetup(setupRun, failed > 0 ? "error" : "success", summary);
        } catch (RuntimeException e) {
            logSafe("Task Scheduler run failed: " + e.getMessage());
            statusSafe("Task Scheduler run failed: " + e.getMessage(), true);
            reportSetup(setupRun, "error", "Task Scheduler run failed: " + e.getMessage());
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    /** Combine the boot record from one copy with the anchors from another. */
    private static SchedulerState mergeState(SchedulerState bootSide, SchedulerState anchorSide, boolean bootKnown, String bootId) {
        String mergedBootId = bootKnown ? bootId : anchorSide.bootId();
        Set<String> completed = bootKnown
                ? bootSide.bootCompletedSnapshot()
                : Collections.<String>emptySet();
        return new SchedulerState(mergedBootId, completed, anchorSide.anchorSnapshot());
    }

    /**
     * The per-task delay of an automatic run: the worker waits this
     * long immediately before the task executes, so tasks in one pass
     * can be staggered. Manual runs never call this. Returns false
     * when the pass should stop instead (plugin stopping, or the
     * settings run that owns the pass was cancelled).
     */
    private boolean waitForDelay(TaskSpec spec) {
        if (spec.delaySeconds <= 0) return true;
        logVerbose(spec.label + ": waiting " + spec.delaySeconds + " s (task delay) before running.");
        try { Thread.sleep(spec.delaySeconds * 1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return !stopped && !setupCancelled;
    }

    /** Run one task through its backend, honouring Shizuku pacing within the pass. */
    private Result executeTask(TaskSpec spec, ExecContext context) {
        BackendChoice choice = chooseBackend(spec, context.rootInPass);
        if (choice.rootInPass != null) context.rootInPass = choice.rootInPass;
        if (choice.backend == null) {
            if (choice.failure != null && choice.failure.contains("Shizuku")) context.shizukuFailure = true;
            return Result.failure(choice.failure == null ? "No backend available" : choice.failure);
        }
        logVerbose(spec.label + ": running via " + choice.backend + " (backend setting: " + spec.backend + ").");
        if ("Shizuku".equals(choice.backend)) {
            long now = System.currentTimeMillis();
            long wait = SHIZUKU_GAP_MS - (now - context.lastShizukuMs);
            if (context.lastShizukuMs != 0 && wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure("Interrupted while waiting to run");
                }
            }
            context.lastShizukuMs = System.currentTimeMillis();
            Result result = runShizuku(spec);
            if (!result.succeeded()) context.shizukuFailure = true;
            return result;
        }
        return RootRunner.run(spec.argv(), spec.timeoutSeconds);
    }

    private static final class BackendChoice {
        final String backend;
        final String failure;
        final Boolean rootInPass;
        BackendChoice(String backend, String failure, Boolean rootInPass) {
            this.backend = backend; this.failure = failure; this.rootInPass = rootInPass;
        }
    }

    private BackendChoice chooseBackend(TaskSpec spec, Boolean rootInPass) {
        String wanted = spec.backend;
        if ("Root".equals(wanted)) return new BackendChoice("Root", null, rootInPass);
        if ("Shizuku".equals(wanted)) {
            String state = shizukuFailureReason();
            if (state != null) return new BackendChoice(null, state, rootInPass);
            return new BackendChoice("Shizuku", null, rootInPass);
        }
        // Auto: root first, Shizuku second.
        if (rootInPass == null) {
            rootInPass = RootRunner.probe();
            logVerbose("Root probe: " + (Boolean.TRUE.equals(rootInPass) ? "available" : "unavailable") + ".");
        }
        if (Boolean.TRUE.equals(rootInPass)) return new BackendChoice("Root", null, rootInPass);
        String state = shizukuFailureReason();
        if (state != null) return new BackendChoice(null, "No backend available (root unavailable; Shizuku: " + state + ")", rootInPass);
        return new BackendChoice("Shizuku", null, rootInPass);
    }

    private String shizukuFailureReason() {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return "plugin stopped";
        try {
            Map<String, Object> state = snapshotHost.shizukuState();
            if (state == null) return "Shizuku state is unavailable";
            if (!Boolean.TRUE.equals(state.get("granted"))) {
                Object status = state.get("status");
                return "Shizuku is not ready (" + (status == null ? "unknown" : String.valueOf(status)) + ")";
            }
            return null;
        } catch (RuntimeException e) {
            return "Shizuku is unavailable (" + message(e) + ")";
        } catch (LinkageError e) {
            return "Shizuku is unavailable on this Kiosk Satellite version";
        }
    }

    private Result runShizuku(TaskSpec spec) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return Result.failure("Plugin stopped");
        List<String> argv = spec.argv();
        if (spec.executable == null || !spec.executable.startsWith("/")) {
            return Result.failure("Shizuku needs an absolute executable path, got: " + spec.executable);
        }
        if (argv.size() > 32) return Result.failure("Shizuku allows at most 32 arguments, got " + argv.size());
        int total = 0;
        for (int i = 0; i < argv.size(); i++) {
            if (argv.get(i).length() > 4096) return Result.failure("Shizuku allows at most 4096 characters per argument");
            total += argv.get(i).length();
        }
        if (total > 16384) return Result.failure("Shizuku allows at most 16384 characters in total");
        int timeoutMs = spec.timeoutSeconds * 1000;
        if (timeoutMs < 100) timeoutMs = 100;
        if (timeoutMs > 30000) timeoutMs = 30000;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final Object[] data = new Object[1];
        final String[] error = new String[1];
        try {
            snapshotHost.executeShizuku(argv.toArray(new String[0]), timeoutMs, new PluginHost.CommandCallback() {
                public void onResult(boolean resultOk, Object resultData, String resultError) {
                    ok[0] = resultOk;
                    data[0] = resultData;
                    error[0] = resultError;
                    latch.countDown();
                }
            });
        } catch (RuntimeException e) {
            return Result.failure("Shizuku request failed: " + message(e));
        } catch (LinkageError e) {
            return Result.failure("Shizuku is unavailable on this Kiosk Satellite version");
        }
        try {
            boolean arrived = latch.await(timeoutMs + 10000L, TimeUnit.MILLISECONDS);
            if (!arrived) return Result.failure("Shizuku callback did not arrive");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("Shizuku command was interrupted");
        }
        if (!ok[0]) return Result.failure(error[0] == null ? "Shizuku request failed." : error[0]);
        if (!(data[0] instanceof Map)) return Result.failure("Shizuku returned no result");
        Map<?, ?> result = (Map<?, ?>) data[0];
        boolean timedOut = Boolean.TRUE.equals(result.get("timedOut"));
        boolean truncated = Boolean.TRUE.equals(result.get("truncated"));
        int exitCode = -1;
        if (result.get("exitCode") instanceof Number) exitCode = ((Number) result.get("exitCode")).intValue();
        String stdout = result.get("stdout") == null ? "" : String.valueOf(result.get("stdout"));
        String stderr = result.get("stderr") == null ? "" : String.valueOf(result.get("stderr"));
        String output = stdout;
        if (stderr.length() > 0) output += "\n[stderr] " + stderr;
        if (output.length() > RootRunner.OUTPUT_CAP) {
            output = output.substring(0, RootRunner.OUTPUT_CAP);
            truncated = true;
        }
        return new Result(exitCode, timedOut, truncated, output, null);
    }

    /**
     * The backend check used to be a settings button; it now runs on
     * its own at plugin start, on settings saves and on Shizuku
     * state changes, on the probe
     * thread (probing spawns su, so it never runs on a callback
     * thread). Throttled to at most once a minute, so a burst of
     * saves cannot spawn a burst of probes. The result is always
     * logged and goes to the plugin status unless a run summary was
     * posted within the last minute — that summary is fresher news.
     * A failed check only logs; it never breaks start or configure.
     */
    private void probeBackendsAutomatically() {
        long now = System.currentTimeMillis();
        if (now - lastProbeMs < PROBE_THROTTLE_MS) return;
        lastProbeMs = now;
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    boolean root = RootRunner.probe();
                    String shizuku = shizukuFailureReason();
                    String message = "Root (su): " + (root ? "available (uid 0 via " + RootRunner.formLabel() + ")" : "unavailable (neither su 0 nor su -c reported uid 0)")
                            + ". Shizuku: " + (shizuku == null ? "ready" : shizuku) + ".";
                    logSafe(message);
                    if (System.currentTimeMillis() - lastSummaryMs >= PROBE_THROTTLE_MS) {
                        statusSafe(message, false);
                    }
                } catch (RuntimeException e) {
                    logSafe("Backend check failed: " + e.getMessage());
                }
            }
        }, "ks-task-scheduler-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean reportSetup(String setupRun, String state, String message) {
        if (setupRun == null) return true;
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return false;
        try {
            return snapshotHost.setupResult(setupRun, state, truncate(message, 1000));
        } catch (RuntimeException e) {
            return true;
        } catch (LinkageError e) {
            return true;
        }
    }

    private PluginHost safeHost() {
        if (stopped) return null;
        return host;
    }

    private void logSafe(String message) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try { snapshotHost.log(truncate(message, 1000)); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    /**
     * The verbose logging path: decision-level lines go to the same
     * log channel as {@link #logSafe}, but only when the Logging
     * level setting is Verbose. Static and host-explicit so the
     * gating itself is unit-testable.
     */
    static void logVerboseTo(PluginHost host, boolean verbose, String message) {
        if (!verbose || host == null) return;
        try { host.log(truncate(message, 1000)); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void logVerbose(String message) {
        logVerboseTo(safeHost(), config.verbose, message);
    }

    private void statusSafe(String message, boolean error) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try { snapshotHost.status(truncate(message, 1000), error); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void publishSensors(String status, String lastRun) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try {
            snapshotHost.publishTextSensor("status", "Task Scheduler status", truncate(status, 512));
            snapshotHost.publishTextSensor("last_run", "Task Scheduler last run", truncate(lastRun, 512));
        } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void publishNextRunSensor(String text) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try {
            snapshotHost.publishTextSensor("next_run", "Task Scheduler next run", truncate(text, 512));
        } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    /** Short local timestamp for verbose log lines, matching the next-run sensor's format. */
    private static String timeText(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epochMs));
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(" | ");
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private static String outputSuffix(Result result) {
        if (result.output == null || result.output.length() == 0) return "";
        String text = result.output.trim();
        if (text.length() == 0) return "";
        return " Output: " + truncate(text, 200);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null ? e.toString() : text;
    }
}
