package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Растяжка на NaN, точка 1: кто записал не-число в координату Y. Пара к {@link Patch_nanWriteX}.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "setY")
public class Patch_nanWriteY {

    @Patch.OnEnter
    public static void enter(@Patch.This Object self, @Patch.Argument(0) float value) {
        if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
            NanGuard.badWrite(self, "setY", value);
        }
    }
}
