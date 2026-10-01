package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * An animal flying off after a vehicle hit: no ragdoll, it slides along the ground.
 *
 * Animals have no ragdoll in the game ({@code IsoAnimal.canRagdoll()} → false, and the ragdoll
 * itself is built for a human skeleton). So the body simply slides in the direction of the hit
 * at the speed the vehicle gave it ({@link AnimalImpact#animalDeltaV}) and is slowed down by
 * friction.
 *
 * <h2>How we move it</h2>
 * Not by setting coordinates directly: we have already lost zombies that way, as the game
 * silently removes a character placed on a square without a floor. We move it the standard way:
 * {@code IsoMovingObject} in {@code postupdate()} adds {@code impulsex/impulsey} to the next
 * position and clamps the step to one square per frame. We only add our own offset there from
 * {@code IsoAnimal.update()}, which runs before postupdate in the same frame.
 *
 * We check walls ourselves, before every step onto a neighbouring square ({@link #blockedAhead}):
 * the game makes a lying animal non-collidable, and the engine does not check walls for it.
 * Once it runs into something, the flight is over.
 *
 * <h2>Where</h2>
 * Wherever the animal is simulated: locally in singleplayer, on the server in multiplayer. Clients
 * see the flight via normal animal position sync. On a multiplayer client the registry is empty:
 * only {@link Patch_animalHit} (singleplayer) and {@link Patch_animalHitServer} register throws.
 *
 * <h2>Killed animals</h2>
 * A killed animal first plays its fall animation, then in the "lying" state calls
 * {@code die()} every frame, which turns it into a corpse: a separate object we no longer move.
 * For the corpse to appear at the end of the flight and not midway while the body is flying,
 * {@code die()} is skipped ({@link Patch_deferCorpse}). In multiplayer the server creates the
 * corpse, so it appears right where the body stopped, without a teleport on the clients.
 */
public final class AnimalThrow {

    /** Sliding deceleration, squares/s². A square is one metre, so this is about 0.7 g. */
    public static final float DECEL = 7.0f;
    /** Anything weaker is a nudge, not a throw. */
    public static final float MIN_START = 0.5f;
    /** Below this the body counts as stopped. */
    public static final float STOP_SPEED = 0.2f;
    /** Safety net: neither the flight nor the held death lasts longer than this. */
    public static final long MAX_NANOS = 3_000_000_000L;
    /** A longer frame counts as this long: the body must not jump after a pause. */
    public static final float MAX_DT = 0.1f;

    public static final class Flight {
        public float dirX;
        public float dirY;
        public float speed;
        public float startSpeed;
        public float travelled;
        public long startNanos;
        public long lastNanos;
    }

    public static final Map<Object, Flight> FLIGHTS = new WeakHashMap<Object, Flight>();
    /** Something is flying. While nothing is, the update() patch costs nothing but this check. */
    public static volatile boolean anyFlying = false;

    public static volatile boolean broken = false;
    public static Method mGetImpulseX;
    public static Method mSetImpulseX;
    public static Method mGetImpulseY;
    public static Method mSetImpulseY;
    public static Method mGetX;
    public static Method mGetY;
    public static Method mGetZ;
    public static Method mCurrentSquare;
    public static Method mTestCollideAdjacent;
    public static Method mSquareGetCell;
    public static Method mGetGridSquare;
    public static Method mTreatAsSolidFloor;
    public static int launchLogged = 0;
    public static int landLogged = 0;
    public static long launched = 0L;
    public static long landed = 0L;
    public static long wallStops = 0L;
    public static long deathsHeld = 0L;
    public static long lastReportNanos = 0L;

    private AnimalThrow() {
    }

    /**
     * Throws the animal. Direction: from the vehicle to the animal (the hit impulse acts along it);
     * speed: what the vehicle imparted.
     */
    public static void launch(Object animal, float dirX, float dirY, float speed) {
        if (broken || animal == null || !(speed >= MIN_START)) {
            return;
        }
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (!(len > 1.0e-4f)) {
            return;
        }
        long now = System.nanoTime();
        Flight f = new Flight();
        f.dirX = dirX / len;
        f.dirY = dirY / len;
        f.speed = speed;
        f.startSpeed = speed;
        f.startNanos = now;
        f.lastNanos = now;
        synchronized (FLIGHTS) {
            FLIGHTS.put(animal, f);
            anyFlying = true;
        }
        launched++;
        if (launchLogged < 8) {
            launchLogged++;
            float expected = speed * speed / (2.0f * DECEL);
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal thrown: %s at %.0f km/h, slides about %.1f m unless a wall stops it",
                    AnimalImpact.typeOf(animal), AnimalImpact.kmh(speed), expected));
        }
    }

    /** One flight step. Called from IsoAnimal.update(), before postupdate of the same frame. */
    public static void step(Object animal) {
        Flight f;
        synchronized (FLIGHTS) {
            f = FLIGHTS.get(animal);
        }
        if (f == null) {
            return;
        }
        try {
            if (mSetImpulseX == null) {
                init(animal);
            }
            long now = System.nanoTime();
            // The engine's collision flag (isCollidedThisFrame) is no good here: it is also raised
            // by pushes from other characters, and in the first frames the body still touches the
            // vehicle, so the flight would end at once. blockedAhead checks walls on every step.
            float dt = (now - f.lastNanos) / 1.0e9f;
            f.lastNanos = now;
            if (dt > MAX_DT) {
                dt = MAX_DT;
            }
            // The engine clamps the step to one square per frame anyway, so we count the same way;
            // otherwise the distance travelled in the log would be wrong on a slow server tick.
            float move = Math.min(f.speed * dt, 1.0f);
            if (move > 0.0f) {
                String blocked = blockedAhead(animal, f, move);
                if (blocked != null) {
                    wallStops++;
                    finish(animal, f, now, blocked);
                    return;
                }
                float ix = ((Float) mGetImpulseX.invoke(animal)).floatValue();
                float iy = ((Float) mGetImpulseY.invoke(animal)).floatValue();
                mSetImpulseX.invoke(animal, Float.valueOf(ix + f.dirX * move));
                mSetImpulseY.invoke(animal, Float.valueOf(iy + f.dirY * move));
                f.travelled += move;
            }
            f.speed -= DECEL * dt;
            if (f.speed <= STOP_SPEED || now - f.startNanos > MAX_NANOS) {
                finish(animal, f, now, "came to rest");
            }
        } catch (Throwable t) {
            broken = true;
            synchronized (FLIGHTS) {
                FLIGHTS.clear();
                anyFlying = false;
            }
            Log.info("[LabVehiclePhysics] ERROR in the animal throw, disabling: " + t);
        }
    }

    /**
     * Whether the step will run into an obstacle. Our own check, not just the engine's: the game
     * makes a lying animal non-collidable ({@code AnimalOnGroundState.enter} →
     * {@code setCollidable(false)}), and then {@code postupdate} does not check walls at all
     * ({@code if (this.collidable) DoCollide(...)}). Without this check a dead body would
     * slide straight through a wall.
     *
     * The engine's own collision check, {@code IsoGridSquare.testCollideAdjacent}:
     * walls, windows, doors, fences, solid objects such as trees.
     *
     * @return the reason for stopping, or null if the way is clear
     */
    public static String blockedAhead(Object animal, Flight f, float move) throws Exception {
        float x = ((Float) mGetX.invoke(animal)).floatValue();
        float y = ((Float) mGetY.invoke(animal)).floatValue();
        int fx = (int) Math.floor(x);
        int fy = (int) Math.floor(y);
        int ox = (int) Math.floor(x + f.dirX * move) - fx;
        int oy = (int) Math.floor(y + f.dirY * move) - fy;
        if (ox == 0 && oy == 0) {
            return null;                    // within its own square
        }
        Object sq = mCurrentSquare.invoke(animal);
        if (sq == null) {
            return "lost its square";
        }
        if (((Boolean) mTestCollideAdjacent.invoke(sq, animal, Integer.valueOf(ox), Integer.valueOf(oy),
                Integer.valueOf(0))).booleanValue()) {
            return "stopped by an obstacle";
        }
        int z = (int) Math.floor(((Float) mGetZ.invoke(animal)).floatValue());
        Object next = mGetGridSquare.invoke(mSquareGetCell.invoke(sq), Integer.valueOf(fx + ox),
                Integer.valueOf(fy + oy), Integer.valueOf(z));
        if (next == null) {
            return "stopped at the edge of the loaded world";
        }
        if (z > 0 && !((Boolean) mTreatAsSolidFloor.invoke(next)).booleanValue()) {
            return "stopped at the edge of the floor";
        }
        return null;
    }

    public static void finish(Object animal, Flight f, long now, String how) {
        synchronized (FLIGHTS) {
            FLIGHTS.remove(animal);
            anyFlying = !FLIGHTS.isEmpty();
        }
        landed++;
        if (landLogged < 8) {
            landLogged++;
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal landed: %s %s after %.1f m in %.1f s (thrown at %.0f km/h)",
                    AnimalImpact.typeOf(animal), how, f.travelled, (now - f.startNanos) / 1.0e9f,
                    AnimalImpact.kmh(f.startSpeed)));
        }
        report();
    }

    /**
     * For die(): do not make a corpse while the body is flying. Once the flight ends, die() goes
     * through on the next frame: the game calls it every frame while the dead animal is down.
     */
    public static boolean holdsDeath(Object chr) {
        if (broken || chr == null) {
            return false;
        }
        Flight f;
        synchronized (FLIGHTS) {
            f = FLIGHTS.get(chr);
        }
        if (f == null) {
            return false;
        }
        if (System.nanoTime() - f.startNanos > MAX_NANOS) {
            return false;
        }
        deathsHeld++;
        return true;
    }

    private static synchronized void init(Object animal) throws Exception {
        if (mSetImpulseX != null) {
            return;
        }
        ClassLoader cl = animal.getClass().getClassLoader();
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        Class<?> gs = Class.forName("zombie.iso.IsoGridSquare", false, cl);
        mGetImpulseX = mo.getMethod("getImpulsex");
        mGetImpulseY = mo.getMethod("getImpulsey");
        mGetX = mo.getMethod("getX");
        mGetY = mo.getMethod("getY");
        mGetZ = mo.getMethod("getZ");
        mCurrentSquare = mo.getMethod("getCurrentSquare");
        mTestCollideAdjacent = gs.getMethod("testCollideAdjacent", mo, int.class, int.class, int.class);
        mSquareGetCell = gs.getMethod("getCell");
        mGetGridSquare = mSquareGetCell.getReturnType().getMethod("getGridSquare", int.class, int.class, int.class);
        mTreatAsSolidFloor = gs.getMethod("TreatAsSolidFloor");
        mSetImpulseY = mo.getMethod("setImpulsey", float.class);
        mSetImpulseX = mo.getMethod("setImpulsex", float.class);
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
        if (launched + landed == 0L) {
            return;
        }
        Log.debug(String.format(
                "[LabVehiclePhysics] animal throws, last 15 s: thrown %d, landed %d (%d against obstacles), death held for %d frames",
                launched, landed, wallStops, deathsHeld));
        launched = 0L;
        landed = 0L;
        wallStops = 0L;
        deathsHeld = 0L;
    }
}
