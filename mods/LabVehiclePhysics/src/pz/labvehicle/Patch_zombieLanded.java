package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Driver's client: a hit body has landed, the server gets the landing point ({@link CorpseSync}).
 *
 * {@code ZombieOnGroundState} is the "lying" state: a zombie enters it when the ragdoll ends
 * ({@code vehicleCollision-ragdoll/to_onground.xml}: {@code !isSimulationActive}) or the fall
 * animation does. For a dead zombie {@code enter()} immediately calls {@code die()}, so entry is
 * the last moment when the live position of the body is the position of the future corpse.
 *
 * On the server and for zombies our driver did not hit, this costs one volatile flag check.
 *
 * IMPORTANT: ByteBuddy inlines enter() into the game's method: public members only, no lambdas.
 */
@Patch(className = "zombie.ai.states.ZombieOnGroundState", methodName = "enter", warmUp = true)
public class Patch_zombieLanded {

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) Object owner) {
        if (CorpseSync.anyFlying) {
            CorpseSync.onLanded(owner);
        }
    }
}
