package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Reliable capture of the corpse at the moment it is created.
 *
 * The previous approach, reading the IsoGameCharacter.diedBody field on every ragdoll step,
 * barely worked: the logs showed 0-3 hits per 78-257 reads. The reason is that
 * VirtualZombieManager returns the zombie object to the pool and calls clearDiedBody(),
 * so the field is visible for literally one frame. Watching for it is pointless.
 *
 * Here we intercept the very method that creates the corpse:
 * <pre>
 * private final IsoDeadBody becomeCorpse() {
 *     if (this.diedBody == null) {
 *         this.diedBody = new IsoDeadBody(this);
 *         this.invokeOnDiedListeners(this.diedBody);
 *     }
 *     return this.diedBody;
 * }
 * </pre>
 * and put the "character -> corpse" pair into the Patch_corpseFollowsRagdoll cache at the exact
 * moment the corpse appears. From then on the ragdoll drags the corpse along, even after the
 * game has nulled the reference.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "becomeCorpse", warmUp = true)
public class Patch_catchCorpse {

    @Patch.OnExit
    public static void exit(@Patch.This Object chr, @Patch.Return(readOnly = true) Object corpse) {
        Impl.remember(chr, corpse);
    }

    public static final class Impl {
        public static long caught = 0L;
        public static boolean logged = false;

        public static void remember(Object chr, Object corpse) {
            if (!LabGate.active() || !LabSettings.corpseFollows()) {
                return;
            }
            if (chr == null || corpse == null) {
                return;
            }
            try {
                synchronized (Patch_corpseFollowsRagdoll.Impl.CORPSE_CACHE) {
                    Patch_corpseFollowsRagdoll.Impl.CORPSE_CACHE.put(chr, corpse);
                    Patch_corpseFollowsRagdoll.Impl.CORPSE_SINCE.put(chr, Long.valueOf(System.nanoTime()));
                }
                caught++;
                if (!logged) {
                    logged = true;
                    Log.debug("[LabVehiclePhysics] corpse capture works: hooking becomeCorpse() "
                            + "instead of watching the field");
                }
            } catch (Throwable t) {
                Log.info("[LabVehiclePhysics] failed to capture the corpse: " + t);
            }
        }
    }
}
