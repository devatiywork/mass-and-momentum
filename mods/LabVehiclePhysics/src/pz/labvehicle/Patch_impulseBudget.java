package pz.labvehicle;

import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.6: one zombie, one impulse.
 *
 * Vanilla calls BaseVehicle.applyImpulseFromHitPedestrian EVERY FRAME while the contact lasts
 * (and VehiclePedestrianContactTracking keeps a contact for up to 3.5 seconds), and each time
 * deducts the full impulse m·v·0.8 from the vehicle. Physically that is wrong: a body takes
 * momentum exactly once, while it is being accelerated to the vehicle's speed. Then there is
 * nothing left to take. Hence the "ran into one zombie and stopped dead" effect.
 *
 * We give each character a BUDGET per contact episode and stop deducting once it is spent.
 *
 * Why the budget is measured in time. The game applies the accumulated impulse as a force with
 * a multiplier of 30 over one 10 ms Bullet step, so one application delivers 30 * 0.01 = 0.3 of
 * the due impulse. The full impulse therefore accumulates in about 1/0.3 = 3.33 frames of
 * 1/30 s, i.e. in 0.111 seconds of contact. That is the budget; it is in seconds, so it does
 * not depend on FPS: at 240 FPS it simply spreads over more frames with a smaller share from
 * each (the share is normalised by the mass patch).
 *
 * An episode counts as over once the character has not touched the vehicle for longer than
 * RESET_SEC; then the budget is granted anew (the vehicle backed off and hit again).
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitPedestrian", warmUp = true)
public class Patch_impulseBudget {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) Object chr) {
        return Impl.shouldSkip(chr);
    }

    public static final class Impl {
        /** Seconds of contact paid for with impulse. 0.111 ≈ one physically correct hit. */
        public static final float BUDGET_SEC = 0.111f;
        /** A gap in contact after which the episode counts as a new one. */
        public static final float RESET_SEC = 0.4f;

        public static volatile boolean broken = false;
        /** character -> {seconds spent, time of the last touch in ns} */
        public static final Map<Object, float[]> SPENT = new WeakHashMap<Object, float[]>();
        public static final Map<Object, Long> LAST = new WeakHashMap<Object, Long>();

        public static long allowed = 0L;
        public static long blocked = 0L;
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        /** @return true = skip the vanilla impulse deduction. */
        public static boolean shouldSkip(Object chr) {
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return false;
            }
            if (broken || chr == null) {
                return false;
            }
            try {
                long now = System.nanoTime();
                float[] spent;
                Long last;
                synchronized (SPENT) {
                    spent = SPENT.get(chr);
                    last = LAST.get(chr);
                    if (spent == null) {
                        spent = new float[1];
                        SPENT.put(chr, spent);
                    }
                    LAST.put(chr, Long.valueOf(now));
                }
                if (last != null && (now - last.longValue()) > (long) (RESET_SEC * 1_000_000_000L)) {
                    spent[0] = 0.0f;   // contact was broken: a new episode, a fresh budget
                }
                if (spent[0] >= BUDGET_SEC) {
                    blocked++;
                    report();
                    return true;       // budget spent: the body is up to speed, nothing to take
                }
                spent[0] += Patch_getMass.Impl.frameFactor() / Patch_getMass.Impl.REFERENCE_HZ;
                allowed++;
                report();
                return false;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the impulse budget patch, disabling: " + t);
                return false;
            }
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] impulse budget on: one character pushes the vehicle for "
                        + "for " + BUDGET_SEC + "s of contact, after that nothing is applied");
            }
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long total = allowed + blocked;
            if (total == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] zombie impulses, last 15 s: applied %d, skipped %d (%d%% of redundant hits removed)",
                    allowed, blocked, blocked * 100 / total));
            allowed = 0L;
            blocked = 0L;
        }
    }
}
