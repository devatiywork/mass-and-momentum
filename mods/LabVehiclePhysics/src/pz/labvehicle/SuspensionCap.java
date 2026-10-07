package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback for systems where the suspension force limit stays: vehicle masses are capped to what
 * the stock suspension carries.
 *
 * The game's physics library caps the force of each suspension spring at 6000 (the stock Bullet
 * default, see NativePatch): at gravity 10 a wheel carries at most 600 kg. NativePatch lifts the
 * cap in memory, but only on Windows (PZBullet64.dll, kernel32). On Linux, the Steam Deck and
 * macOS it stays, and a vehicle whose table mass puts more than that on one wheel sinks at that
 * wheel. A Franklin van carries 62% of its weight on the rear axle: at 2300 kg each rear wheel has
 * 713 kg, so the van squats at the back and cannot drive forward, while reversing moves the weight
 * to the front and works (reported in the Workshop, 03.10.2026).
 *
 * Where the limit stays, a mass from the tables is capped so that the most loaded wheel carries
 * at rest at most {@link #UTILISATION} of the limit; the rest is the margin for the weight that
 * moves when accelerating and braking, and for cargo. The load shares come from the wheel layout.
 * The chassis frame's origin is the centre of mass (centerOfMassOffset only shifts the collision
 * shape, VehicleScript.toBullet), the wheels sit at wheel offset + model offset, and springs of
 * equal stiffness under a rigid body share the load linearly in position:
 * <pre>
 * F_i = a + b·x_i + c·z_i,   Σ F_i = 1,   Σ F_i·x_i = 0,   Σ F_i·z_i = 0
 * </pre>
 * A vehicle is never made lighter than the game or its mod made it: those masses were tuned under
 * this very limit.
 *
 * Only where vehicle physics is simulated, a client or singleplayer. A dedicated server keeps the
 * table masses for its own checks (tree felling compares the driver's force with its mass).
 *
 * Lab switches: {@code -Dlabvehicle.noNativePatch=true} keeps the limit on Windows too, so the
 * fallback can be tried there; {@code -Dlabvehicle.noSuspensionCap=true} turns the fallback off,
 * to see the vehicles sink as they do without it.
 */
public final class SuspensionCap {

    private SuspensionCap() {
    }

    /** The stock force limit of one suspension spring, and the game's gravity. */
    public static final double STOCK_LIMIT = 6000.0;
    public static final double GRAVITY = 10.0;
    /** Share of the limit the most loaded wheel may use at rest. */
    public static final double UTILISATION = 0.7;

    public static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    public static final boolean DISABLED = Boolean.getBoolean("labvehicle.noSuspensionCap");

    /** Cap in kg by bare script name; {@link Float#MAX_VALUE} where no cap applies. */
    public static final Map<String, Float> CAPS = new ConcurrentHashMap<String, Float>();
    /** Script names already reported, so that each capped vehicle is logged once. */
    public static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<String, Boolean>();

    public static volatile boolean broken = false;
    public static volatile boolean announced = false;
    public static Boolean onServer = null;

    /** True where the limit stays and this process simulates vehicle physics. */
    public static boolean active() {
        if (DISABLED || broken || !LabGate.active() || server()) {
            return false;
        }
        if (!WINDOWS) {
            return true;
        }
        return Boolean.FALSE.equals(NativeBridge.lifted);
    }

    public static boolean server() {
        if (onServer == null) {
            try {
                onServer = Boolean.valueOf(Class.forName("zombie.network.GameServer").getField("server").getBoolean(null));
            } catch (Throwable t) {
                return false;
            }
        }
        return onServer.booleanValue();
    }

    /**
     * After the attempt to lift the limit (first vehicle physics). On Windows the masses were
     * applied before it, without a cap; if the attempt failed, they are applied again.
     */
    public static void afterNativeAttempt() {
        if (announced || !active()) {
            return;
        }
        announce();
        if (WINDOWS) {
            VehicleCfg.reapplyReason = "the suspension limit stays - masses capped";
            VehicleCfg.reapplyPending = true;
        }
    }

    public static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        String why = WINDOWS ? "the in-memory patch did not lift it"
                : "this system (" + System.getProperty("os.name", "?") + ") has no in-memory patch yet";
        Log.info("[LabVehiclePhysics] the suspension force limit stays: " + why + ". The stock suspension carries "
                + Math.round(STOCK_LIMIT / GRAVITY) + " kg per wheel, so vehicle masses from the tables are capped to "
                + Math.round(UTILISATION * 100) + "% of that on the most loaded wheel; nothing gets lighter than"
                + " the game or its mod made it");
    }

    /**
     * The mass to use instead of {@code wanted}.
     *
     * @param original the mass the game or the vehicle's mod gives it, 0 if unknown
     */
    public static float limit(Object script, String name, float wanted, float original) {
        if (wanted <= 0.0f || script == null || !active()) {
            return wanted;
        }
        try {
            float cap = capFor(script, name);
            float allowed = Math.max(cap, original);
            if (wanted <= allowed) {
                return wanted;
            }
            announce();
            if (REPORTED.put(VehicleCfg.bareName(name), Boolean.TRUE) == null) {
                Log.debug(String.format(Locale.ROOT,
                        "[LabVehiclePhysics] suspension cap: %s %.0f -> %.0f kg (the most loaded wheel carries %.0f%%)",
                        name, Float.valueOf(wanted), Float.valueOf(allowed), Double.valueOf(maxShare(script) * 100.0)));
            }
            return allowed;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the suspension cap, masses stay uncapped: " + t);
            return wanted;
        }
    }

    /**
     * {@link #limit} from the cap already computed for this script, for callers that have only the
     * name (auto power and brakes); {@code wanted} while the script has not been capped yet.
     */
    public static float cached(String name, float wanted, float original) {
        if (wanted <= 0.0f || name == null || !active()) {
            return wanted;
        }
        Float cap = CAPS.get(VehicleCfg.bareName(name));
        if (cap == null) {
            return wanted;
        }
        float allowed = Math.max(cap.floatValue(), original);
        return wanted <= allowed ? wanted : allowed;
    }

    /** The heaviest mass whose most loaded wheel stays within the margin; MAX_VALUE without one. */
    public static float capFor(Object script, String name) throws Exception {
        String key = VehicleCfg.bareName(name);
        Float known = CAPS.get(key);
        if (known != null) {
            return known.floatValue();
        }
        double share = maxShare(script);
        float cap = share > 0.0 ? (float) (UTILISATION * STOCK_LIMIT / GRAVITY / share) : Float.MAX_VALUE;
        CAPS.put(key, Float.valueOf(cap));
        return cap;
    }

    public static Method mWheelCount, mGetWheel, mGetModel, mModelOffset, mWheelOffset;
    public static Field fX, fZ;

    /**
     * The largest share of the vehicle's weight on one wheel at rest, 0 when it cannot be told
     * (fewer than three wheels, as on a trailer resting on its hitch, or wheels in one line).
     */
    public static double maxShare(Object script) throws Exception {
        if (mWheelCount == null) {
            Class<?> vs = script.getClass();
            mWheelCount = vs.getMethod("getWheelCount");
            mGetWheel = vs.getMethod("getWheel", int.class);
            mGetModel = vs.getMethod("getModel");
            mModelOffset = vs.getMethod("getModelOffset");
            mWheelOffset = Class.forName("zombie.scripting.objects.VehicleScript$Wheel", false, vs.getClassLoader())
                    .getMethod("getOffset");
        }
        int n = ((Integer) mWheelCount.invoke(script)).intValue();
        if (n < 3) {
            return 0.0;
        }
        double mx = 0.0;
        double mz = 0.0;
        if (mGetModel.invoke(script) != null) {
            Object mo = mModelOffset.invoke(script);
            mx = x(mo);
            mz = z(mo);
        }
        double[] xs = new double[n];
        double[] zs = new double[n];
        double sx = 0.0, sz = 0.0, sxx = 0.0, sxz = 0.0, szz = 0.0;
        for (int i = 0; i < n; i++) {
            Object off = mWheelOffset.invoke(mGetWheel.invoke(script, Integer.valueOf(i)));
            xs[i] = x(off) + mx;
            zs[i] = z(off) + mz;
            sx += xs[i];
            sz += zs[i];
            sxx += xs[i] * xs[i];
            sxz += xs[i] * zs[i];
            szz += zs[i] * zs[i];
        }
        // n·a + sx·b + sz·c = 1;  sx·a + sxx·b + sxz·c = 0;  sz·a + sxz·b + szz·c = 0
        double det = n * (sxx * szz - sxz * sxz) - sx * (sx * szz - sxz * sz) + sz * (sx * sxz - sxx * sz);
        if (Math.abs(det) < 1e-9) {
            return 0.0;
        }
        double a = (sxx * szz - sxz * sxz) / det;
        double b = -(sx * szz - sz * sxz) / det;
        double c = (sx * sxz - sz * sxx) / det;
        double max = 0.0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, a + b * xs[i] + c * zs[i]);
        }
        return max;
    }

    private static double x(Object v) throws Exception {
        if (fX == null) {
            fX = v.getClass().getField("x");
            fZ = v.getClass().getField("z");
        }
        return fX.getFloat(v);
    }

    private static double z(Object v) throws Exception {
        if (fZ == null) {
            x(v);
        }
        return fZ.getFloat(v);
    }
}
