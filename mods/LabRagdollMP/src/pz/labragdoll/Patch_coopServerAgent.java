package pz.labragdoll;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Co-op server with ZombieBuddy, patch 2 of 2: in place of the garbage collector flag, an
 * {@code @file} with the ZombieBuddy agent (see {@link CoopServerAgent}). It is the only argument
 * of the server launch command that can be replaced without rewriting the launch itself.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.network.CoopMaster", methodName = "getGarbageCollector")
public class Patch_coopServerAgent {

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String ret) {
        ret = CoopServerAgent.argument(ret);
    }
}
