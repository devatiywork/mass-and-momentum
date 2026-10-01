package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * NaN tripwire, point 3: the client built a vehicle physics packet ({@code VehiclePhysicsPacket.set}).
 * If it holds a NaN, we log what exactly and replace it with the vehicle's last good state
 * ({@link NanGuard#packetOut}): the server applies it unchecked, and the NaN reaches the save.
 *
 * The packet class loads late, so it is listed in {@link Main#PRELOAD}.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.network.packets.vehicle.VehiclePhysicsPacket", methodName = "set", warmUp = true)
public class Patch_nanPacketOut {

    @Patch.OnExit
    public static void exit(@Patch.This Object packet) {
        NanGuard.packetOut(packet);
    }
}
