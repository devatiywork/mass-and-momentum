package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Тяжёлая машина валит деревья — упором или с разгона.
 *
 * <h2>Что было в ванили</h2>
 * Дерево для физики машины — твёрдое препятствие. Врезалась — остановилась; по тому, насколько
 * резко упала скорость, игра бьёт саму машину ({@code crash}) и деревья вокруг
 * ({@code damageObjects} → {@code IsoTree.HitByVehicle}). Урон дереву — 5% от ОСТАВШЕГОСЯ
 * здоровья: оно таяло и не падало даже от пятидесятитонного танка. А упереться и продавить
 * ствол, как бульдозер, нельзя было вовсе: без удара ничего не происходит.
 *
 * <h2>Модель: сила, а не энергия</h2>
 * Первая версия сравнивала энергию удара с прочностью — и танку приходилось разгоняться даже
 * на маленькую ёлку. Деревья валят силой: машина упирается и давит, пока корни не сдадут.
 * <pre>
 *   сила упора  F = 0.7 · M · g           (предел сцепления с грунтом)
 *   с разгона   F · (1 + v / V_REF)       (удар добавляет, но не решает всё)
 *   сопротивление дерева — TREE_KN по размеру 1–8
 * </pre>
 * Сопротивление — порядок величин из опытов по вытягиванию деревьев (опрокидывающий момент
 * у основания, пересчитанный на силу на высоте бампера): молодая ёлка — единицы кН, дерево
 * в 20–25 см — около 25 кН, в 60 см — около 220 кН. Игровые, а не измеренные для PZ числа.
 *
 * Что выходит: танк (52 т, около 360 кН) продавливает любое дерево с места; Bushmaster
 * (11.4 т, 78 кН) — до пятого размера, крупнее — с разгона; легковушка (9 кН) — только
 * саженцы, с разгона — ещё третий размер.
 *
 * <h2>Упор</h2>
 * Каждый кадр машины ({@link #stepPush}): водитель жмёт газ, машина почти стоит, ствол
 * вплотную по ходу. Если силы хватает, дерево падает через {@code PUSH_TIME · Fдерева / Fмашины} —
 * танку на ёлку доля секунды, Bushmaster на пятый размер — около секунды. Не хватает — стоит.
 *
 * <h2>Удар</h2>
 * {@code crash()} ({@link #beforeCrash}) решает до урона: если дерево по ходу ломается, удар
 * отменяется целиком — машина ничего не «встретила», урона нет. Повал — сразу после, в
 * {@code damageObjects} ({@link #afterDamageObjects}). Физика останавливает машину раньше,
 * чем мы узнаём об ударе, поэтому, когда дерево исчезнет, скорость возвращается за вычетом
 * работы на повал: {@code v' = sqrt(v² − 2·Fдерева·BREAK_TRAVEL / M)}.
 *
 * <h2>Повал и сеть</h2>
 * Штатный, как от топора ({@code IsoTree.toppleTree}): дерево исчезает, падают брёвна, у
 * больших остаётся пень. В сети повалить может только сервер ({@code toppleTree} начинается с
 * {@code if (!GameClient.client)}) — клиент водителя шлёт {@code treeHit} с силой, сервер
 * проверяет правдоподобие по своей массе машины и решает по своему дереву ({@link #serverTreeHit}).
 */
public final class TreeBreak {

    /** Коэффициент сцепления с грунтом для силы упора. */
    public static final float PUSH_MU = 0.7f;
    public static final float G = 9.81f;
    /** Скорость, на которой сила удара вдвое больше силы упора, м/с (72 км/ч). */
    public static final float V_REF = 20.0f;
    /** Сопротивление дерева по размеру 1–8, кН. */
    public static final float[] TREE_KN = {2.0f, 5.0f, 12.0f, 25.0f, 45.0f, 80.0f, 130.0f, 220.0f};
    /** Время упора, если сил впритык; с запасом — пропорционально меньше. */
    public static final float PUSH_TIME = 2.0f;
    public static final float MIN_PUSH_TIME = 0.15f;
    /** Быстрее этого — уже не упор, а езда, м/с. */
    public static final float PUSH_MAX_SPEED = 1.0f;
    /** Пауза в упоре, после которой счёт начинается заново. */
    public static final long PUSH_RESET_NANOS = 400_000_000L;
    /** Ствол вплотную: радиус проверки касания для упора и для удара, клеток. */
    public static final float PUSH_CONTACT = 0.5f;
    public static final float IMPACT_CONTACT = 1.0f;
    /** На сколько надо сдвинуть ствол, чтобы корни сдали, м. Работа на повал = сила × это. */
    public static final float BREAK_TRAVEL = 0.6f;
    public static final float SPEED_MAX = 40.0f;
    /**
     * Сервер: насколько далеко от центра машины может стоять дерево, клеток. Не меньше
     * SERVER_MAX_DIST и не меньше половины длины машины плюс SERVER_SLACK — иначе автобус
     * или грузовик с прицепом упрётся в дерево, а сервер решит, что оно слишком далеко.
     */
    public static final float SERVER_MAX_DIST = 6.0f;
    public static final float SERVER_SLACK = 3.0f;
    public static final long RESTORE_WINDOW_NANOS = 2_000_000_000L;
    /** Не просить сервер про то же дерево чаще этого. */
    public static final long REQUEST_COOLDOWN_NANOS = 1_500_000_000L;
    public static final String MODULE = "LabVehiclePhysics";

    public static volatile boolean broken = false;

    /** Решение, принятое в crash(), исполняется в damageObjects() того же удара. */
    public static Object plannedVehicle;
    public static Object plannedTree;
    public static float plannedForce;
    public static float plannedSpeed;
    public static float plannedDirX;
    public static float plannedDirY;

    /** Упор: какое дерево давим и сколько уже. */
    public static final class Push {
        public Object tree;
        public float seconds;
        public long lastNanos;
    }

    public static final Map<Object, Push> PUSHES = new WeakHashMap<Object, Push>();

    /** Ждём, пока дерево исчезнет, чтобы вернуть машине скорость. */
    public static final class Pending {
        public Object tree;
        public Object square;
        public float dirX;
        public float dirY;
        public float targetSpeed;
        public long deadline;
    }

    public static final Map<Object, Pending> PENDING = new WeakHashMap<Object, Pending>();
    public static volatile boolean anyPending = false;
    /** Дерево -> когда последний раз просили сервер. */
    public static final Map<Object, long[]> REQUESTED = new WeakHashMap<Object, long[]>();

    // ---- рефлексия
    public static Class<?> treeClass;
    public static Field fLastVelocity;
    public static Field fVx;
    public static Field fVz;
    public static Method mFudgedMass;
    public static Method mLinearVelocity;
    public static Method mForward;
    public static Object vec3;
    public static Object vec2;
    public static Method mGetX;
    public static Method mGetY;
    public static Method mGetZ;
    public static Method mTreeSize;
    public static Method mTopple;
    public static Method mObjSquare;
    public static Method mSquareObjects;
    public static Method mGetDriver;
    public static Method mIsLocalPlayer;
    public static Method mGetController;
    public static Method mIsGas;
    public static Method mIsGasR;
    public static Method mGetScript;
    public static Method mGetExtents;
    public static Method mGetCell;
    public static Method mCellSquare;
    public static Method mTestCollision;
    public static Method mApplyGeneric;
    public static Class<?> playerClass;
    public static Method mPlayerVehicle;
    public static Field fClient;

    public static int logged = 0;
    public static int pushLogged = 0;

    private TreeBreak() {
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (mApplyGeneric != null) {
            return;
        }
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> io = Class.forName("zombie.iso.IsoObject", false, cl);
        treeClass = Class.forName("zombie.iso.objects.IsoTree", false, cl);
        fLastVelocity = bv.getDeclaredField("lastLinearVelocity");
        fLastVelocity.setAccessible(true);
        Class<?> v3 = fLastVelocity.getType();
        fVx = v3.getField("x");
        fVz = v3.getField("z");
        vec3 = v3.getConstructor().newInstance();
        Class<?> v2 = Class.forName("zombie.iso.Vector2", false, cl);
        vec2 = v2.getConstructor().newInstance();
        mFudgedMass = bv.getMethod("getFudgedMass");
        mLinearVelocity = bv.getMethod("getLinearVelocity", v3);
        mForward = bv.getMethod("getForwardVector", v3);
        mGetX = io.getMethod("getX");
        mGetY = io.getMethod("getY");
        mGetZ = io.getMethod("getZ");
        mTreeSize = treeClass.getMethod("getSize");
        Class<?> chr = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
        playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
        mTopple = treeClass.getMethod("toppleTree", chr);
        mObjSquare = io.getMethod("getSquare");
        mSquareObjects = mObjSquare.getReturnType().getMethod("getObjects");
        mGetDriver = bv.getMethod("getDriver");
        mIsLocalPlayer = playerClass.getMethod("isLocalPlayer");
        mGetController = bv.getMethod("getController");
        mIsGas = mGetController.getReturnType().getMethod("isGas");
        mIsGasR = mGetController.getReturnType().getMethod("isGasR");
        mGetScript = bv.getMethod("getScript");
        mGetExtents = mGetScript.getReturnType().getMethod("getExtents");
        mGetCell = io.getMethod("getCell");
        mCellSquare = mGetCell.getReturnType().getMethod("getGridSquare", int.class, int.class, int.class);
        mTestCollision = bv.getMethod("testCollisionWithObject", io, float.class, v2);
        mPlayerVehicle = chr.getMethod("getVehicle");
        fClient = Class.forName("zombie.network.GameClient", false, cl).getField("client");
        mApplyGeneric = bv.getMethod("applyImpulseGeneric", float.class, float.class, float.class,
                float.class, float.class, float.class, float.class);
        Log.debug("[LabVehiclePhysics] tree breaking ready: push force 0.7*M*g against tree resistance "
                + "2..220 kN by size, a run-up adds (1 + v/" + VehicleCfg.fmt(V_REF) + ")");
    }

    // ================================================================ модель

    public static float pushForce(float mass) {
        return PUSH_MU * mass * G;
    }

    public static float impactForce(float mass, float speed) {
        return pushForce(mass) * (1.0f + Math.min(speed, SPEED_MAX) / V_REF);
    }

    public static float resistance(Object tree) throws Exception {
        int size = ((Integer) mTreeSize.invoke(tree)).intValue();
        int i = Math.max(1, Math.min(size, TREE_KN.length)) - 1;
        return TREE_KN[i] * 1000.0f;
    }

    // ================================================================ удар

    /**
     * Вход в BaseVehicle.crash(): если по ходу стоит дерево, которое этот удар валит, —
     * отменить удар целиком (без урона машине) и запланировать повал.
     *
     * @return true — пропустить crash()
     */
    public static boolean beforeCrash(Object vehicle) {
        plannedVehicle = null;
        if (!LabGate.active() || broken || vehicle == null || !LabSettings.trees()) {
            return false;
        }
        try {
            init(vehicle.getClass().getClassLoader());
            Object last = fLastVelocity.get(vehicle);
            float vx = fVx.getFloat(last);
            float vz = fVz.getFloat(last);
            float speed = (float) Math.sqrt(vx * vx + vz * vz);
            if (!(speed > 0.5f)) {
                return false;
            }
            // Ось Z скорости в Bullet — это Y мира (так их сопоставляет и сама игра).
            float dirX = vx / speed;
            float dirY = vz / speed;
            Object tree = treeAhead(vehicle, dirX, dirY, IMPACT_CONTACT);
            if (tree == null) {
                return false;
            }
            float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
            float force = impactForce(mass, speed);
            float resist = resistance(tree);
            if (force < resist) {
                log(vehicle, tree, mass, speed, force, resist, false, "run-up");
                return false;
            }
            // Удар отменяем при каждом касании ломающегося дерева, а повал просим не чаще
            // кулдауна: пока сервер отвечает, машина успевает ткнуться в ствол ещё не раз.
            if (claim(tree)) {
                log(vehicle, tree, mass, speed, force, resist, true, "run-up");
                plannedVehicle = vehicle;
                plannedTree = tree;
                plannedForce = force;
                plannedSpeed = speed;
                plannedDirX = dirX;
                plannedDirY = dirY;
            }
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** Выход из BaseVehicle.damageObjects(): исполнить повал, решённый в crash(). */
    public static void afterDamageObjects(Object vehicle) {
        if (plannedVehicle == null || plannedVehicle != vehicle) {
            return;
        }
        Object tree = plannedTree;
        plannedVehicle = null;
        plannedTree = null;
        try {
            float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
            float work = resistance(tree) * BREAK_TRAVEL;
            float left = plannedSpeed * plannedSpeed - 2.0f * work / Math.max(mass, 1.0f);
            fell(vehicle, tree, plannedForce);
            Pending p = new Pending();
            p.tree = tree;
            p.square = mObjSquare.invoke(tree);
            p.dirX = plannedDirX;
            p.dirY = plannedDirY;
            p.targetSpeed = left > 0.0f ? (float) Math.sqrt(left) : 0.0f;
            p.deadline = System.nanoTime() + RESTORE_WINDOW_NANOS;
            synchronized (PENDING) {
                PENDING.put(vehicle, p);
                anyPending = true;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================ упор

    /**
     * Каждый кадр машины: водитель жмёт газ, машина почти стоит, ствол вплотную — давим.
     * Считаем только у своего водителя: чужую машину в сети толкает её клиент.
     */
    public static void stepPush(Object vehicle) {
        if (broken || vehicle == null || !LabGate.active() || !LabSettings.trees()) {
            return;
        }
        try {
            init(vehicle.getClass().getClassLoader());
            Object driver = mGetDriver.invoke(vehicle);
            if (driver == null || !playerClass.isInstance(driver)
                    || !((Boolean) mIsLocalPlayer.invoke(driver)).booleanValue()) {
                return;
            }
            Object ctrl = mGetController.invoke(vehicle);
            if (ctrl == null) {
                return;
            }
            boolean fwd = ((Boolean) mIsGas.invoke(ctrl)).booleanValue();
            boolean back = !fwd && ((Boolean) mIsGasR.invoke(ctrl)).booleanValue();
            long now = System.nanoTime();
            Push push;
            synchronized (PUSHES) {
                push = PUSHES.get(vehicle);
            }
            if (!fwd && !back) {
                expire(vehicle, push, now);
                return;
            }
            mLinearVelocity.invoke(vehicle, vec3);
            float vx = fVx.getFloat(vec3);
            float vz = fVz.getFloat(vec3);
            if (vx * vx + vz * vz > PUSH_MAX_SPEED * PUSH_MAX_SPEED) {
                expire(vehicle, push, now);
                return;
            }
            mForward.invoke(vehicle, vec3);
            float fx = fVx.getFloat(vec3);
            float fz = fVz.getFloat(vec3);
            float flen = (float) Math.sqrt(fx * fx + fz * fz);
            if (!(flen > 1.0e-4f)) {
                return;
            }
            float dirX = (back ? -fx : fx) / flen;
            float dirY = (back ? -fz : fz) / flen;
            Object tree = treeAhead(vehicle, dirX, dirY, PUSH_CONTACT);
            if (tree == null) {
                expire(vehicle, push, now);
                return;
            }
            float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
            float force = pushForce(mass);
            float resist = resistance(tree);
            if (force < resist) {
                if (pushLogged < 4) {
                    pushLogged++;
                    log(vehicle, tree, mass, 0.0f, force, resist, false, "push");
                }
                return;
            }
            if (push == null || push.tree != tree || now - push.lastNanos > PUSH_RESET_NANOS) {
                push = new Push();
                push.tree = tree;
                push.lastNanos = now;
                synchronized (PUSHES) {
                    PUSHES.put(vehicle, push);
                }
            }
            float dt = Math.min((now - push.lastNanos) / 1.0e9f, 0.1f);
            push.lastNanos = now;
            push.seconds += dt;
            float need = Math.max(MIN_PUSH_TIME, PUSH_TIME * resist / force);
            if (push.seconds >= need) {
                synchronized (PUSHES) {
                    PUSHES.remove(vehicle);
                }
                if (claim(tree)) {
                    log(vehicle, tree, mass, 0.0f, force, resist, true, String.format("push %.1f s", push.seconds));
                    fell(vehicle, tree, force);
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void expire(Object vehicle, Push push, long now) {
        if (push != null && now - push.lastNanos > PUSH_RESET_NANOS) {
            synchronized (PUSHES) {
                PUSHES.remove(vehicle);
            }
        }
    }

    // ================================================================ общее

    /**
     * Ствол вплотную по ходу: из деревьев, которых машина касается (штатная проверка игры
     * testCollisionWithObject), — стоящее впереди и ближе всех. Стоящие сбоку не в счёт.
     */
    public static Object treeAhead(Object vehicle, float dirX, float dirY, float contact) throws Exception {
        float x = ((Float) mGetX.invoke(vehicle)).floatValue();
        float y = ((Float) mGetY.invoke(vehicle)).floatValue();
        int z = (int) Math.floor(((Float) mGetZ.invoke(vehicle)).floatValue());
        Object ext = mGetExtents.invoke(mGetScript.invoke(vehicle));
        float half = Math.max(fVx.getFloat(ext), fVz.getFloat(ext)) / 2.0f;
        int r = (int) Math.ceil(half + contact + 1.0f);
        Object cell = mGetCell.invoke(vehicle);
        Object best = null;
        float bestScore = -1.0e9f;
        for (int yy = -r; yy <= r; yy++) {
            for (int xx = -r; xx <= r; xx++) {
                Object sq = mCellSquare.invoke(cell, Integer.valueOf((int) Math.floor(x) + xx),
                        Integer.valueOf((int) Math.floor(y) + yy), Integer.valueOf(z));
                if (sq == null) {
                    continue;
                }
                List<?> objects = (List<?>) mSquareObjects.invoke(sq);
                for (int i = 0; i < objects.size(); i++) {
                    Object o = objects.get(i);
                    if (!treeClass.isInstance(o)) {
                        continue;
                    }
                    if (mTestCollision.invoke(vehicle, o, Float.valueOf(contact), vec2) == null) {
                        continue;
                    }
                    float dx = ((Float) mGetX.invoke(o)).floatValue() + 0.5f - x;
                    float dy = ((Float) mGetY.invoke(o)).floatValue() + 0.5f - y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (!(dist > 1.0e-3f)) {
                        continue;
                    }
                    float ahead = (dx * dirX + dy * dirY) / dist;
                    if (ahead < 0.3f) {
                        continue;
                    }
                    float score = ahead - 0.15f * dist;
                    if (score > bestScore) {
                        bestScore = score;
                        best = o;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Можно ли сейчас валить это дерево: про него не просили последние REQUEST_COOLDOWN_NANOS.
     * Если можно — отмечает, что просим сейчас.
     */
    public static boolean claim(Object tree) {
        long now = System.nanoTime();
        synchronized (REQUESTED) {
            long[] last = REQUESTED.get(tree);
            if (last != null && now - last[0] < REQUEST_COOLDOWN_NANOS) {
                return false;
            }
            REQUESTED.put(tree, new long[] {now});
            return true;
        }
    }

    /** Повалить: в одиночной игре сами, в сети просим сервер. Кулдаун проверяет вызывающий ({@link #claim}). */
    public static void fell(Object vehicle, Object tree, float force) throws Exception {
        if (fClient.getBoolean(null)) {
            sendTreeHit(vehicle, tree, force);
        } else {
            mTopple.invoke(tree, mGetDriver.invoke(vehicle));
        }
    }

    public static void sendTreeHit(Object vehicle, Object tree, float force) throws Exception {
        Object driver = mGetDriver.invoke(vehicle);
        if (driver == null || !playerClass.isInstance(driver)) {
            return;
        }
        ServerTable.init();
        Object args = ServerTable.mNewTable.invoke(ServerTable.platform);
        ServerTable.mRawset.invoke(args, "x", Double.valueOf(Math.floor(((Float) mGetX.invoke(tree)).floatValue())));
        ServerTable.mRawset.invoke(args, "y", Double.valueOf(Math.floor(((Float) mGetY.invoke(tree)).floatValue())));
        ServerTable.mRawset.invoke(args, "z", Double.valueOf(Math.floor(((Float) mGetZ.invoke(tree)).floatValue())));
        ServerTable.mRawset.invoke(args, "force", Double.valueOf(force));
        Class<?> gc = Class.forName("zombie.network.GameClient", false, vehicle.getClass().getClassLoader());
        Object client = gc.getField("instance").get(null);
        Method send = gc.getMethod("sendClientCommand", playerClass, String.class, String.class, ServerTable.kahluaTable);
        send.invoke(client, driver, MODULE, "treeHit", args);
    }

    /**
     * Каждый кадр машины: вернуть скорость, когда дерево, сломанное ударом, исчезло.
     * Пока никто не ждёт — одна проверка флага.
     */
    public static void stepPending(Object vehicle) {
        if (!anyPending || vehicle == null) {
            return;
        }
        Pending p;
        synchronized (PENDING) {
            p = PENDING.get(vehicle);
        }
        if (p == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            List<?> objects = p.square != null ? (List<?>) mSquareObjects.invoke(p.square) : null;
            boolean gone = objects == null || !objects.contains(p.tree);
            if (!gone) {
                if (now > p.deadline) {
                    drop(vehicle);
                }
                return;
            }
            drop(vehicle);
            mLinearVelocity.invoke(vehicle, vec3);
            float along = fVx.getFloat(vec3) * p.dirX + fVz.getFloat(vec3) * p.dirY;
            float dv = p.targetSpeed - along;
            if (!(dv > 0.1f)) {
                return;
            }
            float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
            // Очередь импульсов доходит до машины на 0.3 (сила ×30 на один шаг Bullet в 0.01 с).
            float strength = mass * dv / AnimalImpact.APPLIED_FRACTION;
            float x = ((Float) mGetX.invoke(vehicle)).floatValue();
            float y = ((Float) mGetY.invoke(vehicle)).floatValue();
            float z = ((Float) mGetZ.invoke(vehicle)).floatValue();
            // applyImpulseGeneric принимает направление в осях мира (x, y, высота) и сам
            // переставляет их в оси Bullet; точка приложения — центр машины, без закрутки.
            mApplyGeneric.invoke(vehicle, Float.valueOf(x), Float.valueOf(y), Float.valueOf(z),
                    Float.valueOf(p.dirX), Float.valueOf(p.dirY), Float.valueOf(0.0f), Float.valueOf(strength));
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void drop(Object vehicle) {
        synchronized (PENDING) {
            PENDING.remove(vehicle);
            anyPending = !PENDING.isEmpty();
        }
    }

    public static void log(Object vehicle, Object tree, float mass, float speed, float force, float resist,
                           boolean breaks, String how) throws Exception {
        if (logged >= 16) {
            return;
        }
        logged++;
        Log.debug(String.format(
                "[LabVehiclePhysics] tree (%s): size %d tree resists %.0f kN, a %.0f kg vehicle%s gives %.0f kN -> %s",
                how, ((Integer) mTreeSize.invoke(tree)).intValue(), resist / 1000.0f, mass,
                speed > 0.0f ? String.format(" at %.0f km/h", speed * 3.6f) : "", force / 1000.0f,
                breaks ? "FELLED" + (fClient.getBoolean(null) ? " (asked the server)" : "") : "holds"));
    }

    // ================================================================ сервер

    /**
     * Сервер: клиент водителя сообщил, что валит дерево. Проверяем правдоподобие по своей массе
     * машины и решаем по своему дереву.
     */
    public static void serverTreeHit(Object player, Object args) {
        if (!LabGate.active() || broken || player == null || args == null) {
            return;
        }
        if (!LabSettings.trees()) {
            warnOnce("off", "tree felling request ignored: trees are switched off in this server's sandbox settings");
            return;
        }
        try {
            init(player.getClass().getClassLoader());
            ServerTable.init();
            if (!ServerTable.kahluaTable.isInstance(args)) {
                return;
            }
            int x = (int) number(args, "x");
            int y = (int) number(args, "y");
            int z = (int) number(args, "z");
            float force = (float) number(args, "force");
            Object vehicle = mPlayerVehicle.invoke(player);
            if (vehicle == null) {
                return;
            }
            Object sq = mCellSquare.invoke(mGetCell.invoke(vehicle), Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
            if (sq == null) {
                return;
            }
            // Только по индексу: у списка объектов клетки (PZArrayList) iterator() бросает
            // UnsupportedOperationException.
            Object tree = null;
            List<?> objects = (List<?>) mSquareObjects.invoke(sq);
            for (int i = 0; i < objects.size(); i++) {
                if (treeClass.isInstance(objects.get(i))) {
                    tree = objects.get(i);
                    break;
                }
            }
            if (tree == null) {
                return;
            }
            float dx = x + 0.5f - ((Float) mGetX.invoke(vehicle)).floatValue();
            float dy = y + 0.5f - ((Float) mGetY.invoke(vehicle)).floatValue();
            Object ext = mGetExtents.invoke(mGetScript.invoke(vehicle));
            float half = Math.max(fVx.getFloat(ext), fVz.getFloat(ext)) / 2.0f;
            float maxDist = Math.max(SERVER_MAX_DIST, half + SERVER_SLACK);
            if (dx * dx + dy * dy > maxDist * maxDist) {
                warnOnce("far", String.format("tree felling rejected: the tree is %.1f tiles from the vehicle, the limit is %.1f",
                        (float) Math.sqrt(dx * dx + dy * dy), maxDist));
                return;
            }
            float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
            if (!(force >= 0.0f) || force > impactForce(mass, SPEED_MAX) * 1.05f) {
                warnOnce("force", "tree felling rejected: impossible force for this vehicle");
                return;
            }
            float resist = resistance(tree);
            boolean breaks = force >= resist;
            if (breaks) {
                mTopple.invoke(tree, player);
            }
            if (logged < 16) {
                logged++;
                Log.debug(String.format(
                        "[LabVehiclePhysics] tree (server): size %d tree resists %.0f kN, %.0f kN from a %.0f kg vehicle -> %s",
                        ((Integer) mTreeSize.invoke(tree)).intValue(), resist / 1000.0f, force / 1000.0f, mass,
                        breaks ? "FELLED" : "holds"));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static double number(Object table, String key) throws Exception {
        Object v = ServerTable.mRawget.invoke(table, key);
        return v instanceof Double ? ((Double) v).doubleValue() : Double.NaN;
    }

    public static final java.util.Set<String> WARNED = new java.util.HashSet<String>();

    public static void warnOnce(String id, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) {
                return;
            }
        }
        Log.info("[LabVehiclePhysics] " + message);
    }

    public static void fail(Throwable t) {
        broken = true;
        // Исключение из метода игры приходит обёрнутым в InvocationTargetException — нужна причина.
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        Log.info("[LabVehiclePhysics] ERROR in tree breaking, disabling: " + cause + where(cause));
    }

    /** Где упало: верхний кадр стека и, если он не наш, первый кадр нашего кода. */
    public static String where(Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        if (st == null || st.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" at ").append(st[0]);
        if (!st[0].getClassName().startsWith("pz.labvehicle.")) {
            for (int i = 1; i < st.length; i++) {
                if (st[i].getClassName().startsWith("pz.labvehicle.")) {
                    sb.append(", called from ").append(st[i]);
                    break;
                }
            }
        }
        return sb.toString();
    }
}
