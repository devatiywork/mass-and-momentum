package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Exposer;

/**
 * Entry point from Lua into Java for the server table ({@link ServerTable}).
 *
 * ZombieBuddy exposes the class to Lua, and static methods are called with a dot:
 * {@code LabVehiclePhysicsNet.serverTable()}. The annotation sets no name, so the class takes
 * the game's standard route ({@code exposeLikeJavaRecursively} into the environment root), and
 * Kahlua registers it under its simple name if that name is free, like the game's own classes.
 *
 * Everything public and static here is visible to the Lua of any mod, so there is no logic here,
 * only the entry points our Lua files need: {@code LabVehiclePhysics_ServerTable.lua},
 * {@code LabVehiclePhysics_TreeBreak.lua} and {@code LabVehicleFuel.lua}.
 */
@Exposer.LuaClass
public final class LabVehiclePhysicsNet {

    private LabVehiclePhysicsNet() {
    }

    /** Client: already in game and able to send commands to the server. */
    public static boolean clientReady() {
        return ServerTable.clientReady();
    }

    /** Server: version number of its own vehicle-physics.cfg, incremented on every change. */
    public static double serverStamp() {
        return ServerTable.serverStamp();
    }

    /** Server: the table to send to clients, or null if it could not be built. */
    public static Object serverTable() {
        return ServerTable.build();
    }

    /** Client: accept the table sent by the server. */
    public static void receiveServerTable(Object args) {
        ServerTable.accept(args);
    }

    /** Server: the driver's client reports hitting a tree; see {@link TreeBreak#serverTreeHit}. */
    public static void serverTreeHit(Object player, Object args) {
        TreeBreak.serverTreeHit(player, args);
    }

    /** Server: the driver's client reports where a hit body landed; see {@link CorpseSync#serverLanded}. */
    public static void serverZombieLanded(Object player, Object args) {
        CorpseSync.serverLanded(player, args);
    }

    /**
     * Vehicle script mass before our reference table, kg; 0 means the script has not been seen yet.
     * Used for the "as in the game" fuel consumption in {@code LabVehicleFuel.lua}.
     */
    public static double originalMass(String scriptName) {
        return VehicleCfg.originalMass(scriptName);
    }

    /**
     * The sandbox toggle "Fuel consumption by mass". Lua asks here rather than in
     * {@code SandboxVars}: an edit from the singleplayer debug menu changes the options
     * themselves but does not update the {@code SandboxVars} table until you rejoin.
     */
    public static boolean fuelByMass() {
        return LabSettings.fuelByMass();
    }

    // ---- "Vehicle Physics: vehicles" panel (LabVehiclePhysics_VehicleTable.lua), see VehicleTable

    /** All vehicles: array of {name, full, mod, vanilla}; null on failure, reason in the log. */
    public static Object tableVehicles() {
        try {
            return VehicleTable.vehicles();
        } catch (Throwable t) {
            tableFailed("vehicle list", t);
            return null;
        }
    }

    /** Vehicle type presets: array of {id, mass, power, maxSpeed, tank, lowGear, lowGearTo, category}. */
    public static Object tablePresets() {
        try {
            return VehicleTable.presets();
        } catch (Throwable t) {
            tableFailed("presets", t);
            return null;
        }
    }

    /** A vehicle's values without its table row and with row {@code row}: {base, result, game}. */
    public static Object tableDescribe(String name, String row) {
        try {
            return VehicleTable.describe(name, row);
        } catch (Throwable t) {
            tableFailed("vehicle values", t);
            return null;
        }
    }

    public static final java.util.Set<String> TABLE_FAILED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static void tableFailed(String what, Throwable t) {
        if (TABLE_FAILED.add(what)) {
            Log.info("[LabVehiclePhysics] vehicle table panel: could not get the " + what + ": " + t);
        }
    }
}
