// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.io.File;

/** The current process id, without Android APIs, so the same code runs on a host JVM in tests. */
public final class Pid {
    private Pid() {}

    /**
     * Current PID, or null when it cannot be determined. On Linux,
     * /proc/self is a symlink to /proc/<pid>; resolving it gives the
     * pid directly. The ManagementFactory fallback is guarded with
     * Throwable because it is not reliably present on Android.
     */
    public static Integer current() {
        try {
            String path = new File("/proc/self").getCanonicalPath();
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return Integer.valueOf(path.substring(slash + 1));
        } catch (Throwable ignored) {}
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) return Integer.valueOf(name.substring(0, at));
        } catch (Throwable ignored) {}
        return null;
    }
}
