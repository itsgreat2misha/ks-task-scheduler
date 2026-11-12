// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The plugin's persistent run record, stored in two hidden settings.
 *
 * <p>KS settings are the only SDK-sanctioned persistence for a plugin:
 * the host bridge does not expose file creation. Both records are
 * settings the plugin manages with {@code updateSettings}, which does
 * not re-trigger {@code configure}. A string setting holds at most 512
 * characters, so both formats are deliberately compact.
 *
 * <p>Boot record ({@code runState} setting), unchanged from the
 * original once-per-boot design: {@code v1|<bootId>|<hash>,<hash>,...}
 * where each hash is the first 8 hex characters of the SHA-256 of a
 * task's executable plus its arguments. Storing hashes, not indexes or
 * labels, means reordering tasks does not rerun them, while editing a
 * task changes its hash and does rerun it. The boot id is stored once:
 * with 32 tasks the record is under 330 characters.
 *
 * <p>Anchor record ({@code schedulerState} setting):
 * {@code v1|<key>:<epochMinutes>,...} sorted by key, where the key is a
 * task's schedule key (see {@link TaskSpec#scheduleKey}) and the epoch
 * is the minute of its last accounted run, in base 36. An entry costs
 * 8 + 1 + up to 6 + 1 = 16 characters, so 32 tasks fit in about 483
 * characters. Anchors record the last success (or a Skip resync) for
 * interval, daily and weekly tasks; editing a task's schedule fields
 * changes its key, which retires the old anchor and starts it fresh.
 */
public final class SchedulerState {
    static final int MAX_LENGTH = 512;

    private final String bootId;
    private final Set<String> bootCompleted;
    private final Map<String, Long> anchors; // schedule key -> epoch minutes of last accounted run

    public SchedulerState(String bootId, Set<String> bootCompleted, Map<String, Long> anchors) {
        this.bootId = bootId;
        this.bootCompleted = new LinkedHashSet<String>(bootCompleted);
        this.anchors = new TreeMap<String, Long>(anchors);
    }

    public static SchedulerState empty() {
        return new SchedulerState(null, Collections.<String>emptySet(), Collections.<String, Long>emptyMap());
    }

    /** Parse both settings; either may be missing or malformed. */
    public static SchedulerState parse(String bootRaw, String anchorRaw) {
        String bootId = null;
        Set<String> done = new LinkedHashSet<String>();
        if (bootRaw != null && bootRaw.length() > 0) {
            String[] parts = bootRaw.split("\\|", -1);
            if (parts.length == 3 && "v1".equals(parts[0])) {
                bootId = parts[1];
                if (parts[2].length() > 0) {
                    String[] hashes = parts[2].split(",");
                    for (int i = 0; i < hashes.length; i++) {
                        if (hashes[i].length() > 0) done.add(hashes[i]);
                    }
                }
            }
        }
        Map<String, Long> anchors = new TreeMap<String, Long>();
        if (anchorRaw != null && anchorRaw.length() > 0) {
            String[] parts = anchorRaw.split("\\|", -1);
            if (parts.length == 2 && "v1".equals(parts[0]) && parts[1].length() > 0) {
                String[] entries = parts[1].split(",");
                for (int i = 0; i < entries.length; i++) {
                    int colon = entries[i].indexOf(':');
                    if (colon <= 0) continue;
                    try {
                        long minutes = Long.parseLong(entries[i].substring(colon + 1), 36);
                        anchors.put(entries[i].substring(0, colon), Long.valueOf(minutes));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new SchedulerState(bootId, done, anchors);
    }

    public String bootId() { return bootId; }

    /** Copy of the boot-completed hashes, for merging state copies. */
    Set<String> bootCompletedSnapshot() { return new LinkedHashSet<String>(bootCompleted); }

    /** Copy of the anchor map, for merging state copies. */
    Map<String, Long> anchorSnapshot() { return new TreeMap<String, Long>(anchors); }

    public boolean isBootCompleted(String hash) { return bootCompleted.contains(hash); }

    /** A copy with one more boot-completed hash. */
    public SchedulerState withBootCompleted(String hash) {
        Set<String> next = new LinkedHashSet<String>(bootCompleted);
        next.add(hash);
        return new SchedulerState(bootId, next, anchors);
    }

    /** Encode the boot record for the current boot, or null when it would not fit. */
    public String encodeBoot(String currentBootId) {
        StringBuilder out = new StringBuilder();
        out.append("v1|").append(currentBootId).append("|");
        boolean first = true;
        for (String hash : new TreeSet<String>(bootCompleted)) {
            if (!first) out.append(',');
            first = false;
            out.append(hash);
        }
        String encoded = out.toString();
        return encoded.length() <= MAX_LENGTH ? encoded : null;
    }

    /** Epoch millis of the last accounted run for a schedule key, or null. */
    public Long anchorMillis(String scheduleKey) {
        Long minutes = anchors.get(scheduleKey);
        return minutes == null ? null : Long.valueOf(minutes.longValue() * 60000L);
    }

    /** A copy with one task's anchor set (also used to retire stale keys by omission at encode time). */
    public SchedulerState withAnchor(String scheduleKey, long epochMillis) {
        Map<String, Long> next = new TreeMap<String, Long>(anchors);
        next.put(scheduleKey, Long.valueOf(epochMillis / 60000L));
        return new SchedulerState(bootId, bootCompleted, next);
    }

    /**
     * Encode the anchor record keeping only the given live keys, or null
     * when it would not fit. Dropping dead keys at encode time is what
     * retires anchors for deleted or schedule-edited tasks.
     */
    public String encodeAnchors(Set<String> liveKeys) {
        StringBuilder out = new StringBuilder();
        out.append("v1|");
        boolean first = true;
        for (Map.Entry<String, Long> entry : anchors.entrySet()) {
            if (!liveKeys.contains(entry.getKey())) continue;
            if (!first) out.append(',');
            first = false;
            out.append(entry.getKey()).append(':').append(Long.toString(entry.getValue().longValue(), 36));
        }
        String encoded = out.toString();
        return encoded.length() <= MAX_LENGTH ? encoded : null;
    }

    /** First 8 hex characters of SHA-256 over executable, NUL, arguments. */
    public static String hash(String executable, String args) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(executable.getBytes(Charset.forName("UTF-8")));
            digest.update((byte) 0);
            if (args != null) digest.update(args.getBytes(Charset.forName("UTF-8")));
            byte[] sum = digest.digest();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int v = sum[i] & 0xff;
                if (v < 16) out.append('0');
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
