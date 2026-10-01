package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.7: a physically meaningful force for the vehicle's first hit on a character.
 *
 * <p>Vanilla does not pass the real speed to {@code onHitByVehicle()}:</p>
 * <pre>
 * speed = min(|velocity|, 15);                         // everything above ~54 km/h is lost
 * hitDir *= 3 * speed / 15;                            // length no more than 3
 * hitForce = speed + clientForce / vehicleMass;        // mass enters inversely
 * </pre>
 *
 * <p>The first version of the patch replaced this with {@code speed * vehicleMass / 800},
 * capped at {@code x4}. That removed the inverse dependence but mixed up two different sides
 * of the collision. Once the vehicle is already much heavier than the body, making it heavier
 * still barely increases the momentum the body receives. What it reduces is the speed loss of
 * the vehicle itself. For a head-on hit, the correct scale is set by the reduced mass:</p>
 * <pre>
 * reducedMass = vehicleMass * bodyMass / (vehicleMass + bodyMass)
 * factor      = reducedMass / reducedMass(800 kg, bodyMass)
 * </pre>
 *
 * <p>For a 100 kg body the factors come out as: passenger car 800 kg = 1.000,
 * Bushmaster 11 400 kg = 1.115, M60A3 52 000 kg = 1.123. So at the same speed
 * all three vehicles deal the body a comparable blow, and the difference in ploughing through
 * a crowd arises on the reaction-impulse side: the same momentum transfer slows a heavy
 * vehicle far less.</p>
 *
 * <p>The effective speed for damage and knockdown is capped at 25. At this value
 * vanilla already guarantees that even a sturdy zombie goes down, and the damage exceeds its
 * health many times over. Raising the number further would only inflate the quadratic damage
 * formula without adding any observable result. The push direction is scaled separately by
 * the real speed and the reduced mass; speed is no longer multiplied in twice.</p>
 *
 * <p>The reaction impulse on the vehicle is not changed here. It is computed in
 * {@code BaseVehicle.applyImpulseFromHitPedestrian()} from the body mass and speed, and
 * {@link Patch_impulseBudget} keeps it from being deducted repeatedly throughout the contact.</p>
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "onHitByVehicle", warmUp = true)
public class Patch_onHitByVehicle {

    @Patch.OnEnter
    public static void enter(@Patch.This Object character,
                             @Patch.Argument(0) Object vehicle,
                             @Patch.Argument(value = 1, readOnly = false) float hitForce,
                             @Patch.Argument(2) Object hitDir) {
        hitForce = Impl.rewrite(character, vehicle, hitForce, hitDir);
    }

    public static final class Impl {
        /** The vehicle mass the vanilla body reaction is calibrated for. */
        public static final float REFERENCE_VEHICLE_MASS = 800.0f;
        /** Base character mass in the PZ formula; Patch_getMass adds the spread. */
        public static final float BASE_BODY_MASS = 100.0f;
        /** Numeric safeguards for exotically light and erroneously heavy scripts. */
        public static final float FACTOR_MIN = 0.5f;
        public static final float FACTOR_MAX = 1.25f;
        /** The old speed cap and the new limit for reading the real speed. */
        public static final float VANILLA_SPEED_CAP = 15.0f;
        public static final float REAL_SPEED_MAX = 40.0f;
        /** Saturation point of the game's damage/knockdown formula. */
        public static final float EFFECTIVE_IMPACT_MAX = 25.0f;
        /** Guard against an abnormal push vector. The vanilla maximum is 3. */
        public static final float HIT_DIR_MAX = 10.0f;

        public static volatile boolean broken = false;
        public static Method vGetLinearVelocity;
        public static Method vGetFudgedMass;
        public static Object velOut;
        public static Field vx, vz;
        public static Field dirX, dirY;
        public static long hits = 0L;
        public static int logged = 0;

