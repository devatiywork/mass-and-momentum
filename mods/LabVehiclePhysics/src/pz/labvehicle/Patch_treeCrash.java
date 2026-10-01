package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Vehicle hitting an obstacle, second half: carry out the tree felling decided in
 * {@link Patch_treeCrashDamage} ({@link TreeBreak#afterDamageObjects}).
 *
 * The game calls {@code BaseVehicle.damageObjects(float)} right after {@code crash()}, in the same
 * impact-handling block. If we cancelled crash() for the sake of felling, damageObjects still
 * runs, and this is where the tree comes down.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "damageObjects", warmUp = true)
public class Patch_treeCrash {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        TreeBreak.afterDamageObjects(vehicle);
    }
}
