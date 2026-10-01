package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * The mod's sandbox settings: the "Vehicle Physics" page ({@code 42/media/sandbox-options.txt}).
 *
 * The game itself stores them: in singleplayer in the world save, in multiplayer on the server,
 * which sends its values to clients when they join and forwards admin edits mid-game. All that
 * is left for us is to read {@code SandboxOptions.instance}.
 *
 * We read at most once a second: patches call these methods every frame for every vehicle and
 * every character, while the settings are changed by hand. Between reads the values sit in
 * volatile fields.
 *
 * Until the options exist (the game has not yet got to loading mods), the defaults apply, the
 * same as in sandbox-options.txt: everything on, multiplier 1, mod values for vanilla vehicles.
 */
public final class LabSettings {

    public static final String PREFIX = "LabVehiclePhysics.";
    public static final long REFRESH_NANOS = 1_000_000_000L;

    /** Vanilla vehicles: true = mod values from the built-in table, false = as in the game. */
    public static volatile boolean vanillaCustom = true;
    /** Global thrust multiplier for all vehicles, on top of their own settings. */
    public static volatile float powerMul = 1.0f;
    public static volatile boolean zombieImpact = true;
    public static volatile boolean corpseFollows = true;
    public static volatile boolean animalHits = true;
    public static volatile boolean fuelByMass = true;
    public static volatile boolean bushes = true;
    public static volatile boolean trees = true;
    /**
     * Live mass for all vehicles that have a mass set ({@link Patch_vehicleLiveMass}): it is replaced
     * every frame, cargo is not counted. The {@code live} flag on individual vehicle-physics.cfg
     * rules works without this setting too.
     */
    public static volatile boolean liveMass = false;
    /** Vehicle table ("Vehicle Physics: vehicles" page): rows separated by ";", see {@link VehicleTable}. */
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
            // Enum: 1 = as in the game, 2 = mod values.
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

    /** Option value as an object (Boolean, Double; an enum is Double too), null if no such option. */
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