        /** @return the new hitForce value. Side effect: fixes the length of the push vector. */
        public static float rewrite(Object character, Object vehicle, float vanillaForce, Object hitDir) {
            if (!LabGate.active()) {
                return vanillaForce;
            }
            // Multiplayer: our driver hit a zombie; when the body lands, the server gets its point
            // (CorpseSync). Its own switch is "Corpse follows the ragdoll", not the impact physics.
            CorpseSync.onLocalHit(character, vehicle);
            if (!LabSettings.zombieImpact()) {
                return vanillaForce;
            }
            if (broken || vehicle == null) {
                return vanillaForce;
            }
            try {
                if (vGetFudgedMass == null) {
                    init(vehicle);
                }
                float speed = speedOf(vehicle);
                if (!(speed > 0.0f)) {
                    return vanillaForce;
                }
                if (speed > REAL_SPEED_MAX) {
                    speed = REAL_SPEED_MAX;
                }

                float vehicleMass = ((Float) vGetFudgedMass.invoke(vehicle)).floatValue();
                float bodyMass = bodyMassFor(character);
                float factor = reducedMassFactor(vehicleMass, bodyMass);
                float uncappedImpact = speed * factor;
                float impact = Math.min(uncappedImpact, EFFECTIVE_IMPACT_MAX);
                float dirLength = rewriteDirection(hitDir, speed, factor);

                hits++;
                if (logged < 8) {
                    logged++;
                    Log.debug(String.format(
                            "[LabVehiclePhysics] hit #%d: speed %.1f tiles/s (~%.0f km/h), vehicle %.0f kg, "
                            + "body %.0f kg -> reduced-mass factor %.3f, effective impact %.1f%s, hit-dir %.2f "
                            + "(vanilla impact %.1f)",
                            hits, speed, speed * 3.6f, vehicleMass, bodyMass, factor, impact,
                            uncappedImpact > EFFECTIVE_IMPACT_MAX ? " (saturated)" : "", dirLength, vanillaForce));
                }
                return impact;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the hit patch, disabling: " + t);
                return vanillaForce;
            }
        }

        /** Body mass without the per-frame multiplier: same spread as in the reaction impulse. */
        public static float bodyMassFor(Object character) {
            float spread = character == null ? 1.0f : Patch_getMass.Impl.spreadFor(character);
            return BASE_BODY_MASS * spread;
        }

        public static float reducedMass(float firstMass, float secondMass) {
            if (!(firstMass > 0.0f) || !(secondMass > 0.0f)) {
                return 0.0f;
            }
            return firstMass * secondMass / (firstMass + secondMass);
        }

        /** A pure function, kept separate so the formula can be checked without the game. */
        public static float reducedMassFactor(float vehicleMass, float bodyMass) {
            float actual = reducedMass(vehicleMass, bodyMass);
            float reference = reducedMass(REFERENCE_VEHICLE_MASS, bodyMass);
            if (!(actual > 0.0f) || !(reference > 0.0f)) {
                return 1.0f;
            }
            float factor = actual / reference;
            if (factor < FACTOR_MIN) {
                return FACTOR_MIN;
            }
            return factor > FACTOR_MAX ? FACTOR_MAX : factor;
        }

        /**
         * BaseVehicle passes an already scaled vector of length min(speed,15)/5.
         * We normalise it and set the length anew: speed/5 * reducedMassFactor.
         */
        public static float rewriteDirection(Object hitDir, float speed, float factor) throws Exception {
            if (hitDir == null || dirX == null) {
                return 0.0f;
            }
            float x = dirX.getFloat(hitDir);
            float y = dirY.getFloat(hitDir);
            float oldLength = (float) Math.sqrt(x * x + y * y);
            if (!(oldLength > 0.0001f)) {
                return 0.0f;
            }
            float target = 3.0f * speed / VANILLA_SPEED_CAP * factor;
            if (target > HIT_DIR_MAX) {
                target = HIT_DIR_MAX;
            }
            float scale = target / oldLength;
            dirX.setFloat(hitDir, x * scale);
            dirY.setFloat(hitDir, y * scale);
            return target;
        }

        /** Horizontal vehicle speed from the physics, without the vanilla cap. */
        public static float speedOf(Object vehicle) throws Exception {
            Object out = velOut;
            vGetLinearVelocity.invoke(vehicle, out);
            float x = vx.getFloat(out);
            float z = vz.getFloat(out);
            return (float) Math.sqrt(x * x + z * z);
        }

        private static synchronized void init(Object vehicle) throws Exception {
            if (vGetFudgedMass != null) {
                return;
            }
            Class<?> vc = vehicle.getClass();
            vGetFudgedMass = vc.getMethod("getFudgedMass");
            Class<?> v3 = Class.forName("org.joml.Vector3f", false, vc.getClassLoader());
            vGetLinearVelocity = vc.getMethod("getLinearVelocity", v3);
            velOut = v3.getConstructor().newInstance();
            vx = v3.getField("x");
            vz = v3.getField("z");
            Class<?> v2 = Class.forName("zombie.iso.Vector2", false, vc.getClassLoader());
            dirX = v2.getField("x");
            dirY = v2.getField("y");
            Log.debug("[LabVehiclePhysics] hit patch ready: real speed up to " + REAL_SPEED_MAX
                    + " tiles/s, reduced mass instead of linear vehicle-mass multiplier, impact saturates at "
                    + EFFECTIVE_IMPACT_MAX);
        }
    }
}
