package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.6: one zombie, one impulse. Stage 1.9: and only for the speed it does not have yet.
 *
 * Vanilla calls BaseVehicle.applyImpulseFromHitPedestrian EVERY FRAME while the contact lasts
 * (and VehiclePedestrianContactTracking keeps a contact for up to 3.5 seconds), and each time
 * deducts the full impulse m·v·0.8 from the vehicle. Physically that is wrong: a body takes
 * momentum exactly once, while it is being accelerated to the vehicle's speed. Then there is
 * nothing left to take. Hence the "ran into one zombie and stopped dead" effect.
 *
 * We give each character a BUDGET per contact episode and stop deducting once it is spent.
 *
 * Why the budget is measured in time. The game applies the accumulated impulse as a force with
 * a multiplier of 30 over one 10 ms Bullet step, so one application delivers 30 * 0.01 = 0.3 of
 * the due impulse. The full impulse therefore accumulates in about 1/0.3 = 3.33 frames of
 * 1/30 s, i.e. in 0.111 seconds of contact. That is the budget; it is in seconds, so it does
 * not depend on FPS: at 240 FPS it simply spreads over more frames with a smaller share from
 * each (the share is normalised by the mass patch).
 *
 * An episode counts as over once the character has not touched the vehicle for longer than
 * RESET_SEC; then the budget is granted anew (the vehicle backed off and hit again).
 *
 * Stage 1.9, relative speed. The vanilla impulse is m·k·v with v the speed of the VEHICLE, as if
 * every body stood still when hit. A ragdoll does not: after the first hit it tumbles ahead of the
 * bumper at nearly the vehicle's speed, loses contact, touches again, and every new episode used
 * to take the full m·v once more. In a crowd those repeats add up to a dead stop. The momentum a
 * body can still take is m·(v − u), u being its own speed along the vehicle's motion, so the
 * impulse is scaled by (v − u) / v, clamped to 0..1: a body standing still pays in full, one
 * carried along by the bumper pays nothing. u comes from the body's displacement over the last
 * frame (IsoMovingObject.movementLastFrame, which follows the ragdoll's pelvis). The factor goes
 * to Patch_getMass: the character's mass is read only inside this vanilla method, right after
 * this check, and the impulse is proportional to it.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitPedestrian", warmUp = true)
public class Patch_impulseBudget {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle, @Patch.Argument(0) Object chr) {
        return Impl.shouldSkip(vehicle, chr);
    }

    public static final class Impl {
        /** Seconds of contact paid for with impulse. 0.111 ≈ one physically correct hit. */
        public static final float BUDGET_SEC = 0.111f;
        /** A gap in contact after which the episode counts as a new one. */
        public static final float RESET_SEC = 0.4f;
        /** A new episode within this time of the previous one is a repeat hit (for the log). */
        public static final float REPEAT_SEC = 10.0f;
        /** Below this vehicle speed (m/s) the factor is not computed: vanilla impulse as is. */
        public static final float MIN_VEHICLE_SPEED = 0.5f;
        /** A body faster than this (m/s) was teleported or corrected over the network: unknown speed. */
        public static final float MAX_BODY_SPEED = 40.0f;

        public static volatile boolean broken = false;
        public static volatile boolean relBroken = false;
        /** character -> {seconds spent, time of the last touch in ns} */
        public static final Map<Object, float[]> SPENT = new WeakHashMap<Object, float[]>();
        public static final Map<Object, Long> LAST = new WeakHashMap<Object, Long>();
        /** character -> start of its last episode in ns */
        public static final Map<Object, Long> EPISODE = new WeakHashMap<Object, Long>();

        public static Method mLinearVelocity, mMovementLastFrame;
        public static Field fVx, fVz, fMx, fMy;
        public static Object velocity;

        public static long allowed = 0L;
        public static long blocked = 0L;
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        /** @return true = skip the vanilla impulse deduction. */
        public static boolean shouldSkip(Object vehicle, Object chr) {
            Patch_getMass.Impl.pendingRel = 1.0f;   // whatever happens below, no stale factor
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return false;
            }
            if (broken || chr == null) {
                return false;
            }
            try {
                long now = System.nanoTime();
                float[] spent;
                Long last;
                boolean newEpisode = false;
                boolean repeat = false;
                synchronized (SPENT) {
                    spent = SPENT.get(chr);
                    last = LAST.get(chr);
                    if (spent == null) {
                        spent = new float[1];
                        SPENT.put(chr, spent);
                        newEpisode = true;
                    }
                    LAST.put(chr, Long.valueOf(now));
                    if (last != null && (now - last.longValue()) > (long) (RESET_SEC * 1_000_000_000L)) {
                        spent[0] = 0.0f;   // contact was broken: a new episode, a fresh budget
                        newEpisode = true;
                    }
                    if (newEpisode) {
                        Long prev = EPISODE.get(chr);
                        repeat = prev != null && (now - prev.longValue()) < (long) (REPEAT_SEC * 1_000_000_000L);
                        EPISODE.put(chr, Long.valueOf(now));
                    }
                }
                if (spent[0] >= BUDGET_SEC) {
                    blocked++;
                    HordeReport.impulse(vehicle, false, false, 1.0f);
                    report();
                    return true;       // budget spent: the body is up to speed, nothing to take
                }
                spent[0] += Patch_getMass.Impl.frameFactor() / Patch_getMass.Impl.REFERENCE_HZ;
                float rel = relativeFactor(vehicle, chr);
                Patch_getMass.Impl.pendingRel = rel;
                allowed++;
                HordeReport.impulse(vehicle, true, newEpisode && repeat, rel);
                report();
                return false;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the impulse budget patch, disabling: " + t);
                return false;
            }
        }

        /** Share of the vanilla impulse the body can still take: (v − u) / v, clamped to 0..1. */
        public static float relativeFactor(Object vehicle, Object chr) {
            if (relBroken || vehicle == null) {
                return 1.0f;
            }
            try {
                if (mLinearVelocity == null) {
                    init(vehicle, chr);
                }
                mLinearVelocity.invoke(vehicle, velocity);
                float vx = fVx.getFloat(velocity);   // world x
                float vy = fVz.getFloat(velocity);   // world y (Bullet's z)
                float v = (float) Math.sqrt(vx * vx + vy * vy);
                if (!(v > MIN_VEHICLE_SPEED)) {
                    return 1.0f;
                }
                Object move = mMovementLastFrame.invoke(chr);
                float dt = Patch_getMass.Impl.frameFactor() / Patch_getMass.Impl.REFERENCE_HZ;
                if (move == null || !(dt > 0.0f)) {
                    return 1.0f;
                }
                float ux = fMx.getFloat(move) / dt;
                float uy = fMy.getFloat(move) / dt;
                if (!(ux * ux + uy * uy < MAX_BODY_SPEED * MAX_BODY_SPEED)) {
                    return 1.0f;      // NaN, a teleport or a network correction: speed unknown
                }
                float along = (ux * vx + uy * vy) / v;
                float f = (v - along) / v;
                return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
            } catch (Throwable t) {
                relBroken = true;
                Log.info("[LabVehiclePhysics] ERROR in the relative-speed factor, vanilla impulse from now on: " + t);
                return 1.0f;
            }
        }

        private static synchronized void init(Object vehicle, Object chr) throws Exception {
            if (mLinearVelocity != null) {
                return;
            }
            ClassLoader cl = vehicle.getClass().getClassLoader();
            Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
            Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
            Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
            Class<?> v2 = Class.forName("zombie.iso.Vector2", false, cl);
            Object vel = v3.getConstructor().newInstance();
            Method lin = bv.getMethod("getLinearVelocity", v3);
            Method move = mo.getMethod("getMovementLastFrame");
            fVx = v3.getField("x");
            fVz = v3.getField("z");
            fMx = v2.getField("x");
            fMy = v2.getField("y");
            velocity = vel;
            mMovementLastFrame = move;
            mLinearVelocity = lin;
            Log.debug("[LabVehiclePhysics] relative speed on: a hit takes m·(v − u), u = the body's own "
                    + "speed along the vehicle's motion");
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] impulse budget on: one character pushes the vehicle for "
                        + BUDGET_SEC + "s of contact, after that nothing is applied");
            }
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long total = allowed + blocked;
            if (total == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] zombie impulses, last 15 s: applied %d, skipped %d (%d%% of redundant hits removed)",
                    allowed, blocked, blocked * 100 / total));
            allowed = 0L;
            blocked = 0L;
        }
    }
}
