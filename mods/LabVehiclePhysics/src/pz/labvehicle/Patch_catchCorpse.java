package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Надёжный перехват трупа в момент создания.
 *
 * Предыдущий подход — читать поле IsoGameCharacter.diedBody на каждом шаге рэгдолла —
 * почти не работал: логи показали 0-3 попадания на 78-257 обращений. Причина в том,
 * что VirtualZombieManager возвращает объект зомби в пул и зовёт clearDiedBody(),
 * так что поле видно буквально один кадр. Высматривать его бессмысленно.
 *
 * Здесь мы перехватываем сам метод, который труп создаёт:
 * <pre>
 * private final IsoDeadBody becomeCorpse() {
 *     if (this.diedBody == null) {
 *         this.diedBody = new IsoDeadBody(this);
 *         this.invokeOnDiedListeners(this.diedBody);
 *     }
 *     return this.diedBody;
 * }
 * </pre>
 * и кладём пару «персонаж -> труп» в кэш Patch_corpseFollowsRagdoll ровно в тот момент,
 * когда труп появился. Дальше рэгдолл тащит труп за собой, даже когда игра ссылку уже
 * обнулила.
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
