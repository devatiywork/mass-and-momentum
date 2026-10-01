package pz.labragdoll;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Второй мультиплеерный замок рэгдолла — в самом геттере опции (Core, 42.20.4):
 *
 * <pre>
 * public boolean getOptionUsePhysicsHitReaction() {
 *     return !GameClient.client &amp;&amp; !GameServer.server ? this.optionUsePhysicsHitReaction.getValue() : false;
 * }
 * </pre>
 *
 * То есть в мультиплеере он возвращает false независимо от настройки, и любой код,
 * который спрашивает «включена ли физическая реакция», получает «нет». Снимаем:
 * при ванильном false отдаём НАСТОЯЩЕЕ значение опции из приватного поля.
 *
 * Значение опции по умолчанию в игре — true (newOption("usePhysicsHitReaction", true)),
 * так что если пользователь её не выключал руками, здесь будет true.
 */
@Patch(className = "zombie.core.Core", methodName = "getOptionUsePhysicsHitReaction", warmUp = true)
public class Patch_Core_usePhysicsHitReaction {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret) {
        if (!ret) {
            ret = Impl.realValue(self);
        }
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static volatile boolean logged = false;
        public static Field optionField;
        public static Method getValue;
        public static Object cachedOption;

        /** @return настоящее значение опции, минуя мультиплеерный замок. */
        public static boolean realValue(Object core) {
            if (broken || core == null) {
                return false;
            }
            // Мода нет в списке этой игры — например, чужой сервер: оставляем ваниль.
            if (!LabGate.active()) {
                return false;
            }
            try {
                if (optionField == null) {
                    Field f = core.getClass().getDeclaredField("optionUsePhysicsHitReaction");
                    f.setAccessible(true);
                    optionField = f;
                }
                Object opt = cachedOption;
                if (opt == null) {
                    opt = optionField.get(core);
                    if (opt == null) {
                        return false;
                    }
                    cachedOption = opt;
                    getValue = opt.getClass().getMethod("getValue");
                }
                boolean v = ((Boolean) getValue.invoke(opt)).booleanValue();
                if (!logged) {
                    logged = true;
                    Log.info("[LabRagdollMP] option lock removed, real usePhysicsHitReaction value = " + v);
                }
                return v;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabRagdollMP] ERROR in the option patch, disabling: " + t);
                return false;
            }
        }
    }
}
