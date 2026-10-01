package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Bushes, part 2: instead of a vanilla impulse every frame, a single energy deduction per
 * contact; see {@link VegetationDrag}.
 *
 * Vanilla calls {@code applyImpulseFromHitPlant(obj, 0.025 or 0.1)} from
 * {@code checkCollisionWithPlant} every frame while the vehicle rubs against a bush or young tree.
 * We skip all those calls; on the first touch VegetationDrag calls the same method itself with
 * the computed amount, and that call is marked {@code VegetationDrag.ownCall} and goes through.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitPlant", warmUp = true)
public class Patch_plantImpulse {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle, @Patch.Argument(0) Object obj) {
        return VegetationDrag.onPlantImpulse(vehicle, obj);
    }
}
