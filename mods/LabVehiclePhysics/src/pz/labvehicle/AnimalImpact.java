package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hitting an animal: shared physics for braking the vehicle and damaging the animal.
 *
 * <h2>What vanilla does</h2>
 * Animals have their own copy of the collision code ({@code IsoAnimal.testCollideWithVehicles} →
 * {@code BaseVehicle.hitAnimal}), and none of our zombie patches touch it.
 * <pre>
 * // BaseVehicle.hitAnimal — every frame of contact
 * applyImpulseFromHitObject(chr, getFudgedMass() * 7 * min(speed, 15) / 15 * |dot|);
 * // IsoAnimal.testCollideWithVehicles — one more in the same frame, affecting nothing
 * applyImpulseFromHitObject(this, 1.0F);
 * // IsoAnimal.Hit — in singleplayer; in multiplayer the server does it (VehicleHitField.process)
 * setHealth(0.0F);
 * </pre>
 * The impulse is proportional to the vehicle's mass and then divided by it: the mass cancels out,
 * and the animal's weight is not in the formula at all. A cat slows a twelve-tonne armoured car
 * as much as a cow slows a car. And any touch kills: a cow dies from being hit at 5 km/h.
 *
 * <h2>How it works now</h2>
 * One hit is one momentum exchange, as in stages 1.6 and 1.7 for zombies. An animal of mass m
 * gets this speed from a vehicle of mass M:
 * <pre>
 *   dv_animal = v * (1 + e) * M / (M + m)
 *   p         = v * (1 + e) * M * m / (M + m)     — the vehicle loses just as much
 * </pre>
 * where v is the vehicle's speed towards the animal and e is the restitution of the hit. A cat
 * takes a negligible share of an armoured car's speed, a cow takes a third of a car's.
 *
 * Damage follows from the same animal dv, as a fraction of health (animal health is 0 to 1):
 * <pre>
 *   damage = (dv / dv_lethal)^2,   dv_lethal = K * m^P
 * </pre>
 * This is a game model, not medicine. K and P are fitted to two reference points:
 * a 4 kg chicken dies at 3 m/s (11 km/h), a 700 kg cow at 12.5 m/s (45 km/h).
 * A cow survives being hit by a car at 10 km/h (3% of its health), a chicken does not.
 *
 * The animal's weight is {@code getData().getWeight()}; in the game it is in kilograms: chicken
 * 2–6, pig up to 350, cow up to 1300.
 */
public final class AnimalImpact {

    /** Restitution: the animal flies off a bit faster than the vehicle, not stuck to its bumper. */
    public static final float RESTITUTION = 0.2f;
    /**
     * How much of the queued impulse reaches the vehicle in one application.
     * The game applies the queue as a force x30 for exactly one Bullet step of 0.01 s
     * (WorldSimulation.updatePhysic → applyAccumulatedImpulsesFromHitObjectsToPhysics
     * before every stepSimulation(0.01F, 0, 0)), i.e. 30 * 0.01 = 0.3.
     */
    public static final float APPLIED_FRACTION = 0.3f;
    /** Below this speed there is no hit, as in vanilla (speed < 0.05). */
    public static final float MIN_SPEED = 0.05f;
    /** Cap on the speed we read, the same as for zombies (Patch_onHitByVehicle.REAL_SPEED_MAX). */
    public static final float SPEED_MAX = 40.0f;
    /** Gap in contact after which a hit counts as new, as for zombies (Patch_impulseBudget). */
    public static final long RESET_NANOS = 400_000_000L;
    /** dv_lethal = K * m^P, m/s. */
    public static final float LETHAL_K = 2.05f;
    public static final float LETHAL_P = 0.276f;
    public static final float LETHAL_MIN = 1.0f;
    /** Used if the weight cannot be read. The message about it is logged once. */
    public static final float FALLBACK_WEIGHT = 50.0f;

    public static volatile boolean broken = false;
    public static Class<?> animalClass;
    public static Method aGetData;
    public static Method dGetWeight;
    public static Method aGetCurrentState;
    public static Method aGetAnimalType;
    public static Method aGetHealth;
    public static Method aSetHealth;
    public static Method aIsOnFloor;
    public static Method aSetIsRoadKill;
    public static Method oGetX;
    public static Method oGetY;
    public static Object falldownState;
    public static Object onGroundState;
    public static Method vGetFudgedMass;
    public static Method vGetLinearVelocity;
    public static Object velOut;
    public static Field vx;
    public static Field vz;
    public static boolean weightWarned = false;

    /** Contact episode with one animal: last touch time and what has already been applied. */
    public static final class Episode {
        public long last;
        public boolean braked;
        public boolean damaged;
    }

    public static final Map<Object, Episode> EPISODES = new WeakHashMap<Object, Episode>();

    private AnimalImpact() {
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (animalClass != null) {
            return;
        }
        Class<?> a = Class.forName("zombie.characters.animals.IsoAnimal", false, cl);
        aGetData = a.getMethod("getData");
        dGetWeight = aGetData.getReturnType().getMethod("getWeight");
        aGetCurrentState = a.getMethod("getCurrentState");
        aGetAnimalType = a.getMethod("getAnimalType");
        aGetHealth = a.getMethod("getHealth");
        aSetHealth = a.getMethod("setHealth", float.class);
        aIsOnFloor = a.getMethod("isOnFloor");
        aSetIsRoadKill = a.getMethod("setIsRoadKill", boolean.class);
        // Coordinates live on the common ancestor of vehicle and animal: one method reads both.
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        oGetX = mo.getMethod("getX");
        oGetY = mo.getMethod("getY");
        falldownState = Class.forName("zombie.ai.states.animals.AnimalFalldownState", true, cl)
                .getMethod("instance").invoke(null);
        onGroundState = Class.forName("zombie.ai.states.animals.AnimalOnGroundState", true, cl)
                .getMethod("instance").invoke(null);
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        vGetFudgedMass = bv.getMethod("getFudgedMass");
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        vGetLinearVelocity = bv.getMethod("getLinearVelocity", v3);
        velOut = v3.getConstructor().newInstance();
        vx = v3.getField("x");
        vz = v3.getField("z");
        animalClass = a;
    }

