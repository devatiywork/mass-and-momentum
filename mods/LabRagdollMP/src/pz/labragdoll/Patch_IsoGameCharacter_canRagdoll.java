package pz.labragdoll;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * LabRagdollMP: lifts the ban on ragdolls in multiplayer.
 *
 * Vanilla (IsoGameCharacter.canRagdoll, 42.20.4) begins with
 * {@code if (GameClient.client || GameServer.server) return false;}: ragdolls are hard-disabled
 * in multiplayer, and no setting turns them on. We do not force true (that would break the
 * limit on concurrent simulations and the state checks); instead, when vanilla says false, we
 * re-evaluate ALL the other conditions ourselves, skipping only that first branch.
 *
 * All reflection, on purpose: the game classes are built for Java 25, and JDK 17 cannot read them.
 * Anything unexpected → the mod disables itself and no longer interferes.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() straight into canRagdoll(), so only public
 * members may be touched here, and lambdas cannot be used.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "canRagdoll", warmUp = true)
public class Patch_IsoGameCharacter_canRagdoll {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret) {
        if (!ret) {
            ret = Impl.recheckWithoutMpGate(self);
        }
    }

    /** The implementation: a separate public class, called from the inlined code. */
    public static final class Impl {

        // denial reasons
        public static final int OK = 0;
        public static final int R_DEBUG = 1;
        public static final int R_OPTION = 2;
        public static final int R_LIMIT = 3;
        public static final int R_CLOTHING = 4;
        public static final int R_STATE = 5;
        public static final String[] REASON = {
            "allowed", "DisableRagdolls debug flag", "usePhysicsHitReaction option is off",
            "hit the maxActiveRagdolls limit", "clothing forbids ragdoll", "current character state does not allow ragdoll"
        };

        public static volatile boolean ready = false;
        public static volatile boolean broken = false;

        public static final long[] countZombie = new long[6];
        public static final long[] countPlayer = new long[6];
        public static final boolean[] reasonLogged = new boolean[6];
        public static boolean zombieSeen = false;
        public static long lastReportNanos = 0L;

        // reflection cache
        public static Method coreGetInstance;
        public static Method coreUsePhysicsHitReaction;
        public static Method coreMaxActiveRagdolls;
        public static Method chrGetRagdollController;
        public static Method chrCanCurrentStateRagdoll;
        public static Method ragdollNumActive;
        public static Field chrWornClothingCanRagdoll;

        /** Re-evaluates the vanilla conditions without the multiplayer ban. */
        public static boolean recheckWithoutMpGate(Object chr) {
            if (broken || chr == null) {
                return false;
            }
            // The mod is not in this game's mod list (someone else's server, say): keep vanilla.
            if (!LabGate.active()) {
                return false;
            }
            boolean isZombie = false;
            try {
                if (!ready) {
                    init(chr.getClass());
                }
                isZombie = chr.getClass().getName().endsWith("IsoZombie");
                if (isZombie && !zombieSeen) {
                    zombieSeen = true;
                    Log.debug("[LabRagdollMP] first canRagdoll call for a zombie - the branch is live");
                }

                if (isRagdollsDisabledInDebug()) {
                    return tally(isZombie, R_DEBUG);
                }
                Object core = coreGetInstance.invoke(null);
                if (core == null || !((Boolean) coreUsePhysicsHitReaction.invoke(core)).booleanValue()) {
                    return tally(isZombie, R_OPTION);
                }
                if (chrGetRagdollController.invoke(chr) == null) {
                    int active = ((Integer) ragdollNumActive.invoke(null)).intValue();
                    int max = ((Integer) coreMaxActiveRagdolls.invoke(core)).intValue();
                    if (active >= max) {
                        return tally(isZombie, R_LIMIT);
                    }
                }
                if (!chrWornClothingCanRagdoll.getBoolean(chr)) {
                    return tally(isZombie, R_CLOTHING);
                }
                if (!((Boolean) chrCanCurrentStateRagdoll.invoke(chr)).booleanValue()) {
                    return tally(isZombie, R_STATE);
                }
                tally(isZombie, OK);
                return true;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabRagdollMP] ERROR, the mod disabled itself and will not interfere further: " + t);
                return false;
            }
        }

        /** Counts the reason + logs each new reason once + a periodic summary. */
        public static boolean tally(boolean isZombie, int reason) {
            if (isZombie) {
                countZombie[reason]++;
            } else {
                countPlayer[reason]++;
            }
            if (isZombie && !reasonLogged[reason]) {
                reasonLogged[reason] = true;
                if (reason == OK) {
                    Log.info("[LabRagdollMP] BLOCK LIFTED: a zombie ragdolled in multiplayer for the first time");
                } else {
                    Log.debug("[LabRagdollMP] zombie denied, reason: " + REASON[reason]);
                }
            }
            reportPeriodic();
            return reason == OK;
        }

        /** Once every 15 s: a summary by reason. */
        public static void reportPeriodic() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long zt = 0L, pt = 0L;
            for (int i = 0; i < 6; i++) {
                zt += countZombie[i];
                pt += countPlayer[i];
            }
            if (zt == 0L && pt == 0L) {
                return;
            }
            StringBuilder sb = new StringBuilder("[LabRagdollMP] last 15 s: zombies=").append(zt)
                    .append(" player=").append(pt).append(" | zombies by reason:");
            for (int i = 0; i < 6; i++) {
                if (countZombie[i] > 0) {
                    sb.append(' ').append(REASON[i]).append('=').append(countZombie[i]);
                }
            }
            Log.debug(sb.toString());
            for (int i = 0; i < 6; i++) {
                countZombie[i] = 0L;
                countPlayer[i] = 0L;
            }
        }

        private static synchronized void init(Class<?> chrClass) throws Exception {
            if (ready) {
                return;
            }
            ClassLoader cl = chrClass.getClassLoader();
            Class<?> coreClass = Class.forName("zombie.core.Core", false, cl);
            coreGetInstance = coreClass.getMethod("getInstance");
            coreUsePhysicsHitReaction = coreClass.getMethod("getOptionUsePhysicsHitReaction");
            coreMaxActiveRagdolls = coreClass.getMethod("getMaxActiveRagdolls");

            Class<?> ragdollClass = Class.forName("zombie.core.physics.RagdollController", false, cl);
            ragdollNumActive = ragdollClass.getMethod("getNumberOfActiveSimulations");

            Class<?> c = chrClass;
            while (c != null && chrWornClothingCanRagdoll == null) {
                try {
                    chrWornClothingCanRagdoll = c.getDeclaredField("wornClothingCanRagdoll");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (chrWornClothingCanRagdoll == null) {
                throw new NoSuchFieldException("wornClothingCanRagdoll");
            }
            chrWornClothingCanRagdoll.setAccessible(true);

            chrGetRagdollController = chrClass.getMethod("getRagdollController");
            chrCanCurrentStateRagdoll = chrClass.getMethod("canCurrentStateRagdoll");

            ready = true;
            Log.debug("[LabRagdollMP] reflection ready (" + chrClass.getName() + ")");
        }

        /** DisableRagdolls debug checkbox; if the chain is unreachable, assume it is not set. */
        private static boolean isRagdollsDisabledInDebug() {
            try {
                Class<?> dbg = Class.forName("zombie.debug.DebugOptions");
                Object inst = dbg.getField("instance").get(null);
                Object anim = inst.getClass().getField("animation").get(inst);
                Object opt = anim.getClass().getField("disableRagdolls").get(anim);
                return ((Boolean) opt.getClass().getMethod("getValue").invoke(opt)).booleanValue();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
