package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * Safety gate: the mod takes effect only where it is allowed.
 *
 * The problem this was written for. Java mods are loaded by the ZombieBuddy agent at JVM
 * startup, before the game even knows which server the player is heading to. So the class
 * patches are always in memory, regardless of whether the mod is installed on the server.
 * Lua and scripts would not get through like that: the server compares their checksums
 * ({@code ChecksumPacket: okLua && okScript && okAnim}) and drops the connection on a mismatch.
 * Native classes and libraries, however, are not part of this check at all.
 *
 * Without the safety gate, this is what would happen: you join someone else's server that lacks
 * the mod and run down zombies in an eleven-tonne vehicle while everyone else drives vanilla
 * one-tonne cars. Technically nothing catches it, but in essence it is an advantage no one granted.
 *
 * The solution relies on the game's own standard mechanism: we ask which mods are actually
 * loaded in this session.
 *
 * It MATTERS which list exactly to ask. The first version looked at {@code ActiveMods},
 * and that was a mistake: on the client it is not filled in at all:
 * <pre>
 * // GameLoadingState:449
 * if (!GameClient.client) {
 *     ActiveMods activeMods = ActiveMods.getById("currentGame");
 *     ActiveMods.setLoadedMods(activeMods);
 * }
 * </pre>
 * The safety gate dutifully concluded that the mod was absent and muted the whole mod on every
 * server, including your own. Multiplayer works differently on the client:
 * <pre>
 * // ZomboidFileSystem.loadMods(String)
 * if (GameClient.client) {
 *     toLoad.addAll(GameClient.instance.serverMods);   // the SERVER's mod list
 *     this.loadMods(toLoad);
 * }
 * </pre>
 * So we ask {@code ZomboidFileSystem.instance.getModIDs()}: it returns what is actually
 * loaded, the same way on the client, on the server and in singleplayer. On the client
 * this list comes from the server, which is exactly what we need.
 *
 * <ul>
 *   <li>singleplayer: the list holds what the player selected, the mod is there, we run;</li>
 *   <li>your own server with the mod: it is in the server's list, we run;</li>
 *   <li>someone else's server without the mod: it is not in the list, all patches stay silent.</li>
 * </ul>
 *
 * A nice bonus is that this is not an "are you a cheater" check but exactly the same list the
 * game uses to decide which content to load. Agreeing with the server admin simply means
 * adding the mod to the list, and everything then works by itself.
 */
public final class LabGate {

    public static final String MOD_ID = "LabVehiclePhysics";
    /** How often to recheck. The list changes between sessions, not within a frame. */
    public static final long RECHECK_NANOS = 2_000_000_000L;

    public static volatile boolean broken = false;
    public static volatile boolean active = false;
    public static long lastCheckNanos = 0L;
    public static Method mGetById;
    public static Method mIsModActive;
    public static java.lang.reflect.Field fInstance;
    public static Method mGetModIDs;
    public static boolean logged = false;
    public static boolean lastLogged = false;

    private LabGate() {
    }

    /** @return true if the mod is allowed to interfere with the game. */
    public static boolean active() {
        if (broken) {
            return true;      // could not tell: behave as before, but the log says so
        }
        long now = System.nanoTime();
        if (lastCheckNanos != 0L && now - lastCheckNanos < RECHECK_NANOS) {
            return active;
        }
        lastCheckNanos = now;
        try {
            if (mGetModIDs == null) {
                Class<?> zfs = Class.forName("zombie.ZomboidFileSystem");
                fInstance = zfs.getField("instance");
                mGetModIDs = zfs.getMethod("getModIDs");
                Class<?> cls = Class.forName("zombie.modding.ActiveMods");
                mGetById = cls.getMethod("getById", String.class);
                mIsModActive = cls.getMethod("isModActive", String.class);
            }
            boolean on = isLoaded() || isActiveIn("loaded") || isActiveIn("currentGame");
            active = on;
            if (!logged || on != lastLogged) {
                logged = true;
                lastLogged = on;
                Log.info("[LabVehiclePhysics] safety gate: mod " + MOD_ID
                        + (on ? " is active - running"
                             : " is NOT in the active mod list of this game - all changes disabled"));
            }
            return active;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] safety gate could not read the mod list ("
                    + t + ") - changes stay enabled");
            return true;
        }
    }

    /** Mods actually loaded in this session. On the client, the ones received from the server. */
    public static boolean isLoaded() {
        try {
            Object zfs = fInstance.get(null);
            if (zfs == null) {
                return false;
            }
            Object ids = mGetModIDs.invoke(zfs);
            return ids instanceof java.util.Collection && ((java.util.Collection<?>) ids).contains(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isActiveIn(String listId) {
        try {
            Object list = mGetById.invoke(null, listId);
            if (list == null) {
                return false;
            }
            return ((Boolean) mIsModActive.invoke(list, MOD_ID)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }
}
