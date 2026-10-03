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
 * 3. (stage 1.9) The lift is proportional to the mass of the VEHICLE: a body under a wheel tosses
 *    a 52-tonne tank exactly as hard, relative to its weight, as a city car. Physically the push
 *    is bounded by the body, which a heavy vehicle simply crushes. We keep the vanilla lift up to
 *    LIFT_RATIO times the body's mass (100 kg body, 1.5 t car) and above that scale it by
 *    LIFT_RATIO · m_body / M: the Bushmaster gets about a tenth, the tank a fortieth.
 *
 * 4. (stage 1.9) Virtual tracks: where no wheel touched the body, a tracked vehicle may still have
 *    it under a track ({@link TrackFootprint}); then the method returns 1 and the game crushes the
 *    body as if a wheel had run over it. The return value is what IsoGameCharacter uses to call
 *    hitCharacter; the vehicle's own update and the corpse test ignore it.
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
    public static void exit(@Patch.This Object vehicle, @Patch.Argument(0) Object body,
                            @Patch.Return(readOnly = false) int hitWheels) {
        hitWheels = Impl.leave(vehicle, body, hitWheels);
    }

    public static final class Impl {
        /** Fraction of the lever arm that remains: 1.0 is vanilla, 0 means no tilt at all. */
        public static final float LEVER = 0.45f;
        /** Vehicle-to-body mass ratio up to which the vanilla lift is kept. */
        public static final float LIFT_RATIO = 15.0f;
        /** Body mass the vanilla constants are tuned for (vanilla getMass() is 100 for everyone). */
        public static final float BODY_MASS = 100.0f;
        public static final float REFERENCE_HZ = 30.0f;
        public static final float FRAME_MIN = 0.02f;
        public static final float FRAME_MAX = 2.0f;

        /** Nesting depth of the overloads (the game calls them from the main thread). */
        public static int depth = 0;

        public static volatile boolean broken = false;
        public static Field fImpulses, fImpulse, fRelPos, fEnable, fApplied, fx, fy, fz;
        public static java.lang.reflect.Method mFudgedMass;
        public static boolean logged = false;
        public static long scaled = 0L;
        public static double sumFrame = 0.0;
        public static double sumMassFactor = 0.0;
        public static long lastReportNanos = 0L;

        /** Lift share for this body under this vehicle: 1 up to LIFT_RATIO, then LIFT_RATIO·m/M. */
        public static float massFactor(Object vehicle, Object body) {
            try {
                float m = BODY_MASS * Patch_getMass.Impl.spreadFor(body == null ? vehicle : body);
                float vehicleMass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
                if (!(vehicleMass > LIFT_RATIO * m)) {
                    return 1.0f;
                }
                return LIFT_RATIO * m / vehicleMass;
            } catch (Throwable t) {
                return 1.0f;
            }
        }

        /** @return the number of wheels that ran over the body, with the virtual tracks counted in. */
        public static int leave(Object vehicle, Object body, int hitWheels) {
            if (depth > 0) {
                depth--;
            }
            if (depth != 0 || vehicle == null) {
                return hitWheels;
            }
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return hitWheels;
            }
            if (hitWheels <= 0) {
                // No wheel touched it; a tracked vehicle may still have it under the hull.
                int under = TrackFootprint.check(vehicle, body);
                if (under > 0) {
                    HordeReport.track(vehicle, body, under == 2);
                    return 1;
                }
                return hitWheels;
            }
            if (broken) {
                return hitWheels;
            }
            try {
                if (fImpulses == null) {
                    init(vehicle);
                }
                float frame = Patch_getMass.Impl.frameFactor();
                Object[] arr = (Object[]) fImpulses.get(vehicle);
                if (arr == null) {
                    return hitWheels;
                }
                float mass = massFactor(vehicle, body);
                float k = frame * mass;
                int lifts = 0;
                for (Object imp : arr) {
                    if (imp == null || !fEnable.getBoolean(imp) || fApplied.getBoolean(imp)) {
                        continue;
                    }
                    Object v = fImpulse.get(imp);
                    fx.setFloat(v, fx.getFloat(v) * k);
                    fy.setFloat(v, fy.getFloat(v) * k);
                    fz.setFloat(v, fz.getFloat(v) * k);
                    Object rp = fRelPos.get(imp);
                    fx.setFloat(rp, fx.getFloat(rp) * LEVER);
                    fz.setFloat(rp, fz.getFloat(rp) * LEVER);
                    scaled++;
                    sumFrame += frame;
                    sumMassFactor += mass;
                    lifts++;
                }
                HordeReport.lift(vehicle, lifts, mass);
                report();
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the prone-bodies patch, disabling: " + t);
            }
            return hitWheels;
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
            mFudgedMass = Class.forName("zombie.vehicles.BaseVehicle", false, bv.getClassLoader())
                    .getMethod("getFudgedMass");
            fImpulses = f;
            Log.debug("[LabVehiclePhysics] prone-bodies patch ready: lift is normalised by frame time, "
                    + "scaled by body/vehicle mass above " + (int) LIFT_RATIO + ":1, "
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
            double mass = sumMassFactor / scaled;
            Log.debug(String.format(
                    "[LabVehiclePhysics] prone bodies, last 15 s: impulses %d (%.1f/s), "
                    + "mean frame factor %.3f (=> ~%.0f FPS), mean mass factor %.3f, lift reduced %.1fx",
                    scaled, scaled / 15.0, avg, avg > 0 ? REFERENCE_HZ / avg : 0.0, mass,
                    avg * mass > 0 ? 1.0 / (avg * mass) : 0.0));
            scaled = 0L;
            sumFrame = 0.0;
            sumMassFactor = 0.0;
        }
    }
}
