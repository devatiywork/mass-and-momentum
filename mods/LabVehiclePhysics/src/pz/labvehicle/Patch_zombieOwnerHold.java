package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Server: while a zombie hit by a vehicle is in flight, its owner is the driver, and the owner
 * must not change ({@link CorpseSync}).
 *
 * {@code NetworkZombieManager.updateAuth} reviews the owner of every zombie on every server frame:
 * once every 2 seconds it hands the zombie to the player it is chasing, or to the nearest one. The
 * body flies longer than that, so a player with no ragdoll would become the owner, and the server
 * would stop taking the driver's messages ({@code NetworkZombiePacker.parseZombie}: owner only).
 *
 * While nothing is pending, this costs one volatile flag check per zombie per frame.
 *
 * IMPORTANT: ByteBuddy inlines enter() into the game's method: public members only, no lambdas.
 */
@Patch(className = "zombie.popman.NetworkZombieManager", methodName = "updateAuth", warmUp = true)
public class Patch_zombieOwnerHold {

    /** @return true = skip the owner review. */
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) Object zombie) {
        return CorpseSync.anyPending && CorpseSync.holdOwner(zombie);
    }
}
