package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.8: the corpse rides along with the vehicle instead of teleporting to the impact point.
 *
 * What happens in vanilla. A zombie hit at high speed dies from the first impact
 * (health 1.8-2.1, run-over damage up to 10: death on first contact is by design), but its
 * ragdoll keeps simulating and the body is carried along on the hood. Meanwhile the character's
 * LOGICAL position does not follow the ragdoll: for a living character the animation system pulls
 * it along (AnimationPlayer: deferredMovement = (ragdoll_position - character_position) * weight),
 * but for a dead one this branch does not run. When the ragdoll ends, the corpse shows up
 * at its old position, which looks like a teleport back to the collision point.
 *
 * The fix: while the ragdoll is active, we move the dead character's logical position ourselves
 * to the position the ragdoll computes. We use the game's stock methods:
 * setPosition(x,y,z) + setCurrentSquareFromPosition(); the second one re-registers the object
 * on the right world square. Without it the body would stay registered on the old square
 * and, for example, would not open when searched.
 *
 * Living characters are left alone: the animation system handles them, no need to interfere.
 *
 * <h2>Height is not copied from the ragdoll (26.09.2026)</h2>
 * The height in the ragdoll position is a pose, not the body's place in the world: after falling,
 * a zombie lowers its pelvis by a third of a meter but stays on the same square. Previously, such
 * a dip below zero got the whole move rejected (775 times in one session), and for those frames
 * the body stopped following horizontally as well. Now x and y come from the ragdoll,
 * and the character keeps its own height. Vehicles in PZ drive only on floor zero
 * ({@code AddVehicleCommand}: "Z coordinate must be 0 for now"), so a body that is hit
 * does not change floors, and the game sets the height within the square itself.
 *
 * <h2>What NOT to do here</h2>
 * On 26.09.2026 I decided that our position writes close a loop through the pelvis, and switched
 * the patch to deltas minus its own previous step. That was wrong:
 * {@code RagdollController.calculateRagdollWorldTransform} anchors the ragdoll to the
 * character's position, and the pelvis position remains the true position of the body; our
 * writes do not corrupt it. Subtracting a loop that did not exist, the patch moved the character
 * after the body at half strength: shift, zero, shift, zero. In game it looked like a double
 * image: the ragdoll drawn in one place, the corpse in a lagging one; and after the ragdoll ended
 * the corpse ended up far behind. Checking with a model did not catch this: the model tested
 * my own hypothesis, not the game. Commit 1651e00; the revert is the next one.
 */
@Patch(className = "zombie.core.physics.RagdollController", methodName = "postUpdate", warmUp = true)
public class Patch_corpseFollowsRagdoll {

    @Patch.OnExit
    public static void exit(@Patch.This Object ragdoll) {
        if (!LabGate.active() || !LabSettings.corpseFollows()) {
            return;
        }
        Impl.sync(ragdoll);
    }

    public static final class Impl {
        /** Leave the square alone if the shift is below this (in tiles). */
        public static final float MIN_MOVE = 0.05f;
        /**
         * Cap on the jump per frame, in tiles (a tile is about a meter).
         *
         * It used to be 20, i.e. forty meters per frame. The log showed the ragdoll hitting
         * this cap report after report: max 19.98, 19.85, 19.78 with a mean step of 3 tiles.
         * Such values are garbage, yet we applied them: the body flew off beyond the
         * loaded chunks and was silently deleted (details at {@link #hasSquare}).
         *
         * Four tiles per frame is 864 km/h at 60 fps and 216 km/h even at 15 fps,
         * so for a corpse on the bumper there is still a huge margin here.
         * How much actually gets cut off is shown by the rejFar counter in the summary.
         */
        public static final float MAX_JUMP = 4.0f;

        public static volatile boolean broken = false;
        public static Method rGetChar, rX, rY, rComputed;
        public static Method cIsDead, cGetX, cGetY, cGetZ, cSetPosition, cSetSquare;
        /** To check that the destination has a world square at all. */
        public static Method cGetCell, cellGetSquare;
        /** Private field IsoGameCharacter.diedBody: the corpse object itself, no getter. */
        public static java.lang.reflect.Field cDiedBody;
        public static Method bSetPosition, bSetSquare;
        public static long corpseMoves = 0L;
        /**
         * The corpse reference has to be cached: VirtualZombieManager returns the zombie object
         * to the pool and calls clearDiedBody(), so the diedBody field is visible for literally
         * one frame in 150. Catch it while visible, then keep moving it via our own reference.
         */
        public static final java.util.Map<Object, Object> CORPSE_CACHE = new java.util.WeakHashMap<Object, Object>();
        public static final java.util.Map<Object, Long> CORPSE_SINCE = new java.util.WeakHashMap<Object, Long>();
        /** Do not drag the corpse after the ragdoll for longer than this. */
        public static final long CORPSE_MAX_NANOS = 8_000_000_000L;
        public static boolean logged = false;
        public static long moves = 0L;
        public static double sumDist = 0.0;
        public static double maxDist = 0.0;
        public static long lastReportNanos = 0L;
        /** Rejected moves, by reason. Needed to see how often the ragdoll lies. */
        public static long rejNaN = 0L;
        public static long rejFar = 0L;
        public static long rejNoSquare = 0L;
        /** The ragdoll is not measured yet: its desired position is still the previous body's. */
        public static long rejEarly = 0L;
        public static boolean noSquareLogged = false;

