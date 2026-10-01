package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * Настройки мода в песочнице: страница «Физика транспорта» ({@code 42/media/sandbox-options.txt}).
 *
 * Хранит их сама игра: в одиночной — в сейве мира, в сети — на сервере, который рассылает
 * свои значения клиентам при входе и пересылает правки админа посреди игры. Нам остаётся
 * только читать {@code SandboxOptions.instance}.
 *
 * Читаем не чаще раза в секунду: патчи зовут эти методы каждый кадр на каждую машину и
 * каждого персонажа, а настройки меняют руками. Между чтениями значения лежат в
 * volatile-полях.
 *
 * Пока опций нет (игра ещё не дошла до загрузки модов), действуют значения по умолчанию —
 * те же, что в sandbox-options.txt: всё включено, множитель 1, у ванильных машин значения мода.
 */
public final class LabSettings {

    public static final String PREFIX = "LabVehiclePhysics.";
    public static final long REFRESH_NANOS = 1_000_000_000L;

    /** Ванильные машины: true — значения мода из встроенного справочника, false — как в игре. */
    public static volatile boolean vanillaCustom = true;
    /** Общий множитель тяги всех машин поверх их собственных настроек. */
    public static volatile float powerMul = 1.0f;
    public static volatile boolean zombieImpact = true;
    public static volatile boolean corpseFollows = true;
    public static volatile boolean animalHits = true;
    public static volatile boolean fuelByMass = true;
    public static volatile boolean bushes = true;
    public static volatile boolean trees = true;
    /**
     * Масса на лету для всех машин, у которых она задана ({@link Patch_vehicleLiveMass}): подменяется
     * каждый кадр, груз не учитывается. Флаг {@code live} у отдельных правил vehicle-physics.cfg
     * работает и без этой настройки.
     */
    public static volatile boolean liveMass = false;
    /** Таблица машин со страницы «Физика транспорта: машины» — строки через «;», см. {@link VehicleTable}. */
    public static volatile String vehicleTable = "";

    public static volatile long lastNanos = 0L;
    public static volatile boolean broken = false;
    public static Object options;
    public static Method mGetOption;
    public static Method mAsConfig;
    public static Method mGetValue;
    public static String lastLine = null;
    public static boolean missingLogged = false;

    private LabSettings() {
    }

    public static boolean vanillaCustom() {
        refresh();
        return vanillaCustom;
    }

    public static float powerMul() {
        refresh();
        return powerMul;
    }

    public static boolean zombieImpact() {
        refresh();
        return zombieImpact;
    }

    public static boolean corpseFollows() {
        refresh();
        return corpseFollows;
    }

    public static boolean animalHits() {
        refresh();
        return animalHits;
    }

    public static boolean fuelByMass() {
        refresh();
        return fuelByMass;
    }

    public static boolean bushes() {
        refresh();
        return bushes;
    }

    public static boolean trees() {
        refresh();
        return trees;
    }

    public static boolean liveMass() {
        refresh();
        return liveMass;
    }

    public static String vehicleTable() {
        refresh();
        return vehicleTable;
    }

    public static void refresh() {
        long now = System.nanoTime();
        if (lastNanos != 0L && now - lastNanos < REFRESH_NANOS) {
            return;
        }
        synchronized (LabSettings.class) {
            if (lastNanos != 0L && now - lastNanos < REFRESH_NANOS) {
                return;
            }
            lastNanos = now;
            read();
        }
    }

    public static void read() {
        if (broken) {
            return;
        }
        try {
            if (mGetOption == null) {
                Class<?> so = Class.forName("zombie.SandboxOptions");
                options = so.getField("instance").get(null);
                mGetOption = so.getMethod("getOptionByName", String.class);
                mAsConfig = Class.forName("zombie.SandboxOptions$SandboxOption").getMethod("asConfigOption");
                mGetValue = Class.forName("zombie.config.ConfigOption").getMethod("getValueAsObject");
            }
            Object vanilla = value("VanillaVehicles");
            if (vanilla == null) {
                if (!missingLogged) {
                    missingLogged = true;
                    Log.info("[LabVehiclePhysics] settings: the sandbox options of the mod are not loaded yet - defaults apply");
                }
                return;
            }
            // Перечисление: 1 — как в игре, 2 — значения мода.
            vanillaCustom = number(vanilla, 2.0) != 1.0;
            powerMul = (float) Math.max(0.5, Math.min(3.0, number(value("PowerMul"), 1.0)));
            zombieImpact = bool(value("ZombieImpact"), true);
            corpseFollows = bool(value("CorpseFollowsRagdoll"), true);
            animalHits = bool(value("AnimalHits"), true);
            fuelByMass = bool(value("FuelByMass"), true);
            bushes = bool(value("Bushes"), true);
            trees = bool(value("Trees"), true);
            liveMass = bool(value("LiveMass"), false);
            Object table = value("VehicleTable");
            vehicleTable = table instanceof String ? ((String) table).trim() : "";
            String line = describe();
            if (!line.equals(lastLine)) {
                lastLine = line;
                Log.info("[LabVehiclePhysics] settings: " + line);
            }
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] could not read the sandbox settings (" + t + ") - defaults stay in effect");
        }
    }

    /** Значение опции как объект (Boolean, Double; у перечисления тоже Double), null — опции нет. */
    public static Object value(String name) throws Exception {
        Object option = mGetOption.invoke(options, PREFIX + name);
        if (option == null) {
            return null;
        }
        Object config = mAsConfig.invoke(option);
        return config != null ? mGetValue.invoke(config) : null;
    }

    public static double number(Object v, double fallback) {
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public static boolean bool(Object v, boolean fallback) {
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : fallback;
    }

    public static String describe() {
        return "vanilla vehicles - " + (vanillaCustom ? "mod values" : "standard")
                + ", engine power x" + String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(powerMul))
                + ", zombie impacts " + onOff(zombieImpact)
                + ", corpse follows ragdoll " + onOff(corpseFollows)
                + ", animal hits " + onOff(animalHits)
                + ", fuel by mass " + onOff(fuelByMass)
                + ", bushes by mass " + onOff(bushes)
                + ", trees " + onOff(trees)
                + ", live mass " + onOff(liveMass)
                + ", vehicle table " + VehicleTable.count(vehicleTable) + " vehicle(s)";
    }

    public static String onOff(boolean v) {
        return v ? "on" : "off";
    }
}
