package pz.labvehicle;

import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Option 2: postpone turning into a corpse while the body is pinned to the vehicle.
 *
 * Why the previous attempts did not work. In multiplayer the corpse is created by the SERVER:
 * <pre>
 * public final void die() {
 *     if (GameClient.client) this.getNetworkCharacterAI().onDied();  // client only receives a packet
 *     else                   this.becomeCorpse();                    // the corpse is born here
 * }
 * </pre>
 * Meanwhile the ragdoll lives only on the client: the server knows nothing of the body's flight
 * and puts the corpse where the zombie died, i.e. at the point of impact.
 *
 * Here we come in not from the visual side but from the server side: we touch neither damage
 * nor death, but POSTPONE the corpse's creation while the zombie is in contact with the vehicle.
 * Once contact ends, die() goes through as usual, and the corpse is born at the position where
 * the zombie has been dragged by then. For this the server needs no knowledge of the ragdoll.
 *
 * Safeguards: the deferral lasts no longer than MAX_DEFER_SEC (a body cannot be left in limbo
 * forever), plus a short GRACE_SEC pause after contact is lost, so the corpse is not created
 * at the moment the body is still peeling off the bumper.
 *
 * For zombies this deferral never actually kicks in (backlog.md §1a). The same entry point,
 * however, is used by the throw of a hit animal: while it slides, die() is skipped, and the corpse
 * is born at the end of its path ({@link AnimalThrow#holdsDeath}, no longer than 3 s).
 *
 * Since 28.09.2026 {@link CorpseSync} also defers death through the same entry point: on the
 * server, a zombie hit by a vehicle waits for the landing point from the driver.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "die", warmUp = true)
public class Patch_deferCorpse {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object chr) {
        return Impl.shouldDefer(chr);
    }

    public static final class Impl {
        /** Maximum total deferral. */
        public static final float MAX_DEFER_SEC = 4.0f;
        /** How long to wait after contact is lost before letting go. */
        public static final float GRACE_SEC = 0.35f;

        public static volatile boolean broken = false;
        public static java.lang.reflect.Method mVehicleCollision;
        /** character -> {when the deferral started, when contact was last seen} */
        public static final Map<Object, long[]> STATE = new WeakHashMap<Object, long[]>();

        public static long deferred = 0L;
        public static long released = 0L;
        public static double maxHeldSec = 0.0;
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        /** @return true = defer death (skip die() in this call). */
        public static boolean shouldDefer(Object chr) {
            if (!LabGate.active()) {
                return false;
            }
            if (broken || chr == null) {
                return false;
            }
            // A hit animal is still flying: let the corpse be born where the body stops.
            // This path is separate from the zombie one; see AnimalThrow.
            if (AnimalThrow.holdsDeath(chr)) {
                return true;
            }
            if (!LabSettings.corpseFollows()) {
                return false;
            }
            // Multiplayer server: the driver still sees the hit zombie flying; the corpse is born
            // where the body lands, once the driver sends that point (CorpseSync). Option 2 below
            // failed here: the server sets the "flying" mark itself, at impact, not a per-frame flag.
            if (CorpseSync.deferDeath(chr)) {
                return true;
            }
            try {
                if (mVehicleCollision == null) {
                    mVehicleCollision = chr.getClass().getMethod("isVehicleCollision");
                    Log.debug("[LabVehiclePhysics] corpse deferral on: while the body is pinned to the vehicle, "
                            + "die() is skipped (at most " + MAX_DEFER_SEC + " s)");
                }
                boolean inContact = ((Boolean) mVehicleCollision.invoke(chr)).booleanValue();
                long now = System.nanoTime();
                long[] st;
                synchronized (STATE) {
                    st = STATE.get(chr);
                    if (st == null) {
                        if (!inContact) {
                            return false;      // an ordinary death, no vehicle involved
                        }
                        st = new long[]{now, now};
                        STATE.put(chr, st);
                    }
                }
                if (inContact) {
                    st[1] = now;
                }
                double heldSec = (now - st[0]) / 1.0e9;
                double sinceContactSec = (now - st[1]) / 1.0e9;

                if (heldSec > MAX_DEFER_SEC || sinceContactSec > GRACE_SEC) {
                    synchronized (STATE) {
                        STATE.remove(chr);
                    }
                    released++;
                    if (heldSec > maxHeldSec) {
                        maxHeldSec = heldSec;
                    }
                    report();
                    return false;              // let go: the corpse is created at the current spot
                }
                deferred++;
                report();
                return true;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in corpse deferral, disabling: " + t);
                return false;
            }
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] death deferred for the first time - the body is pinned to the vehicle");
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
            if (deferred == 0L && released == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] corpse deferral, last 15 s: calls deferred %d, bodies released %d, "
                    + "longest deferral %.2f s",
                    deferred, released, maxHeldSec));
            deferred = 0L;
            released = 0L;
            maxHeldSec = 0.0;
        }
    }
}