    public static boolean isAnimal(Object o) {
        return o != null && animalClass != null && animalClass.isInstance(o);
    }

    public static float weightOf(Object animal) {
        try {
            Object data = aGetData.invoke(animal);
            if (data != null) {
                float w = ((Float) dGetWeight.invoke(data)).floatValue();
                if (w > 0.0f) {
                    return w;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!weightWarned) {
            weightWarned = true;
            Log.info("[LabVehiclePhysics] animal hit: could not read an animal's weight - using "
                    + FALLBACK_WEIGHT + " kg for it");
        }
        return FALLBACK_WEIGHT;
    }

    public static String typeOf(Object animal) {
        try {
            Object t = aGetAnimalType.invoke(animal);
            return t != null ? t.toString() : "animal";
        } catch (Throwable t) {
            return "animal";
        }
    }

    /** Falling or lying down. Vanilla no longer pushes such an animal, and neither do we. */
    public static boolean isDown(Object animal) throws Exception {
        Object s = aGetCurrentState.invoke(animal);
        return s == falldownState || s == onGroundState;
    }

    public static float fudgedMass(Object vehicle) throws Exception {
        return ((Float) vGetFudgedMass.invoke(vehicle)).floatValue();
    }

    /**
     * The vehicle's speed towards the animal, from the physics, without the vanilla cap of 15.
     * 0 if the vehicle is moving away from it. Only where there is physics: on the client and in
     * singleplayer. The server has no Bullet; there the speed comes from the packet.
     *
     * The Z axis of the Bullet velocity corresponds to the world's Y axis; the game itself maps
     * them the same way in hitAnimal: velocity.dot((dx, 0, dy)).
     */
    public static float closingSpeed(Object vehicle, Object animal) throws Exception {
        float dx = ((Float) oGetX.invoke(animal)).floatValue() - ((Float) oGetX.invoke(vehicle)).floatValue();
        float dy = ((Float) oGetY.invoke(animal)).floatValue() - ((Float) oGetY.invoke(vehicle)).floatValue();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 1.0e-4f)) {
            return 0.0f;
        }
        Object out = velOut;
        vGetLinearVelocity.invoke(vehicle, out);
        float vn = (vx.getFloat(out) * dx + vz.getFloat(out) * dy) / len;
        if (!(vn > 0.0f)) {
            return 0.0f;
        }
        return vn > SPEED_MAX ? SPEED_MAX : vn;
    }

    /** The speed the animal receives. */
    public static float animalDeltaV(float vehicleMass, float animalMass, float v) {
        if (!(vehicleMass > 0.0f) || !(animalMass > 0.0f) || !(v > 0.0f)) {
            return 0.0f;
        }
        return v * (1.0f + RESTITUTION) * vehicleMass / (vehicleMass + animalMass);
    }

    /** The momentum exchanged between the vehicle and the animal. */
    public static float momentum(float vehicleMass, float animalMass, float v) {
        return animalMass * animalDeltaV(vehicleMass, animalMass, v);
    }

    public static float lethalDeltaV(float animalMass) {
        float d = LETHAL_K * (float) Math.pow(Math.max(animalMass, 0.001f), LETHAL_P);
        return d < LETHAL_MIN ? LETHAL_MIN : d;
    }

    /** Damage as a fraction of health, from 0 to 1. */
    public static float damage(float vehicleMass, float animalMass, float v) {
        float dv = animalDeltaV(vehicleMass, animalMass, v);
        float r = dv / lethalDeltaV(animalMass);
        float d = r * r;
        return d > 1.0f ? 1.0f : d;
    }

    /**
     * Records a touch and returns the episode. A touch after a gap longer than RESET_NANOS is
     * a new hit: the vehicle backed off and struck again.
     */
    public static Episode touch(Object animal) {
        long now = System.nanoTime();
        synchronized (EPISODES) {
            Episode ep = EPISODES.get(animal);
            if (ep == null) {
                ep = new Episode();
                EPISODES.put(animal, ep);
            } else if (now - ep.last > RESET_NANOS) {
                ep.braked = false;
                ep.damaged = false;
            }
            ep.last = now;
            return ep;
        }
    }

    /**
     * Throws the animal away from the vehicle. Direction: vehicle to animal, along the hit impulse;
     * speed: what the vehicle imparted. Called wherever the animal is simulated.
     */
    public static void throwAway(Object vehicle, Object animal, float vehicleMass, float animalMass, float v)
            throws Exception {
        float dx = ((Float) oGetX.invoke(animal)).floatValue() - ((Float) oGetX.invoke(vehicle)).floatValue();
        float dy = ((Float) oGetY.invoke(animal)).floatValue() - ((Float) oGetY.invoke(vehicle)).floatValue();
        AnimalThrow.launch(animal, dx, dy, animalDeltaV(vehicleMass, animalMass, v));
    }

    public static float kmh(float tilesPerSecond) {
        return tilesPerSecond * 3.6f;
    }
}
