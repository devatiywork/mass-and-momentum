package pz.labragdoll;

/**
 * Лог мода: важное и подробности. Как в LabVehiclePhysics.
 *
 * {@link #info} — всегда: загрузка, предохранитель, снятые замки, ошибки.
 * {@link #debug} — только в сборке лаборатории (в jar лежит метка {@code DevBuild}) или с ключом
 * JVM {@code -Dlabvehicle.verbose=true}: отчёт раз в 15 с и строки по отдельным зомби.
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
