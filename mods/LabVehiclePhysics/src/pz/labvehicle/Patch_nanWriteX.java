package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * NaN tripwire, point 1: who wrote a NaN into coordinate X ({@link NanGuard#badWrite}).
 *
 * setX is not overridden by vehicles, characters or zombies, so the patch on
 * IsoMovingObject sees every write through the setter, including
 * {@code VehiclePhysicsPacket.processServer}, which writes the packet's coordinates into the
 * vehicle. setX is called thousands of times per frame: the fast path is only a number
 * comparison, and the safety gate is checked later, in badWrite.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "setX")
public class Patch_nanWriteX {

    @Patch.OnEnter
    public static void enter(@Patch.This Object self, @Patch.Argument(0) float value) {
        if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
            NanGuard.badWrite(self, "setX", value);
        }
    }
}
