package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Bushes, part 1: remove the vanilla speed cap and deduct energy for new objects in the contact
 * list; see {@link VegetationDrag}.
 *
 * On exit from {@code BaseVehicle.breakingObjects()} the {@code breakingSlowFactor} field is
 * already computed; we zero it, and {@code updateVelocityMultiplier} keeps only the game's general
 * speed limit. Objects with the CarSlowFactor property that the vehicle is currently in contact
 * with sit in {@code breakingObjectsList}; each one costs energy once per contact.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "breakingObjects", warmUp = true)
public class Patch_vegetationCap {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        VegetationDrag.afterBreakingObjects(vehicle);
    }
}
