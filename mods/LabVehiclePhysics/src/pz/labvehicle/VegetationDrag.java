package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bushes and saplings: the vehicle loses energy on each one instead of hitting a speed cap.
 *
 * <h2>What vanilla does</h2>
 * Two mechanisms, and neither knows the vehicle's mass.
 * <pre>
 * // 1. Speed cap. BaseVehicle.breakingObjects → updateVelocityMultiplier
 * slowFactor = max over objects (33 - (10 - CarSlowFactor));      // the maximum, not the sum
 * if (speed &gt; 34 - slowFactor) speed is clamped to 34 - slowFactor;  // 11 - CarSlowFactor m/s
 *
 * // 2. Impulse on every frame of contact. BaseVehicle.checkCollisionWithPlant
 * applyImpulseFromHitPlant(obj, 0.1F);   // -velocity * 0.1 * mass: 3% of the speed per frame
 * </pre>
 * The cap is the same for a car and a tank: one bush in the way, and the vehicle instantly, with
 * no braking, goes no faster than 36 km/h, or even 4. The impulse is scaled by the vehicle's mass
 * and divided by the same mass, which cancels out again; it is applied every frame, so at
 * 60 FPS a bush takes half the speed, more at higher FPS. Analysis: {@code backlog.md} §3f.
 *
 * <h2>How it works now</h2>
 * On first contact each object takes a fixed energy E from the vehicle, the energy needed to
 * crush or break it:
 * <pre>
 *   v' = sqrt(v^2 - 2 * E / M)
 * </pre>
 * A light vehicle visibly slows in bushes, a heavy one barely does, a tank does not notice.
 * Objects add up: a hedge of five bushes is five charges. There is no cap any more, only the
 * game's general speed limit. It does not depend on FPS: one charge per contact.
 *
 * The energies are gameplay values, tuned by feel, not measured. The first encounters are logged
 * with the sprite and the CarSlowFactor value; use them for calibration.
 *
 * Runs wherever the vehicle is simulated: on the driver's client and in singleplayer. On the
 * server vanilla applies no impulses to the vehicle.
 */
public final class VegetationDrag {

    /** Energy per unit of CarSlowFactor, J. For a car at 40 km/h one bush is about −3 km/h. */
    public static final float E_PER_SLOW_FACTOR = 4000.0f;
    /** A bush without CarSlowFactor. */
    public static final float E_BUSH = 8000.0f;
    /**
     * A young tree (IsoTree of size 1): a small car at 30 km/h drops to 19. At 40 kJ it
     * stopped dead, which is too much for a sapling. Big trees are a solid obstacle, a
     * different code path; we leave them alone.
     */
    public static final float E_SMALL_TREE = 20000.0f;
    /**
     * Grass, ground plants, foliage ({@code IsoObject.isGrassLike()}). Almost free: otherwise
     * a field of tall grass, if it has a CarSlowFactor, would become a hundred charges in a row.
     */
    public static final float E_GRASS_LIKE = 500.0f;
    /** Gap in contact after which the next one is new: the vehicle backed off and drove in again. */
    public static final long RESET_NANOS = 1_000_000_000L;
    /** 0.3 of a queued impulse gets through per application; see AnimalImpact.APPLIED_FRACTION. */
    public static final float APPLIED_FRACTION = AnimalImpact.APPLIED_FRACTION;

    public static volatile boolean broken = false;
    /** Our own call to applyImpulseFromHitPlant; the patch lets it through. */
    public static volatile boolean ownCall = false;

    /** vehicle -> (object -> time of the last touch, ns). Objects already charged. */
    public static final Map<Object, Map<Object, long[]>> CHARGED = new WeakHashMap<Object, Map<Object, long[]>>();

    public static Field fBreakingList;
    public static Field fSlowFactor;
    public static Method mApplyPlant;
    public static Method mFudgedMass;
    public static Method mLinearVelocity;
    public static Object velOut;
    public static Field vx;
    public static Field vz;
    public static Method mGetProperties;
    public static Method mPropsHas;
    public static Method mPropsGet;
    public static Method mIsBush;
    public static Method mIsGrassLike;
    public static Class<?> treeClass;
    public static Method mTreeSize;
    public static Field fSprite;
    public static Field fSpriteName;

