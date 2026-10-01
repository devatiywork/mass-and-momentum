package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 3, patch 2 of 2: adjusting the mass on the fly, without restarting the game.
 *
 * Why. Suspension stiffness is baked in once at load time (Bullet.defineVehicleScript),
 * but the mass goes into the physics EVERY frame:
 * <pre>
 * // BaseVehicle.update(), under !GameServer.server
 * Bullet.setVehicleMass(this.vehicleId, this.getFudgedMass());
 * </pre>
 * So by overriding the return value of getFudgedMass(), the mass can be tuned right during play.
 * This is needed to find the ceiling: for now there are only reports that above ~9-10 tonnes
 * the wheels sink into the ground, and the cause has not been established. There is nothing to
 * guess here; it has to be measured, and measuring is easier in one run than over ten restarts.
 *
 * The same getFudgedMass() is read by our hit-force patch from stage 1, so the live mass
 * also affects how the vehicle knocks zombies back, and that is visible right away too.
 *
 * As long as the file has no rule marked {@code live} and the "Live vehicle mass" switch
 * on the sandbox page ({@link LabSettings#liveMass}) is off, the patch does nothing
 * and costs not a single cycle: two volatile flags are checked. The switch turns on the
 * override for all vehicles that have a mass set; cargo then adds no weight.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into getFudgedMass(): public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "getFudgedMass", warmUp = true)
public class Patch_vehicleLiveMass {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) float ret) {
        ret = Impl.adjust(self, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mScriptName;
        public static final Map<String, VehicleCfg.Rule> CACHE = new HashMap<String, VehicleCfg.Rule>();
        public static int cachedGeneration = -1;
        /**
         * Which vehicle/mass pairs have already been printed. Each one is printed once.
         *
         * Previously it compared only with the last printed vehicle. While {@code live} was set
         * on two vehicles, that was tolerable. When the whole fleet got rules with {@code live},
         * every vehicle around the player displaced the "last" one and was printed every frame:
         * 2 688 lines per second, 98% of the client log. The log got truncated and lost the first
         * twenty minutes of the session, with the very lines it is read for. The same mistake was
         * already in the tank log and was fixed there with a set; the lesson was not carried over.
         */
        public static final java.util.Set<String> LOGGED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        public static float adjust(Object vehicle, float vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || vehicle == null) {
                return vanilla;
            }
            try {
                // Re-read the file first, and only then look at hasLive.
                // The reverse order was a dead end: the flag is set inside reloadIfNeeded(),
                // which was never reached because the flag itself blocked the way.
                VehicleCfg.reloadIfNeeded();
                VehicleCfg.maybeFallback();
                VehicleCfg.printSummaryOnce();
                // Live mass: for individual rules, the live flag in vehicle-physics.cfg;
                // for all vehicles at once, the switch on the sandbox page.
                boolean all = LabSettings.liveMass();
                if (!VehicleCfg.hasLive && !all) {
                    return vanilla;
                }
                if (mScriptName == null) {
                    mScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mScriptName.invoke(vehicle);
                if (name == null) {
                    return vanilla;
                }
                VehicleCfg.Rule rule;
                synchronized (CACHE) {
                    if (cachedGeneration != VehicleCfg.generation) {
                        cachedGeneration = VehicleCfg.generation;
                        CACHE.clear();
                    }
                    if (CACHE.containsKey(name)) {
                        rule = CACHE.get(name);
                    } else {
                        rule = VehicleCfg.forName(name);
                        CACHE.put(name, rule);
                    }
                }
                if (rule == null || !(rule.live || all) || rule.mass <= 0.0f) {
                    return vanilla;
                }
                // Cargo is not part of the key: it changes every time the trunk is searched,
                // and the log would fill up with repeats again.
                if (LOGGED.add(name + "|" + rule.mass)) {
                    Log.debug("[LabVehiclePhysics] live mass: " + name + " = "
                            + VehicleCfg.fmt(rule.mass) + " kg (game computed " + VehicleCfg.fmt(vanilla) + ")");
                }
                return rule.mass;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in live mass, disabling: " + t);
                return vanilla;
            }
        }
    }
}