        public static void sync(Object ragdoll) {
            if (broken || ragdoll == null) {
                return;
            }
            try {
                if (rGetChar == null) {
                    initRagdoll(ragdoll.getClass());
                }
                Object chr = rGetChar.invoke(ragdoll);
                if (chr == null) {
                    return;
                }
                if (cIsDead == null) {
                    initChar(chr.getClass());
                }
                if (!((Boolean) cIsDead.invoke(chr)).booleanValue()) {
                    // the zombie object may have gone back to the pool and been reused for a
                    // living zombie; then the old corpse has nothing to do with it
                    synchronized (CORPSE_CACHE) {
                        CORPSE_CACHE.remove(chr);
                        CORPSE_SINCE.remove(chr);
                    }
                    return;   // a living one is driven by the animation system
                }
                // The summary goes BEFORE the checks: if the ragdoll lies and every move is
                // rejected, we never reach the end of the method and would stay silent exactly
                // when it matters most to see it.
                report();
                // In its first frames the controller has not computed the body yet
                // (calculateSimulationData skips the first frame) and the desired position is
                // whatever the pooled field held: the jumps of up to 4 tiles seen in the log.
                if (!((Boolean) rComputed.invoke(ragdoll)).booleanValue()) {
                    rejEarly++;
                    return;
                }
                float nx = ((Float) rX.invoke(ragdoll)).floatValue();
                float ny = ((Float) rY.invoke(ragdoll)).floatValue();
                if (Float.isNaN(nx) || Float.isNaN(ny)) {
                    rejNaN++;
                    return;
                }
                float ox = ((Float) cGetX.invoke(chr)).floatValue();
                float oy = ((Float) cGetY.invoke(chr)).floatValue();
                float oz = ((Float) cGetZ.invoke(chr)).floatValue();
                // Height stays our own: the ragdoll's is a pose, not a floor (see class header).
                // Ours can dip below zero too: while the zombie is still alive, vanilla itself
                // moves it in Z (doDeferredMovementFromRagdoll: setZ(getZ() + dz)), and the
                // pelvis drags it under the floor. Log: "move a body to 11701.3, 6805.0, -0.1
                // where the world has no square": the body was intact but did not move. Negative
                // height counts as the floor; upper floors are unaffected, only negatives change.
                float nz = oz < 0.0f ? 0.0f : oz;
                float dx = nx - ox, dy = ny - oy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < MIN_MOVE) {
                    return;
                }
                if (dist > MAX_JUMP) {
                    rejFar++;
                    return;
                }
                if (!hasSquare(chr, nx, ny, nz)) {
                    rejNoSquare++;
                    return;
                }
                cSetPosition.invoke(chr, Float.valueOf(nx), Float.valueOf(ny), Float.valueOf(nz));
                cSetSquare.invoke(chr);
                moves++;
                if (!logged) {
                    logged = true;
                    Log.debug("[LabVehiclePhysics] corpse moved with its ragdoll for the first time - no teleport expected");
                }

                // KEY POINT: the corpse is created at the moment of death at the impact point and
                // never moves after that: it has no ragdoll of its own. Move it the same way.
                Object corpse = cDiedBody.get(chr);
                long nowNanos = System.nanoTime();
                synchronized (CORPSE_CACHE) {
                    if (corpse != null) {
                        if (CORPSE_CACHE.put(chr, corpse) == null) {
                            CORPSE_SINCE.put(chr, Long.valueOf(nowNanos));
                        }
                    } else {
                        Long since = CORPSE_SINCE.get(chr);
                        if (since != null && nowNanos - since.longValue() < CORPSE_MAX_NANOS) {
                            corpse = CORPSE_CACHE.get(chr);   // field already cleared, use ours
                        } else if (since != null) {
                            CORPSE_CACHE.remove(chr);
                            CORPSE_SINCE.remove(chr);
                        }
                    }
                }
                if (corpse != null) {
                    if (bSetPosition == null) {
                        bSetPosition = corpse.getClass().getMethod("setPosition", float.class, float.class, float.class);
                        bSetSquare = corpse.getClass().getMethod("setCurrentSquareFromPosition");
                        Log.debug("[LabVehiclePhysics] corpse object found (" + corpse.getClass().getSimpleName()
                                + ") - moving it as well");
                    }
                    bSetPosition.invoke(corpse, Float.valueOf(nx), Float.valueOf(ny), Float.valueOf(nz));
                    bSetSquare.invoke(corpse);
                    corpseMoves++;
                }
                sumDist += dist;
                if (dist > maxDist) {
                    maxDist = dist;
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the corpse patch, disabling: " + t);
            }
        }

