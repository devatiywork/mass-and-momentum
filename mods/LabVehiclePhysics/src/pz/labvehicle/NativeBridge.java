package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * A bridge to {@link NativePatch}.
 *
 * Why it is needed. {@code NativePatch} uses the Foreign Function &amp; Memory API, which
 * appeared in Java 22, so it is compiled with {@code --release 25}. The rest of the mod is
 * compiled for 17: that is safer for ByteBuddy, which inlines our advice
 * into the game's classes.
 *
 * The two cannot be mixed directly: javac with {@code --release 17} refuses to read a version 25
 * class file from the classpath. So the only call into {@code NativePatch} goes through
 * reflection: one line instead of the hundred and fifty it would take to write the FFM code
 * entirely through reflection.
 *
 * Handling of a missing class lives here too: if for some reason {@code NativePatch} was not
 * built or the JVM is older than 22, the mod keeps working with the vanilla suspension ceiling.
 */
public final class NativeBridge {

    public static volatile boolean broken = false;
    public static Method mEnsure;

    private NativeBridge() {
    }

    public static void ensure() {
        if (broken) {
            return;
        }
        try {
            if (mEnsure == null) {
                mEnsure = Class.forName("pz.labvehicle.NativePatch").getMethod("ensure");
            }
            mEnsure.invoke(null);
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] in-memory suspension patch unavailable (" + t
                    + ") - the game keeps the vanilla 2400 kg ceiling");
        }
    }
}