    public static int logged = 0;
    public static boolean capLogged = false;
    public static long charges = 0L;
    public static long capsRemoved = 0L;
    public static double speedLostKmh = 0.0;
    public static long lastReportNanos = 0L;

    private VegetationDrag() {
    }

    /** After BaseVehicle.breakingObjects(): removes the cap and charges new objects in the list. */
    public static void afterBreakingObjects(Object vehicle) {
        if (!LabGate.active() || broken || vehicle == null || !LabSettings.bushes()) {
            return;
        }
        try {
            init(vehicle);
            float cap = fSlowFactor.getFloat(vehicle);
            if (cap > 0.0f) {
                fSlowFactor.setFloat(vehicle, 0.0f);
                capsRemoved++;
                if (!capLogged) {
                    capLogged = true;
                    Log.debug(String.format(
                            "[LabVehiclePhysics] vegetation: vanilla speed cap removed - it would have held this vehicle at %.0f km/h",
                            (34.0f - cap) * 3.6f));
                }
            }
            List<?> list = (List<?>) fBreakingList.get(vehicle);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Object obj = list.get(i);
                    if (obj != null) {
                        charge(vehicle, obj, "slow-factor object");
                    }
                }
            }
            report();
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the vegetation patch, disabling: " + t);
        }
    }

    /**
     * Instead of the vanilla applyImpulseFromHitPlant: charges the object once.
     *
     * @return true to skip the vanilla impulse
     */
    public static boolean onPlantImpulse(Object vehicle, Object obj) {
        if (ownCall) {
            return false;
        }
        if (!LabGate.active() || broken || vehicle == null || obj == null || !LabSettings.bushes()) {
            return false;
        }
        try {
            init(vehicle);
            // Big trees come here too: in checkCollisionWithPlant they share a branch with bushes.
            // We leave them alone: without the vanilla impulse the vehicle would pass through the trunk.
            if (!(energyOf(obj) > 0.0f)) {
                return false;
            }
            charge(vehicle, obj, "plant hit");
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the vegetation patch, disabling: " + t);
            return false;
        }
    }

    /**
     * Whether to skip the IsoObject.Collision impulse for an object with CarSlowFactor
     * (singleplayer and server): braking against such objects is computed here.
     */
    public static boolean skipsHitObjectImpulse(Object vehicle, Object obj) throws Exception {
        if (broken || vehicle == null || obj == null || !LabSettings.bushes()) {
            return false;
        }
        init(vehicle);
        if (!mGetProperties.getDeclaringClass().isInstance(obj)) {
            return false;
        }
        Object props = mGetProperties.invoke(obj);
        return props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue();
    }

    /** First contact in an episode charges the energy; repeat touches only extend the episode. */
    public static void charge(Object vehicle, Object obj, String via) throws Exception {
        long now = System.nanoTime();
        synchronized (CHARGED) {
            Map<Object, long[]> seen = CHARGED.get(vehicle);
            if (seen == null) {
                seen = new WeakHashMap<Object, long[]>();
                CHARGED.put(vehicle, seen);
            }
            long[] last = seen.get(obj);
            if (last != null && now - last[0] <= RESET_NANOS) {
                last[0] = now;
                return;
            }
            seen.put(obj, new long[] {now});
        }
        float e = energyOf(obj);
        if (!(e > 0.0f)) {
            return;
        }
        mLinearVelocity.invoke(vehicle, velOut);
        float x = vx.getFloat(velOut);
        float z = vz.getFloat(velOut);
        float v = (float) Math.sqrt(x * x + z * z);
        if (!(v > 0.05f)) {
            return;
        }
        float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
        if (!(mass > 0.0f)) {
            return;
        }
        float v2 = v * v - 2.0f * e / mass;
        float after = v2 > 0.0f ? (float) Math.sqrt(v2) : 0.0f;
        float dv = v - after;
        if (!(dv > 0.0f)) {
            return;
        }
        // applyImpulseFromHitPlant queues -velocity * mul * mass; 0.3 of that reaches the vehicle.
        float mul = dv / (APPLIED_FRACTION * v);
        ownCall = true;
        try {
            mApplyPlant.invoke(vehicle, obj, Float.valueOf(mul));
        } finally {
            ownCall = false;
        }
        charges++;
        speedLostKmh += dv * 3.6f;
        if (logged < 12) {
            logged++;
            Log.debug(String.format(
                    "[LabVehiclePhysics] vegetation: %s (%s) takes %.0f kJ from a %.0f kg vehicle at %.0f km/h -> %.0f km/h",
                    describe(obj), via, e / 1000.0f, mass, v * 3.6f, after * 3.6f));
        }
    }

    /** How much energy the object takes, J. 0 means it does not slow the vehicle. */
    public static float energyOf(Object obj) throws Exception {
        if (((Boolean) mIsGrassLike.invoke(obj)).booleanValue()) {
            return E_GRASS_LIKE;
        }
        Object props = mGetProperties.invoke(obj);
        if (props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue()) {
            float f = 1.0f;
            try {
                f = Float.parseFloat(String.valueOf(mPropsGet.invoke(props, "CarSlowFactor")).trim());
            } catch (NumberFormatException ignored) {
            }
            return Math.max(1.0f, f) * E_PER_SLOW_FACTOR;
        }
        if (treeClass.isInstance(obj)) {
            return ((Integer) mTreeSize.invoke(obj)).intValue() <= 1 ? E_SMALL_TREE : 0.0f;
        }
        return ((Boolean) mIsBush.invoke(obj)).booleanValue() ? E_BUSH : 0.0f;
    }

    public static String describe(Object obj) {
        StringBuilder sb = new StringBuilder();
        try {
            Object sprite = fSprite.get(obj);
            Object name = sprite != null ? fSpriteName.get(sprite) : null;
            sb.append(name != null ? name : obj.getClass().getSimpleName());
            Object props = mGetProperties.invoke(obj);
            if (props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue()) {
                sb.append(" CarSlowFactor=").append(mPropsGet.invoke(props, "CarSlowFactor"));
            }
        } catch (Throwable t) {
            sb.append(obj.getClass().getSimpleName());
        }
        return sb.toString();
    }

    public static synchronized void init(Object vehicle) throws Exception {
        if (mApplyPlant != null) {
            return;
        }
        ClassLoader cl = vehicle.getClass().getClassLoader();
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> io = Class.forName("zombie.iso.IsoObject", false, cl);
        fBreakingList = bv.getDeclaredField("breakingObjectsList");
        fBreakingList.setAccessible(true);
        fSlowFactor = bv.getDeclaredField("breakingSlowFactor");
        fSlowFactor.setAccessible(true);
        mFudgedMass = bv.getMethod("getFudgedMass");
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        mLinearVelocity = bv.getMethod("getLinearVelocity", v3);
        velOut = v3.getConstructor().newInstance();
        vx = v3.getField("x");
        vz = v3.getField("z");
        mGetProperties = io.getMethod("getProperties");
        Class<?> pc = mGetProperties.getReturnType();
        mPropsHas = pc.getMethod("has", String.class);
        mPropsGet = pc.getMethod("get", String.class);
        mIsBush = io.getMethod("isBush");
        mIsGrassLike = io.getMethod("isGrassLike");
        treeClass = Class.forName("zombie.iso.objects.IsoTree", false, cl);
        mTreeSize = treeClass.getMethod("getSize");
        fSprite = io.getField("sprite");
        fSpriteName = fSprite.getType().getField("name");
        mApplyPlant = bv.getMethod("applyImpulseFromHitPlant", io, float.class);
        Log.debug("[LabVehiclePhysics] vegetation drag ready: no speed cap in bushes, each bush or young tree"
                + " takes a fixed energy once per contact - heavy vehicles barely notice");
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
        if (charges == 0L && capsRemoved == 0L) {
            return;
        }
        Log.debug(String.format(
                "[LabVehiclePhysics] vegetation, last 15 s: objects %d, speed lost %.0f km/h in total, vanilla cap removed on %d frames",
                charges, speedLostKmh, capsRemoved));
        charges = 0L;
        capsRemoved = 0L;
        speedLostKmh = 0.0;
    }
}