        /**
         * Whether the destination has a world square.
         *
         * Why this is needed at all. {@code setCurrentSquareFromPosition()} assigns the lookup
         * result UNCONDITIONALLY, null included:
         * <pre>
         * IsoGridSquare current = this.getCell().getGridSquare(x1, y1, z1);
         * if (current == null) {
         *     for (int n = PZMath.fastfloor(z1); n &gt;= 0; n--) {   // downward only, same x,y
         *         current = this.getCell().getGridSquare(x1, y1, n);
         *         if (current != null) break;
         *     }
         * }
         * this.setCurrent(current);
         * </pre>
         * The fallback search only rescues a vertical miss. If x,y flew off beyond the
         * loaded chunks, every attempt returns null. And then, in {@code IsoZombie.update}:
         * <pre>
         * if (this.current == null &amp;&amp; (!GameClient.client || !this.isRemoteZombie())) {
         *     this.removeFromWorld();
         *     this.removeFromSquare();
         * }
         * </pre>
         * No death, no corpse, no sound: the object is silently erased.
         *
         * This is what made zombies vanish when driving into a crowd: a zombie that is hit dies
         * on first contact (up to 10 damage at 1.8-2.1 health), falls under this patch at once,
         * and some of the bodies we carried off into the void ourselves. In a crowd, ragdolls
         * interpenetrate and Bullet jerks them apart: more outliers, which is why it shows there.
         *
         * We repeat the same downward ladder as the game, so as not to refuse a move where
         * vanilla would have managed on its own.
         */
        public static boolean hasSquare(Object chr, float nx, float ny, float nz) throws Exception {
            Object cell = cGetCell.invoke(chr);
            if (cell == null) {
                return false;
            }
            if (cellGetSquare.invoke(cell, Double.valueOf(nx), Double.valueOf(ny), Double.valueOf(nz)) != null) {
                return true;
            }
            for (int n = (int) Math.floor((double) nz); n >= 0; n--) {
                if (cellGetSquare.invoke(cell, Double.valueOf(nx), Double.valueOf(ny), Double.valueOf(n)) != null) {
                    return true;
                }
            }
            if (!noSquareLogged) {
                noSquareLogged = true;
                Log.debug(String.format(
                        "[LabVehiclePhysics] ragdoll asked to move a body to %.1f, %.1f, %.1f where the world has no "
                        + "square - move skipped (before this fix such a body was silently deleted)", nx, ny, nz));
            }
            return false;
        }

        private static synchronized void initRagdoll(Class<?> rc) throws Exception {
            if (rGetChar != null) {
                return;
            }
            rX = rc.getMethod("getDesiredCharacterPositionX");
            rY = rc.getMethod("getDesiredCharacterPositionY");
            rComputed = rc.getMethod("isSimulationDirectionCalculated");
            rGetChar = rc.getMethod("getGameCharacterObject");
        }

        private static synchronized void initChar(Class<?> cc) throws Exception {
            if (cIsDead != null) {
                return;
            }
            cIsDead = cc.getMethod("isDead");
            cGetX = cc.getMethod("getX");
            cGetY = cc.getMethod("getY");
            cGetZ = cc.getMethod("getZ");
            cSetPosition = cc.getMethod("setPosition", float.class, float.class, float.class);
            cSetSquare = cc.getMethod("setCurrentSquareFromPosition");
            cGetCell = cc.getMethod("getCell");
            cellGetSquare = Class.forName("zombie.iso.IsoCell", false, cc.getClassLoader())
                    .getMethod("getGridSquare", double.class, double.class, double.class);
            Class<?> c = cc;
            while (c != null && cDiedBody == null) {
                try {
                    cDiedBody = c.getDeclaredField("diedBody");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (cDiedBody == null) {
                throw new NoSuchFieldException("diedBody");
            }
            cDiedBody.setAccessible(true);
            Log.debug("[LabVehiclePhysics] corpse patch ready: the dead body follows its ragdoll ("
                    + cc.getName() + ")");
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long rejected = rejNaN + rejFar + rejNoSquare + rejEarly;
            if (moves == 0L && rejected == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] ragdoll follow, last 15 s: bodies %d, CORPSES %d (caught in total %d), "
                    + "mean step %.2f tiles, max %.2f; rejected %d (not measured yet %d, no square %d, "
                    + "farther than %.1f tiles %d, NaN %d)",
                    moves, corpseMoves, Patch_catchCorpse.Impl.caught,
                    moves > 0L ? sumDist / moves : 0.0, maxDist,
                    rejected, rejEarly, rejNoSquare, MAX_JUMP, rejFar, rejNaN));
            moves = 0L;
            corpseMoves = 0L;
            sumDist = 0.0;
            maxDist = 0.0;
            rejNaN = 0L;
            rejFar = 0L;
            rejNoSquare = 0L;
            rejEarly = 0L;
        }
    }
}
