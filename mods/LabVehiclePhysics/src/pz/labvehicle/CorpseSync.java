package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Труп там, где упало тело, — в мультиплеере (пункт 6.1).
 *
 * <h2>Как было</h2>
 * У каждого зомби в сети есть хозяин — клиент, который сообщает серверу позицию и здоровье
 * зомби; сервер ему верит. Как только хозяин сообщил «мёртв», сервер сразу создаёт труп в
 * текущей точке — почти на месте удара:
 * <pre>
 * // NetworkZombiePacker.parseZombie
 * this.applyZombie(zombie);
 * if (zombie.isDead()) zombie.die();
 * </pre>
 * А рэгдолл у водителя ещё летит. Когда тело ложится, клиент получает серверный труп и, если
 * клетка другая, переносит тело туда ({@code DeadCharacterPacket.processClient}) — прыжок назад.
 *
 * <h2>Как теперь</h2>
 * <ol>
 *   <li>Сервер, удар машиной, до урона ({@code VehicleHitField.process}): водитель становится
 *       хозяином зомби ({@code NetworkZombieManager.moveZombie} — мёртвого игра не передаёт,
 *       поэтому до урона), зомби помечен «летит».</li>
 *   <li>Сервер, {@code NetworkZombieManager.updateAuth}: пока летит, хозяина не меняем.</li>
 *   <li>Сервер, {@code die()}: пока летит, смерть откладывается.</li>
 *   <li>Клиент водителя, {@code ZombieOnGroundState.enter}: тело легло — точку и направление
 *       серверу командой {@code zombieLanded}.</li>
 *   <li>Сервер: точку прислал водитель — зомби переносится туда и, если мёртв, умирает. Труп
 *       создаётся в точке приземления, пакет смерти у всех с этими координатами, прыжка нет.</li>
 * </ol>
 *
 * <h2>Ожидание снимается, когда тело легло, — не по часам</h2>
 * Тело может долго ехать на капоте. Поэтому ждём не фиксированное время, а пока:
 * <ul>
 *   <li>не пришла точка приземления;</li>
 *   <li>водитель в обычном сообщении хозяина не сообщил {@code realState == OnGround} — позиция
 *       в том же сообщении и есть точка приземления, сервер применяет её до {@code die()};</li>
 *   <li>у зомби не пропал хозяин — водитель вышел;</li>
 *   <li>не прошло {@link #PENDING_NANOS} — только от вечного ожидания: если клиент водителя
 *       выгрузил зомби, от него не придёт ни точки, ни «лежит», и мёртвый зомби без трупа
 *       висел бы на сервере до перезапуска.</li>
 * </ul>
 *
 * <h2>Расстояние точки не проверяется</h2>
 * Защиты такая проверка не даёт: хозяин зомби и так может сообщить серверу любую его позицию,
 * игра её не проверяет ({@code applyZombie}). А вред есть: сервер видит зомби по сообщениям
 * хозяина, а те идут раз в 4 секунды, если рядом нет других игроков, — тело на капоте за это
 * время уезжает на сотню клеток, и настоящую точку пришлось бы отвергнуть. Проверяем только,
 * что точку прислал тот, кто сбил.
 *
 * Клиентскую сторону ожидания трупа трогать не нужно: {@code die()} у клиента повторяется каждый
 * кадр, пока трупа нет ({@code ZombieOnGroundState.execute}), и пакет смерти, пришедший после
 * приземления, обрабатывается сразу; его пятисекундный таймаут отсчитывается от прихода пакета.
 */
public final class CorpseSync {

    /**
     * Сервер: страховка от вечного ожидания — см. описание класса. Не ограничение на полёт:
     * тело кончает лететь раньше — точкой, «лежит» от хозяина или уходом водителя.
     */
    public static final long PENDING_NANOS = 300_000_000_000L;
    /** Клиент водителя: та же страховка — после этого тело уже не наше. */
    public static final long FLYING_NANOS = 300_000_000_000L;
    public static final long REPORT_NANOS = 15_000_000_000L;
    public static final String MODULE = "LabVehiclePhysics";
    public static final String COMMAND = "zombieLanded";

    /** Сервер: сбитый машиной зомби ждёт точку приземления от водителя. */
    public static final class Pending {
        public Object driver;
        public long deadline;
    }

    /** Клиент водителя: кого сбили и кто был за рулём. */
    public static final class Flying {
        public Object driver;
        public long since;
    }

    public static final Map<Object, Pending> PENDING = Collections.synchronizedMap(new WeakHashMap<Object, Pending>());
    public static final Map<Object, Flying> FLYING = Collections.synchronizedMap(new WeakHashMap<Object, Flying>());
    /** Пока никого не ждём — патчи на горячих путях стоят одну проверку флага. */
    public static volatile boolean anyPending = false;
    public static volatile boolean anyFlying = false;
    public static volatile boolean broken = false;

    // ---- рефлексия
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

    // ---- счётчики для строки в логе
    public static int logged = 0;
    public static long held = 0L;
    public static long landed = 0L;
    public static long landedAlive = 0L;
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

    // ================================================================ сервер

    /**
     * Сервер, удар машиной — до урона. Полёт будет, только если удар валит с ног или убивает:
     * рэгдолл у водителя стартует на {@code bDead} или {@code bKnockedDown}. Остальных держать
     * незачем — иначе смерть от чего-то другого в ближайшие секунды тоже ждала бы приземления.
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
                return;     // мёртвого игра другому хозяину не отдаёт
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

    /** Сервер, вход в {@code die()}: true — отложить, тело ещё летит у водителя. */
    public static boolean deferDeath(Object chr) {
        return anyPending && chr != null && stillFlying(chr);
    }

    /** Сервер, вход в {@code NetworkZombieManager.updateAuth}: true — хозяина не трогать. */
    public static boolean holdOwner(Object zombie) {
        return anyPending && zombie != null && stillFlying(zombie);
    }

    /**
     * Сервер: ждём ли ещё этого зомби. Нет — ожидание снимается, дальше ваниль: {@code die()}
     * пройдёт в той точке, где сервер видит зомби сейчас.
     */
    public static boolean stillFlying(Object zombie) {
        Pending p = PENDING.get(zombie);
        if (p == null) {
            return false;
        }
        try {
            String why = null;
            if (fRealState.get(zombie) == onGroundState) {
                // Хозяин-водитель сам сообщил, что тело лежит, — позицию из того же сообщения
                // сервер уже применил, она и есть точка приземления.
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

    /** Сервер: водитель сообщил, где легло тело. {@code args = {id, x, y, a}}, a — градусы. */
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
                return;     // уже труп или выгружен — поздно
            }
            Pending p = PENDING.get(zombie);
            if (p == null) {
                return;     // не наш: ванильный путь
            }
            if (p.driver != player) {
                TreeBreak.warnOnce("corpse-driver", "corpse sync: a landing point came from a player who did not hit the zombie - ignored");
                return;
            }
            // Смещение — только для лога: насколько сервер отставал от тела. Не проверяем —
            // см. описание класса.
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

    /** Перенести зомби на сервере так же, как это делает приём сообщения хозяина ({@code applyZombie}). */
    public static void place(Object zombie, float x, float y, float angleDeg) throws Exception {
        mSetLastX.invoke(zombie, mSetNextX.invoke(zombie, mSetX.invoke(zombie, Float.valueOf(x))));
        mSetLastY.invoke(zombie, mSetNextY.invoke(zombie, mSetY.invoke(zombie, Float.valueOf(y))));
        if (!Float.isNaN(angleDeg)) {
            mSetDirectionAngle.invoke(zombie, Float.valueOf(angleDeg));
        }
        mSquareFromPosition.invoke(zombie);
        if (mGetCurrentSquare.invoke(zombie) != mGetMovingSquare.invoke(zombie)) {
            mSetMovingSquareNow.invoke(zombie);
        }
    }

    // ================================================================ клиент водителя

    /** Клиент: зомби сбила машина, за рулём которой наш игрок, — ждём, когда тело ляжет. */
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

    /** Клиент, вход в {@code ZombieOnGroundState.enter}: тело легло — точку серверу. */
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

    // ================================================================ общее

    /** Сводка раз в 15 секунд, только если что-то происходило. */
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
                        + "driver left %d, safety timeout %d",
                held, done, landed, landedAlive, done > 0 ? offsetSum / done : 0.0, onGround, ownerLost, timeouts));
        held = 0L;
        landed = 0L;
        landedAlive = 0L;
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
