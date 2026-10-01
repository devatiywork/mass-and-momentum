package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Client: while our own ragdoll of a hit zombie runs, its owner's messages are not applied to the
 * body; otherwise it jerks between its own flight and the owner's ({@link RemoteRagdoll}).
 *
 * {@code NetworkZombieAI.parse} handles the owner's message on a non-owning client: a path to the
 * owner's position, rotation, a teleport past 3 squares. Not called on the owner or the server.
 *
 * IMPORTANT: ByteBuddy inlines enter() into the game's method: public members only, no lambdas.
 */
@Patch(className = "zombie.characters.NetworkZombieAI", methodName = "parse", warmUp = true)
public class Patch_remoteRagdollUpdate {

    /** @return true = skip the owner's message. */
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object networkAi, @Patch.Argument(0) Object packet) {
        return RemoteRagdoll.skipOwnerUpdate(networkAi, packet);
    }
}
