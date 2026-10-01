package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Ragdoll of a hit zombie on a client that is not the body's owner (item 6.2).
 *
 * The body's flight is not networked: every client with the vehicle nearby computes the hit and
 * starts the ragdoll on its own. A test with two clients showed two problems for the observer.
 *
 * <h2>1. Jitter</h2>
 * The zombie's owner, the driver, sends its ragdoll's position five times a second, and on every
 * message the observer's client builds a path to it, turns the body to the direction it carries
 * and teleports it if they diverge by more than 3 tiles ({@code NetworkZombieAI.parse}). The two
 * flights do not match, and the body darts between its own ragdoll and the other one: the
 * observer's follow step is 0.7–1.6 tiles per frame versus 0.1 for the driver. Now the owner's
 * positions are not applied to the body while its local ragdoll runs; after that, as in vanilla.
 *
 * <h2>2. Endless flight</h2>
 * While the body touches a vehicle, simulation is extended each frame ({@code RagdollController}:
 * {@code isContactingVehicle → simulationTimeout = 1.5}), but contact with a vehicle driven by
 * someone else is not checked by the game at all:
 * <pre>
 * // BaseVehicle.isCollided
 * if (GameClient.client &amp;&amp; getDriver() != null &amp;&amp; !getDriver().isLocal()) return true;
 * </pre>
 * The observer's ragdoll did not end while the driver was at the wheel, even if the vehicle had
 * left, and the corpse appeared on the five-second timeout, snapping into place. Now contact is
 * checked with the same geometry as for a vehicle with a local driver. Only
 * {@code RagdollController} calls {@code isCollided}, so nothing else is affected.
 *
 * <h2>Measurement</h2>
 * The two flights still end at different points, and the observer's body is moved to the corpse
 * that the server placed at the driver's point: either by the owner's message after landing
 * (over 3 tiles: teleport) or by the corpse packet (another square: move). Both moves are counted.
 */
public final class RemoteRagdoll {

    public static final long REPORT_NANOS = 15_000_000_000L;
    /** Beyond this distance, in tiles, {@code NetworkZombieAI.parse} teleports. */
    public static final float TELEPORT_DIST = 3.0f;

    public static volatile boolean broken = false;

    // ---- reflection
    public static Field fClient;
    public static Field fNetZombie;
    public static Field fRealX;
    public static Field fRealY;
    public static Method mRagdollActive;
    public static Method mIsDead;
    public static Method mGetX;
    public static Method mGetY;
    public static Method mGetDriver;
    public static Method mIsLocal;
    public static Method mTestCollision;
    public static Object collisionOut;
    public static Field fVecX;
    public static Field fCharacterId;
    public static Method mCharacterOf;
    public static Method mPacketX;
    public static Method mPacketY;

    // ---- counters for the log line
    public static long heldBack = 0L;
    public static long contactReleased = 0L;
    public static long ownerSnaps = 0L;
    public static double ownerSnapSum = 0.0;
    public static long corpses = 0L;
    public static long corpseMoved = 0L;
    public static double corpseMoveSum = 0.0;
    public static float corpseMoveMax = 0.0f;
    public static long lastReportNanos = 0L;

    private RemoteRagdoll() {
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (mPacketY != null) {
            return;
        }
        fClient = Class.forName("zombie.network.GameClient", false, cl).getField("client");
        Class<?> chr = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
        Class<?> mov = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        Class<?> ai = Class.forName("zombie.characters.NetworkZombieAI", false, cl);
        fNetZombie = ai.getField("zombie");
        Class<?> zp = Class.forName("zombie.network.packets.character.ZombiePacket", false, cl);
        fRealX = zp.getField("realX");
        fRealY = zp.getField("realY");
        mRagdollActive = chr.getMethod("isRagdollSimulationActive");
        mIsDead = chr.getMethod("isDead");
        mGetX = mov.getMethod("getX");
        mGetY = mov.getMethod("getY");
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        mGetDriver = bv.getMethod("getDriver");
        mIsLocal = chr.getMethod("isLocal");
        Class<?> v2 = Class.forName("zombie.iso.Vector2", false, cl);
        mTestCollision = bv.getMethod("testCollisionWithCharacter", chr, float.class, v2);
        collisionOut = v2.getConstructor().newInstance();
        fVecX = v2.getField("x");
        Class<?> dead = Class.forName("zombie.network.packets.character.DeadCharacterPacket", false, cl);
        fCharacterId = dead.getDeclaredField("characterId");
        fCharacterId.setAccessible(true);
        mCharacterOf = fCharacterId.getType().getMethod("getCharacter");
        Class<?> pos = Class.forName("zombie.network.fields.Position", false, cl);
        mPacketX = pos.getMethod("getX");
        mPacketY = pos.getMethod("getY");
    }

    public static boolean enabled() {
        return !broken && LabGate.active() && LabSettings.corpseFollows();
    }

