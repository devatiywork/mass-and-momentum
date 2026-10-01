package pz.labvehicle;

/**
 * Lab build or Workshop build.
 *
 * build.sh builds two jars from the same classes. The lab build gets everything; the Workshop
 * build goes without the {@link DevBuild} marker and without {@link VehicleService}. The marker
 * tells which jar is loaded: the class is either in the jar or it is not.
 *
 * Only in the lab build:
 * - {@code service} key in vehicle-physics.cfg: vehicle repair and refuel ({@link VehicleService});
 * - verbose log: reports every 15 s, a line per vehicle, "patch ready" lines ({@link Log}).
 *
 * The call to VehicleService sits behind the {@link #ENABLED} check. The JVM resolves a class
 * reference only when the instruction first runs, so the build without the class never gets there.
 */
public final class Dev {

    private Dev() {
    }

    /** true: the lab jar is loaded. */
    public static final boolean ENABLED = present("pz.labvehicle.DevBuild");

    private static boolean present(String name) {
        try {
            Class.forName(name, false, Dev.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
