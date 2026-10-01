package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Vehicle hitting an animal in multiplayer: the server decides the damage, with the same model
 * as in singleplayer ({@link Patch_animalHit}, {@link AnimalImpact}).
 *
 * In multiplayer the server simulates animals: on the client {@code IsoAnimal.updateInternal}
 * has no behaviour, only rendering and sending the hit, and an animal's {@code isLocalPlayer()}
 * on the client is always false. The driver's client sends a {@code VehicleHitAnimalPacket}, and
 * the server processes it:
 * <pre>
 * // VehicleHitField.process
 * if (target instanceof IsoAnimal isoAnimal) isoAnimal.setHealth(0.0F);   // any hit is death
 * </pre>
 *
 * <h2>Impact speed</h2>
 * The server has no vehicle physics, so the speed comes from the packet. The client puts
 * {@code HitVars.hitSpeed} there, and for a standing animal that is {@code max(2 * v, 5)}, where
 * v is the vehicle's full horizontal speed, uncapped. Above 2.5 m/s v is recovered exactly.
 * Below that the hit direction helps: {@code IsoAnimal.Hit} on the client sets its length to
 * {@code 3 * min(v, 15) / 15 = 0.2 * v}, and the packet carries it.
 *
 * Difference from singleplayer: here v is the vehicle's full speed, not its component towards
 * the animal; the packet has no direction of travel. A glancing touch deals more damage.
 *
 * Zombies need the same entry point, a vehicle hit on the server: before the damage the driver
 * becomes the zombie's owner, and its corpse waits for the landing point ({@link CorpseSync}).
 *
 * IMPORTANT: ByteBuddy inlines the bodies of enter()/exit() into the game's method: public
 * members only, no lambdas.
 */
@Patch(className = "zombie.network.fields.hit.VehicleHitField", methodName = "process", warmUp = true)
public class Patch_animalHitServer {

    @Patch.OnEnter
    public static void enter(@Patch.This Object field, @Patch.Argument(0) Object wielder,
                             @Patch.Argument(1) Object target, @Patch.Argument(2) Object vehicle) {
        // Zombie: hand it to the driver before damage, defer the corpse until landing (CorpseSync).
        CorpseSync.onServerVehicleHit(wielder, target, vehicle, field);
        Impl.enter(target);
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object field, @Patch.Argument(1) Object target,
                            @Patch.Argument(2) Object vehicle) {
        Impl.exit(field, target, vehicle);
    }

    public static final class Impl {
        /** Floor of hitSpeed = max(2v, 5): below it the speed field is stuck at the floor. */
        public static final float HIT_SPEED_FLOOR = 5.0f;
        /** Length of the hit direction per unit of speed: 3 / 15. */
        public static final float DIR_PER_SPEED = 0.2f;

        public static volatile boolean broken = false;
        public static Field fServer;
        public static Field fVehicleSpeed;
        public static Field fDirX;
        public static Field fDirY;
        public static Object pending;
        public static float savedHealth = 0.0f;
        public static boolean savedStanding = false;
        public static long total = 0L;

        public static void enter(Object target) {
            pending = null;
            if (!LabGate.active() || broken || target == null || !LabSettings.animalHits()) {
                return;
            }
            try {
                if (fServer == null) {
                    fServer = Class.forName("zombie.network.GameServer").getField("server");
                }
                if (!fServer.getBoolean(null)) {
                    return;
                }
                AnimalImpact.init(target.getClass().getClassLoader());
                if (!AnimalImpact.isAnimal(target)) {
                    return;
                }
                savedHealth = ((Float) AnimalImpact.aGetHealth.invoke(target)).floatValue();
                savedStanding = !((Boolean) AnimalImpact.aIsOnFloor.invoke(target)).booleanValue()
                        && AnimalImpact.aGetCurrentState.invoke(target) != AnimalImpact.onGroundState;
                pending = target;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the server animal damage patch, disabling: " + t);
            }
        }

        public static void exit(Object field, Object target, Object vehicle) {
            if (pending == null || pending != target) {
                return;
            }
            pending = null;
            try {
                if (!(savedHealth > 0.0f) || !savedStanding || vehicle == null || field == null) {
                    return;
                }
                if (fVehicleSpeed == null) {
                    init(field);
                }
                AnimalImpact.Episode ep = AnimalImpact.touch(target);
                if (ep.damaged) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(savedHealth));
                    return;
                }
                float v = speedFromPacket(field);
                if (v < AnimalImpact.MIN_SPEED) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(savedHealth));
                    return;
                }
                float vehicleMass = AnimalImpact.fudgedMass(vehicle);
                float animalMass = AnimalImpact.weightOf(target);
                float dmg = AnimalImpact.damage(vehicleMass, animalMass, v);
                ep.damaged = true;
                float left = savedHealth - dmg;
                if (left > 0.0f) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(left));
                }
                total++;
                Patch_animalHit.Impl.log(" (server)", target, vehicleMass, animalMass, v, savedHealth, left);
                AnimalImpact.throwAway(vehicle, target, vehicleMass, animalMass, v);
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the server animal damage patch, disabling: " + t);
            }
        }

        public static float speedFromPacket(Object field) throws Exception {
            float hitSpeed = fVehicleSpeed.getFloat(field);
            if (hitSpeed > HIT_SPEED_FLOOR + 0.001f) {
                return Math.min(hitSpeed / 2.0f, AnimalImpact.SPEED_MAX);
            }
            float x = fDirX.getFloat(field);
            float y = fDirY.getFloat(field);
            float fromDir = (float) Math.sqrt(x * x + y * y) / DIR_PER_SPEED;
            return Math.min(fromDir, HIT_SPEED_FLOOR / 2.0f);
        }

        private static synchronized void init(Object field) throws Exception {
            if (fVehicleSpeed != null) {
                return;
            }
            Class<?> vhf = field.getClass();
            Class<?> hit = Class.forName("zombie.network.fields.hit.Hit", false, vhf.getClassLoader());
            fDirX = hit.getDeclaredField("hitDirectionX");
            fDirY = hit.getDeclaredField("hitDirectionY");
            fDirX.setAccessible(true);
            fDirY.setAccessible(true);
            fVehicleSpeed = vhf.getField("vehicleSpeed");
            Log.debug("[LabVehiclePhysics] server animal damage ready: speed from the hit packet,"
                    + " damage by speed and masses instead of instant death");
        }
    }
}
