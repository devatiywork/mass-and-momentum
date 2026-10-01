package pz.labragdoll;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Третий мультиплеерный замок рэгдолла: поза трупа.
 *
 * Ваниль, IsoGameCharacter.canUseCurrentPoseForCorpse:
 * <pre>
 * if (GameClient.client || GameServer.server) return false;   // ← снимаем только это
 * else if (isSceneCulled())                   return false;
 * else if (!hasActiveModel())                 return false;
 * else {
 *     AnimationPlayer ap = getAnimationPlayer();
 *     if (ap == null) return false;
 *     return isRagdollSimulationActive() ? true : !ap.isBoneTransformsNeedFirstFrame();
 * }
 * </pre>
 *
 * Результат используется при создании трупа (IsoDeadBody):
 * <pre>
 * if (this.ragdollFall &amp;&amp; died.canUseCurrentPoseForCorpse()) {
 *     ... копируем трансформации всех костей — труп сохраняет позу, в которой упал
 * }
 * </pre>
 *
 * То есть в мультиплеере труп ВСЕГДА получает стандартную позу (на спине), даже если
 * тело только что эффектно улетело рэгдоллом и легло как попало. Отсюда заметная подмена:
 * видел одну позу — после превращения в труп стала другая.
 *
 * Как и в остальных случаях, не форсируем true, а пересчитываем ванильные условия,
 * пропуская только первую ветку.
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
            // Мода нет в списке этой игры — например, чужой сервер: оставляем ваниль.
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
