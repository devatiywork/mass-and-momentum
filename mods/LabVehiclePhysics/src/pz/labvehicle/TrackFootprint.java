package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 1.9: virtual tracks. Whether a lying body is run over, the game decides per wheel
 * (BaseVehicle.testCollisionWithProneCharacter): the body counts only if it lies within the wheel's
 * radius of that wheel's point. A tracked vehicle in PZ is four physics wheels at the corners of
 * the hull, and Papa_Chad's tanks give them a radius of 0.15, so a body had to lie almost exactly
 * under a corner to be crushed. Everything under the tank between them was simply not there.
 *
 * For vehicles of category tracked_armour (vehicle-physics-defaults.cfg, or the author's data) the
 * whole footprint counts: from the outer edge of one track to the outer edge of the other (each
 * track HALF_WIDTH to either side of its wheels; the M60's track is 0.71 m wide), from the rearmost
 * wheel to the foremost one and EXTEND beyond (the track runs on to the sprocket and the idler). A
 * living body whose centre lies within BODY_REACH of it is under the vehicle. Between the tracks
 * that is not strict physics (the hull clears a lying body), but nothing survives under a moving
 * tank, and a body left alive there is exactly what a tank used to bog down on.
 *
 * The vanilla test stops below 3 km/h, and a vehicle that has bogged down stands still, so it
 * stopped crushing just when it had to: here a tracked vehicle keeps crushing while it barely
 * moves (PUSH_MIN_SPEED_KMH) as long as the engine runs and the driver holds the throttle — the
 * tracks turn on whatever lies under them. Below that the game's own hit gives no damage anyway.
 *
 * The test runs only where the vanilla per-wheel test found nothing, and the vehicle gets no lift
 * from it: a track spreads the load, it does not bump.
 */
public final class TrackFootprint {

    private TrackFootprint() {
    }

    public static final String CATEGORY = "tracked_armour";
    public static final float HALF_WIDTH = 0.35f;
    public static final float EXTEND = 0.4f;
    /** A body is not a point: about half its width, and it lies at an angle. */
    public static final float BODY_REACH = 0.3f;
    /** Moving: the tracks crush whatever they roll over. */
    public static final float MIN_SPEED_KMH = 1.0f;
    /** Barely moving: still crushing while the engine pushes (BaseVehicle.hitCharacter needs 0.05 m/s). */
    public static final float PUSH_MIN_SPEED_KMH = 0.2f;
    public static final float PUSH_THROTTLE = 0.1f;
    /** Squares around the vehicle searched for bodies under it (the census, ending dead ragdolls). */
    public static final int CENSUS_RADIUS = 4;
    /** Dead ragdolls are ended this far around the wheel footprint (bodies the bumper pushes). */
    public static final float RELEASE_MARGIN = 0.5f;
    /** A body lies low (not on the hood) if its pelvis is below the vehicle's level + this. */
    public static final float RELEASE_LOW = 0.25f;
    /** A body is ended only after lying under the vehicle this long without a break. */
    public static final long RELEASE_AFTER_NANOS = 400_000_000L;
    /** Not seen under the vehicle for longer than this: the streak starts over. */
    public static final long RELEASE_GAP_NANOS = 250_000_000L;
    /** Dead body -> {first seen under the vehicle, last seen}, main thread only. */
    public static final java.util.Map<Object, long[]> UNDER = new java.util.WeakHashMap<Object, long[]>();

    public static volatile boolean broken = false;
    public static volatile boolean censusBroken = false;
    public static volatile boolean releaseBroken = false;
    public static Method mDriver, mRagdollController, mStateData, mSetActive, mPelvisZ, mComputed, mUpright;
    public static Field fSimulating;
    public static long released = 0L, lastReleaseReport = 0L;
    public static Method mScriptName, mGetScript, mWheelCount, mGetWheel, mWheelOffset, mLocalPos, mSpeed;
    public static Method mEngineRunning, mThrottle, mBodyX, mBodyY, mBodyZ, mDead, mRagdoll;
    public static Method mGetCell, mGridSquare, mMovingObjects;
    public static Class<?> cCharacter, cZombie;
    public static Field fX, fZ, fServer;
    public static Object local;
    /** script name -> {xLeft, xRight, zMin, zMax}; an empty array for a script without wheels on both sides */
    public static final Map<String, float[]> STRIPS = new HashMap<String, float[]>();

    /**
     * @return 0 — not under a tracked vehicle; 1 — under it while it moves;
     *         2 — under it while it barely moves but the engine pushes.
     */
    public static int check(Object vehicle, Object body) {
        if (broken || vehicle == null || body == null) {
            return 0;
        }
        try {
            if (mScriptName == null) {
                init(vehicle);
            }
            if (fServer.getBoolean(null) || !cCharacter.isInstance(body)) {
                return 0;   // corpses: the game ignores the result for them anyway
            }
            String name = (String) mScriptName.invoke(vehicle);
            VehicleCfg.Rule rule = VehicleCfg.forName(name);
            if (rule == null || !CATEGORY.equalsIgnoreCase(rule.category)) {
                return 0;
            }
            float[] s = strips(vehicle, name);
            if (s.length == 0) {
                return 0;
            }
            float speed = Math.abs(((Float) mSpeed.invoke(vehicle)).floatValue());
            boolean moving = speed >= MIN_SPEED_KMH;
            if (!moving) {
                if (speed < PUSH_MIN_SPEED_KMH || !((Boolean) mEngineRunning.invoke(vehicle)).booleanValue()
                        || Math.abs(((Float) mThrottle.invoke(vehicle)).floatValue()) < PUSH_THROTTLE) {
                    return 0;
                }
            }
            if (((Boolean) mDead.invoke(body)).booleanValue() || !underHull(vehicle, s, body, 0f)) {
                return 0;
            }
            return moving ? 1 : 2;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the virtual tracks, disabling them: " + t);
            return 0;
        }
    }

    /** Is the body's centre within the footprint, widened by BODY_REACH plus a margin? */
    private static boolean underHull(Object vehicle, float[] s, Object body, float margin) throws Exception {
        float bx = ((Float) mBodyX.invoke(body)).floatValue();
        float by = ((Float) mBodyY.invoke(body)).floatValue();
        float bz = ((Float) mBodyZ.invoke(body)).floatValue();
        mLocalPos.invoke(vehicle, Float.valueOf(bx), Float.valueOf(by), Float.valueOf(bz), local);
        float lx = fX.getFloat(local);
        float lz = fZ.getFloat(local);
        float reach = BODY_REACH + margin;
        if (lz < s[2] - reach || lz > s[3] + reach) {
            return false;
        }
        return lx >= s[1] - HALF_WIDTH - reach && lx <= s[0] + HALF_WIDTH + reach;
    }

    /**
     * For the log: zombies under the vehicle's wheel footprint (any vehicle, not only tracked).
     * @return {living ragdolls, dead ragdolls, living without a ragdoll}, or null if unavailable
     */
    public static int[] census(Object vehicle) {
        if (broken || censusBroken || vehicle == null) {
            return null;
        }
        try {
            if (mScriptName == null) {
                init(vehicle);
            }
            String name = (String) mScriptName.invoke(vehicle);
            float[] s = strips(vehicle, name);
            if (s.length == 0) {
                return null;
            }
            Object cell = mGetCell.invoke(vehicle);
            if (cell == null) {
                return null;
            }
            int vx = (int) Math.floor(((Float) mBodyX.invoke(vehicle)).floatValue());
            int vy = (int) Math.floor(((Float) mBodyY.invoke(vehicle)).floatValue());
            int vz = (int) Math.floor(((Float) mBodyZ.invoke(vehicle)).floatValue());
            int[] out = new int[3];
            for (int dx = -CENSUS_RADIUS; dx <= CENSUS_RADIUS; dx++) {
                for (int dy = -CENSUS_RADIUS; dy <= CENSUS_RADIUS; dy++) {
                    Object sq = mGridSquare.invoke(cell, Integer.valueOf(vx + dx), Integer.valueOf(vy + dy), Integer.valueOf(vz));
                    if (sq == null) {
                        continue;
                    }
                    List<?> list = (List<?>) mMovingObjects.invoke(sq);
                    if (list == null) {
                        continue;
                    }
                    for (int i = 0; i < list.size(); i++) {
                        Object o = list.get(i);
                        if (!cZombie.isInstance(o) || !underHull(vehicle, s, o, 0f)) {
                            continue;
                        }
                        boolean dead = ((Boolean) mDead.invoke(o)).booleanValue();
                        boolean ragdoll = ((Boolean) mRagdoll.invoke(o)).booleanValue();
                        if (ragdoll) {
                            out[dead ? 1 : 0]++;
                        } else if (!dead) {
                            out[2]++;
                        }
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            censusBroken = true;
            Log.info("[LabVehiclePhysics] ERROR in the census of bodies under the vehicle, disabling it: " + t);
            return null;
        }
    }

    /**
     * Stage 1.9: a dead body under a vehicle stops being a ragdoll at once.
     *
     * A ragdoll ends (RagdollController.updateSimulationTimeout) only when it has stopped moving,
     * its timeout of 1.5 s has run out and Bullet reports its island asleep; then the zombie,
     * waiting on the ground, dies into an ordinary corpse (ZombieOnGroundState: die() once the
     * ragdoll is inactive). Under a vehicle none of that happens: every frame the body counts as
     * touching the vehicle (BaseVehicle.isCollided: anywhere inside its outline, so also right
     * under it), and that contact puts the timeout back to 1.5 s; and an island holding a body
     * pressed by a running vehicle does not fall asleep. So a dead body under a vehicle stays a
     * solid physics body for as long as the vehicle stands on it, and a tank bogs down on its own
     * kills.
     *
     * Every frame, for a vehicle with a driver: a dead zombie whose ragdoll is still simulating,
     * lying low (pelvis below the vehicle's level + RELEASE_LOW, the same test the game uses to
     * tell a body on the hood from one on the ground) within the wheel footprint widened by
     * RELEASE_MARGIN (bodies the bumper is pushing) gets its ragdoll ended the way the game ends
     * it: isSimulating = false and setActive(false). The body becomes an ordinary corpse in the
     * pose it lay in, without physics; the vehicle rolls over it like over any corpse. Bodies in
     * the air or on the hood are left alone, and so are living ones (under a heavy vehicle they
     * are crushed first, see Patch_crushDamage).
     *
     * Only a ragdoll the game has already measured. In its first frames the controller has not
     * computed the body yet (RagdollController.calculateSimulationData skips the first frame), and
     * getPelvisPositionZ() still returns the pooled field from the previous body, often 0. Ending
     * the ragdoll then froze a zombie killed on first contact in its standing pose, and the corpse
     * copied that pose (canUseCurrentPoseForCorpse is unlocked by LabRagdollMP): a dead zombie
     * standing in the street. So: skipped until isSimulationDirectionCalculated(), and while the
     * game itself still sees the body upright.
     *
     * And only a body that STAYS there. "Pelvis below the vehicle's level + 0.25" is about 0.6 m:
     * a zombie thrown at bumper height passes it, and so does one flying low past the wheels. With
     * the first frame skipped, those were ended a frame or two later in mid-air and froze in their
     * flying pose. A body pinned under the hull stays there for seconds; one thrown by the impact
     * leaves the footprint within a fraction of a second. So a body is ended only after
     * RELEASE_AFTER_NANOS under the vehicle without a break; the ones thrown clear land and become
     * corpses the vanilla way.
     */
    public static void releaseDeadRagdolls(Object vehicle) {
        if (broken || releaseBroken || vehicle == null) {
            return;
        }
        try {
            if (mScriptName == null) {
                init(vehicle);
            }
            if (mDriver == null) {
                initRelease(vehicle);
            }
            if (fServer.getBoolean(null) || mDriver.invoke(vehicle) == null) {
                return;
            }
            String name = (String) mScriptName.invoke(vehicle);
            float[] s = strips(vehicle, name);
            if (s.length == 0) {
                return;
            }
            Object cell = mGetCell.invoke(vehicle);
            if (cell == null) {
                return;
            }
            float level = ((Float) mBodyZ.invoke(vehicle)).floatValue();
            long now = System.nanoTime();
            int vx = (int) Math.floor(((Float) mBodyX.invoke(vehicle)).floatValue());
            int vy = (int) Math.floor(((Float) mBodyY.invoke(vehicle)).floatValue());
            int vz = (int) Math.floor(level);
            for (int dx = -CENSUS_RADIUS; dx <= CENSUS_RADIUS; dx++) {
                for (int dy = -CENSUS_RADIUS; dy <= CENSUS_RADIUS; dy++) {
                    Object sq = mGridSquare.invoke(cell, Integer.valueOf(vx + dx), Integer.valueOf(vy + dy), Integer.valueOf(vz));
                    if (sq == null) {
                        continue;
                    }
                    List<?> list = (List<?>) mMovingObjects.invoke(sq);
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    for (int i = 0; i < list.size(); i++) {
                        Object o = list.get(i);
                        if (!cZombie.isInstance(o) || !((Boolean) mDead.invoke(o)).booleanValue()
                                || !((Boolean) mRagdoll.invoke(o)).booleanValue()) {
                            continue;
                        }
                        Object rc = mRagdollController.invoke(o);
                        if (rc == null || !((Boolean) mComputed.invoke(rc)).booleanValue()
                                || ((Boolean) mUpright.invoke(rc)).booleanValue()
                                || ((Float) mPelvisZ.invoke(rc)).floatValue() >= level + RELEASE_LOW) {
                            continue;
                        }
                        if (!underHull(vehicle, s, o, RELEASE_MARGIN)) {
                            continue;
                        }
                        long[] streak = UNDER.get(o);
                        if (streak == null || now - streak[1] > RELEASE_GAP_NANOS) {
                            UNDER.put(o, new long[] {now, now});
                            continue;
                        }
                        streak[1] = now;
                        if (now - streak[0] < RELEASE_AFTER_NANOS) {
                            continue;
                        }
                        UNDER.remove(o);
                        fSimulating.setBoolean(mStateData.invoke(rc), false);
                        mSetActive.invoke(rc, Boolean.FALSE);
                        released++;
                        HordeReport.released(vehicle);
                    }
                }
            }
            reportReleased();
        } catch (Throwable t) {
            releaseBroken = true;
            Log.info("[LabVehiclePhysics] ERROR while ending ragdolls of bodies under a vehicle, disabling it: " + t);
        }
    }

    private static void reportReleased() {
        long now = System.nanoTime();
        if (lastReleaseReport == 0L) {
            lastReleaseReport = now;
            return;
        }
        if (now - lastReleaseReport < 15_000_000_000L) {
            return;
        }
        lastReleaseReport = now;
        if (released > 0L) {
            Log.debug("[LabVehiclePhysics] ragdolls of dead bodies under vehicles ended, last 15 s: " + released);
            released = 0L;
        }
    }

    private static synchronized void initRelease(Object vehicle) throws Exception {
        if (mDriver != null) {
            return;
        }
        ClassLoader cl = vehicle.getClass().getClassLoader();
        Class<?> rcClass = Class.forName("zombie.core.physics.RagdollController", false, cl);
        Class<?> sdClass = Class.forName("zombie.core.physics.RagdollStateData", false, cl);
        mRagdollController = cCharacter.getMethod("getRagdollController");
        mStateData = rcClass.getMethod("getRagdollStateData");
        mSetActive = rcClass.getMethod("setActive", boolean.class);
        mPelvisZ = rcClass.getMethod("getPelvisPositionZ");
        mComputed = rcClass.getMethod("isSimulationDirectionCalculated");
        mUpright = rcClass.getMethod("isUpright");
        fSimulating = sdClass.getField("isSimulating");
        mDriver = Class.forName("zombie.vehicles.BaseVehicle", false, cl).getMethod("getDriver");
        Log.debug("[LabVehiclePhysics] dead bodies under a driven vehicle stop being ragdolls at once "
                + "(within " + RELEASE_MARGIN + " of its wheel footprint, pelvis below its level + " + RELEASE_LOW + ")");
    }

    /** The footprint of a script, from its physics wheels; cached by script name. */
    private static float[] strips(Object vehicle, String name) throws Exception {
        float[] s = STRIPS.get(name);
        if (s != null) {
            return s;
        }
        Object script = mGetScript.invoke(vehicle);
        int n = script == null ? 0 : ((Integer) mWheelCount.invoke(script)).intValue();
        float sumL = 0f, sumR = 0f, zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
        int left = 0, right = 0;
        for (int i = 0; i < n; i++) {
            Object wheel = mGetWheel.invoke(script, Integer.valueOf(i));
            Object off = mWheelOffset.invoke(wheel);
            float x = fX.getFloat(off);
            float z = fZ.getFloat(off);
            if (x >= 0f) {
                sumL += x;
                left++;
            } else {
                sumR += x;
                right++;
            }
            zMin = Math.min(zMin, z);
            zMax = Math.max(zMax, z);
        }
        if (left == 0 || right == 0) {
            s = new float[0];
        } else {
            s = new float[]{sumL / left, sumR / right, zMin - EXTEND, zMax + EXTEND};
            Log.debug(String.format("[LabVehiclePhysics] footprint of %s: x %.2f..%.2f across, %.2f..%.2f along the hull",
                    name, s[1] - HALF_WIDTH, s[0] + HALF_WIDTH, s[2], s[3]));
        }
        STRIPS.put(name, s);
        return s;
    }

    private static synchronized void init(Object vehicle) throws Exception {
        if (mScriptName != null) {
            return;
        }
        ClassLoader cl = vehicle.getClass().getClassLoader();
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> vs = Class.forName("zombie.scripting.objects.VehicleScript", false, cl);
        Class<?> wheel = Class.forName("zombie.scripting.objects.VehicleScript$Wheel", false, cl);
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        Class<?> cellClass = Class.forName("zombie.iso.IsoCell", false, cl);
        Class<?> square = Class.forName("zombie.iso.IsoGridSquare", false, cl);
        cCharacter = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
        cZombie = Class.forName("zombie.characters.IsoZombie", false, cl);
        mGetScript = bv.getMethod("getScript");
        mWheelCount = vs.getMethod("getWheelCount");
        mGetWheel = vs.getMethod("getWheel", int.class);
        mWheelOffset = wheel.getMethod("getOffset");
        mLocalPos = bv.getMethod("getLocalPos", float.class, float.class, float.class, v3);
        mSpeed = bv.getMethod("getCurrentSpeedKmHour");
        mEngineRunning = bv.getMethod("isEngineRunning");
        mThrottle = bv.getMethod("getThrottle");
        mGetCell = mo.getMethod("getCell");
        mGridSquare = cellClass.getMethod("getGridSquare", int.class, int.class, int.class);
        mMovingObjects = square.getMethod("getMovingObjects");
        mBodyX = mo.getMethod("getX");
        mBodyY = mo.getMethod("getY");
        mBodyZ = mo.getMethod("getZ");
        mDead = cCharacter.getMethod("isDead");
        mRagdoll = cCharacter.getMethod("isRagdollSimulationActive");
        fX = v3.getField("x");
        fZ = v3.getField("z");
        fServer = Class.forName("zombie.network.GameServer", false, cl).getField("server");
        local = v3.getConstructor().newInstance();
        mScriptName = bv.getMethod("getScriptName");
    }
}
