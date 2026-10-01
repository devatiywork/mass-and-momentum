package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Lifts the fuel clamp imposed by the capacity of the gas tank item.
 *
 * <h2>Why</h2>
 * We raise the tank volume through {@link Patch_tankCapacity} by replacing the return value of
 * {@code VehiclePart.getContainerCapacity()}. But on write the fuel amount gets clamped by a
 * different value, the capacity of the ITEM itself:
 * <pre>
 * // VehiclePart.setContainerContentAmount(amount, force, noUpdateMass)
 * int cap = this.scriptPart.container.capacity;
 * if (this.getInventoryItem() != null) {
 *     cap = this.getInventoryItem().getMaxCapacity();   // bypasses our patch
 * }
 * if (!force) {
 *     amount = Math.min(amount, cap);
 * }
 * </pre>
 *
 * In the service we call the overload with {@code force = true} and so avoid the clamp. But the
 * client has a path where {@code force} is out of our control, receiving data over the network:
 * <pre>
 * // VehiclePartModData
 * part.getModData().load(bb.bb, 249);
 * if (part.isContainer()) {
 *     part.setContainerContentAmount(part.getContainerContentAmount());   // force = false
 * }
 * </pre>
 * So the client receives the true 659 litres from the server and at once clamps them itself
 * to the capacity of its own item. A military tank gets 33 litres: practically empty.
 *
 * <h2>Why we patch the getter, not the field</h2>
 * The obvious solution, calling {@code item.setMaxCapacity()}, will not do: the field is
 * serialised ({@code InventoryItem} writes it to the save), and the raised capacity would stay
 * with the item forever, even after the mod is turned off. The save writes the field directly,
 * bypassing the getter, so the replaced return value never reaches the save.
 *
 * <h2>Why this is safe</h2>
 * We only make the clamp PERMISSIVE; we do not set the real capacity. The real one stays with
 * {@link Patch_tankCapacity}, and the vanilla overflow check uses that same value:
 * <pre>
 * // BaseVehicle.update()
 * if (gasTank.getContainerContentAmount() > gasTank.getContainerCapacity()) {
 *     gasTank.setContainerContentAmount(gasTank.getContainerCapacity());
 * }
 * </pre>
 * So no excess fuel ends up in the tank; the fuel that belongs there simply stops being cut off.
 *
 * We touch only gas tank items, and only when the config has rules with a tank volume.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit(): public members only, no lambdas.
 */
@Patch(className = "zombie.inventory.InventoryItem", methodName = "getMaxCapacity", warmUp = true)
public class Patch_tankItemCapacity {

    @Patch.OnExit
    public static void exit(@Patch.This Object item, @Patch.Return(readOnly = false) int ret) {
        ret = Impl.adjust(item, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mGetType;
        public static boolean logged = false;

        public static int adjust(Object item, int vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || item == null || !VehicleCfg.hasTank) {
                return vanilla;
            }
            int allow = VehicleCfg.largestTank();
            if (allow <= vanilla) {
                return vanilla;
            }
            try {
                if (mGetType == null) {
                    mGetType = item.getClass().getMethod("getType");
                }
                Object type = mGetType.invoke(item);
                if (!(type instanceof String) || ((String) type).indexOf("GasTank") < 0) {
                    return vanilla;
                }
                if (!logged) {
                    logged = true;
                    Log.debug("[LabVehiclePhysics] fuel tank items: clamp raised from "
                            + vanilla + " to " + allow
                            + " so that received fuel is not truncated (real capacity is still per-vehicle)");
                }
                return allow;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the tank item patch, disabling: " + t);
                return vanilla;
            }
        }
    }
}
