package pz.labragdoll;

import java.lang.reflect.Method;

/**
 * Предохранитель: мод действует только там, где он разрешён.
 *
 * Та же схема, что у LabVehiclePhysics (там же подробный разбор — {@code pz.labvehicle.LabGate}
 * и {@code Docs/modding-notes.md} §6, §11). Java-мод агент грузит при старте JVM, ещё до
 * того, как игра узнает, на какой сервер игрок зайдёт, и патчи оказываются в памяти всегда.
 * Без проверки рэгдоллы включались бы и на чужом сервере, где мода нет.
 *
 * Спрашиваем {@code ZomboidFileSystem.instance.getModIDs()} — моды, реально загруженные в
 * этой сессии. На клиенте в сети этот список приходит от сервера. {@code ActiveMods} на
 * клиенте не заполняется вовсе — на этом LabVehiclePhysics однажды глушил сам себя.
 *
 * Рэгдолл боевого преимущества не даёт, это картинка, поэтому приоритет у предохранителя
 * был низкий. Но правило одно для всех наших модов: на чужом сервере — ваниль.
 */
public final class LabGate {

    public static final String MOD_ID = "LabRagdollMP";
    /** Как часто перепроверять. Список меняется между сессиями, а не в течение кадра. */
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

    /** @return true, если моду разрешено вмешиваться в игру. */
    public static boolean active() {
        if (broken) {
            return true;      // не смогли определить — ведём себя как раньше, но об этом сказано в логе
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

    /** Моды, реально загруженные в этой сессии. На клиенте — пришедшие от сервера. */
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
