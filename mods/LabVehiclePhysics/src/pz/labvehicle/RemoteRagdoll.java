package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Рэгдолл сбитого зомби у клиента, который не хозяин тела (пункт 6.2).
 *
 * Полёт тела в сети не передаётся: каждый клиент, у которого машина рядом, сам считает удар и
 * сам запускает рэгдолл. Проверка двумя клиентами показала у того, кто смотрит, две беды.
 *
 * <h2>1. Рывки</h2>
 * Хозяин зомби — водитель — пять раз в секунду шлёт позицию своего рэгдолла, а клиент
 * наблюдателя на каждое сообщение прокладывает к ней путь, поворачивает тело по её направлению
 * и телепортирует, если разошлось больше чем на 3 клетки ({@code NetworkZombieAI.parse}). Два
 * полёта не совпадают, и тело мечется между своим рэгдоллом и чужим: шаг следования у
 * наблюдателя 0.7–1.6 клетки за кадр против 0.1 у водителя. Теперь, пока у клиента идёт свой
 * рэгдолл, позиции хозяина к телу не применяются; потом — снова как в ванили.
 *
 * <h2>2. Вечный полёт</h2>
 * Пока тело касается машины, симуляция продлевается каждый кадр ({@code RagdollController}:
 * {@code isContactingVehicle → simulationTimeout = 1.5}), а касание машины с чужим водителем игра
 * не проверяет вовсе:
 * <pre>
 * // BaseVehicle.isCollided
 * if (GameClient.client &amp;&amp; getDriver() != null &amp;&amp; !getDriver().isLocal()) return true;
 * </pre>
 * Рэгдолл наблюдателя не кончался, пока водитель за рулём, даже если машина уехала, и труп
 * приходил по пятисекундному таймауту — скачком. Теперь касание проверяется той же геометрией,
 * что для своей машины. {@code isCollided} зовёт только {@code RagdollController} — больше ничего
 * это не задевает.
 *
 * <h2>Замер</h2>
 * Два полёта всё равно кончаются в разных точках, и тело у наблюдателя переносится к трупу,
 * который сервер положил по точке водителя: либо сообщением хозяина после приземления (больше
 * 3 клеток — телепорт), либо пакетом трупа (другая клетка — перенос). Оба переноса считаются.
 */
public final class RemoteRagdoll {

    public static final long REPORT_NANOS = 15_000_000_000L;
    /** Дальше этого {@code NetworkZombieAI.parse} телепортирует, клеток. */
    public static final float TELEPORT_DIST = 3.0f;

    public static volatile boolean broken = false;

    // ---- рефлексия
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

    // ---- счётчики для строки в логе
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
     * Клиент, вход в {@code NetworkZombieAI.parse}: true — не применять сообщение хозяина, у нас
     * идёт свой рэгдолл. Заодно замер: мёртвое тело после приземления, которое сообщение хозяина
     * сейчас телепортирует к точке водителя.
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

    /** Клиент, выход из {@code BaseVehicle.isCollided}: машина с чужим водителем — честная проверка касания. */
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
                return vanilla;     // свой водитель — ваниль уже проверила геометрией
            }
            if (!enabled()) {
                return vanilla;
            }
            // Тот же тест и радиус, что у ванили для своего водителя.
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

    /** Клиент, вход в {@code DeadCharacterPacket.processClient}: насколько тело переносится к трупу сервера. */
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
            // processClient переносит тело, только если клетка другая.
            if ((int) Math.floor(px) != (int) Math.floor(cx) || (int) Math.floor(py) != (int) Math.floor(cy)) {
                corpseMoved++;
            }
            report();
        } catch (Throwable t) {
            fail("the corpse packet", t);
        }
    }

    /** Сводка раз в 15 секунд, только если что-то происходило. */
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
