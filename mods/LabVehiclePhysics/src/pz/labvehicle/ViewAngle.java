package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Where a 3D view looks, read from the game's own look angle; used by
 * LabVehiclePhysics_TurretSight.lua through {@link LabVehiclePhysicsNet}.
 *
 * The game derives a character's look angle from the animation (IsoGameCharacter.getLookAngleRadians):
 * the body's angle plus the upper-body twist, plus the head turn while the head looks around, or the
 * facing while the animation is not ready. Viewpoint patches that method so that the vision cone and
 * the line of sight follow its camera in first and third person. So when the method's answer differs
 * from the game's own formula, a 3D view is on, and the answer is where it looks. While the player is
 * seated, Viewpoint 0.1.5a leaves the angle to the game (measured in free look, 03.10.2026), so in a
 * vehicle this starts to work only if a later version answers there too.
 *
 * Only the game's classes are read here. Angles are in degrees, in the measure of getDirectionAngle().
 */
public final class ViewAngle {

    private ViewAngle() {
    }

    public static volatile boolean broken = false;
    public static Field fAnimPlayer;
    public static Method mLook, mDirection, mHeadLook, mHeadHorizontal, mReady, mAngle, mTwist;

    /** The look angle as the game's method answers it now; NaN on failure. */
    public static double look(Object character) {
        if (broken || character == null) {
            return Double.NaN;
        }
        try {
            if (mLook == null) {
                init(character);
            }
            return Math.toDegrees(((Float) mLook.invoke(character)).floatValue());
        } catch (Throwable t) {
            fail(t);
            return Double.NaN;
        }
    }

    /** The look angle by the game's own formula, in the same float steps; NaN on failure. */
    public static double game(Object character) {
        if (broken || character == null) {
            return Double.NaN;
        }
        try {
            if (mLook == null) {
                init(character);
            }
            Object player = fAnimPlayer.get(character);
            float angle;
            if (player == null || !((Boolean) mReady.invoke(player)).booleanValue()) {
                angle = ((Float) mDirection.invoke(character)).floatValue();
            } else {
                angle = ((Float) mAngle.invoke(player)).floatValue() + ((Float) mTwist.invoke(player)).floatValue();
                if (((Boolean) mHeadLook.invoke(character)).booleanValue()) {
                    angle += ((Float) mHeadHorizontal.invoke(character)).floatValue();
                }
            }
            return Math.toDegrees(angle);
        } catch (Throwable t) {
            fail(t);
            return Double.NaN;
        }
    }

    private static synchronized void init(Object character) throws Exception {
        if (mLook != null) {
            return;
        }
        ClassLoader cl = character.getClass().getClassLoader();
        Class<?> chr = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
        Class<?> anim = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
        Field f = chr.getDeclaredField("animPlayer");
        f.setAccessible(true);
        fAnimPlayer = f;
        mDirection = chr.getMethod("getDirectionAngleRadians");
        mHeadLook = chr.getMethod("isHeadLookAround");
        mHeadHorizontal = chr.getMethod("getHeadLookHorizontal");
        mReady = anim.getMethod("isReady");
        mAngle = anim.getMethod("getAngle");
        mTwist = anim.getMethod("getTwistAngle");
        mLook = chr.getMethod("getLookAngleRadians");
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR reading the look angle, the turret sight loses free look: " + t);
        }
    }
}
