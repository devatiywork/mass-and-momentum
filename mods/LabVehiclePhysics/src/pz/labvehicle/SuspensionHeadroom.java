package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Suspension headroom: travel enough that a vehicle does not live at the end of its stroke.
 *
 * What was seen. Vanilla pickup vans (and, less, vans and police cars) visibly sank: the wheels
 * went into the ground while driving, and our floor (Patch_vehicleFloor) caught the body at -0.30.
 * The native spring was not too weak for the mass: PZBullet multiplies the spring force by the
 * chassis mass (btRaycastVehicle.updateSuspension: force x 1/invMass), so a parked vehicle sags
 * the same at 1104 and at 2300 kg. It is the vanilla geometry. PickUpVan: stiffness 20, travel
 * 10 cm and spring 0.2 in the script, times model scale 1.82 = 0.182 of travel, and parked it
 * already uses 0.14..0.16 of it (measured in the lab). The game draws a wheel at
 * {@code offset + rest - suspensionLength}, and the physics never lets suspensionLength drop below
 * {@code rest - travel}: once squat, a bump or a body under a wheel asks for more than the
 * remaining 0.02, the body keeps going down and the wheel is drawn in the ground. Our harder pull
 * made it show more often, but vanilla sits there too.
 *
 * What we do. For every vehicle script, vanilla or modded, the parked compression of the more
 * loaded axle is computed the way the physics computes it, and if it uses more than
 * {@link #TARGET} of the travel, the travel is lengthened until it does not, at most to the spring
 * length. Only the travel: stiffness and spring length stay, so the ride height and the feel of the
 * suspension do not change at all; the stroke just no longer ends where the vehicle already sits.
 *
 * Parked compression of an axle, per wheel: {@code share x g / (wheels x stiffness)}. The share
 * comes from where the centre of mass sits between the axles; g is Bullet's default 10 (the world
 * constructor in PZBullet64.dll keeps it). {@link #CAL} fits the model to the measured PickUpVan.
 *
 * A rule with {@code travel=} or {@code stiffness=} is an explicit choice and is left alone.
 * {@code -Dlabvehicle.noHeadroom=true} turns this off, for comparisons in the lab.
 */
public final class SuspensionHeadroom {

    /** Bullet's default gravity, m/s^2. */
    public static final float G = 10.0f;
    /** Model to measurement: the parked PickUpVan sagged 1.13 times the computed value. */
    public static final float CAL = 1.15f;
    /** Parked compression may use at most this share of the travel. Vanilla cars sit at 0.34..0.48. */
    public static final float TARGET = 0.5f;
    /** Travel never longer than the spring itself (Papa_Chad's vehicles already have travel = rest). */
    public static final float MAX_OF_REST = 1.0f;

    public static final boolean DISABLED = Boolean.getBoolean("labvehicle.noHeadroom");

    public static volatile boolean broken = false;
    public static Method mWheelCount, mGetWheel, mComOffset;
    public static Field fFront, fOffset, fZ;
    /** Scripts that got more travel, for the summary line. */
    public static final java.util.Set<String> ADJUSTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * @param original unscaled original fields ({@link VehicleCfg#SCRIPT_FIELDS})
     * @param rule     the merged rule or null
     * @param scale    1 before {@code Loaded()} scaled the script, its model scale after
     */
    public static void apply(Object script, String name, float[] original, VehicleCfg.Rule rule, float scale) {
        if (DISABLED || broken || script == null || original == null) {
            return;
        }
        if (rule != null && (rule.travel > 0.0f || rule.stiffness != null)) {
            return;
        }
        try {
            if (mWheelCount == null) {
                init(script);
            }
            float k = original[1];
            float travelCm = original[4];
            float rest = rule != null && rule.rest > 0.0f ? rule.rest : original[5];
            float ms = VehicleCfg.modelScale(script);
            if (k <= 0.0f || travelCm <= 0.0f || rest <= 0.0f) {
                return;
            }
            float worst = parkedCompression(script, k);
            if (worst <= 0.0f) {
                return;
            }
            float travel = travelCm * 0.01f * ms;
            float use = worst / travel;
            if (use <= TARGET) {
                return;
            }
            float wanted = Math.min(worst / TARGET, MAX_OF_REST * rest * ms);
            if (wanted <= travel) {
                return;
            }
            float newTravelCm = wanted / (0.01f * ms);
            VehicleCfg.field(script.getClass(), "maxSuspensionTravelCm").setFloat(script, newTravelCm * scale);
            if (ADJUSTED.add(VehicleCfg.bareName(name))) {
                Log.debug(String.format(Locale.ROOT,
                        "[LabVehiclePhysics] suspension headroom: %s parked uses %.0f%% of its %.3f travel"
                        + " -> travel %.3f (%.1f cm in the script), now %.0f%%",
                        name, use * 100.0f, travel, wanted, newTravelCm, worst / wanted * 100.0f));
            }
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the suspension headroom, disabling it: " + t);
        }
    }

    /**
     * Parked compression of the more loaded axle, per wheel, in physics units. 0 when the
     * script has no front and rear axle to compare (trailers, oddities).
     */
    public static float parkedCompression(Object script, float k) throws Exception {
        int n = ((Integer) mWheelCount.invoke(script)).intValue();
        float zf = 0.0f, zr = 0.0f;
        int nf = 0, nr = 0;
        for (int i = 0; i < n; i++) {
            Object w = mGetWheel.invoke(script, Integer.valueOf(i));
            if (w == null) {
                continue;
            }
            float z = fZ.getFloat(fOffset.get(w));
            if (fFront.getBoolean(w)) {
                zf += z;
                nf++;
            } else {
                zr += z;
                nr++;
            }
        }
        if (nf == 0 || nr == 0) {
            return 0.0f;
        }
        zf /= nf;
        zr /= nr;
        if (Math.abs(zf - zr) < 1e-4f) {
            return 0.0f;
        }
        // Offsets and the centre of mass are in the same units at any moment (both scaled or
        // both not), and only their ratio matters here.
        float comZ = fZ.getFloat(mComOffset.invoke(script));
        float rearShare = Math.max(0.0f, Math.min(1.0f, (zf - comZ) / (zf - zr)));
        float rear = CAL * rearShare * G / (nr * k);
        float front = CAL * (1.0f - rearShare) * G / (nf * k);
        return Math.max(rear, front);
    }

    private static synchronized void init(Object script) throws Exception {
        if (mWheelCount != null) {
            return;
        }
        Class<?> vs = script.getClass();
        mGetWheel = vs.getMethod("getWheel", int.class);
        mComOffset = vs.getMethod("getCenterOfMassOffset");
        Class<?> wheel = Class.forName("zombie.scripting.objects.VehicleScript$Wheel", false, vs.getClassLoader());
        fFront = accessible(wheel, "front");
        fOffset = accessible(wheel, "offset");
        fZ = fOffset.getType().getField("z");
        mWheelCount = vs.getMethod("getWheelCount");
    }

    private static Field accessible(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
