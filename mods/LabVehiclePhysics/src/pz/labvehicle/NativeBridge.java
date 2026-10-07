package pz.labvehicle;

import java.lang.reflect.Field;
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
 * built or the JVM is older than 22, the mod keeps working with the vanilla suspension ceiling,
 * and {@link SuspensionCap} caps the masses to it.
 */
public final class NativeBridge {

    /** Lab switch: leave the limit in place on Windows too, to try the fallback there. */
    public static final boolean SKIP = Boolean.getBoolean("labvehicle.noNativePatch");

    public static volatile boolean broken = false;
    /** Whether the limit was lifted: null until the first attempt, then true or false. */
    public static volatile Boolean lifted = null;
    public static Method mEnsure;
    public static Field fDone, fFailed;

    private NativeBridge() {
    }

    public static void ensure() {
        if (broken) {
            return;
        }
        if (SKIP) {
            if (lifted == null) {
                lifted = Boolean.FALSE;
                Log.info("[LabVehiclePhysics] in-memory suspension patch skipped (-Dlabvehicle.noNativePatch=true)");
            }
            return;
        }
        try {
            if (mEnsure == null) {
                Class<?> patch = Class.forName("pz.labvehicle.NativePatch");
                mEnsure = patch.getMethod("ensure");
                fDone = patch.getField("done");
                fFailed = patch.getField("failed");
            }
            mEnsure.invoke(null);
            if (fFailed.getBoolean(null)) {
                lifted = Boolean.FALSE;
            } else if (fDone.getBoolean(null)) {
                lifted = Boolean.TRUE;
            }
        } catch (Throwable t) {
            broken = true;
            lifted = Boolean.FALSE;
            Log.info("[LabVehiclePhysics] in-memory suspension patch unavailable (" + t
                    + ") - the game keeps the vanilla 2400 kg ceiling");
        }
    }
}
