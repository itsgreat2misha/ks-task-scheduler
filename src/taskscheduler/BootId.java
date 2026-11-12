// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * The current boot, as a stable string.
 *
 * <p>{@code /proc/sys/kernel/random/boot_id} is preferred: the kernel
 * generates a fresh UUID on every boot, it does not move while the device
 * is up, and it does not depend on the wall clock. That last point matters
 * on devices whose RTC resets on reboot, where a boot time computed from
 * the wall clock can repeat across boots and falsely skip commands.
 *
 * <p>Fallbacks, in order: the kernel boot time from {@code /proc/stat}
 * ({@code btime}), then null. A null boot id means once-per-boot tracking
 * cannot be trusted, so the caller must run (not skip) and must not mark
 * the command as completed for the boot.
 */
public final class BootId {
    private BootId() {}

    public static String current() {
        String id = readFirstLine(new File("/proc/sys/kernel/random/boot_id"));
        if (id != null && id.length() > 0) return id;
        String btime = readBtime();
        if (btime != null) return "btime:" + btime;
        return null;
    }

    private static String readFirstLine(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static String readBtime() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File("/proc/stat"));
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("btime ")) {
                    String value = line.substring(6).trim();
                    return value.length() > 0 ? value : null;
                }
            }
            return null;
        } catch (IOException ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }
}
