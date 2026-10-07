package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The vehicle table in the sandbox: the "Vehicle Physics: vehicles" page.
 *
 * <h2>Storage</h2>
 * A single string sandbox option {@code LabVehiclePhysics.VehicleTable}: lines in the
 * vehicle-physics.cfg format separated by ";", one per vehicle the player has changed.
 * <pre>
 *   97bushAmbulance: preset=tank mass=38000;M60A3: power=800
 * </pre>
 * Left side: the exact script name without the module, so the rule matches both forms the game
 * uses ({@code "97bushAmbulance"} and {@code "Base.97bushAmbulance"}). The game itself keeps the
 * option with the world, on the server in multiplayer, sending it on join and relaying admin edits.
 *
 * <h2>Place among the layers</h2>
 * The top layer: above the built-in table, the mod author's data and the vehicle-physics.cfg file
 * ({@link VehicleCfg#resolveLayers}). The file remains a lab tool: live, service.
 *
 * <h2>Panel</h2>
 * Also here: data for the Lua panel (the vehicle list, presets, a vehicle's values without its
 * table row and with it). The same {@link VehicleCfg} that serves the game computes them, so the
 * panel shows exactly what the vehicle will get.
 */
public final class VehicleTable {

    public static final String SEPARATOR = ";";

    private VehicleTable() {
    }

    /** Option string -> rules, one per vehicle. An empty string gives an empty list. */
    public static List<VehicleCfg.Rule> parse(String table) {
        List<String> lines = new ArrayList<String>();
        if (table != null) {
            String[] rows = table.split(SEPARATOR);
            for (int i = 0; i < rows.length; i++) {
                String s = rows[i].trim();
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        return VehicleCfg.parseLines(lines, "sandbox", "sandbox vehicle table");
    }

    /** How many vehicles are in the table, for the settings: line in the log. */
    public static int count(String table) {
        return parse(table).size();
    }

    // ================================================================ data for the panel

    /**
     * All vehicle scripts by name: {@code {name, full, mod, vanilla}}, an array of Lua tables.
     * {@code mod}: id of the mod that first defined the vehicle; {@code pz-vanilla} for vanilla.
     */
    public static Object vehicles() throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
        Object sm = smCls.getField("instance").get(null);
        List<?> scripts = (List<?>) smCls.getMethod("getAllVehicleScripts").invoke(sm);
        List<Object[]> rows = new ArrayList<Object[]>();
        Method mFull = null;
        Method mBodies = null;
        for (int i = 0; i < scripts.size(); i++) {
            Object script = scripts.get(i);
            if (script == null) {
                continue;
            }
            if (mFull == null) {
                mFull = script.getClass().getMethod("getScriptObjectFullType");
                mBodies = script.getClass().getMethod("getLoadedScriptBodies");
            }
            String name = VehicleCfg.scriptName(script);
            String full = (String) mFull.invoke(script);
            List<?> bodies = (List<?>) mBodies.invoke(script);
            String mod = bodies != null && !bodies.isEmpty() ? String.valueOf(bodies.get(0)) : "";
            rows.add(new Object[] {name, full != null ? full : name, mod});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((String) a[0]).compareToIgnoreCase((String) b[0]);
            }
        });
        Object out = newTable();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            Object t = newTable();
            set(t, "name", r[0]);
            set(t, "full", r[1]);
            set(t, "mod", r[2]);
            set(t, "vanilla", Boolean.valueOf("pz-vanilla".equals(r[2])));
            ServerTable.mRawset.invoke(out, Double.valueOf(i + 1), t);
        }
        return out;
    }

    /** Presets in file order: array of {@code {id, mass, power, maxSpeed, tank, lowGear, lowGearTo, category}}. */
    public static Object presets() throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        Object out = newTable();
        int i = 0;
        for (Map.Entry<String, VehicleCfg.Rule> e : VehicleCfg.presets.entrySet()) {
            Object t = values(e.getValue());
            set(t, "id", e.getKey());
            ServerTable.mRawset.invoke(out, Double.valueOf(++i), t);
        }
        return out;
    }

    /**
     * What the vehicle will get: {@code {base, result, game}}.
     * <ul>
     *   <li>{@code base} — without the table row: built-in table, mod author, file;</li>
     *   <li>{@code result} — with {@code row} (the part after the colon: {@code "preset=tank mass=38000"});
     *       an empty row gives the same as base;</li>
     *   <li>{@code game} — the game's own numbers: mass, top speed, hp from the script's thrust.</li>
     * </ul>
     */
    public static Object describe(String name, String row) throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        String bare = VehicleCfg.bareName(name);
        VehicleCfg.Rule base = VehicleCfg.resolveLayers(name, false, null);
        VehicleCfg.Rule rowRule = null;
        if (row != null && !row.trim().isEmpty()) {
            List<VehicleCfg.Rule> one = parse(bare + ": " + row.trim());
            rowRule = one.isEmpty() ? null : one.get(0);
        }
        VehicleCfg.Rule result = rowRule != null ? VehicleCfg.resolveLayers(name, false, rowRule) : base;
        Object out = newTable();
        set(out, "base", values(base, name));
        set(out, "result", values(result, name));
        set(out, "game", game(name));
        return out;
    }

    /** Rule fields for Lua; a null rule gives an empty table with source = "". */
    public static Object values(VehicleCfg.Rule r) throws Exception {
        Object t = newTable();
        if (r == null) {
            set(t, "source", "");
            return t;
        }
        num(t, "mass", r.mass);
        num(t, "power", r.powerHp);
        if (r.powerMul != null) {
            set(t, "powerMul", r.powerMul);
        }
        num(t, "maxSpeed", r.maxSpeed);
        num(t, "tank", r.tank);
        num(t, "lowGear", r.lowGear);
        num(t, "lowGearTo", r.lowGear > 0.0f ? r.lowGearTo : 0.0f);
        if (r.category != null) {
            set(t, "category", r.category);
        }
        set(t, "source", r.source != null ? r.source : "");
        return t;
    }

    /**
     * {@link #values(VehicleCfg.Rule)} for one vehicle: where the suspension force limit stays, the
     * mass it really gets, with "cap[suspension]" at the head of the source chain (SuspensionCap).
     */
    public static Object values(VehicleCfg.Rule r, String name) throws Exception {
        Object t = values(r);
        if (r != null && r.mass > 0.0f) {
            float mass = SuspensionCap.cached(name, r.mass, VehicleCfg.originalMass(name));
            if (mass < r.mass) {
                num(t, "mass", mass);
                set(t, "source", "cap[suspension]" + (r.source != null && !r.source.isEmpty() ? " over " + r.source : ""));
            }
        }
        return t;
    }

    /** The game's own numbers: from script fields saved before our write, else the script as is. */
    public static Object game(String name) throws Exception {
        Object t = newTable();
        float mass = 0.0f;
        float maxSpeed = 0.0f;
        float engineForce = 0.0f;
        float[] o = VehicleCfg.ORIGINAL_FIELDS.get(VehicleCfg.bareName(name));
        if (o != null) {
            mass = o[0];
            engineForce = o[2];
            maxSpeed = o[3];
        } else {
            Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
            Object sm = smCls.getField("instance").get(null);
            Object script = smCls.getMethod("getVehicle", String.class).invoke(sm, name);
            if (script != null) {
                mass = VehicleCfg.field(script.getClass(), "mass").getFloat(script);
                engineForce = VehicleCfg.field(script.getClass(), "engineForce").getFloat(script);
                maxSpeed = VehicleCfg.field(script.getClass(), "maxSpeed").getFloat(script);
            }
        }
        num(t, "mass", mass);
        num(t, "maxSpeed", maxSpeed);
        // How many hp the script's thrust corresponds to: the power key converted back.
        num(t, "power", engineForce > 0.0f ? Math.round(engineForce / VehicleCfg.FORCE_PER_HP) : 0.0f);
        return t;
    }

    public static Object newTable() throws Exception {
        return ServerTable.mNewTable.invoke(ServerTable.platform);
    }

    public static void set(Object table, String key, Object value) throws Exception {
        ServerTable.mRawset.invoke(table, key, value);
    }

    /** Puts a number into the table only if set (above zero): in Lua an empty field is nil. */
    public static void num(Object table, String key, float value) throws Exception {
        if (value > 0.0f) {
            ServerTable.mRawset.invoke(table, key, Double.valueOf(value));
        }
    }
}
