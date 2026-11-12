// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

/** The outcome of one command execution, from either backend. */
public final class Result {
    public final int exitCode;
    public final boolean timedOut;
    public final boolean truncated;
    public final String output;
    public final String error;

    public Result(int exitCode, boolean timedOut, boolean truncated, String output, String error) {
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.truncated = truncated;
        this.output = output == null ? "" : output;
        this.error = error;
    }

    public boolean succeeded() { return !timedOut && exitCode == 0; }

    public static Result failure(String error) {
        return new Result(-1, false, false, "", error);
    }

    /** One-line summary for status and log, with output capped. */
    public String summary() {
        if (error != null && error.length() > 0) return error;
        String base = timedOut ? "timed out" : "exit " + exitCode;
        if (truncated) base += ", output truncated";
        return base;
    }
}
