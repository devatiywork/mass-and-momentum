package pz.labragdoll;

/**
 * The mod's log: the essentials and the details. Same as in LabVehiclePhysics.
 *
 * {@link #info}: always (loading, the safety gate, lifted locks, errors).
 * {@link #debug}: only in the lab build (the jar contains the {@code DevBuild} marker) or with the
 * JVM flag {@code -Dlabvehicle.verbose=true}: a report every 15 s and lines on individual zombies.
 */
public final class Log {

    private Log() {
    }

    public static final boolean VERBOSE = present("pz.labragdoll.DevBuild") || Boolean.getBoolean("labvehicle.verbose");

    private static boolean present(String name) {
        try {
            Class.forName(name, false, Log.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void info(String line) {
        System.out.println(line);
    }

    public static void debug(String line) {
        if (VERBOSE) {
            System.out.println(line);
        }
    }
}
