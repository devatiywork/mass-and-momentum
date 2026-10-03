package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Stage 1.9 instrument: one line a second while a vehicle ploughs through bodies, so that a hard
 * stop in a crowd can be read off the log. Per second: the speed at its start and end, the
 * pedestrian impulses the vehicle paid for and those the budget skipped, how many of the paid
 * ones were repeat hits (a body already hit within the last ten seconds), the mean relative-speed
 * share and how many hits it zeroed, the lifts from bodies under the wheels with their mean mass
 * factor, the bodies crushed and killed, the distinct bodies the virtual tracks found under the
 * hull (and how many of those finds were made while the vehicle stood with the throttle held).
 *
 * At the end of each second also the state of the vehicle itself, to tell the ways it can bog
 * down apart: the throttle, each wheel's suspension length against the script's rest length (a
 * wheel whose ray found no ground reports exactly the rest length) and its skid share (Bullet's
 * skidInfo: 1 = full grip, less = the wheel slides or spins), and the zombies under the wheel
 * footprint: living ragdolls, dead ragdolls and the living without one.
 *
 * Debug log only (lab build or -Dlabvehicle.verbose=true): in the Workshop build nothing is counted.
 * One vehicle at a time — the one that hit something first in the current second; in practice
 * the player's.
 */
public final class HordeReport {

    private HordeReport() {
    }

    public static final long SECOND = 1_000_000_000L;

    public static volatile boolean broken = false;
    public static volatile boolean wheelsBroken = false;
    public static Method mSpeed, mScriptName, mThrottle, mGetScript, mRestLength;
    public static Field fWheelInfo, fSusp, fSkid;

    public static Object vehicle;
    public static long startNanos = 0L, lastNanos = 0L;
    public static float startSpeed, lastSpeed;
    public static int applied, skipped, repeats, zeroed, lifts, crushed, killed, pushed, ended;
    public static double sumRel, sumMass;
    public static final Map<Object, Boolean> UNDER = new IdentityHashMap<Object, Boolean>();

    public static void impulse(Object v, boolean paid, boolean repeat, float rel) {
        if (!begin(v)) {
            return;
        }
        if (paid) {
            applied++;
            sumRel += rel;
            if (rel < 0.1f) {
                zeroed++;
            }
            if (repeat) {
                repeats++;
            }
        } else {
            skipped++;
        }
    }

    /** A lying body was crushed (run-over damage applied); killed = it died of it. */
    public static void crush(Object v, boolean died) {
        if (!begin(v)) {
            return;
        }
        crushed++;
        if (died) {
            killed++;
        }
    }

    /** The virtual tracks found a living body under the hull (no wheel touched it). */
    public static void track(Object v, Object body, boolean standing) {
        if (!begin(v)) {
            return;
        }
        UNDER.put(body, Boolean.TRUE);
        if (standing) {
            pushed++;
        }
    }

    /** The ragdoll of a dead body under the vehicle was ended (TrackFootprint.releaseDeadRagdolls). */
    public static void released(Object v) {
        if (!begin(v)) {
            return;
        }
        ended++;
    }

    public static void lift(Object v, int count, float mass) {
        if (count <= 0 || !begin(v)) {
            return;
        }
        lifts += count;
        sumMass += (double) mass * count;
    }

    /** Opens a second for this vehicle, flushing the previous one when it is over. */
    private static boolean begin(Object v) {
        if (!Log.VERBOSE || broken || v == null) {
            return false;
        }
        try {
            long now = System.nanoTime();
            if (vehicle != null && now - startNanos >= SECOND) {
                flush();
            }
            if (vehicle == null) {
                vehicle = v;
                startNanos = now;
                startSpeed = speed(v);
            }
            if (v != vehicle) {
                return false;
            }
            lastNanos = now;
            lastSpeed = speed(v);
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the horde report, disabling it: " + t);
            return false;
        }
    }

    /** The second is reported from its first to its last event: speeds at those two moments. */
    private static void flush() throws Exception {
        int[] census = TrackFootprint.census(vehicle);
        Log.debug(String.format(
                "[LabVehiclePhysics] horde, %.1f s: %s %.0f -> %.0f km/h | impulses paid %d (repeat hits %d, "
                        + "mean relative share %.2f, zeroed %d), budget-skipped %d | wheel lifts %d (mean mass factor %.3f) "
                        + "| crushed %d (killed %d), found under the hull %d (with throttle at a standstill %d), "
                        + "dead ragdolls ended %d "
                        + "| %s | under the vehicle: %s",
                (lastNanos - startNanos) / 1e9, scriptName(vehicle), startSpeed, lastSpeed,
                applied, repeats, applied > 0 ? sumRel / applied : 0.0, zeroed, skipped,
                lifts, lifts > 0 ? sumMass / lifts : 0.0, crushed, killed, UNDER.size(), pushed, ended,
                wheels(vehicle),
                census == null ? "n/a" : String.format("ragdolls alive %d, dead %d; alive without ragdoll %d",
                        census[0], census[1], census[2])));
        vehicle = null;
        applied = skipped = repeats = zeroed = lifts = crushed = killed = pushed = ended = 0;
        sumRel = sumMass = 0.0;
        UNDER.clear();
    }

    /** "throttle 1.00, rest 0.20, wheels susp/skid 0.20/1.00 0.11/0.35 ..." */
    private static String wheels(Object v) {
        if (wheelsBroken) {
            return "wheels n/a";
        }
        try {
            if (fWheelInfo == null) {
                Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, v.getClass().getClassLoader());
                Class<?> wi = Class.forName("zombie.vehicles.BaseVehicle$WheelInfo", false, v.getClass().getClassLoader());
                Class<?> vs = Class.forName("zombie.scripting.objects.VehicleScript", false, v.getClass().getClassLoader());
                mThrottle = bv.getMethod("getThrottle");
                mGetScript = bv.getMethod("getScript");
                mRestLength = vs.getMethod("getSuspensionRestLength");
                fSusp = wi.getField("suspensionLength");
                fSkid = wi.getField("skidInfo");
                fWheelInfo = bv.getField("wheelInfo");
            }
            StringBuilder sb = new StringBuilder();
            Object script = mGetScript.invoke(v);
            sb.append(String.format("throttle %.2f, rest %.2f, wheels susp/skid",
                    ((Float) mThrottle.invoke(v)).floatValue(),
                    script == null ? 0f : ((Float) mRestLength.invoke(script)).floatValue()));
            Object[] infos = (Object[]) fWheelInfo.get(v);
            for (Object w : infos) {
                if (w != null) {
                    sb.append(String.format(" %.2f/%.2f", fSusp.getFloat(w), fSkid.getFloat(w)));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            wheelsBroken = true;
            return "wheels n/a (" + t + ")";
        }
    }

    private static float speed(Object v) throws Exception {
        init(v);
        return ((Float) mSpeed.invoke(v)).floatValue();
    }

    private static String scriptName(Object v) throws Exception {
        init(v);
        return String.valueOf(mScriptName.invoke(v));
    }

    private static void init(Object v) throws Exception {
        if (mSpeed == null) {
            Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, v.getClass().getClassLoader());
            mScriptName = bv.getMethod("getScriptName");
            mSpeed = bv.getMethod("getCurrentSpeedKmHour");
        }
    }
}
