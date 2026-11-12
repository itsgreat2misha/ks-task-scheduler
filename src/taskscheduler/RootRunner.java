// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root execution, outside the Shizuku API.
 *
 * <p>The plugin process itself is not root. It spawns {@code su}, which
 * runs the argv as root. That is the whole root backend: no Shizuku
 * server, grant or binder is involved, which is why it works when
 * Shizuku is down, provided Kiosk Satellite's UID has a silent su grant
 * (for example via Magisk).
 *
 * <p>There is no single su syntax. Two are in common use, and this
 * class tries them in order, once, and remembers which one worked:
 * <ul>
 * <li>{@code su 0 <argv...>} — direct argv, no shell. Some devices'
 * {@code su} rejects the {@code -c} form (it exits 1 even though su
 * itself works), so probing only that form would wrongly report
 * root as unavailable on those devices.
 * <li>{@code su -c <shell string>} — the Magisk-style form, with the
 * argv shell-quoted into one string.
 * </ul>
 * The probe runs {@code /system/bin/id} (no side effects) through
 * each form and caches the first form that reports uid 0. Commands
 * then run exactly once, through the cached form, so a command is
 * never executed twice because the first su form was wrong.
 *
 * <p>Only success is cached. A failed probe is retried at most once
 * a minute, so a root stack that starts just after the plugin (or
 * comes back after a crash) is picked up on a later pass instead of
 * root being treated as unavailable until the process restarts. If
 * a root execution itself fails at the spawn level — su could not
 * be started at all — the cached form is dropped, so the next pass
 * re-probes rather than trusting a form that stopped working.
 */
public final class RootRunner {
    static final int OUTPUT_CAP = 32768;

    private static final String FORM_SU0 = "su0";
    private static final String FORM_SHELL = "suShell";

    /** How long a failed probe stands before a later pass may probe again. */
    static final long NEGATIVE_RETRY_MS = 60000L;

    /** Cached working su form for this session: FORM_SU0, FORM_SHELL, or null (none proven yet). */
    private static volatile String rootForm = null;

    /** When the last probe found no working form, or 0 when no probe has failed. */
    private static volatile long lastNegativeProbeMs = 0;

    /** Runs one su command and reports its outcome; the seam tests use to drive probe/run without a real su. */
    interface CommandRunner {
        Result run(List<String> command, int timeoutSeconds);
    }

    private static final CommandRunner REAL_RUNNER = new CommandRunner() {
        public Result run(List<String> command, int timeoutSeconds) {
            return runProcess(command, timeoutSeconds);
        }
    };

    private static volatile CommandRunner commandRunner = REAL_RUNNER;

    /** Current time in epoch millis; the seam tests use to control the negative-probe retry window. */
    interface Ticker {
        long nowMs();
    }

    private static final Ticker SYSTEM_TICKER = new Ticker() {
        public long nowMs() { return System.currentTimeMillis(); }
    };

    private static volatile Ticker ticker = SYSTEM_TICKER;

    static void setCommandRunnerForTests(CommandRunner runner) {
        commandRunner = runner == null ? REAL_RUNNER : runner;
    }

    static void setTickerForTests(Ticker testTicker) {
        ticker = testTicker == null ? SYSTEM_TICKER : testTicker;
    }

    static void resetCacheForTests() {
        rootForm = null;
        lastNegativeProbeMs = 0;
    }

    private RootRunner() {}

    /** The direct-argv su command for a given argv, for tests. */
    static List<String> su0Command(List<String> argv) {
        List<String> out = new ArrayList<String>();
        out.add("su");
        out.add("0");
        out.addAll(argv);
        return out;
    }

    /** The shell-form su command for a given argv, for tests. */
    static List<String> suShellCommand(List<String> argv) {
        List<String> out = new ArrayList<String>();
        out.add("su");
        out.add("-c");
        out.add(ArgParser.joinShell(argv));
        return out;
    }

    /** Human label for the cached form, for probe messages. */
    static String formLabel() {
        if (FORM_SU0.equals(rootForm)) return "su 0";
        if (FORM_SHELL.equals(rootForm)) return "su -c";
        return "unknown";
    }

    /** Run argv as root. Never throws for a failed command; it returns it. */
    public static Result run(List<String> argv, int timeoutSeconds) {
        if (rootForm == null && !probe()) {
            return Result.failure("Root is unavailable: su did not report uid 0 with either su 0 or su -c");
        }
        List<String> command = FORM_SU0.equals(rootForm) ? su0Command(argv) : suShellCommand(argv);
        Result result = commandRunner.run(command, timeoutSeconds);
        if (result.error != null && result.error.length() > 0) {
            // The su form itself failed — it could not be started, or
            // reported itself unavailable — rather than the command
            // running and exiting nonzero. Drop the cached form so
            // the next pass re-probes instead of trusting it.
            rootForm = null;
        }
        return result;
    }

    /**
     * True when su runs and reports uid 0, through either form. A
     * working form is cached for the session; a failed probe is not —
     * it is retried at most once per {@link #NEGATIVE_RETRY_MS}, so a
     * root stack that appears later is picked up on a later pass.
     */
    public static synchronized boolean probe() {
        if (rootForm != null) return true;
        long now = ticker.nowMs();
        if (lastNegativeProbeMs != 0 && now - lastNegativeProbeMs < NEGATIVE_RETRY_MS) return false;
        Result su0 = commandRunner.run(su0Command(java.util.Arrays.asList("/system/bin/id")), 5);
        if (su0.succeeded() && su0.output.contains("uid=0")) {
            rootForm = FORM_SU0;
            return true;
        }
        Result shell = commandRunner.run(suShellCommand(java.util.Arrays.asList("/system/bin/id")), 5);
        if (shell.succeeded() && shell.output.contains("uid=0")) {
            rootForm = FORM_SHELL;
            return true;
        }
        lastNegativeProbeMs = now;
        return false;
    }

    private static Result runProcess(List<String> command, int timeoutSeconds) {
        Process process = null;
        Thread readerThread = null;
        final StringBuilder output = new StringBuilder();
        final boolean[] truncated = new boolean[1];
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream in = process.getInputStream();
            readerThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        char[] buffer = new char[4096];
                        int read;
                        while ((read = reader.read(buffer)) != -1) {
                            if (output.length() < OUTPUT_CAP) {
                                int take = Math.min(read, OUTPUT_CAP - output.length());
                                output.append(buffer, 0, take);
                                if (take < read) truncated[0] = true;
                            } else {
                                truncated[0] = true;
                            }
                        }
                    } catch (IOException ignored) {}
                }
            }, "ks-task-scheduler-root-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return new Result(-1, true, truncated[0], output.toString(), null);
            }
            try { readerThread.join(2000); } catch (InterruptedException ignored) {}
            return new Result(process.exitValue(), false, truncated[0], output.toString(), null);
        } catch (IOException e) {
            return Result.failure("Root is unavailable: " + message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return Result.failure("Root command was interrupted");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static String message(Exception e) {
        String text = e.getMessage();
        return text == null ? e.toString() : text;
    }
}
