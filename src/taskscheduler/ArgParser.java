// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Quote-aware argument handling, shared by the root and Shizuku backends.
 *
 * <p>Commands are stored as one executable plus one arguments string so the
 * same command can run through either backend. Splitting respects single
 * quotes, double quotes and backslash escapes, and does no shell expansion.
 * Quoting does the reverse for the root backend, where su takes a single
 * shell string: every argument is quoted so it survives that shell intact.
 */
public final class ArgParser {
    private ArgParser() {}

    /** Split an arguments string into argv elements. Throws on unterminated quotes. */
    public static List<String> split(String args) {
        List<String> out = new ArrayList<String>();
        if (args == null) return out;
        StringBuilder current = new StringBuilder();
        boolean hasToken = false;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                hasToken = true;
            } else if (c == '\\' && !inSingle) {
                escaped = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
            } else if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (hasToken) {
                    out.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (escaped) current.append('\\');
        if (inSingle || inDouble) throw new IllegalArgumentException("Unterminated quote in arguments");
        if (hasToken) out.add(current.toString());
        return out;
    }

    /** Quote one argument for inclusion in a POSIX shell string. */
    public static String shellQuote(String arg) {
        if (arg == null) return "''";
        if (arg.length() == 0) return "''";
        boolean safe = true;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '@' || c == '%' || c == '+' || c == '=' || c == ':' || c == ',' || c == '.' || c == '/' || c == '-';
            if (!ok) { safe = false; break; }
        }
        if (safe) return arg;
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /** Join an executable and its arguments into one shell string. */
    public static String joinShell(List<String> argv) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(shellQuote(argv.get(i)));
        }
        return out.toString();
    }
}
