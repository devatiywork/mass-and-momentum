package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Vehicle hitting an animal, animal side, singleplayer: damage instead of instant death.
 *
 * Vanilla, {@code IsoAnimal.Hit(BaseVehicle, ...)}: a standing animal gets {@code setHealth(0)}
 * at any speed. A cow died from being hit at 5 km/h. We compute the damage from the impact speed
 * and the masses (the model is {@link AnimalImpact}) and let a survivor keep its health.
 *
 * What is left to vanilla:
 * <ul>
 *   <li>a lying animal, run over by a wheel: vanilla finishes it off, we stay out of it;</li>
 *   <li>multiplayer. There {@code Hit} on the client does not touch health at all; the damage is
 *       decided by the server, see {@link Patch_animalHitServer}.</li>
 * </ul>
 *
 * The game calls {@code Hit} every frame while the vehicle touches the animal. Damage is dealt
 * once per impact; on repeat frames of the same impact we restore the health vanilla zeroed.
 * A survivor has its "road kill" flag cleared: butchering reads it to count the animal as
 * road kill ({@code ButcheringUtil.lua}: {@code modData["roadKill"] = died:isRoadKill()}) even
 * if it later dies of something else.
 *
 * Overloads: {@code Hit(BaseVehicle, float, boolean, float, float, boolean, float, float)}
 * delegates to {@code Hit(BaseVehicle, float, boolean, Vector2)}; the by-name patch hooks both.
 * We count the nesting depth and act on exit from the outermost call.
 *
 * IMPORTANT: ByteBuddy inlines the bodies of enter()/exit() into the game's method: public
 * members only, no lambdas.
 */
@Patch(className = "zombie.characters.animals.IsoAnimal", methodName = "Hit", warmUp = true)
public class Patch_animalHit {

    @Patch.OnEnter
    public static void enter(@Patch.This Object animal) {
        Impl.enter(animal);
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object animal, @Patch.Argument(0) Object vehicle) {
        Impl.exit(animal, vehicle);
    }

    public static final class Impl {
        /** Nesting depth of the overloads. Hit is called from the main thread. */
        public static int depth = 0;
        public static boolean active = false;
        public static float savedHealth = 0.0f;
        public static boolean savedStanding = false;

        public static volatile boolean broken = false;
        public static Field fClient;
        public static Field fServer;
        public static int logged = 0;
        public static long total = 0L;

        public static void enter(Object animal) {
            depth++;
            if (depth != 1) {
                return;
            }
            active = false;
            if (!LabGate.active() || broken || animal == null || !LabSettings.animalHits()) {
                return;
            }
            try {
                if (isNetwork()) {
                    return;
                }
                AnimalImpact.init(animal.getClass().getClassLoader());
                savedHealth = ((Float) AnimalImpact.aGetHealth.invoke(animal)).floatValue();
                // Vanilla's own "standing" test: not on the floor and not in the "lying" state.
                savedStanding = !((Boolean) AnimalImpact.aIsOnFloor.invoke(animal)).booleanValue()
                        && AnimalImpact.aGetCurrentState.invoke(animal) != AnimalImpact.onGroundState;
                active = true;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the animal damage patch, disabling: " + t);
            }
        }

        public static void exit(Object animal, Object vehicle) {
            if (depth > 0) {
                depth--;
            }
            if (depth != 0 || !active) {
                return;
            }
            active = false;
            try {
                if (!(savedHealth > 0.0f) || !savedStanding || vehicle == null) {
                    return;
                }
                AnimalImpact.Episode ep = AnimalImpact.touch(animal);
                if (ep.damaged) {
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(savedHealth));
                    return;
                }
                float v = AnimalImpact.closingSpeed(vehicle, animal);
                if (v < AnimalImpact.MIN_SPEED) {
                    // A touch, not an impact: the vehicle pulls away or rubs against it side-on.
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(savedHealth));
                    return;
                }
                float vehicleMass = AnimalImpact.fudgedMass(vehicle);
                float animalMass = AnimalImpact.weightOf(animal);
                float dmg = AnimalImpact.damage(vehicleMass, animalMass, v);
                ep.damaged = true;
                float left = savedHealth - dmg;
                if (left > 0.0f) {
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(left));
                    AnimalImpact.aSetIsRoadKill.invoke(animal, Boolean.FALSE);
                }
                // Otherwise vanilla has already done it all: health 0, "road kill" flag set.
                total++;
                log("", animal, vehicleMass, animalMass, v, savedHealth, left);
                AnimalImpact.throwAway(vehicle, animal, vehicleMass, animalMass, v);
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the animal damage patch, disabling: " + t);
            }
        }

        public static void log(String where, Object animal, float vehicleMass, float animalMass, float v,
                               float before, float after) {
            if (logged >= 12) {
                return;
            }
            logged++;
            float dv = AnimalImpact.animalDeltaV(vehicleMass, animalMass, v);
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal damage%s: %s %.1f kg hit at %.0f km/h by a %.0f kg vehicle"
                    + " -> thrown at %.0f km/h, lethal from %.0f km/h, health %.0f%% -> %s",
                    where, AnimalImpact.typeOf(animal), animalMass, AnimalImpact.kmh(v), vehicleMass,
                    AnimalImpact.kmh(dv), AnimalImpact.kmh(AnimalImpact.lethalDeltaV(animalMass)),
                    before * 100.0f, after > 0.0f ? String.format("%.0f%%", after * 100.0f) : "killed"));
        }

        /** Multiplayer: the server decides damage, the client Hit leaves health alone, so do we. */
        public static boolean isNetwork() throws Exception {
            if (fClient == null) {
                fServer = Class.forName("zombie.network.GameServer").getField("server");
                fClient = Class.forName("zombie.network.GameClient").getField("client");
            }
            return fClient.getBoolean(null) || fServer.getBoolean(null);
        }
    }
}
