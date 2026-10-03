package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * A floor under vehicles, plus a measurement of suspension sag.
 *
 * Why a vehicle falls through at all. A vehicle in PZ is a btRaycastVehicle: there are no wheels
 * as physical bodies; a ray is cast down from the chassis, and a spring pushes the chassis away
 * from the point it finds. The ground has NO collision for the chassis itself. As long as the ray
 * reaches the ground, all is well. But the ray length is limited by the suspension travel, and
 * the scripts set it tiny: 10 cm in all of vanilla, 12 for Bushmaster, 20 for the heaviest mod.
 * Once the spring uses up that travel, the ray stops reaching the ground, support is lost
 * not partially but completely, and the chassis drops into the void.
 *
 * The first version of this patch caught the chassis at height zero, and that was a measurement
 * error: zero is ground level, while the chassis centre normally sits ABOVE it by the ride height.
 * For the Bushmaster that is 0.47, so the vehicle managed to sink by more than a wheel radius
 * before the safeguard kicked in. Both branches of the experiment ran into the same
 * artificial floor, and comparing them was pointless: the instrument was saturated.
 *
 * The ride height is now computed by the same formula the game uses when creating a vehicle:
 * <pre>
 * // CarController(BaseVehicle)
 * wheelBottom   = modelOffset.y + wheel(0).offset.y - wheel(0).radius;
 * chassisBottom = centerOfMassOffset.y - extents.y / 2;
 * physicsZ      = floor(getZ()) * 3 * 0.8164967f - min(wheelBottom, chassisBottom);
 * </pre>
 * Everything is measured from it: sag deeper than SAG_LIMIT counts as falling through, and the
 * chassis is put back at SAG_LIFT below normal (not at normal itself, or the vehicle would hop).
 *
 * And most importantly: the log records the real sag, mean and worst. That is the instrument
 * used to measure whether increasing suspension travel helps.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into update(): public members only, no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "update", warmUp = true)
