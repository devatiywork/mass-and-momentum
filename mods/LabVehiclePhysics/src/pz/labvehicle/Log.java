package pz.labvehicle;

/**
 * The mod's log: essentials and details.
 *
 * {@link #info} — always: loading, the safety gate, the suspension fix, errors, config errors,
 * NaN tripwire events, the server table, the settings summary.
 *
 * {@link #debug} — only in the lab build ({@link Dev#ENABLED}) or with the JVM option
 * {@code -Dlabvehicle.verbose=true}: reports every 15 s, per-vehicle lines, "patch ready".
 * Off in the Workshop build, or the mod would write a line per second to the player's console.txt.
 */
public final class Log {

    private Log() {
    }

    public static final boolean VERBOSE = Dev.ENABLED || Boolean.getBoolean("labvehicle.verbose");

    public static void info(String line) {
        System.out.println(line);
    }

    public static void debug(String line) {
        if (VERBOSE) {
            System.out.println(line);
        }
    }
}
