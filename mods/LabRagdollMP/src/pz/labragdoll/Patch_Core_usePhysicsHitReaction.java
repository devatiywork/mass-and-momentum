package pz.labragdoll;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * The second multiplayer lock on ragdolls, in the option getter itself (Core, 42.20.4):
 *
 * <pre>
 * public boolean getOptionUsePhysicsHitReaction() {
 *     return !GameClient.client &amp;&amp; !GameServer.server ? this.optionUsePhysicsHitReaction.getValue() : false;
 * }
 * </pre>
 *
 * So in multiplayer it returns false regardless of the setting, and any code that asks
 * "is the physics hit reaction on?" gets "no". We lift that: when vanilla returns false,
 * we return the REAL option value from the private field.
 *
 * The option's default in the game is true (newOption("usePhysicsHitReaction", true)),
 * so unless the user has turned it off by hand, this will be true.
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

        /** @return the real option value, bypassing the multiplayer lock. */
        public static boolean realValue(Object core) {
            if (broken || core == null) {
                return false;
            }
            // The mod is not in this game's mod list (someone else's server, say): keep vanilla.
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
