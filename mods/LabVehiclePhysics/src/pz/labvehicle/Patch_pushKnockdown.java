package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.9: a heavy vehicle knocks a zombie over at any speed.
 *
 * Whether a zombie hit by a vehicle falls, IsoZombie.postHitByVehicleUpdateStance decides from the
 * impact speed and the zombie's "unbalanced level" alone:
 * <pre>
 * minSpeedToPossibleKnockdown = lerp(5.0, 0.1, unbalanced);
 * maxSpeedToPossibleKnockdown = lerp(25.0, 15.0, unbalanced);
 * t      = lerp(1, 10, unbalanced) * (speed - min) / (max - min);
 * chance = 1 - (1 - t)^2;                       // PZMath's "EaseInQuad"
 * // after a stagger: unbalanced = lerp(unbalanced, 1, 0.35 * multiplier), at least 0.35
 * </pre>
 * A steady zombie cannot fall below 5 m/s (18 km/h). Below that it only staggers, the game slides
 * it out of the vehicle's outline, and it rides on the bumper: each contact unbalances it a little
 * more, so it does go down, but only after dozens of contacts. For a city car at walking pace
 * that is fair: a person braces and steps back. A tank pushes with a force no one can resist.
 *
 * The vehicle's mass therefore sets a floor on the unbalanced level before the vanilla check:
 * UNBALANCE_SLOPE per kilogram above FREE_MASS, at most UNBALANCE_MAX. A city car (≤ 1.5 t): 0, as
 * vanilla. Humvee (3 t): ~0.13. Bushmaster, M113 and the tanks: ~0.85, which brings the knockdown
 * threshold from 5 down to ~0.8 m/s: at 5 km/h the zombie goes down within a frame or two of
 * contact, and then the vehicle runs it over. The speed passed to the check is not touched, so
 * the damage and the chance of becoming a crawler stay as they were.
 */
@Patch(className = "zombie.characters.IsoZombie", methodName = "postHitByVehicleUpdateStance", warmUp = true)
public class Patch_pushKnockdown {

    @Patch.OnEnter
    public static void enter(@Patch.This Object zombie) {
        Impl.unbalance(zombie);
    }

    public static final class Impl {
        /** Vehicles up to this mass do not unbalance anyone beyond vanilla. */
        public static final float FREE_MASS = 1500.0f;
        /** Unbalance per kilogram above FREE_MASS: full at about 11.7 t. */
        public static final float UNBALANCE_SLOPE = 1.0f / 12000.0f;
        public static final float UNBALANCE_MAX = 0.85f;

        public static volatile boolean broken = false;
        public static Method mGet, mSet;
        public static long raised = 0L;
        public static int logged = 0;

        public static void unbalance(Object zombie) {
            Object hit = Patch_onHitByVehicle.Impl.lastCharacter;
            Patch_onHitByVehicle.Impl.lastCharacter = null;
            if (hit == null || hit != zombie || broken) {
                return;
            }
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return;
            }
            try {
                float floor = (Patch_onHitByVehicle.Impl.lastVehicleMass - FREE_MASS) * UNBALANCE_SLOPE;
                if (!(floor > 0.0f)) {
                    return;
                }
                if (floor > UNBALANCE_MAX) {
                    floor = UNBALANCE_MAX;
                }
                if (mGet == null) {
                    Class<?> z = Class.forName("zombie.characters.IsoZombie", false, zombie.getClass().getClassLoader());
                    mGet = z.getMethod("getUnbalancedLevel");
                    mSet = z.getMethod("setUnbalancedLevel", float.class);
                    Log.debug("[LabVehiclePhysics] push knockdown ready: a vehicle heavier than " + (int) FREE_MASS
                            + " kg unbalances the zombie it hits, up to " + UNBALANCE_MAX + " at "
                            + (int) (FREE_MASS + UNBALANCE_MAX / UNBALANCE_SLOPE) + " kg");
                }
                float current = ((Float) mGet.invoke(zombie)).floatValue();
                if (current < floor) {
                    mSet.invoke(zombie, Float.valueOf(floor));
                    raised++;
                    if (logged < 3) {
                        logged++;
                        Log.debug(String.format("[LabVehiclePhysics] push knockdown #%d: vehicle %.0f kg, unbalanced %.2f -> %.2f",
                                raised, Patch_onHitByVehicle.Impl.lastVehicleMass, current, floor));
                    }
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the push knockdown, disabling it: " + t);
            }
        }
    }
}
