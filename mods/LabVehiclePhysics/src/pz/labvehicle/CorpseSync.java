package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The corpse lies where the body fell, in multiplayer (item 6.1).
 *
 * <h2>How it was</h2>
 * In multiplayer every zombie has an owner: the client that reports the zombie's position and
 * health to the server, and the server trusts it. As soon as the owner reports "dead", the server
 * immediately creates the corpse at the current point, almost at the impact point:
 * <pre>
 * // NetworkZombiePacker.parseZombie
 * this.applyZombie(zombie);
 * if (zombie.isDead()) zombie.die();
 * </pre>
 * Yet the driver's ragdoll still flies. On landing, the client gets the server corpse and, if the
 * square differs, moves the body there ({@code DeadCharacterPacket.processClient}): a jump back.
 *
 * <h2>How it is now</h2>
 * <ol>
 *   <li>Server, on a vehicle hit, before damage ({@code VehicleHitField.process}): the driver
 *       becomes the zombie's owner ({@code NetworkZombieManager.moveZombie}; the game does
 *       not transfer a dead zombie, hence before damage), and the zombie is marked "flying".</li>
 *   <li>Server, {@code NetworkZombieManager.updateAuth}: keep the owner while it flies.</li>
 *   <li>Server, {@code die()}: while it flies, the death is deferred.</li>
 *   <li>Driver's client, {@code ZombieOnGroundState.enter}: the body has landed, so it sends the
 *       point and direction to the server with the {@code zombieLanded} command.</li>
 *   <li>Server: the point came from the driver, so the zombie moves there and, if dead, dies. The
 *       corpse is created at the landing point, death packets carry it to everyone: no jump.</li>
 * </ol>
 *
 * <h2>The wait ends when the body lands, not on a timer</h2>
 * A body can ride on the hood for a long time. So instead of a fixed time we wait until:
 * <ul>
 *   <li>the landing point arrives;</li>
 *   <li>the driver, in a regular owner update, reports {@code realState == OnGround}: the position
 *       in that update is the landing point, and the server applies it before {@code die()};</li>
 *   <li>the zombie loses its owner (the driver left);</li>
 *   <li>{@link #PENDING_NANOS} passes. This only guards against waiting forever: if the driver's
 *       client unloaded the zombie, neither a point nor "on ground" will come from it, and a dead
 *       zombie without a corpse would hang on the server until a restart.</li>
 * </ul>
 *
 * <h2>The landing point's distance is not checked</h2>
 * Such a check gives no protection: the zombie's owner can report any position for it anyway,
 * and the game does not check it ({@code applyZombie}). But it does harm: the server sees the
 * zombie through the owner's updates, and those come once every 4 seconds when no other players
 * are nearby; in that time a body on the hood travels a hundred tiles, and the real point would
 * have to be rejected. We only check that the point came from the player who hit the zombie.
 *
 * The client side of waiting for the corpse needs no changes: on the client {@code die()} repeats
 * every frame until there is a corpse ({@code ZombieOnGroundState.execute}), and a death packet
 * that arrives after landing is handled at once; its five-second timeout counts from its arrival.
 */
public final class CorpseSync {

    /**
     * Server: a safeguard against waiting forever, see the class description. Not a flight limit:
     * the flight ends sooner, via a landing point, the owner's "on ground", or the driver leaving.
     */
    public static final long PENDING_NANOS = 300_000_000_000L;
    /** Driver's client: the same safeguard; after this the body is no longer ours. */
    public static final long FLYING_NANOS = 300_000_000_000L;
    public static final long REPORT_NANOS = 15_000_000_000L;
    public static final String MODULE = "LabVehiclePhysics";
    public static final String COMMAND = "zombieLanded";

    /** Server: a zombie hit by a vehicle waits for the landing point from the driver. */
    public static final class Pending {
        public Object driver;
        public long deadline;
    }

    /** Driver's client: who was hit and who was driving. */
    public static final class Flying {
        public Object driver;
        public long since;
    }

    public static final Map<Object, Pending> PENDING = Collections.synchronizedMap(new WeakHashMap<Object, Pending>());
    public static final Map<Object, Flying> FLYING = Collections.synchronizedMap(new WeakHashMap<Object, Flying>());
    /** While nobody is awaited, the patches on hot paths cost one flag check. */
    public static volatile boolean anyPending = false;
    public static volatile boolean anyFlying = false;
    public static volatile boolean broken = false;

    // ---- reflection
    public static Class<?> zombieClass;
    public static Class<?> playerClass;
    public static Field fServer;
    public static Field fClient;
    public static Field fHitDamage;
    public static Field fKnockedDown;
    public static Method mGetDriver;
    public static Method mIsLocalPlayer;
    public static Method mIsDead;
    public static Method mGetHealth;
    public static Method mDie;
    public static Method mGetOnlineID;
    public static Method mGetX;
    public static Method mGetY;
    public static Method mGetZ;
    public static Method mSetZ;
    public static Method mAnimAngle;
    public static Method mSetX;
    public static Method mSetNextX;
    public static Method mSetLastX;
    public static Method mSetY;
    public static Method mSetNextY;
    public static Method mSetLastY;
    public static Method mSetDirectionAngle;
    public static Method mSquareFromPosition;
    public static Method mGetCurrentSquare;
    public static Method mGetMovingSquare;
    public static Method mSetMovingSquareNow;
    public static Method mMoveZombie;
    public static Method mManagerInstance;
    public static Method mConnectionFromPlayer;
    public static Method mZombieMapGet;
    public static Field fServerMapInstance;
    public static Field fZombieMap;
    public static Method mSendClientCommand;
    public static Object gameClient;
    public static Method mGetOwner;
    public static Field fRealState;
    public static Object onGroundState;

    // ---- counters for the log line
    public static int logged = 0;
    public static long held = 0L;
    public static long landed = 0L;
    public static long landedAlive = 0L;
    /** Landing points with no square on the server: the zombie was left where the server saw it. */
    public static long noSquare = 0L;
    public static long timeouts = 0L;
    public static long rejected = 0L;
    public static long onGround = 0L;
    public static long ownerLost = 0L;
    public static long reported = 0L;
    public static double offsetSum = 0.0;
    public static long lastReportNanos = 0L;

    private CorpseSync() {
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (mSetMovingSquareNow != null) {
            return;
        }
        zombieClass = Class.forName("zombie.characters.IsoZombie", false, cl);
        playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
        Class<?> chr = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
        Class<?> mov = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> gs = Class.forName("zombie.network.GameServer", false, cl);
        Class<?> gc = Class.forName("zombie.network.GameClient", false, cl);
        fServer = gs.getField("server");
        fClient = gc.getField("client");
        Class<?> hit = Class.forName("zombie.network.fields.hit.Hit", false, cl);
        fHitDamage = hit.getDeclaredField("damage");
        fHitDamage.setAccessible(true);
        fKnockedDown = Class.forName("zombie.network.fields.hit.VehicleHitField", false, cl).getField("isKnockedDown");
        mGetDriver = bv.getMethod("getDriver");
        mIsLocalPlayer = playerClass.getMethod("isLocalPlayer");
        mIsDead = chr.getMethod("isDead");
        mGetHealth = chr.getMethod("getHealth");
        mDie = chr.getMethod("die");
        mGetOnlineID = zombieClass.getMethod("getOnlineID");
        mGetX = mov.getMethod("getX");
        mGetY = mov.getMethod("getY");
        mGetZ = mov.getMethod("getZ");
        mSetZ = mov.getMethod("setZ", float.class);
        mAnimAngle = chr.getMethod("getAnimAngleRadians");
        mSetX = mov.getMethod("setX", float.class);
        mSetNextX = mov.getMethod("setNextX", float.class);
        mSetLastX = mov.getMethod("setLastX", float.class);
        mSetY = mov.getMethod("setY", float.class);
        mSetNextY = mov.getMethod("setNextY", float.class);
        mSetLastY = mov.getMethod("setLastY", float.class);
        mSetDirectionAngle = chr.getMethod("setDirectionAngle", float.class);
        mSquareFromPosition = mov.getMethod("setCurrentSquareFromPosition");
        mGetCurrentSquare = mov.getMethod("getCurrentSquare");
        mGetMovingSquare = mov.getMethod("getMovingSquare");
        Class<?> manager = Class.forName("zombie.popman.NetworkZombieManager", false, cl);
        mManagerInstance = manager.getMethod("getInstance");
        Class<?> udp = Class.forName("zombie.core.raknet.UdpConnection", false, cl);
        mMoveZombie = manager.getMethod("moveZombie", zombieClass, udp, playerClass);
        mConnectionFromPlayer = gs.getMethod("getConnectionFromPlayer", playerClass);
        Class<?> serverMap = Class.forName("zombie.network.ServerMap", false, cl);
        fServerMapInstance = serverMap.getField("instance");
        fZombieMap = serverMap.getField("zombieMap");
        mZombieMapGet = fZombieMap.getType().getMethod("get", short.class);
        mGetOwner = zombieClass.getMethod("getOwner");
        fRealState = chr.getField("realState");
        onGroundState = Class.forName("zombie.network.NetworkVariables$ZombieState", false, cl).getField("OnGround").get(null);
        mSetMovingSquareNow = mov.getMethod("setMovingSquareNow");
        Log.debug("[LabVehiclePhysics] corpse sync ready: the driver owns the zombies it hits, "
                + "the server waits until the body lies and creates the corpse where it landed");
    }

    public static boolean enabled() {
        return !broken && LabGate.active() && LabSettings.corpseFollows();
    }

    // ================================================================ server

    /**
     * Server, vehicle hit, before damage. A flight happens only if the hit knocks down or kills:
     * the driver's ragdoll starts on {@code bDead} or {@code bKnockedDown}. No reason to hold the
     * others: otherwise a death from another cause shortly after would also await a landing.
     */
    public static void onServerVehicleHit(Object wielder, Object target, Object vehicle, Object field) {
        if (wielder == null || target == null || vehicle == null || field == null || !enabled()) {
            return;
        }
        try {
            init(target.getClass().getClassLoader());
            if (!fServer.getBoolean(null) || !zombieClass.isInstance(target) || !playerClass.isInstance(wielder)) {
                return;
            }
            if (mGetDriver.invoke(vehicle) != wielder) {
                return;
            }
            if (((Boolean) mIsDead.invoke(target)).booleanValue()) {
                return;     // the game does not hand a dead zombie to another owner
            }
            float health = ((Float) mGetHealth.invoke(target)).floatValue();
            float damage = fHitDamage.getFloat(field);
            boolean knocked = fKnockedDown.getBoolean(field);
            if (!knocked && damage < health) {
                return;
            }
            Object connection = mConnectionFromPlayer.invoke(null, wielder);
            if (connection == null) {
                return;
            }
            mMoveZombie.invoke(mManagerInstance.invoke(null), target, connection, wielder);
            Pending p = new Pending();
            p.driver = wielder;
            p.deadline = System.nanoTime() + PENDING_NANOS;
            PENDING.put(target, p);
            anyPending = true;
            held++;
            report();
        } catch (Throwable t) {
            fail("the server vehicle hit", t);
        }
    }

    /** Server, entry to {@code die()}: true to defer, the driver still sees the body flying. */
    public static boolean deferDeath(Object chr) {
        return anyPending && chr != null && stillFlying(chr);
    }

    /** Server, entry to {@code NetworkZombieManager.updateAuth}: true to leave the owner alone. */
    public static boolean holdOwner(Object zombie) {
        return anyPending && zombie != null && stillFlying(zombie);
    }

    /**
     * Server: whether we still wait for this zombie. If not, the wait ends and vanilla takes
     * over: {@code die()} goes through at the point where the server sees the zombie now.
     */
    public static boolean stillFlying(Object zombie) {
        Pending p = PENDING.get(zombie);
        if (p == null) {
            return false;
        }
        try {
            String why = null;
            if (fRealState.get(zombie) == onGroundState) {
                // The owner (the driver) itself reported the body on the ground; the server has
                // already applied the position from that same update, and it is the landing point.
                onGround++;
                why = "on-ground";
            } else if (mGetOwner.invoke(zombie) == null) {
                ownerLost++;
                why = "owner-lost";
            } else if (System.nanoTime() > p.deadline) {
                timeouts++;
                why = "timeout";
            }
            if (why == null) {
                return true;
            }
            drop(zombie);
            // die() goes through right after this, at the point the owner's updates left the
            // zombie at; after a ragdoll that can be under the floor, with no square (see place()).
            ensureFloor(zombie);
            report();
            return false;
        } catch (Throwable t) {
            drop(zombie);
            fail("the pending check", t);
            return false;
        }
    }

    public static void drop(Object zombie) {
        synchronized (PENDING) {
            PENDING.remove(zombie);
            anyPending = !PENDING.isEmpty();
        }
    }

    /** Server: the driver's landing report. {@code args = {id, x, y, a}}, a in degrees. */
    public static void serverLanded(Object player, Object args) {
        if (player == null || args == null || !enabled()) {
            return;
        }
        try {
            init(player.getClass().getClassLoader());
            if (!fServer.getBoolean(null)) {
                return;
            }
            ServerTable.init();
            if (!ServerTable.kahluaTable.isInstance(args)) {
                return;
            }
            double id = TreeBreak.number(args, "id");
            float x = (float) TreeBreak.number(args, "x");
            float y = (float) TreeBreak.number(args, "y");
            float a = (float) TreeBreak.number(args, "a");
            if (Double.isNaN(id) || Float.isNaN(x) || Float.isNaN(y)) {
                return;
            }
            Object zombie = mZombieMapGet.invoke(fZombieMap.get(fServerMapInstance.get(null)), Short.valueOf((short) id));
            if (zombie == null) {
                return;     // already a corpse or unloaded: too late
            }
            Pending p = PENDING.get(zombie);
            if (p == null) {
                return;     // not ours: the vanilla path
            }
            if (p.driver != player) {
                TreeBreak.warnOnce("corpse-driver", "corpse sync: a landing point came from a player who did not hit the zombie - ignored");
                return;
            }
            // The offset is only for the log: how far the server lagged behind the body. Not
            // checked: see the class description.
            float dx = x - ((Float) mGetX.invoke(zombie)).floatValue();
            float dy = y - ((Float) mGetY.invoke(zombie)).floatValue();
            float offset = (float) Math.sqrt(dx * dx + dy * dy);
            drop(zombie);
            place(zombie, x, y, a);
            boolean dead = ((Boolean) mIsDead.invoke(zombie)).booleanValue();
            if (dead) {
                mDie.invoke(zombie);
                landed++;
            } else {
                landedAlive++;
            }
            offsetSum += offset;
            if (logged < 12) {
                logged++;
                Log.debug(String.format(java.util.Locale.ROOT,
                        "[LabVehiclePhysics] corpse sync: zombie %d landed %.1f tiles from where the server saw it - %s",
                        (int) id, offset, dead ? "corpse created at the landing point" : "alive, position updated"));
            }
            report();
        } catch (Throwable t) {
            fail("the landing report", t);
        }
    }

    /**
     * Move the zombie on the server the way an incoming owner update does ({@code applyZombie}).
     *
     * The zombie must end up on a square: die() sends the death through DeadCharacterPacket,
     * which reads {@code square.getStaticMovingObjects()}. Without a square that throws ("Packet
     * ZombieDeath send failed"), the corpse exists only on the server, and every client keeps the
     * zombie standing where its body froze. Seen when ramming a crowd of 200: after the ragdoll
     * the server had the zombie slightly under the floor (z -0.1), and the game looks for a square
     * only from floor(z) down to 0, so there was none. So: z below zero counts as the floor, and if
     * the landing point still has no square on the server, the zombie stays where the server saw it.
     */
    public static void place(Object zombie, float x, float y, float angleDeg) throws Exception {
        float oldX = ((Float) mGetX.invoke(zombie)).floatValue();
        float oldY = ((Float) mGetY.invoke(zombie)).floatValue();
        if (((Float) mGetZ.invoke(zombie)).floatValue() < 0.0f) {
            mSetZ.invoke(zombie, Float.valueOf(0.0f));
        }
        moveTo(zombie, x, y);
        if (!Float.isNaN(angleDeg)) {
            mSetDirectionAngle.invoke(zombie, Float.valueOf(angleDeg));
        }
        mSquareFromPosition.invoke(zombie);
        if (mGetCurrentSquare.invoke(zombie) == null) {
            noSquare++;
            TreeBreak.warnOnce("corpse-nosquare", String.format(java.util.Locale.ROOT,
                    "corpse sync: the landing point %.1f, %.1f has no square on the server - the corpse stays at %.1f, %.1f "
                    + "(otherwise its death packet fails and clients keep the zombie standing)", x, y, oldX, oldY));
            moveTo(zombie, oldX, oldY);
            mSquareFromPosition.invoke(zombie);
        }
        if (mGetCurrentSquare.invoke(zombie) != mGetMovingSquare.invoke(zombie)) {
            mSetMovingSquareNow.invoke(zombie);
        }
    }

    /** Server: z below the floor counts as the floor, and the zombie gets its square back. */
    public static void ensureFloor(Object zombie) throws Exception {
        if (((Float) mGetZ.invoke(zombie)).floatValue() < 0.0f) {
            mSetZ.invoke(zombie, Float.valueOf(0.0f));
            mSquareFromPosition.invoke(zombie);
        } else if (mGetCurrentSquare.invoke(zombie) == null) {
            mSquareFromPosition.invoke(zombie);
        }
    }

    public static void moveTo(Object zombie, float x, float y) throws Exception {
        mSetLastX.invoke(zombie, mSetNextX.invoke(zombie, mSetX.invoke(zombie, Float.valueOf(x))));
        mSetLastY.invoke(zombie, mSetNextY.invoke(zombie, mSetY.invoke(zombie, Float.valueOf(y))));
    }

    // ================================================================ driver's client

    /** Client: a zombie was hit by a vehicle our player is driving; wait for the body to land. */
    public static void onLocalHit(Object character, Object vehicle) {
        if (character == null || vehicle == null || !enabled()) {
            return;
        }
        try {
            init(character.getClass().getClassLoader());
            if (!fClient.getBoolean(null) || !zombieClass.isInstance(character)) {
                return;
            }
            Object driver = mGetDriver.invoke(vehicle);
            if (driver == null || !playerClass.isInstance(driver)
                    || !((Boolean) mIsLocalPlayer.invoke(driver)).booleanValue()) {
                return;
            }
            Flying f = FLYING.get(character);
            if (f == null) {
                f = new Flying();
                FLYING.put(character, f);
            }
            f.driver = driver;
            f.since = System.nanoTime();
            anyFlying = true;
        } catch (Throwable t) {
            fail("the local vehicle hit", t);
        }
    }

    /** Client, entry to {@code ZombieOnGroundState.enter}: body landed, point to the server. */
    public static void onLanded(Object zombie) {
        if (!anyFlying || zombie == null) {
            return;
        }
        Flying f;
        synchronized (FLYING) {
            f = FLYING.remove(zombie);
            anyFlying = !FLYING.isEmpty();
        }
        if (f == null || broken) {
            return;
        }
        try {
            if (!fClient.getBoolean(null) || System.nanoTime() - f.since > FLYING_NANOS) {
                return;
            }
            short id = ((Short) mGetOnlineID.invoke(zombie)).shortValue();
            if (id < 0) {
                return;
            }
            float x = ((Float) mGetX.invoke(zombie)).floatValue();
            float y = ((Float) mGetY.invoke(zombie)).floatValue();
            float angleDeg = (float) Math.toDegrees(((Float) mAnimAngle.invoke(zombie)).floatValue());
            send(zombie, f.driver, id, x, y, angleDeg);
            reported++;
            if (logged < 12) {
                logged++;
                Log.debug(String.format(java.util.Locale.ROOT,
                        "[LabVehiclePhysics] corpse sync: zombie %d landed at (%.2f, %.2f) - landing point sent to the server",
                        (int) id, x, y));
            }
        } catch (Throwable t) {
            fail("the landing report", t);
        }
    }

    public static void send(Object zombie, Object driver, short id, float x, float y, float angleDeg) throws Exception {
        ServerTable.init();
        if (mSendClientCommand == null) {
            Class<?> gc = Class.forName("zombie.network.GameClient", false, zombie.getClass().getClassLoader());
            gameClient = gc.getField("instance").get(null);
            mSendClientCommand = gc.getMethod("sendClientCommand", playerClass, String.class, String.class, ServerTable.kahluaTable);
        }
        Object args = ServerTable.mNewTable.invoke(ServerTable.platform);
        ServerTable.mRawset.invoke(args, "id", Double.valueOf(id));
        ServerTable.mRawset.invoke(args, "x", Double.valueOf(x));
        ServerTable.mRawset.invoke(args, "y", Double.valueOf(y));
        ServerTable.mRawset.invoke(args, "a", Double.valueOf(angleDeg));
        mSendClientCommand.invoke(gameClient, driver, MODULE, COMMAND, args);
    }

    // ================================================================ common

    /** A summary every 15 seconds, only if something happened. */
    public static void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < REPORT_NANOS) {
            return;
        }
        lastReportNanos = now;
        long done = landed + landedAlive;
        Log.debug(String.format(java.util.Locale.ROOT,
                "[LabVehiclePhysics] corpse sync, last 15 s: held %d, landing points %d (dead %d, alive %d, "
                        + "the server lagged behind the body by %.1f tiles on average), finished by the owner's on-ground update %d, "
                        + "driver left %d, safety timeout %d, landing point without a square %d",
                held, done, landed, landedAlive, done > 0 ? offsetSum / done : 0.0, onGround, ownerLost, timeouts, noSquare));
        held = 0L;
        landed = 0L;
        landedAlive = 0L;
        noSquare = 0L;
        timeouts = 0L;
        onGround = 0L;
        ownerLost = 0L;
        offsetSum = 0.0;
    }

    public static void fail(String where, Throwable t) {
        broken = true;
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        Log.info("[LabVehiclePhysics] ERROR in corpse sync (" + where + "), disabling: " + cause + TreeBreak.where(cause));
    }
}
