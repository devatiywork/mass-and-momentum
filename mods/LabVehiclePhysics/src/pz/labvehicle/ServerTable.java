package pz.labvehicle;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server table: in multiplayer the server's file defines the vehicle physics.
 *
 * <h2>Why</h2>
 * In multiplayer a vehicle is simulated by the driver's client: the server does not register
 * vehicles in Bullet at all ({@code VehicleScript.Loaded()} calls {@code toBullet()} only when
 * {@code !GameServer.server}). While each client read its own {@code vehicle-physics.cfg},
 * the same vehicle weighed differently depending on who was driving.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>singleplayer: its own file, as before;</li>
 *   <li>server: its own file. On a co-op host it is the host's file: a separate server process,
 *       but the same Zomboid folder. On a dedicated one, the admin's file in its folder;</li>
 *   <li>a multiplayer client does not read its own file at all and takes the server's table. Until
 *       it arrives, only the built-in data and mod author data apply, the same for everyone.</li>
 * </ul>
 * Built-in data and author data are not sent over the network: they live in mods, and the server
 * already imposes its mod list on the client.
 *
 * <h2>How it travels</h2>
 * Via the stock mod commands: the client asks ({@code sendClientCommand}), the server answers
 * ({@code sendServerCommand}) and broadcasts again when the file changes. Lua here is only the
 * postman: {@code LabVehiclePhysics_ServerTable.lua} in {@code client/} and {@code server/}.
 * It reaches Java through {@link LabVehiclePhysicsNet}.
 *
 * The table arrives only after the world has loaded, and there is no way to get it earlier:
 * the command can only be sent from in game. So on arrival the scripts are reapplied, and the
 * vehicles that have already appeared are updated; see {@link VehicleCfg#refreshVehicles()}.
 *
 * The file goes out line by line, not whole: a string in a game packet is prefixed with its length
 * as a {@code short} ({@code GameWindow.StringUTF}), so a string is at most 32 KB.
 */
public final class ServerTable {

    /** Table format. Bump it only when the meaning of the fields changes. */
    public static final int PROTOCOL = 1;
    public static final int MAX_LINES = 5000;
    public static final int MAX_LINE_CHARS = 1000;
    /** The game's packet buffer is 1 000 000 bytes (UdpConnection); we keep a wide margin. */
    public static final int MAX_TOTAL_CHARS = 256 * 1024;

    public static volatile boolean broken = false;
    public static Field fClient;
    public static Field fIngame;
    public static Field fEnv;
    public static Object platform;
    public static Method mNewTable;
    public static Method mRawset;
    public static Method mRawget;
    public static Class<?> kahluaTable;

    /** Rules of the last accepted table. An empty list until something arrives. */
    public static volatile List<VehicleCfg.Rule> rules = Collections.<VehicleCfg.Rule>emptyList();
    public static volatile boolean received = false;
    /** Grows on each new table and on dropping the old one; VehicleCfg spots changes by it. */
    public static int generation = 0;
    public static double stamp = -1.0;
    public static List<String> lines = Collections.<String>emptyList();
    /**
     * The Lua environment the table arrived in. A new connection recreates the environment
     * ({@code LuaManager.init()}: {@code env = platform.newEnvironment()}), so a previous server's
     * table is recognized by it and does not survive into the next one. A weak reference, so as
     * not to keep the whole Lua state of the old session in memory.
     */
    public static WeakReference<Object> receivedIn = new WeakReference<Object>(null);
    public static final Set<String> WARNED = new HashSet<String>();

    private ServerTable() {
    }

    /** Network flags. Separate from Lua: the mode is queried every frame and needs no Lua. */
    public static void initNet() throws Exception {
        if (fClient != null) {
            return;
        }
        Class<?> gc = Class.forName("zombie.network.GameClient");
        fIngame = gc.getField("ingame");
        fClient = gc.getField("client");
    }

    public static void init() throws Exception {
        initNet();
        if (mRawget != null) {
            return;
        }
        Class<?> lm = Class.forName("zombie.Lua.LuaManager");
        fEnv = lm.getField("env");
        platform = lm.getField("platform").get(null);
        mNewTable = platform.getClass().getMethod("newTable");
        kahluaTable = Class.forName("se.krka.kahlua.vm.KahluaTable");
        mRawset = kahluaTable.getMethod("rawset", Object.class, Object.class);
        mRawget = kahluaTable.getMethod("rawget", Object.class);
    }

    /**
     * A multiplayer client, including the co-op host's own client: it also connects
     * to its own server and gets the table from there, like everyone else.
     */
    public static boolean isMpClient() {
        if (broken) {
            return false;
        }
        try {
            initNet();
            return fClient.getBoolean(null);
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] server table: cannot tell single player from multiplayer ("
                    + t + ") - the local vehicle-physics.cfg applies everywhere");
            return false;
        }
    }

    /**
     * Whether we can already send to the server.
     *
     * {@code sendClientCommand} goes out only with {@code GameClient.ingame}, and the flag
     * is set in {@code IngameState.UpdateStuff()}, only after {@code OnGameStart}. Before that
     * the command silently takes the singleplayer path and never reaches the server.
     */
    public static boolean clientReady() {
        try {
            return isMpClient() && fIngame.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- server

    /** Version number of our own file: it grows with every change to the file. */
    public static double serverStamp() {
        VehicleCfg.reloadIfNeeded();
        return VehicleCfg.playerStamp;
    }

    /**
     * The table to send to clients: {@code {v, present, stamp, count, lines = {...}}}.
     * Called from Lua, i.e. on the main thread, so touching Kahlua here is safe.
     *
     * @return null if it could not be built; Lua then sends nothing
     */
    public static Object build() {
        try {
            init();
            VehicleCfg.reloadIfNeeded();
            List<String> src = VehicleCfg.playerLines;
            Object out = mNewTable.invoke(platform);
            Object lt = mNewTable.invoke(platform);
            int n = 0;
            int total = 0;
            for (int i = 0; i < src.size(); i++) {
                String s = src.get(i);
                if (s.length() > MAX_LINE_CHARS) {
                    warn("long:" + i, "server table: a line of " + s.length() + " characters in vehicle-physics.cfg"
                            + " is longer than " + MAX_LINE_CHARS + " - not sent to players");
                    continue;
                }
                if (n >= MAX_LINES || total + s.length() > MAX_TOTAL_CHARS) {
                    warn("truncated", "server table: vehicle-physics.cfg is too big to send - only the first "
                            + n + " rule lines go to players");
                    break;
                }
                n++;
                total += s.length();
                mRawset.invoke(lt, Double.valueOf(n), s);
            }
            mRawset.invoke(out, "v", Double.valueOf(PROTOCOL));
            mRawset.invoke(out, "present", Boolean.valueOf(VehicleCfg.playerFilePresent));
            mRawset.invoke(out, "stamp", Double.valueOf(VehicleCfg.playerStamp));
            mRawset.invoke(out, "count", Double.valueOf(n));
            mRawset.invoke(out, "lines", lt);
            return out;
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] server table: could not build it for players: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------- client

    /** Accept the server's table. Called from Lua on the {@code table} command. */
    public static void accept(Object args) {
        try {
            init();
            if (!isMpClient()) {
                warn("not-mp", "server table: arrived outside multiplayer - ignored");
                return;
            }
            if (args == null || !kahluaTable.isInstance(args)) {
                warn("format", "server table: the server sent it in an unknown format - ignored;"
                        + " built-in and mod author data apply, the local vehicle-physics.cfg stays ignored");
                return;
            }
            Object v = mRawget.invoke(args, "v");
            if (v instanceof Double && ((Double) v).doubleValue() > PROTOCOL) {
                warn("newer", "server table: the server runs a newer LabVehiclePhysics (table format "
                        + v + ", this one knows " + PROTOCOL + ") - reading the rule lines anyway");
            }
            List<String> got = new ArrayList<String>();
            Object lt = mRawget.invoke(args, "lines");
            if (lt != null && kahluaTable.isInstance(lt)) {
                for (int i = 1; ; i++) {
                    Object s = mRawget.invoke(lt, Double.valueOf(i));
                    if (s == null) {
                        break;
                    }
                    if (i > MAX_LINES) {
                        warn("too-many", "server table: more than " + MAX_LINES + " lines - the rest ignored");
                        break;
                    }
                    if (s instanceof String && ((String) s).length() <= MAX_LINE_CHARS) {
                        got.add((String) s);
                    }
                }
            }
            boolean present = Boolean.TRUE.equals(mRawget.invoke(args, "present"));
            Object st = mRawget.invoke(args, "stamp");
            double stampIn = st instanceof Double ? ((Double) st).doubleValue() : -1.0;
            Object env = fEnv.get(null);

            synchronized (ServerTable.class) {
                // The same reply arrives twice when the client's request crossed paths with
                // the broadcast after a file edit. No reason to reapply the scripts for that.
                if (received && receivedIn.get() == env && stampIn == stamp && got.equals(lines)) {
                    return;
                }
            }
            List<VehicleCfg.Rule> parsed = VehicleCfg.parseLines(got, "server", "server table");
            synchronized (ServerTable.class) {
                rules = Collections.unmodifiableList(parsed);
                lines = got;
                stamp = stampIn;
                receivedIn = new WeakReference<Object>(env);
                received = true;
                generation++;
            }
            // Pick it up in the next reloadIfNeeded without waiting out the two-second pause.
            VehicleCfg.lastCheckNanos = 0L;
            Log.info("[LabVehiclePhysics] server table received: " + parsed.size() + " rule(s), version "
                    + VehicleCfg.fmt((float) stampIn)
                    + (present ? "" : " - the server has no vehicle-physics.cfg, built-in and mod author data only"));
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] server table: could not read what the server sent: " + t);
        }
    }

    /**
     * The table generation. Also discards the previous connection's table: if between
     * sessions {@code GameClient.client} was false too briefly for us to see, the mode does
     * not switch, and without this check a new server would start with someone else's numbers.
     */
    public static synchronized int generation() {
        if (received) {
            Object env = null;
            try {
                init();
                env = fEnv.get(null);
            } catch (Throwable ignored) {
            }
            if (env != receivedIn.get()) {
                received = false;
                rules = Collections.<VehicleCfg.Rule>emptyList();
                lines = Collections.<String>emptyList();
                stamp = -1.0;
                generation++;
                Log.info("[LabVehiclePhysics] server table of the previous connection dropped"
                        + " - waiting for this server's table");
            }
        }
        return generation;
    }

    public static void warn(String id, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) {
                return;
            }
        }
        Log.info("[LabVehiclePhysics] " + message);
    }
}
