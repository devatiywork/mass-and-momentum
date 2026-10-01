package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * NaN tripwire, point 1: who wrote a NaN into coordinate Y. Twin of {@link Patch_nanWriteX}.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
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
