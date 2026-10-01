package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Engine power and brakes on the fly, without a restart and without polluting the save.
 *
 * Why not through the engine power directly. The obvious route, overriding
 * {@code BaseVehicle.getEnginePower()}, does not work: it is read not only by the physics but
 * also by serialization.
 * <pre>
 * // BaseVehicle, saving the vehicle
 * output.putInt(this.getEnginePower());
 * // VehicleEngine, network packet
 * b.putInt(this.getVehicle().getEnginePower());
 * </pre>
 * The inflated number would go into the save and over the network, and stay there forever, even
 * after the mod is turned off. Besides, for existing vehicles the power is taken from the save
 * anyway, not from the script, so editing the script would not affect them regardless.
 *
 * So we hook into the last point before the forces are applied:
 * <pre>
 * // CarController.update()
 * BulletVariables bv = bulletVariables.set(vehicleObject, engineForce, brakingForce, steering);
 * this.checkTire(bv);              // ← here
 * this.engineForce = bv.engineForce;
 * ...
 * Bullet.controlVehicle(vehicleId, this.engineForce, this.brakingForce, this.vehicleSteering);
 * </pre>
 * {@code checkTire} itself does exactly this kind of work: it divides thrust and braking for flat
 * tyres. We just multiply the same two fields after it. Nothing ends up in the save.
 *
 * The values come from the file and are re-read on the fly: {@code powerMul} and {@code brakeMul},
 * a number or {@code auto}. {@code auto} is the ratio of the new mass to the vanilla one, so
 * acceleration and braking distance stay as on the stock vehicle despite the real weight.
 *
 * On top comes the global power multiplier from the sandbox ({@link LabSettings#powerMul}): it
 * applies to all vehicles, including those that have no rules, and does not touch the brakes.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into checkTire: public members only, no lambdas.
 */
@Patch(className = "zombie.core.physics.CarController", methodName = "checkTire", warmUp = true)
public class Patch_enginePower {

    @Patch.OnExit
    public static void exit(@Patch.This Object controller, @Patch.Argument(0) Object bv) {
        Impl.scale(controller, bv);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Field fVehicleObject;
        public static Field fSpeed;
        public static Field fEngineForce;
        public static Field fBrakingForce;
        public static Method mScriptName;
        /**
         * Which vehicle/multiplier combinations have already been printed. Comparing with the
         * last printed one, as before, prints every frame as soon as there are two vehicles;
         * on a server with several players driving, that would happen immediately. This is how
         * the mass and tank lines already flooded the log.
         */
        public static final java.util.Set<String> LOGGED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        /**
         * Diagnostics: at which early exit the method bails out.
         *
         * The "power and brakes" line was NEVER printed, even though ZombieBuddy reports
         * "patching zombie.core.physics.CarController.checkTire" and there are no errors. So
         * the body runs but exits before the multipliers are applied, and thrust and brakes
         * are not applied at all, on any vehicle.
         *
         * Each reason is printed once so as not to flood the log: the method is called
         * every frame for every vehicle.
         */
        public static final java.util.Set<String> BAILED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        public static void bail(String why) {
            if (BAILED.add(why)) {
                Log.info("[LabVehiclePhysics] engine patch bailed out: " + why);
            }
        }

        /** Tells "the advice does not run at all" apart from "it runs and exits early". */
        public static volatile boolean sawFirstCall = false;

        public static void scale(Object controller, Object bv) {
            if (!sawFirstCall) {
                sawFirstCall = true;
                Log.debug("[LabVehiclePhysics] engine patch: the checkTire advice is running");
            }
            if (!LabGate.active()) {
                bail("LabGate is closed - the mod is not in the active mod list");
                return;
            }
            if (broken || controller == null || bv == null) {
                bail("broken=" + broken + ", controller=" + (controller == null ? "null" : "ok")
                        + ", bulletVariables=" + (bv == null ? "null" : "ok"));
                return;
            }
            try {
                VehicleCfg.reloadIfNeeded();
                // The global power multiplier from the sandbox applies to all vehicles, even those
                // that have no rules. It does not touch the brakes.
                float global = LabSettings.powerMul();
                boolean globalOn = Math.abs(global - 1.0f) > 0.001f;
                if (!VehicleCfg.hasTuning && !globalOn) {
                    bail("VehicleCfg.hasTuning is false - no rule carries powerMul/brakeMul/lowGear, "
                            + "and the sandbox power multiplier is 1");
                    return;
                }
                if (fVehicleObject == null) {
                    fVehicleObject = controller.getClass().getField("vehicleObject");
                    fSpeed = controller.getClass().getField("speed");
                    fEngineForce = bv.getClass().getDeclaredField("engineForce");
                    fEngineForce.setAccessible(true);
                    fBrakingForce = bv.getClass().getDeclaredField("brakingForce");
                    fBrakingForce.setAccessible(true);
                }
                Object vehicle = fVehicleObject.get(controller);
                if (vehicle == null) {
                    bail("controller.vehicleObject is null");
                    return;
                }
                if (mScriptName == null) {
                    mScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mScriptName.invoke(vehicle);
                VehicleCfg.Rule rule = VehicleCfg.forName(name);
                if (rule == null && !globalOn) {
                    bail("no rule matches script name \"" + name + "\"");
                    return;
                }
                float ruled = 1.0f;
                float brake = 1.0f;
                float boost = 1.0f;
                float speedKmh = fSpeed.getFloat(controller);
                if (rule != null) {
                    // The last moment the vanilla mass is still visible, in case the rule never
                    // reached the script. Without this, brakeMul=auto silently degenerates to 1.0.
                    VehicleCfg.noteVanillaMassFromVehicle(vehicle, name);
                    ruled = VehicleCfg.powerMultiplier(rule, name);
                    brake = VehicleCfg.multiplier(rule.brakeMul, rule, name);
                    boost = VehicleCfg.lowGearBoost(rule, speedKmh);
                }
                float power = ruled * boost * global;
                if (power == 1.0f && brake == 1.0f) {
                    bail("both multipliers resolved to 1.0 for \"" + name + "\"" + (rule == null ? ""
                            : " (powerMul=" + rule.powerMul + ", power=" + rule.powerHp + "hp, brakeMul=" + rule.brakeMul
                            + ", lowGear=" + rule.lowGear + ", low-gear boost=" + boost + ", speed=" + speedKmh + ")"));
                    return;
                }
                if (power != 1.0f) {
                    fEngineForce.setFloat(bv, fEngineForce.getFloat(bv) * power);
                }
                if (brake != 1.0f) {
                    fBrakingForce.setFloat(bv, fBrakingForce.getFloat(bv) * brake);
                }
                String stamp = name + "|" + ruled + "|" + brake + "|" + (rule != null ? rule.lowGear : 0.0f) + "|" + global;
                if (LOGGED.add(stamp)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[LabVehiclePhysics] power and brakes: ").append(name)
                      .append(" power x").append(VehicleCfg.fmt(ruled));
                    if (globalOn) {
                        sb.append(", sandbox multiplier x").append(VehicleCfg.fmt(global));
                    }
                    sb.append(", braking x").append(VehicleCfg.fmt(brake));
                    if (rule != null && rule.lowGear > 1.0f) {
                        sb.append(", low-gear boost x").append(VehicleCfg.fmt(rule.lowGear))
                          .append(" fading out at ").append(VehicleCfg.fmt(rule.lowGearTo)).append(" km/h");
                    }
                    Log.debug(sb.toString());
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the engine power patch, disabling: " + t);
            }
        }
    }
}
