package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Vehicle data from mod authors: layer 1 of {@code Docs/vehicle-data-design.md}.
 *
 * <h2>Contract</h2>
 * A vehicle author puts a Lua file in their mod:
 * <pre>
 * LabVehiclePhysicsData = LabVehiclePhysicsData or {}
 * LabVehiclePhysicsData["M60A3"] = { mass = 52000, power = 750, maxSpeed = 48, tank = 659 }
 * </pre>
 * Without our mod this table just sits in memory and does nothing, so the author needs no second
 * version of the mod, nor a dependency on us.
 *
 * <h2>Why a global table and not just a function</h2>
 * A call to {@code LabVehiclePhysics.register(...)} only works if our Lua file
 * loaded before the author's. The author does not control the Lua load order between
 * mods: a file named {@code AAA_tank.lua} runs before ours, and
 * {@code if LabVehiclePhysics then} silently does nothing. The table, however, is created by
 * whoever comes first ({@code X = X or {}}) and survives any order. The
 * {@code register} function is kept as a convenience wrapper; it writes to the same table.
 *
 * <h2>How we read it</h2>
 * From Java, via {@code zombie.Lua.LuaManager.env}, exactly the way the game itself reads
 * {@code SmashedCarDefinitions} in {@code BaseVehicle.setSmashed}. Everything via reflection:
 * the mod builds without the game's classes.
 *
 * The table is re-read at the same two-second interval as the config file, because mod
 * Lua files and vehicle scripts each load in their own order. The snapshot is
 * copied into Java whole; we hold no references to Lua objects.
 *
 * <h2>Value validation</h2>
 * We validate here, not in Lua: the data is consumed here, and entries written directly into
 * the table, bypassing {@code register}, end up here too. An invalid field is dropped, the
 * entry's other fields stay; each problem is logged
 * once.
 */
public final class LuaRegistry {

    /** Name of the global table. It is a public contract and must not change. */
    public static final String TABLE = "LabVehiclePhysicsData";

    /** Known categories. An unknown one is accepted with a warning: probably a typo. */
    public static final Set<String> CATEGORIES = new HashSet<String>(java.util.Arrays.asList(
            "car", "suv", "pickup", "van", "delivery_van", "light_military",
            "wheeled_armour", "military_truck", "tracked_armour", "trailer"));

    public static volatile boolean broken = false;
    public static Field fEnv;
    public static Method mRawget;
    public static Method mIterator;
    public static Method mAdvance;
    public static Method mGetKey;
    public static Method mGetValue;

    /** The current snapshot: script name without module -> author's rule. Replaced as a whole. */
    public static volatile Map<String, VehicleCfg.Rule> entries =
            Collections.<String, VehicleCfg.Rule>emptyMap();
    /** Content fingerprint: tells us that the table has changed. */
    public static String signature = "";
    /** Problems already reported, so they are not repeated every two seconds. */
    public static final Set<String> WARNED = new HashSet<String>();

    private LuaRegistry() {
    }

    /**
     * Re-read the authors' table.
     *
     * @return true if the contents changed since the last time
     */
    public static boolean refresh() {
        if (broken) {
            return false;
        }
        try {
            Object table = globalTable();
            // TreeMap: Lua table iteration order is not guaranteed, and the fingerprint must
            // not depend on it, otherwise we would "see changes" out of nowhere.
            TreeMap<String, VehicleCfg.Rule> fresh = new TreeMap<String, VehicleCfg.Rule>();
            TreeMap<String, String> prints = new TreeMap<String, String>();
            if (table != null) {
                Object it = mIterator.invoke(table);
                while (((Boolean) mAdvance.invoke(it)).booleanValue()) {
                    Object key = mGetKey.invoke(it);
                    Object value = mGetValue.invoke(it);
                    if (!(key instanceof String)) {
                        warn("key:" + key, TABLE + " has a non-string key (" + describe(key)
                                + ") - entries must be keyed by vehicle script name, e.g. "
                                + TABLE + "[\"M60A3\"] = { ... }");
                        continue;
                    }
                    String bare = VehicleCfg.bareName((String) key);
                    if (value == null || !isTable(value)) {
                        warn(bare + ":entry", TABLE + "[\"" + key + "\"] must be a table, got "
                                + describe(value) + " - entry ignored");
                        continue;
                    }
                    StringBuilder print = new StringBuilder();
                    VehicleCfg.Rule rule = parseEntry(bare, value, print);
                    if (rule != null) {
                        if (fresh.containsKey(bare)) {
                            // "Base.M60A3" and "M60A3" are the same vehicle. Which entry wins
                            // depends on the table's iteration order, i.e. on chance.
                            warn(bare + ":dup", TABLE + " has two entries for the same vehicle '" + bare
                                    + "' (with and without the module prefix) - one of them wins at random, keep one key");
                        }
                        fresh.put(bare, rule);
                        prints.put(bare, print.toString());
                    }
                }
            }
            String sig = prints.toString();
            if (sig.equals(signature)) {
                return false;
            }
            signature = sig;
            entries = Collections.unmodifiableMap(new HashMap<String, VehicleCfg.Rule>(fresh));
            report(fresh);
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR reading " + TABLE + ", author data disabled: " + t);
            return false;
        }
    }

