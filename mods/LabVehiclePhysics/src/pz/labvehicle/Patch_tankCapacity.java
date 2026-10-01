package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Real fuel tank volume, the {@code tank} key in the config.
 *
 * Why. We raised vehicle masses to their spec values, but every tank stayed a vanilla car
 * tank: the Bushmaster has the item {@code BigGasTank1} with {@code MaxCapacity = 59}.
 * The real one has a 319-litre tank and an 800 km range. Without this fix the result is
 * nonsense: truck consumption with a car tank, and the vehicle gets nowhere.
 *
 * Where the capacity comes from:
 * <pre>
 * // VehiclePart.getContainerCapacity(IsoGameCharacter)
 * return conditionAffectsCapacity
 *      ? (int) getNumberByCondition(item.getMaxCapacity(), getCondition(), 5.0F)
 *      : item.getMaxCapacity();
 * </pre>
 * We could call {@code item.setMaxCapacity(319)}, but that is a property of the item,
 * and it would go into the save for good: the tank would stay big even after the mod is off.
 * So we replace the return value without writing anything.
 *
 * The method is overloaded (with and without a character), so the advice does not touch the
 * arguments: only {@code @Patch.This} and the return value. That way both overloads are patched
 * safely: the outer one simply delegates to the inner one and gets the same number.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit(): public members only, no lambdas.
 */
@Patch(className = "zombie.vehicles.VehiclePart", methodName = "getContainerCapacity", warmUp = true)
public class Patch_tankCapacity {

    @Patch.OnExit
    public static void exit(@Patch.This Object part, @Patch.Return(readOnly = false) int ret) {
        ret = Impl.adjust(part, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mGetId;
        public static Method mGetVehicle;
        public static Method mGetScriptName;
        public static Method mGetCondition;
        public static final java.util.Set<String> LOGGED = new java.util.HashSet<String>();

        public static int adjust(Object part, int vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || part == null || !VehicleCfg.hasTank) {
                return vanilla;
            }
            try {
                if (mGetId == null) {
                    mGetId = part.getClass().getMethod("getId");
                    mGetVehicle = part.getClass().getMethod("getVehicle");
                    mGetCondition = part.getClass().getMethod("getCondition");
                }
                Object id = mGetId.invoke(part);
                if (!"GasTank".equals(id)) {
                    return vanilla;
                }
                Object vehicle = mGetVehicle.invoke(part);
                if (vehicle == null) {
                    return vanilla;
                }
                if (mGetScriptName == null) {
                    mGetScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mGetScriptName.invoke(vehicle);
                VehicleCfg.Rule rule = VehicleCfg.forName(name);
                if (rule == null || rule.tank <= 0.0f) {
                    return vanilla;
                }
                // Vanilla reduces the capacity by part condition; we keep that behaviour.
                int cond = ((Integer) mGetCondition.invoke(part)).intValue();
                int result = Math.round(rule.tank * Math.max(cond, 5) / 100.0f);
                // Per vehicle, not by the last one seen: otherwise two vehicles side by side
                // take turns and write to the log every frame.
                if (LOGGED.add(name)) {
                    Log.debug("[LabVehiclePhysics] fuel tank: " + name + " " + vanilla
                            + " -> " + result + " L (spec " + VehicleCfg.fmt(rule.tank)
                            + ", part condition " + cond + "%)");
                }
                return result;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the fuel tank patch, disabling: " + t);
                return vanilla;
            }
        }
    }
}
