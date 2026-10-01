package pz.labvehicle;

/**
 * Лог мода: важное и подробности.
 *
 * {@link #info} — всегда: загрузка, предохранитель, правка подвески, ошибки, ошибки в конфиге,
 * события растяжки NaN, серверная таблица, сводка настроек.
 *
 * {@link #debug} — только в сборке лаборатории ({@link Dev#ENABLED}) или с ключом JVM
 * {@code -Dlabvehicle.verbose=true}: отчёты раз в 15 с, строки по каждой машине, «patch ready».
 * В сборке для Мастерской их нет, иначе мод пишет в console.txt игрока по строке в секунду.
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