    /** The authors' global table, or null if it does not exist yet (or Lua is not up yet). */
    public static Object globalTable() throws Exception {
        if (fEnv == null) {
            Class<?> lm = Class.forName("zombie.Lua.LuaManager");
            fEnv = lm.getField("env");
            Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable");
            mRawget = kt.getMethod("rawget", Object.class);
            mIterator = kt.getMethod("iterator");
            Class<?> kit = Class.forName("se.krka.kahlua.vm.KahluaTableIterator");
            mAdvance = kit.getMethod("advance");
            mGetKey = kit.getMethod("getKey");
            mGetValue = kit.getMethod("getValue");
        }
        Object env = fEnv.get(null);
        if (env == null) {
            return null;
        }
        Object table = mRawget.invoke(env, TABLE);
        return isTable(table) ? table : null;
    }

    public static boolean isTable(Object o) {
        if (o == null) {
            return false;
        }
        try {
            return Class.forName("se.krka.kahlua.vm.KahluaTable").isInstance(o);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Fields accepted from authors. Anything else gets a warning. */
    public static final Set<String> KNOWN = new HashSet<String>(java.util.Arrays.asList(
            "mass", "power", "maxSpeed", "tank", "lowGear", "lowGearTo", "category", "mod"));

    /**
     * Parse one entry. Invalid fields are dropped one by one; the entry stays.
     *
     * Only spec-sheet values are accepted. {@code powerMul}, {@code brakeMul} and other
     * game multipliers are not for authors: an author knows their vehicle but need not know
     * that engine force in PZ is relative to the vanilla passenger car. Brakes scale with
     * mass on their own ({@code brakeMul=auto}); the player can override that in the config.
     */
    public static VehicleCfg.Rule parseEntry(String bare, Object entry, StringBuilder print) throws Exception {
        Object it = mIterator.invoke(entry);
        while (((Boolean) mAdvance.invoke(it)).booleanValue()) {
            Object k = mGetKey.invoke(it);
            if (!(k instanceof String) || !KNOWN.contains(k)) {
                String hint = "powerMul".equals(k)
                        ? " - give passport horsepower as 'power' instead, the mod converts it"
                        : " - known fields: mass, power, maxSpeed, tank, lowGear, lowGearTo, category, mod";
                warn(bare + ":field:" + k, TABLE + "[\"" + bare + "\"]: unknown field '" + k + "' ignored" + hint);
            }
        }

        float mass = number(bare, entry, "mass", 50.0, 200000.0, "kg");
        float power = number(bare, entry, "power", 1.0, 5000.0, "hp");
        float maxSpeed = number(bare, entry, "maxSpeed", 1.0, 400.0, "km/h");
        float tank = number(bare, entry, "tank", 1.0, 5000.0, "litres");
        float lowGear = number(bare, entry, "lowGear", 1.0, 10.0, "x");
        float lowGearTo = number(bare, entry, "lowGearTo", 1.0, 200.0, "km/h");
        String category = text(bare, entry, "category", 64);
        String mod = text(bare, entry, "mod", 128);

        if (maxSpeed > 122.0f) {
            warn(bare + ":speedcap", TABLE + "[\"" + bare + "\"]: maxSpeed " + VehicleCfg.fmt(maxSpeed)
                    + " km/h is above the game's hard global limit of about 122 km/h - accepted, but it will not go faster");
        }
        if (lowGearTo > 0.0f && lowGear <= 0.0f) {
            warn(bare + ":lowGearTo", TABLE + "[\"" + bare + "\"]: lowGearTo without lowGear has no effect");
        }
        if (category != null && !CATEGORIES.contains(category)) {
            warn(bare + ":category", TABLE + "[\"" + bare + "\"]: unknown category '" + category
                    + "' kept as is - known: " + new java.util.TreeSet<String>(CATEGORIES));
        }
        if (mass <= 0.0f && power <= 0.0f && maxSpeed <= 0.0f && tank <= 0.0f && lowGear <= 0.0f) {
            warn(bare + ":empty", TABLE + "[\"" + bare + "\"]: no usable values - entry ignored");
            return null;
        }

        print.append("mass=").append(mass).append(" power=").append(power)
             .append(" maxSpeed=").append(maxSpeed).append(" tank=").append(tank)
             .append(" lowGear=").append(lowGear).append('/').append(lowGearTo)
             .append(" category=").append(category).append(" mod=").append(mod);

        return new VehicleCfg.Rule(
                bare,
                mass,
                null,                               // stiffness: Bullet limiter removed, unneeded
                null,                               // engine: power sets the engine force
                maxSpeed,
                0.0f,                               // travel: suspension geometry left as is
                0.0f,                               // rest
                null,                               // powerMul: not accepted from authors
                power,
                mass > 0.0f ? "auto" : null,        // brakes scale together with mass
                lowGear,
                lowGearTo,
                false,                              // service: a lab tool
                tank,
                false,                              // live: a lab tool
                category,
                "author:" + (mod != null ? mod : "?"));
    }

    public static float number(String bare, Object entry, String key, double min, double max, String unit)
            throws Exception {
        Object v = mRawget.invoke(entry, key);
        if (v == null) {
            return 0.0f;
        }
        if (!(v instanceof Number)) {
            warn(bare + ":" + key + ":type", TABLE + "[\"" + bare + "\"]." + key + " must be a number ("
                    + unit + "), got " + describe(v) + " - field ignored");
            return 0.0f;
        }
        double d = ((Number) v).doubleValue();
        if (!(d >= min && d <= max)) {
            warn(bare + ":" + key + ":range", TABLE + "[\"" + bare + "\"]." + key + " = " + d + " " + unit
                    + " is outside " + min + ".." + max + " - field ignored");
            return 0.0f;
        }
        return (float) d;
    }

    public static String text(String bare, Object entry, String key, int maxLen) throws Exception {
        Object v = mRawget.invoke(entry, key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > maxLen) {
            warn(bare + ":" + key + ":text", TABLE + "[\"" + bare + "\"]." + key
                    + " must be a non-empty string up to " + maxLen + " chars - field ignored");
            return null;
        }
        return (String) v;
    }

    public static void warn(String id, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) {
                return;
            }
        }
        Log.info("[LabVehiclePhysics] author data: " + message);
    }

    public static String describe(Object o) {
        if (o == null) {
            return "nil";
        }
        if (o instanceof String) {
            return "string \"" + o + "\"";
        }
        if (o instanceof Number) {
            return "number " + o;
        }
        if (o instanceof Boolean) {
            return "boolean " + o;
        }
        return isTable(o) ? "table" : o.getClass().getSimpleName();
    }

    /** A summary on every change: how many vehicles and from which mods. */
    public static void report(TreeMap<String, VehicleCfg.Rule> fresh) {
        if (fresh.isEmpty()) {
            Log.debug("[LabVehiclePhysics] author data: " + TABLE + " is empty");
            return;
        }
        TreeMap<String, Integer> byMod = new TreeMap<String, Integer>();
        for (VehicleCfg.Rule r : fresh.values()) {
            Integer n = byMod.get(r.source);
            byMod.put(r.source, n == null ? 1 : n + 1);
        }
        Log.info("[LabVehiclePhysics] author data: " + fresh.size() + " vehicle(s) from "
                + byMod + ": " + fresh.keySet());
    }
}
