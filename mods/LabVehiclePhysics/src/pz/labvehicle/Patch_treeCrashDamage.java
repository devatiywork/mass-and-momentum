package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Vehicle hitting an obstacle, first half: if a tree in the way is felled by this impact,
 * cancel {@code BaseVehicle.crash()} entirely ({@link TreeBreak#beforeCrash}).
 *
 * crash() damages the vehicle by how abruptly it stops, knowing neither the mass nor that the
 * trunk broke: a tank took damage from a spruce. In multiplayer it also sends "vehicle/crash"
 * to the server, which deals the damage; skipping crash() on the driver's client cancels that too.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "crash", warmUp = true)
public class Patch_treeCrashDamage {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle) {
        return TreeBreak.beforeCrash(vehicle);
    }
}
