package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.5: the vehicle getting tossed up by bodies under its wheels.
 *
 * Vanilla, BaseVehicle.testCollisionWithProneCharacter, when a wheel runs over someone lying down:
 * <pre>
 * impulse.impulse.set(0, 1, 0);                                  // strictly UP
 * float speedMult = max(speedKmH, 10) / 10;
 * impulse.impulse.mul(0.065F * getFudgedMass() * speedMult * corpseSizeMul);
 * impulse.relPos.set(wheelPos - vehiclePos);                     // applied AT THE WHEEL
 * impulse.enable = true; impulse.applied = false;
 * </pre>
 * and then BaseVehicle.applyAllImpulsesFromProneCharacters:
 * <pre>
 * float limit = getFudgedMass() * 0.15F;
 * if (force.lengthSquared() > limit*limit) force.mul(limit / force.length());   // cap on the force ONLY
 * Bullet.applyCentralForceToVehicle(id, force * 30.0F);
 * Bullet.applyTorqueToVehicle(id, torque * 30.0F);                             // torque NOT capped
 * </pre>
 *
 * Two defects:
 *
 * 1. The impulse is created PER FRAME (from the body's update) but applied on the physics step,
 *    which runs exactly 100 times a second. At 30 FPS that is 30 applications a second, at 240
 *    up to 100. The net result is four times the lift: the higher the FPS, the harder the vehicle
 *    bucks. The fix multiplies by the real frame duration, normalised to 1/30 s.
 *
 * 2. The force points up but is applied at the WHEEL rather than at the centre of mass, hence
 *    the torque and the urge to roll over. The limiter only covers the force; the torque is cut
 *    merely as a side effect. We shorten the lever arm (relPos) and leave the lift untouched:
 *    the vehicle hops but does not tilt.
 *
 * The overload testCollisionWithProneCharacter(chr, doSound, out) delegates to the long one, so
 * the by-name patch fires twice: we count the nesting depth and scale only on exit from the
 * outermost call.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "testCollisionWithProneCharacter", warmUp = true)
public class Patch_proneImpulse {

    @Patch.OnEnter
    public static void enter() {
        // The nesting depth is always counted; the safety gate and the toggle are checked on exit.
        // The check used to live here: with the safety gate closed the counter did not grow, so
        // exit saw zero and scaled the impulses, twice at that, once for each overload.
        Impl.depth++;
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle, @Patch.Return(readOnly = true) int hitWheels) {
        Impl.leave(vehicle, hitWheels);
    }

    public static final class Impl {
        /** Fraction of the lever arm that remains: 1.0 is vanilla, 0 means no tilt at all. */
        public static final float LEVER = 0.45f;
        public static final float REFERENCE_HZ = 30.0f;
        public static final float FRAME_MIN = 0.02f;
        public static final float FRAME_MAX = 2.0f;

        /** Nesting depth of the overloads (the game calls them from the main thread). */
        public static int depth = 0;

        public static volatile boolean broken = false;
        public static Field fImpulses, fImpulse, fRelPos, fEnable, fApplied, fx, fy, fz;
        public static boolean logged = false;
        public static long scaled = 0L;
        public static double sumFrame = 0.0;
        public static long lastReportNanos = 0L;

        public static void leave(Object vehicle, int hitWheels) {
            if (depth > 0) {
                depth--;
            }
            if (depth != 0 || broken || vehicle == null || hitWheels <= 0) {
                return;
            }
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return;
            }
            try {
                if (fImpulses == null) {
                    init(vehicle);
                }
                float frame = Patch_getMass.Impl.frameFactor();
                Object[] arr = (Object[]) fImpulses.get(vehicle);
                if (arr == null) {
                    return;
                }
                for (Object imp : arr) {
                    if (imp == null || !fEnable.getBoolean(imp) || fApplied.getBoolean(imp)) {
                        continue;
                    }
                    Object v = fImpulse.get(imp);
                    fx.setFloat(v, fx.getFloat(v) * frame);
                    fy.setFloat(v, fy.getFloat(v) * frame);
                    fz.setFloat(v, fz.getFloat(v) * frame);
                    Object rp = fRelPos.get(imp);
                    fx.setFloat(rp, fx.getFloat(rp) * LEVER);
                    fz.setFloat(rp, fz.getFloat(rp) * LEVER);
                    scaled++;
                    sumFrame += frame;
                }
                report();
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the prone-bodies patch, disabling: " + t);
            }
        }

        private static synchronized void init(Object vehicle) throws Exception {
            if (fImpulses != null) {
                return;
            }
            Class<?> bv = vehicle.getClass();
            Field f = null;
            Class<?> c = bv;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("impulsesFromSquishedBodies");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException("impulsesFromSquishedBodies");
            }
            f.setAccessible(true);
            Object[] probe = (Object[]) f.get(vehicle);
            Class<?> imp = Class.forName("zombie.vehicles.BaseVehicle$VehicleImpulse", false, bv.getClassLoader());
            fImpulse = imp.getDeclaredField("impulse");
            fRelPos = imp.getDeclaredField("relPos");
            fEnable = imp.getDeclaredField("enable");
            fApplied = imp.getDeclaredField("applied");
            for (Field ff : new Field[]{fImpulse, fRelPos, fEnable, fApplied}) {
                ff.setAccessible(true);
            }
            Class<?> v3 = Class.forName("org.joml.Vector3f", false, bv.getClassLoader());
            fx = v3.getField("x");
            fy = v3.getField("y");
            fz = v3.getField("z");
            fImpulses = f;
            Log.debug("[LabVehiclePhysics] prone-bodies patch ready: lift is normalised by frame time, "
                    + "torque arm reduced to " + (int) (LEVER * 100) + "% (array slots: "
                    + (probe == null ? 0 : probe.length) + ")");
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            if (scaled == 0L) {
                return;
            }
            double avg = sumFrame / scaled;
            Log.debug(String.format(
                    "[LabVehiclePhysics] prone bodies, last 15 s: impulses %d (%.1f/s), "
                    + "mean frame factor %.3f (=> ~%.0f FPS), lift reduced %.1fx",
                    scaled, scaled / 15.0, avg, avg > 0 ? REFERENCE_HZ / avg : 0.0, avg > 0 ? 1.0 / avg : 0.0));
            scaled = 0L;
            sumFrame = 0.0;
        }
    }
}
