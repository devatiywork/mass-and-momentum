package pz.labragdoll;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Co-op server with ZombieBuddy, patch 1 of 2: which server is being launched. By its name
 * {@link CoopServerAgent} finds its ini and checks whether the mod is listed there.
 *
 * Both launchServer overloads match the name: the public one and the private one, which
 * softreset also goes through; both take the server name as the first argument.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.network.CoopMaster", methodName = "launchServer")
public class Patch_coopServerName {

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String serverName) {
        CoopServerAgent.launching(serverName);
    }
}
