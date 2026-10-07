package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * The capacity a fuel tank item reports to the fuel clamp, while a write into its tank is going on.
 *
 * {@code VehiclePart.setContainerContentAmount} clamps every write to the item's capacity, so a
 * real-size tank would be cut to the size of the vanilla item. {@link Patch_tankWrite} marks the
 * write, and only during it, only for that one item, this getter answers with the vehicle's tank
 * volume. All other callers, the tooltip and the capacity of vehicles without tank data among
 * them, get the item's own number. The full reasoning is in {@link Patch_tankWrite}.
 *
 * <h2>Why we patch the getter, not the field</h2>
 * The obvious solution, calling {@code item.setMaxCapacity()}, will not do: the field is
 * serialised ({@code InventoryItem} writes it to the save), and the raised capacity would stay
 * with the item forever, even after the mod is turned off. The save writes the field directly,
 * bypassing the getter, so the replaced return value never reaches the save.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit(): public members only, no lambdas.
 */
@Patch(className = "zombie.inventory.InventoryItem", methodName = "getMaxCapacity", warmUp = true)
public class Patch_tankItemCapacity {

    @Patch.OnExit
    public static void exit(@Patch.This Object item, @Patch.Return(readOnly = false) int ret) {
        ret = Patch_tankWrite.Impl.allowance(item, ret);
    }
}
