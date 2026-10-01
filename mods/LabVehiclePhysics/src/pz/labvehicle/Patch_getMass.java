package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1, patch 1 of 2: the character's mass in the vehicle braking formula.
 *
 * Vanilla: {@code IsoGameCharacter.getMass() { return 100.0F; }}, hardcoded for everyone.
 * The only consumer of this method in the whole game is {@code BaseVehicle.applyImpulseFromHitPedestrian}:
 * <pre>impulseStrength = -dot * characterMass * (isProne ? 0.2 : 0.8) * vehicleSpeed;</pre>
 * Verified by searching the decompiled source: there are no other calls, so the change
 * affects nothing but vehicle hits.
 *
 * We do two things:
 *
 * 1. SPREAD BY PHYSIQUE. Zombies have no body-weight field in the save, so we take a
 *    deterministic spread of 0.75..1.35 from the character's identity (stable within a
 *    session). The mean is deliberately left at 1.0: at this stage we do NOT change the
 *    braking force on average, so that the effect of the other changes shows cleanly.
 *
 * 2. HONEST FRAME TIME. The impulse is queued every render frame but drained with
 *    a hardcoded constant of 30 (the game even has an fpsScale variable for this, which
 *    nobody remembered to use). Because of that, at 240 FPS the vehicle receives 8 times more
 *    braking per second than at 30. We multiply each frame's contribution by its real duration,
 *    normalised to 1/30 s: the factor is 1.0 at 30 FPS and 0.125 at 240. The total impulse
 *    per second becomes the same at any FPS and equal to vanilla at 30 FPS.
 *
 * Mathematically this is identical to multiplying the impulse itself; the hook point is just
 * more convenient: getMass() is short and has no other consumers.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into getMass(), so public members only
 * and no lambdas.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getMass", warmUp = true)
public class Patch_getMass {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) float ret) {
        ret = Impl.adjust(self, ret);
    }

    public static final class Impl {
        /** Mass spread bounds. Mean ≈ 1.0, so the average braking force does not change. */
        public static final float SPREAD_MIN = 0.75f;
        public static final float SPREAD_MAX = 1.35f;
        /** Reference rate that vanilla is calibrated for. */
        public static final float REFERENCE_HZ = 30.0f;
        /** Frame factor clamps: protection against pauses, loading and freezes. */
        public static final float FRAME_MIN = 0.02f;
        public static final float FRAME_MAX = 2.0f;

        public static volatile boolean broken = false;
        public static Method gtGetInstance;
        public static Method gtRealSeconds;
        public static boolean logged = false;
        public static long calls = 0L;
        public static double sumFrame = 0.0;
        public static float lastSpread = 1.0f;
        public static long lastReportNanos = 0L;

        public static float adjust(Object chr, float vanilla) {
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return vanilla;
            }
            if (broken || chr == null) {
                return vanilla;
            }
            try {
                float frame = frameFactor();
                float spread = spreadFor(chr);
                lastSpread = spread;
                calls++;
                sumFrame += frame;
                report();
                return vanilla * spread * frame;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the mass patch, disabling: " + t);
                return vanilla;
            }
        }

        /** Frame duration normalised to 1/30 s. */
        public static float frameFactor() throws Exception {
            if (gtRealSeconds == null) {
                Class<?> gt = Class.forName("zombie.GameTime");
                gtGetInstance = gt.getMethod("getInstance");
                gtRealSeconds = gt.getMethod("getRealworldSecondsSinceLastUpdate");
            }
            Object inst = gtGetInstance.invoke(null);
            if (inst == null) {
                return 1.0f;
            }
            float sec = ((Float) gtRealSeconds.invoke(inst)).floatValue();
            float f = sec * REFERENCE_HZ;
            if (!(f > FRAME_MIN)) {
                return FRAME_MIN;
            }
            return f > FRAME_MAX ? FRAME_MAX : f;
        }

        /** Deterministic mass spread for a specific character. */
        public static float spreadFor(Object chr) {
            int h = System.identityHashCode(chr);
            h ^= (h >>> 16);
            h *= 0x7feb352d;
            h ^= (h >>> 15);
            float t = (h & 0xffff) / 65535.0f;
            return SPREAD_MIN + (SPREAD_MAX - SPREAD_MIN) * t;
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] character mass: vanilla 100 -> spread "
                        + SPREAD_MIN + ".." + SPREAD_MAX + " x frame factor (30 Hz = 1.0)");
            }
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos >= 15_000_000_000L) {
                double avg = calls > 0 ? sumFrame / calls : 0.0;
                Log.debug(String.format(
                        "[LabVehiclePhysics] last 15 s: mass calls=%d, mean frame factor=%.3f (=> ~%.0f FPS)",
                        calls, avg, avg > 0 ? REFERENCE_HZ / avg : 0.0));
                calls = 0L;
                sumFrame = 0.0;
                lastReportNanos = now;
            }
        }
    }
}
