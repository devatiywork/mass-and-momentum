package pz.labvehicle;

/**
 * Сборка для лаборатории или для Мастерской.
 *
 * Из одних и тех же классов build.sh собирает два jar. В сборку лаборатории идёт всё; в сборку
 * для Мастерской — без метки {@link DevBuild} и без {@link VehicleService}. Какой jar загружен,
 * видно по наличию метки: класс либо лежит в jar, либо нет.
 *
 * Что есть только в сборке лаборатории:
 * - ключ {@code service} в vehicle-physics.cfg — починка и заправка машины ({@link VehicleService});
 * - подробный лог: отчёты раз в 15 с, строка на каждую машину, строки «patch ready» ({@link Log}).
 *
 * Вызов VehicleService стоит за проверкой {@link #ENABLED}. JVM разрешает ссылку на класс только
 * при первом выполнении инструкции, поэтому в сборке без него до неё дело не доходит.
 */
public final class Dev {

    private Dev() {
    }

    /** true — загружен jar лаборатории. */
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