public class Patch_vehicleFloor {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        Impl.keepAboveGround(vehicle);
        if (LabGate.active() && LabSettings.zombieImpact()) {
            TrackFootprint.releaseDeadRagdolls(vehicle);
        }
        VehicleDrawCheck.maybe(vehicle);
    }

    public static final class Impl {
        /** How far below normal the vehicle may sag before we intervene. */
        public static final float SAG_LIMIT = 0.30f;
        /** How far below normal it is put back, so that it does not get tossed up. */
        public static final float SAG_LIFT = 0.10f;
        /** Height of one floor level in physics units: 3 * 0.8164967. */
        public static final float LEVEL = 2.4494901f;

        public static volatile boolean broken = false;
        public static Method mGetWorldTransform;
        public static Method mSetWorldTransform;
        public static Method mSetWorldTransformArg;
        public static Method mGetScriptName;
        public static Method mGetScript;
        public static Method mGetZ;
        public static Method mWheelCount;
        public static Method mGetWheel;
        public static Method mModelOffset;
        public static Method mComOffset;
        public static Method mExtents;
        public static Method mWheelOffset;
        public static Field fWheelRadius;
        public static Field fOrigin;
        public static Field fY;
        public static Object scratch;

        public static long clamps = 0L;
        public static long samples = 0L;
        public static double sagSum = 0.0;
        public static float worstSag = 0.0f;
        public static String worstName = "";
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        public static void init(Object vehicle) throws Exception {
            Class<?> tr = Class.forName("zombie.core.physics.Transform");
            Class<?> bv = vehicle.getClass();
            mGetWorldTransform = bv.getMethod("getWorldTransform", tr);
            mSetWorldTransform = bv.getMethod("setWorldTransform", tr);
            mGetScriptName = bv.getMethod("getScriptName");
            mGetScript = bv.getMethod("getScript");
            mGetZ = bv.getMethod("getZ");
            fOrigin = tr.getField("origin");
            fY = fOrigin.getType().getField("y");
            scratch = tr.getDeclaredConstructor().newInstance();

            Object script = mGetScript.invoke(vehicle);
            Class<?> vs = script.getClass();
            mWheelCount = vs.getMethod("getWheelCount");
            mGetWheel = vs.getMethod("getWheel", int.class);
            mModelOffset = vs.getMethod("getModelOffset");
            mComOffset = vs.getMethod("getCenterOfMassOffset");
            mExtents = vs.getMethod("getExtents");
            Class<?> wheel = Class.forName("zombie.scripting.objects.VehicleScript$Wheel");
            mWheelOffset = wheel.getMethod("getOffset");
            fWheelRadius = wheel.getField("radius");

            Log.debug("[LabVehiclePhysics] vehicle floor: measured from each vehicle's own ride height, "
                    + "sag deeper than this counts as falling through: " + SAG_LIMIT);
        }

        /** Height at which the chassis centre normally sits. Formula from CarController. */
        public static float naturalHeight(Object vehicle, Object script) throws Exception {
            float base = (float) Math.floor(((Float) mGetZ.invoke(vehicle)).floatValue()) * LEVEL;
            Object com = mComOffset.invoke(script);
            Object ext = mExtents.invoke(script);
            float chassisBottom = fY.getFloat(com) - fY.getFloat(ext) / 2.0f;
            int wheels = ((Integer) mWheelCount.invoke(script)).intValue();
            if (wheels <= 0) {
                return base + 0.1f;          // trailers: the game has a separate branch for them
            }
            Object w0 = mGetWheel.invoke(script, Integer.valueOf(0));
            Object mo = mModelOffset.invoke(script);
            Object wo = mWheelOffset.invoke(w0);
            float wheelBottom = fY.getFloat(mo) + fY.getFloat(wo) - fWheelRadius.getFloat(w0);
            return base - Math.min(wheelBottom, chassisBottom);
        }

        public static void keepAboveGround(Object vehicle) {
            if (!LabGate.active()) {
                return;
            }
            // NaN tripwire first: the floor and the trees below both read the vehicle transform.
            // It works even if the floor has disabled itself after an error.
            NanGuard.vehicle(vehicle);
            if (broken || vehicle == null) {
                return;
            }
            try {
                if (mGetWorldTransform == null) {
                    init(vehicle);
                }
                // Mod authors' data may have arrived after the scripts, so we finish applying it
                // here: BaseVehicle.update runs on the main thread, toBullet() is safe.
                VehicleCfg.reapplyIfPending();
                // Trees (TreeBreak): pushing a trunk, and restoring speed after ramming one down.
                TreeBreak.stepPush(vehicle);
                TreeBreak.stepPending(vehicle);
                // Repair and refuelling via the service key, only in the lab build (see Dev).
                if (Dev.ENABLED) {
                    VehicleService.maybe(vehicle);
                }
                Object script = mGetScript.invoke(vehicle);
                if (script == null) {
                    return;
                }
                Object t = scratch;
                mGetWorldTransform.invoke(vehicle, t);
                Object origin = fOrigin.get(t);
                float height = fY.getFloat(origin);
                float natural = naturalHeight(vehicle, script);
                float sag = height - natural;

                samples++;
                sagSum += sag;
                if (sag < worstSag) {
                    worstSag = sag;
                    worstName = String.valueOf(mGetScriptName.invoke(vehicle));
                }

                if (sag < -SAG_LIMIT) {
                    fY.setFloat(origin, natural - SAG_LIFT);
                    mSetWorldTransform.invoke(vehicle, t);
                    clamps++;
                    if (!logged) {
                        logged = true;
                        Log.debug("[LabVehiclePhysics] "
                                + String.valueOf(mGetScriptName.invoke(vehicle))
                                + " sagged by " + String.format("%.2f", Float.valueOf(-sag))
                                + " from a normal ride height of " + String.format("%.2f", Float.valueOf(natural))
                                + " - lifting it back");
                    }
                }
                report();
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the vehicle floor, disabling: " + t);
            }
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L || samples == 0L) {
                return;
            }
            lastReportNanos = now;
            Log.debug(String.format(
                    "[LabVehiclePhysics] suspension, last 15 s: mean sag %.3f, worst %.3f (%s), "
                    + "floor triggered %d times out of %d samples",
                    Double.valueOf(sagSum / samples), Float.valueOf(worstSag), worstName,
                    Long.valueOf(clamps), Long.valueOf(samples)));
            clamps = 0L;
            samples = 0L;
            sagSum = 0.0;
            worstSag = 0.0f;
            worstName = "";
        }
    }
}
