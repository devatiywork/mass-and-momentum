package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * NaN tripwire, point 2 for the player: after every update we remember the last finite
 * position, and once it turns NaN we restore it ({@link NanGuard#player}). Otherwise the NaN goes
 * into players.db, and on the next login the player ends up at (0,0), off the edge of the map.
 *
 * IsoPlayer has a single update() with no arguments; the advice does not touch arguments, so
 * even if an overload appears, the by-name patch will not harm it.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class Patch_nanPlayer {

    @Patch.OnExit
    public static void exit(@Patch.This Object player) {
        NanGuard.player(player);
    }
}
