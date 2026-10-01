package pz.labragdoll;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * The third multiplayer lock on ragdolls: the corpse pose.
 *
 * Vanilla, IsoGameCharacter.canUseCurrentPoseForCorpse:
 * <pre>
 * if (GameClient.client || GameServer.server) return false;   // ← we lift only this
 * else if (isSceneCulled())                   return false;
 * else if (!hasActiveModel())                 return false;
 * else {
 *     AnimationPlayer ap = getAnimationPlayer();
 *     if (ap == null) return false;
 *     return isRagdollSimulationActive() ? true : !ap.isBoneTransformsNeedFirstFrame();
 * }
 * </pre>
 *
 * The result is used when the corpse (IsoDeadBody) is created:
 * <pre>
 * if (this.ragdollFall &amp;&amp; died.canUseCurrentPoseForCorpse()) {
 *     ... copy the transforms of all bones: the corpse keeps the pose it fell in
 * }
 * </pre>
 *
 * So in multiplayer the corpse ALWAYS gets the standard pose (on its back), even if the body
 * has just flown off spectacularly as a ragdoll and landed any which way. Hence a visible swap:
 * you saw one pose, and once it turned into a corpse it was a different one.
 *
 * As in the other cases, we do not force true but re-evaluate the vanilla conditions,
 * skipping only the first branch.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "canUseCurrentPoseForCorpse", warmUp = true)
public class Patch_corpsePose {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret) {
        if (!ret) {
            ret = Impl.recheck(self);
        }
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mSceneCulled, mHasModel, mGetAnimPlayer, mRagdollActive, mNeedsFirstFrame;
        public static long allowed = 0L;
        public static boolean logged = false;

        public static boolean recheck(Object chr) {
            if (broken || chr == null) {
                return false;
            }
            // The mod is not in this game's mod list (someone else's server, say): keep vanilla.
            if (!LabGate.active()) {
                return false;
            }
            try {
                if (mSceneCulled == null) {
                    init(chr.getClass());
                }
                if (((Boolean) mSceneCulled.invoke(chr)).booleanValue()) {
                    return false;
                }
                if (!((Boolean) mHasModel.invoke(chr)).booleanValue()) {
                    return false;
                }
                Object ap = mGetAnimPlayer.invoke(chr);
                if (ap == null) {
                    return false;
                }
                boolean ok;
                if (((Boolean) mRagdollActive.invoke(chr)).booleanValue()) {
                    ok = true;
                } else {
                    if (mNeedsFirstFrame == null) {
                        mNeedsFirstFrame = ap.getClass().getMethod("isBoneTransformsNeedFirstFrame");
                    }
                    ok = !((Boolean) mNeedsFirstFrame.invoke(ap)).booleanValue();
                }
                if (ok) {
                    allowed++;
                    if (!logged) {
                        logged = true;
                        Log.info("[LabRagdollMP] corpse pose: lock removed, the body will keep its ragdoll pose");
                    }
                }
                return ok;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabRagdollMP] ERROR in the corpse pose patch, disabling: " + t);
                return false;
            }
        }

        private static synchronized void init(Class<?> cc) throws Exception {
            if (mSceneCulled != null) {
                return;
            }
            mSceneCulled = cc.getMethod("isSceneCulled");
            mHasModel = cc.getMethod("hasActiveModel");
            mGetAnimPlayer = cc.getMethod("getAnimationPlayer");
            mRagdollActive = cc.getMethod("isRagdollSimulationActive");
        }
    }
}