    /**
     * Client, entry to {@code NetworkZombieAI.parse}: true means do not apply the owner's
     * message, our own ragdoll is running. Also measures: a dead body after landing that the
     * owner's message is about to teleport to the driver's point.
     */
    public static boolean skipOwnerUpdate(Object networkAi, Object packet) {
        if (broken || networkAi == null) {
            return false;
        }
        try {
            if (mPacketY == null) {
                init(networkAi.getClass().getClassLoader());
            }
            if (!fClient.getBoolean(null)) {
                return false;
            }
            Object zombie = fNetZombie.get(networkAi);
            if (zombie == null || !enabled()) {
                return false;
            }
            if (((Boolean) mRagdollActive.invoke(zombie)).booleanValue()) {
                heldBack++;
                report();
                return true;
            }
            if (packet != null && ((Boolean) mIsDead.invoke(zombie)).booleanValue()) {
                float dx = fRealX.getFloat(packet) - ((Float) mGetX.invoke(zombie)).floatValue();
                float dy = fRealY.getFloat(packet) - ((Float) mGetY.invoke(zombie)).floatValue();
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > TELEPORT_DIST) {
                    ownerSnaps++;
                    ownerSnapSum += d;
                    report();
                }
            }
            return false;
        } catch (Throwable t) {
            fail("the owner update", t);
            return false;
        }
    }

    /** Client, exit from {@code BaseVehicle.isCollided}: a real contact check when the driver is remote. */
    public static boolean contact(Object vehicle, Object character, boolean vanilla) {
        if (!vanilla || broken || vehicle == null || character == null) {
            return vanilla;
        }
        try {
            if (mPacketY == null) {
                init(vehicle.getClass().getClassLoader());
            }
            if (!fClient.getBoolean(null)) {
                return vanilla;
            }
            Object driver = mGetDriver.invoke(vehicle);
            if (driver == null || ((Boolean) mIsLocal.invoke(driver)).booleanValue()) {
                return vanilla;     // local driver: vanilla has already checked the geometry
            }
            if (!enabled()) {
                return vanilla;
            }
            // The same test and radius that vanilla uses for a local driver.
            Object v = mTestCollision.invoke(vehicle, character, Float.valueOf(0.20000002f), collisionOut);
            boolean touching = v != null && fVecX.getFloat(v) != -1.0f;
            if (!touching) {
                contactReleased++;
                report();
            }
            return touching;
        } catch (Throwable t) {
            fail("the vehicle contact", t);
            return vanilla;
        }
    }

    /** Client, entry to {@code DeadCharacterPacket.processClient}: the body's shift to the server's corpse. */
    public static void onCorpsePacket(Object packet) {
        if (broken || packet == null) {
            return;
        }
        try {
            init(packet.getClass().getClassLoader());
            Object character = mCharacterOf.invoke(fCharacterId.get(packet));
            if (character == null) {
                return;
            }
            float px = ((Float) mPacketX.invoke(packet)).floatValue();
            float py = ((Float) mPacketY.invoke(packet)).floatValue();
            float cx = ((Float) mGetX.invoke(character)).floatValue();
            float cy = ((Float) mGetY.invoke(character)).floatValue();
            float d = (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
            corpses++;
            corpseMoveSum += d;
            if (d > corpseMoveMax) {
                corpseMoveMax = d;
            }
            // processClient moves the body only if the square is different.
            if ((int) Math.floor(px) != (int) Math.floor(cx) || (int) Math.floor(py) != (int) Math.floor(cy)) {
                corpseMoved++;
            }
            report();
        } catch (Throwable t) {
            fail("the corpse packet", t);
        }
    }

    /** A summary every 15 seconds, only if something happened. */
    public static void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < REPORT_NANOS) {
            return;
        }
        lastReportNanos = now;
        Log.debug(String.format(java.util.Locale.ROOT,
                "[LabVehiclePhysics] remote ragdolls, last 15 s: owner updates held back during a local ragdoll %d, "
                        + "contact frames with someone else's vehicle released %d; after landing the owner's update "
                        + "teleported a body %d times (%.1f tiles on average); corpses from the server %d, "
                        + "body moved to another square %d, %.2f tiles on average, max %.2f",
                heldBack, contactReleased, ownerSnaps, ownerSnaps > 0 ? ownerSnapSum / ownerSnaps : 0.0,
                corpses, corpseMoved, corpses > 0 ? corpseMoveSum / corpses : 0.0, corpseMoveMax));
        heldBack = 0L;
        contactReleased = 0L;
        ownerSnaps = 0L;
        ownerSnapSum = 0.0;
        corpses = 0L;
        corpseMoved = 0L;
        corpseMoveSum = 0.0;
        corpseMoveMax = 0.0f;
    }

    public static void fail(String where, Throwable t) {
        broken = true;
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        Log.info("[LabVehiclePhysics] ERROR in remote ragdolls (" + where + "), disabling: " + cause + TreeBreak.where(cause));
    }
}
