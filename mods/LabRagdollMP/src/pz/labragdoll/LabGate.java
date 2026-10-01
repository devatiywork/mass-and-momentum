package pz.labragdoll;

import java.lang.reflect.Method;

/**
 * Safety gate: the mod takes effect only where it is allowed.
 *
 * The same scheme as in LabVehiclePhysics (detailed analysis there: {@code pz.labvehicle.LabGate}
 * and {@code Docs/modding-notes.md} §6, §11). The agent loads a Java mod at JVM startup, before
 * the game knows which server the player will join, so the patches are always in memory.
 * Without the check, ragdolls would also be enabled on someone else's server without the mod.
 *
 * We ask {@code ZomboidFileSystem.instance.getModIDs()}: the mods actually loaded in
 * this session. On a multiplayer client this list comes from the server. {@code ActiveMods} is
 * not filled in on the client at all; that is how LabVehiclePhysics once muted itself.
 *
 * A ragdoll gives no combat advantage, it is just visuals, so the safety gate had
 * low priority. But the rule is the same for all our mods: on someone else's server, vanilla.
 */
public final class LabGate {

    public static final String MOD_ID = "LabRagdollMP";
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
                Log.info("[LabRagdollMP] safety gate: mod " + MOD_ID
                        + (on ? " is active - running"
                             : " is NOT in the active mod list of this game - ragdolls stay vanilla"));
            }
            return active;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabRagdollMP] safety gate could not read the mod list ("
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
