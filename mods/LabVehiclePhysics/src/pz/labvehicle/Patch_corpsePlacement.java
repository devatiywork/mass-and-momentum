package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Client: measures how far the body is moved when the corpse arrives from the server
 * ({@link RemoteRagdoll#onCorpsePacket}). Vanilla does not log this: its "Corpse … teleport"
 * line is debug-only and never shows up in the normal log.
 *
 * {@code DeadCharacterPacket.processClient} puts the body at the server corpse's coordinates if the
 * square differs, and creates the corpse. Here we only measure and change nothing.
 *
 * IMPORTANT: ByteBuddy inlines enter() into the game's method: public members only, no lambdas.
 */
@Patch(className = "zombie.network.packets.character.DeadCharacterPacket", methodName = "processClient", warmUp = true)
public class Patch_corpsePlacement {

    @Patch.OnEnter
    public static void enter(@Patch.This Object packet) {
        RemoteRagdoll.onCorpsePacket(packet);
    }
}
