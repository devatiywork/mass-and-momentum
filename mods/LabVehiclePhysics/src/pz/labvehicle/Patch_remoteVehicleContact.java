package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Client: contact between a body and a vehicle driven by another player is checked for real, not
 * "always yes"; otherwise a ragdoll from someone else's vehicle never ends ({@link RemoteRagdoll}).
 *
 * Only {@code RagdollController.vehicleCollision} calls {@code BaseVehicle.isCollided}: contact
 * extends the body's simulation. For a local driver vanilla checks geometry anyway; left as is.
 *
 * IMPORTANT: ByteBuddy inlines exit() into the game's method: public members only, no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "isCollided", warmUp = true)
public class Patch_remoteVehicleContact {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle, @Patch.Argument(0) Object character,
                            @Patch.Return(readOnly = false) boolean ret) {
        ret = RemoteRagdoll.contact(vehicle, character, ret);
    }
}
