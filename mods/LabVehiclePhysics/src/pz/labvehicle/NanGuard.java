package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * NaN tripwire for vehicle and player coordinates.
 *
 * Incident of 30.09.2026 (42.21, test server): the driver of an ambulance Bushmaster drove into
 * an old pile of skeletons at the Louisville checkpoint, in Viewpoint third-person view. Three
 * seconds later, on the SERVER, the vehicle copy and the player itself got NaN coordinates;
 * the client drove on as if nothing had happened. On exit the NaN went into players.db and
 * vehicles.db (SQLite writes NaN as NULL), and on the next login the server put the player at
 * (0,0), off the edge of the map: loading hung while the server generated the world around zero.
 *
 * The server does not compute vehicle coordinates itself. {@code VehiclePhysicsPacket.processServer}
 * writes x, y, z, rotation and velocity from the driver's packet as is, without a single check:
 * <pre>
 * vehicle.setX(this.x); vehicle.setY(this.y); vehicle.setZ(this.z);
 * vehicle.savedRot.set(qx, qy, qz, qw); vehicle.jniTransform.origin.set(...);
 * vehicle.jniLinearVelocity.set(vx, vy, vz);
 * </pre>
 * So the NaN most likely came in a packet from the client. The cause has not been found; the
 * tripwire is there to find it. Four points:
 *
 * 1. A NaN written to setX/setY of any IsoMovingObject: a line with the stack showing WHO wrote it
 *    (Patch_nanWriteX/Y). Log only, the first {@link #MAX_STACKS} times.
 * 2. Every vehicle update (from Patch_vehicleFloor) and player update (Patch_nanPlayer): we keep
 *    the last finite position; if it turns NaN, we log the context and restore the last good
 *    one. On the client the restore goes through setWorldTransform → Bullet.teleportVehicle.
 * 3. Client, vehicle physics packet assembly (Patch_nanPacketOut): NaN in the packet is replaced
 *    by the last good position, velocity by zero. The server never receives a poisoned packet.
 * 4. Server, packet reception (Patch_nanPacketIn): a packet with NaN is not applied at all.
 *
 * All of this runs only with the safety gate open. The {@link LabGate#active()} check comes only
 * once a NaN is found: setX runs thousands of times per frame, so the fast path must stay lean.
 */
public final class NanGuard {

    private NanGuard() {
    }

    /** How many times per session to log the stack of a NaN write. */
    public static final int MAX_STACKS = 5;
    /** Detailed lines per session for each kind of event; after that, counters only. */
    public static final int MAX_DETAILED = 10;
    /** Stack depth in a log line. */
    public static final int STACK_FRAMES = 14;
    /** Radius around the last good point in which corpses and zombies are counted. */
    public static final int AROUND = 2;

    public static volatile boolean broken = false;
    public static volatile boolean ready = false;

    public static Method mGetX, mGetY, mGetZ, mSetX, mSetY, mSetZ, mSetLastX, mSetLastY, mSetLastZ;
    public static Method mSetNextX, mSetNextY;
    public static Method mGetWorldTransform, mSetWorldTransform, mTransformSet, mGetRotation;
    public static Method mVehicleId, mScriptName, mDriver, mSpeed, mMaxPassengers, mGetCharacter;
    public static Method mUsername, mIsLocal, mGetVehicle, mPacketVehicle;
    public static Method mGetCell, mGetSquare, mDeadBodies, mMovingObjects;
    public static Field fOrigin, fVx, fVy, fVz, fQx, fQy, fQz, fQw, fJniVelocity, fWorld;
    public static Field fServer, fClient;
    public static Field pX, pY, pZ, pQx, pQy, pQz, pQw, pVx, pVy, pVz;
    public static Class<?> transformClass, quaternionClass, zombieClass;
    public static Object scratchT, scratchQ;
    public static boolean viewpoint = false;

    /** The vehicle's last finite state. */
    public static final class Good {
        public Object transform;
        public float x, y, z;
        public long nanos;
    }

    public static final Map<Object, Good> VEHICLES = new WeakHashMap<Object, Good>();
    public static final Map<Object, float[]> PLAYERS = new WeakHashMap<Object, float[]>();

    public static long writes = 0L, vehicleFixes = 0L, vehicleLost = 0L, playerFixes = 0L;
    public static long packetsCleaned = 0L, packetsDropped = 0L;
    public static int stacks = 0, vehicleLines = 0, playerLines = 0, packetLines = 0;
    public static long lastReportNanos = 0L;

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (ready) {
            return;
        }
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        mGetX = mo.getMethod("getX");
        mGetY = mo.getMethod("getY");
        mGetZ = mo.getMethod("getZ");
        mSetX = mo.getMethod("setX", float.class);
        mSetY = mo.getMethod("setY", float.class);
        mSetZ = mo.getMethod("setZ", float.class);
        mSetLastX = mo.getMethod("setLastX", float.class);
        mSetLastY = mo.getMethod("setLastY", float.class);
        mSetLastZ = mo.getMethod("setLastZ", float.class);
        mSetNextX = mo.getMethod("setNextX", float.class);
        mSetNextY = mo.getMethod("setNextY", float.class);

        transformClass = Class.forName("zombie.core.physics.Transform", false, cl);
        quaternionClass = Class.forName("org.joml.Quaternionf", false, cl);
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        fOrigin = transformClass.getField("origin");
        mTransformSet = transformClass.getMethod("set", transformClass);
        mGetRotation = transformClass.getMethod("getRotation", quaternionClass);
        fVx = v3.getField("x");
        fVy = v3.getField("y");
        fVz = v3.getField("z");
        fQx = quaternionClass.getField("x");
        fQy = quaternionClass.getField("y");
        fQz = quaternionClass.getField("z");
        fQw = quaternionClass.getField("w");
        scratchT = transformClass.getDeclaredConstructor().newInstance();
        scratchQ = quaternionClass.getDeclaredConstructor().newInstance();

        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        mGetWorldTransform = bv.getMethod("getWorldTransform", transformClass);
        mSetWorldTransform = bv.getMethod("setWorldTransform", transformClass);
        mVehicleId = bv.getMethod("getId");
        mScriptName = bv.getMethod("getScriptName");
        mDriver = bv.getMethod("getDriver");
        mSpeed = bv.getMethod("getCurrentSpeedKmHour");
        mMaxPassengers = bv.getMethod("getMaxPassengers");
        mGetCharacter = bv.getMethod("getCharacter", int.class);
        fJniVelocity = bv.getField("jniLinearVelocity");

        Class<?> player = Class.forName("zombie.characters.IsoPlayer", false, cl);
        mUsername = player.getMethod("getUsername");
        mIsLocal = player.getMethod("isLocalPlayer");
        mGetVehicle = Class.forName("zombie.characters.IsoGameCharacter", false, cl).getMethod("getVehicle");
        zombieClass = Class.forName("zombie.characters.IsoZombie", false, cl);

        // The packet fields are declared in VehicleInterpolationData, which the packet extends.
        Class<?> data = Class.forName("zombie.vehicles.VehicleInterpolationData", false, cl);
        pX = open(data, "x");
        pY = open(data, "y");
        pZ = open(data, "z");
        pQx = open(data, "qx");
        pQy = open(data, "qy");
        pQz = open(data, "qz");
        pQw = open(data, "qw");
        pVx = open(data, "vx");
        pVy = open(data, "vy");
        pVz = open(data, "vz");
        mPacketVehicle = Class.forName("zombie.network.packets.vehicle.VehiclePhysicsPacket", false, cl)
                .getMethod("getVehicle");

        Class<?> world = Class.forName("zombie.iso.IsoWorld", false, cl);
        fWorld = world.getField("instance");
        mGetCell = world.getMethod("getCell");
        Class<?> cell = Class.forName("zombie.iso.IsoCell", false, cl);
        mGetSquare = cell.getMethod("getGridSquare", int.class, int.class, int.class);
        Class<?> square = Class.forName("zombie.iso.IsoGridSquare", false, cl);
        mDeadBodies = square.getMethod("getDeadBodys");
        mMovingObjects = square.getMethod("getMovingObjects");

        fServer = Class.forName("zombie.network.GameServer", false, cl).getField("server");
        fClient = Class.forName("zombie.network.GameClient", false, cl).getField("client");
        try {
            Class.forName("viewpoint.FP", false, cl);
            viewpoint = true;
        } catch (Throwable ignored) {
            viewpoint = false;
        }
        ready = true;
        Log.debug("[LabVehiclePhysics] NaN tripwire ready: NaN writes to x/y are traced, vehicles and players "
                + "return to their last finite position, vehicle physics packets with NaN are cleaned (client) or dropped (server)");
    }

    private static Field open(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ================================================================ 1. who wrote the NaN

    /** From Patch_nanWriteX/Y: setX/setY received a non-number. Log only. */
    public static void badWrite(Object self, String axis, float value) {
        writes++;
        if (broken || stacks >= MAX_STACKS || !LabGate.active()) {
            return;
        }
        stacks++;
        try {
            if (!ready) {
                init(self.getClass().getClassLoader());
            }
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            // [0] getStackTrace, [1] badWrite, [2] setX/setY with the inlined advice; callers follow.
            for (int i = 2; i < st.length && i < 2 + STACK_FRAMES; i++) {
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(st[i].getClassName().replace("zombie.", "")).append('.').append(st[i].getMethodName())
                        .append(':').append(st[i].getLineNumber());
            }
            Log.info("[LabVehiclePhysics] NaN tripwire (" + side() + "): " + axis + "(" + value + ") on "
                    + who(self) + " | written by: " + sb);
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================ 2. vehicle and player

    /** From Patch_vehicleFloor on every vehicle update, client and server. */
    public static void vehicle(Object vehicle) {
        if (broken || vehicle == null) {
            return;
        }
        try {
            if (!ready) {
                init(vehicle.getClass().getClassLoader());
            }
            Object t = scratchT;
            mGetWorldTransform.invoke(vehicle, t);
            Object o = fOrigin.get(t);
            mGetRotation.invoke(t, scratchQ);
            Object vel = fJniVelocity.get(vehicle);
            float x = ((Float) mGetX.invoke(vehicle)).floatValue();
            float y = ((Float) mGetY.invoke(vehicle)).floatValue();
            float z = ((Float) mGetZ.invoke(vehicle)).floatValue();
            boolean ok = finite(fVx.getFloat(o)) && finite(fVy.getFloat(o)) && finite(fVz.getFloat(o))
                    && finite(fQx.getFloat(scratchQ)) && finite(fQy.getFloat(scratchQ))
                    && finite(fQz.getFloat(scratchQ)) && finite(fQw.getFloat(scratchQ))
                    && finite(x) && finite(y) && finite(z)
                    && finite(fVx.getFloat(vel)) && finite(fVy.getFloat(vel)) && finite(fVz.getFloat(vel));
            Good g;
            synchronized (VEHICLES) {
                g = VEHICLES.get(vehicle);
            }
            if (ok) {
                if (g == null) {
                    g = new Good();
                    g.transform = transformClass.getDeclaredConstructor().newInstance();
                    synchronized (VEHICLES) {
                        VEHICLES.put(vehicle, g);
                    }
                }
                mTransformSet.invoke(g.transform, t);
                g.x = x;
                g.y = y;
                g.z = z;
                g.nanos = System.nanoTime();
                return;
            }
            if (!LabGate.active()) {
                return;
            }
            String state = String.format("origin (%s, %s, %s), rotation (%s, %s, %s, %s), position (%s, %s, %s), velocity (%s, %s, %s)",
                    fVx.getFloat(o), fVy.getFloat(o), fVz.getFloat(o),
                    fQx.getFloat(scratchQ), fQy.getFloat(scratchQ), fQz.getFloat(scratchQ), fQw.getFloat(scratchQ),
                    x, y, z, fVx.getFloat(vel), fVy.getFloat(vel), fVz.getFloat(vel));
            if (g == null) {
                vehicleLost++;
                if (vehicleLines < MAX_DETAILED) {
                    vehicleLines++;
                    Log.info("[LabVehiclePhysics] NaN tripwire (" + side() + "): " + who(vehicle)
                            + " has no finite state to return to - " + state);
                }
                return;
            }
            // Restore: transform (on the client also Bullet.teleportVehicle), coordinates, zero velocity.
            mSetWorldTransform.invoke(vehicle, g.transform);
            fVx.setFloat(vel, 0.0f);
            fVy.setFloat(vel, 0.0f);
            fVz.setFloat(vel, 0.0f);
            place(vehicle, g.x, g.y, g.z);
            int seatsFixed = fixOccupants(vehicle, g.x, g.y, g.z);
            vehicleFixes++;
            if (vehicleLines < MAX_DETAILED) {
                vehicleLines++;
                Log.info(String.format(
                        "[LabVehiclePhysics] NaN tripwire (%s): %s went non-finite %.0f ms after its last good state at (%.1f, %.1f, %.1f) - %s; "
                        + "driver %s, %s; returned to the last good state, occupants fixed %d",
                        side(), who(vehicle), (System.nanoTime() - g.nanos) / 1e6, g.x, g.y, g.z, state,
                        who(mDriver.invoke(vehicle)), around(g.x, g.y, g.z), seatsFixed));
            }
            report();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** From Patch_nanPlayer on every player update. */
    public static void player(Object player) {
        if (broken || player == null || !LabGate.active()) {
            return;
        }
        try {
            if (!ready) {
                init(player.getClass().getClassLoader());
            }
            float x = ((Float) mGetX.invoke(player)).floatValue();
            float y = ((Float) mGetY.invoke(player)).floatValue();
            float z = ((Float) mGetZ.invoke(player)).floatValue();
            float[] last;
            synchronized (PLAYERS) {
                last = PLAYERS.get(player);
            }
            if (finite(x) && finite(y) && finite(z)) {
                if (last == null) {
                    last = new float[3];
                    synchronized (PLAYERS) {
                        PLAYERS.put(player, last);
                    }
                }
                last[0] = x;
                last[1] = y;
                last[2] = z;
                return;
            }
            if (last == null) {
                return;
            }
            place(player, last[0], last[1], last[2]);
            playerFixes++;
            if (playerLines < MAX_DETAILED) {
                playerLines++;
                Object v = mGetVehicle.invoke(player);
                Log.info(String.format(
                        "[LabVehiclePhysics] NaN tripwire (%s): %s position became (%s, %s, %s), %s; returned to (%.1f, %.1f, %.1f); %s",
                        side(), who(player), x, y, z, v == null ? "on foot" : "in " + who(v),
                        last[0], last[1], last[2], around(last[0], last[1], last[2])));
            }
            report();
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================ 3–4. vehicle physics packet

    /** Client: packet built ({@code VehiclePhysicsPacket.set}). Replaces NaN with the last good state. */
    public static void packetOut(Object packet) {
        if (broken || packet == null) {
            return;
        }
        try {
            if (!ready) {
                init(packet.getClass().getClassLoader());
            }
            if (packetFinite(packet) || !LabGate.active()) {
                return;
            }
            Object vehicle = mPacketVehicle.invoke(packet);
            String state = packetState(packet);
            Good g;
            synchronized (VEHICLES) {
                g = vehicle == null ? null : VEHICLES.get(vehicle);
            }
            if (g != null) {
                mGetRotation.invoke(g.transform, scratchQ);
                pX.setFloat(packet, g.x);
                pY.setFloat(packet, g.y);
                pZ.setFloat(packet, g.z);
                pQx.setFloat(packet, fQx.getFloat(scratchQ));
                pQy.setFloat(packet, fQy.getFloat(scratchQ));
                pQz.setFloat(packet, fQz.getFloat(scratchQ));
                pQw.setFloat(packet, fQw.getFloat(scratchQ));
                pVx.setFloat(packet, 0.0f);
                pVy.setFloat(packet, 0.0f);
                pVz.setFloat(packet, 0.0f);
            }
            packetsCleaned++;
            if (packetLines < MAX_DETAILED) {
                packetLines++;
                Log.info("[LabVehiclePhysics] NaN tripwire (" + side() + "): outgoing physics packet of "
                        + who(vehicle) + " had " + state + (g != null
                                ? String.format(" - replaced with the last good state (%.1f, %.1f, %.1f)", g.x, g.y, g.z)
                                : " - no good state known, sent as is"));
            }
            report();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Server: a packet arrived ({@code processServer}). @return true: do not apply it. */
    public static boolean packetIn(Object packet) {
        if (broken || packet == null) {
            return false;
        }
        try {
            if (!ready) {
                init(packet.getClass().getClassLoader());
            }
            if (packetFinite(packet) || !LabGate.active()) {
                return false;
            }
            packetsDropped++;
            if (packetLines < MAX_DETAILED) {
                packetLines++;
                Object vehicle = mPacketVehicle.invoke(packet);
                Log.info("[LabVehiclePhysics] NaN tripwire (" + side() + "): incoming physics packet for "
                        + who(vehicle) + " driven by " + who(vehicle == null ? null : mDriver.invoke(vehicle))
                        + " had " + packetState(packet) + " - dropped, the server keeps the last good state");
            }
            report();
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public static boolean packetFinite(Object p) throws Exception {
        return finite(pX.getFloat(p)) && finite(pY.getFloat(p)) && finite(pZ.getFloat(p))
                && finite(pQx.getFloat(p)) && finite(pQy.getFloat(p)) && finite(pQz.getFloat(p)) && finite(pQw.getFloat(p))
                && finite(pVx.getFloat(p)) && finite(pVy.getFloat(p)) && finite(pVz.getFloat(p));
    }

    public static String packetState(Object p) throws Exception {
        return String.format("position (%s, %s, %s), rotation (%s, %s, %s, %s), velocity (%s, %s, %s)",
                pX.getFloat(p), pY.getFloat(p), pZ.getFloat(p),
                pQx.getFloat(p), pQy.getFloat(p), pQz.getFloat(p), pQw.getFloat(p),
                pVx.getFloat(p), pVy.getFloat(p), pVz.getFloat(p));
    }

    // ================================================================ helpers

    public static void place(Object obj, float x, float y, float z) throws Exception {
        mSetX.invoke(obj, Float.valueOf(x));
        mSetY.invoke(obj, Float.valueOf(y));
        mSetZ.invoke(obj, Float.valueOf(z));
        mSetLastX.invoke(obj, Float.valueOf(x));
        mSetLastY.invoke(obj, Float.valueOf(y));
        mSetLastZ.invoke(obj, Float.valueOf(z));
        mSetNextX.invoke(obj, Float.valueOf(x));
        mSetNextY.invoke(obj, Float.valueOf(y));
    }

    /** Occupants with a non-number for a position get the vehicle's position. */
    public static int fixOccupants(Object vehicle, float x, float y, float z) throws Exception {
        int fixed = 0;
        int seats = ((Integer) mMaxPassengers.invoke(vehicle)).intValue();
        for (int i = 0; i < seats; i++) {
            Object chr = mGetCharacter.invoke(vehicle, Integer.valueOf(i));
            if (chr == null) {
                continue;
            }
            float cx = ((Float) mGetX.invoke(chr)).floatValue();
            float cy = ((Float) mGetY.invoke(chr)).floatValue();
            if (!finite(cx) || !finite(cy)) {
                place(chr, x, y, z);
                fixed++;
            }
        }
        return fixed;
    }

    /** What lies and walks near the point: this count tests the pile-of-bodies hypothesis. */
    public static String around(float x, float y, float z) {
        try {
            Object cell = mGetCell.invoke(fWorld.get(null));
            int bodies = 0, zombies = 0, squares = 0;
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                for (int dy = -AROUND; dy <= AROUND; dy++) {
                    Object sq = mGetSquare.invoke(cell, Integer.valueOf((int) x + dx), Integer.valueOf((int) y + dy),
                            Integer.valueOf((int) z));
                    if (sq == null) {
                        continue;
                    }
                    squares++;
                    Object dead = mDeadBodies.invoke(sq);
                    if (dead instanceof List) {
                        bodies += ((List<?>) dead).size();
                    }
                    Object moving = mMovingObjects.invoke(sq);
                    if (moving instanceof List) {
                        List<?> list = (List<?>) moving;
                        for (int i = 0; i < list.size(); i++) {
                            if (zombieClass.isInstance(list.get(i))) {
                                zombies++;
                            }
                        }
                    }
                }
            }
            int side = AROUND * 2 + 1;
            return String.format("around it (%dx%d squares, %d loaded): corpses %d, zombies %d; Viewpoint %s",
                    side, side, squares, bodies, zombies, viewpoint ? "loaded" : "absent");
        } catch (Throwable t) {
            return "around it: n/a (" + t + ")";
        }
    }

    public static String who(Object obj) {
        if (obj == null) {
            return "nobody";
        }
        try {
            String cls = obj.getClass().getSimpleName();
            if (cls.equals("BaseVehicle")) {
                return "vehicle " + mVehicleId.invoke(obj) + " " + mScriptName.invoke(obj)
                        + String.format(" at %.0f km/h", ((Float) mSpeed.invoke(obj)).floatValue());
            }
            if (cls.equals("IsoPlayer")) {
                return "player " + mUsername.invoke(obj) + (((Boolean) mIsLocal.invoke(obj)).booleanValue() ? " (local)" : " (remote)");
            }
            return cls;
        } catch (Throwable t) {
            return obj.getClass().getSimpleName();
        }
    }

    public static String side() {
        try {
            if (fServer.getBoolean(null)) {
                return "server";
            }
            return fClient.getBoolean(null) ? "client" : "single player";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Summary every 15 s if anything was caught: detailed lines are capped, counters are not. */
    public static void report() {
        long now = System.nanoTime();
        if (lastReportNanos != 0L && now - lastReportNanos < 15_000_000_000L) {
            return;
        }
        lastReportNanos = now;
        Log.info(String.format(
                "[LabVehiclePhysics] NaN tripwire (%s), so far: NaN writes %d, vehicles returned %d (no good state %d), "
                + "players returned %d, packets cleaned %d, dropped %d",
                side(), writes, vehicleFixes, vehicleLost, playerFixes, packetsCleaned, packetsDropped));
    }

    public static void fail(Throwable t) {
        broken = true;
        Log.info("[LabVehiclePhysics] ERROR in the NaN tripwire, disabling it: " + t);
    }
}
