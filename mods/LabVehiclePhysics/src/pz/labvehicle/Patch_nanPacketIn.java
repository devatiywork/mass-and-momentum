package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * NaN tripwire, point 4: the server has received a vehicle physics packet. processServer writes
 * its x, y, z, rotation and velocity into the vehicle without a single check; a packet with a NaN
 * is not applied at all ({@link NanGuard#packetIn}), so the vehicle stays in its last good state.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.network.packets.vehicle.VehiclePhysicsPacket", methodName = "processServer", warmUp = true)
public class Patch_nanPacketIn {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object packet) {
        return NanGuard.packetIn(packet);
    }
}
