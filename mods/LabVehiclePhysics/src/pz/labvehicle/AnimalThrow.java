package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Отлёт животного после удара машиной — без рэгдолла, скольжением по земле.
 *
 * Рэгдолла у животных в игре нет ({@code IsoAnimal.canRagdoll()} → false, а сам рэгдолл
 * построен под человеческий скелет). Поэтому тело просто скользит по направлению удара со
 * скоростью, которую ему придала машина ({@link AnimalImpact#animalDeltaV}), и тормозится
 * трением.
 *
 * <h2>Как двигаем</h2>
 * Не координатами напрямую — на этом у нас уже пропадали зомби: персонаж, поставленный на
 * клетку без пола, игра молча удаляет. Двигаем штатным путём: {@code IsoMovingObject}
 * в {@code postupdate()} прибавляет {@code impulsex/impulsey} к следующей позиции и режет
 * шаг до одной клетки за кадр. Мы только дописываем туда своё смещение из
 * {@code IsoAnimal.update()} — он идёт раньше postupdate того же кадра.
 *
 * Стены проверяем сами, перед каждым шагом на соседнюю клетку ({@link #blockedAhead}):
 * лежащее животное игра делает несталкиваемым, и движок стены для него не разбирает.
 * Упёрлось — полёт окончен.
 *
 * <h2>Где</h2>
 * Там, где животное считают: в одиночной игре — у себя, в сети — на сервере. Клиенты видят
 * полёт обычной синхронизацией позиции животного. На клиенте в сети реестр пуст: бросок
 * регистрируют только {@link Patch_animalHit} (одиночная игра) и {@link Patch_animalHitServer}.
 *
 * <h2>Убитое животное</h2>
 * Убитое сначала играет анимацию падения, потом в состоянии «лежит» каждый кадр зовёт
 * {@code die()}, и тот превращает его в труп — отдельный объект, который мы уже не двигаем.
 * Чтобы труп родился в конце полёта, а не посередине, пока тело летит, {@code die()}
 * пропускается ({@link Patch_deferCorpse}). В сети труп создаёт сервер, так что он сразу
 * окажется там, где тело остановилось, — без телепорта у клиентов.
 */
public final class AnimalThrow {

    /** Торможение скольжением, клеток/с². Клетка — метр, это около 0.7 g. */
    public static final float DECEL = 7.0f;
    /** Слабее этого — не бросок, а толчок. */
    public static final float MIN_START = 0.5f;
    /** Ниже этого тело считается остановившимся. */
    public static final float STOP_SPEED = 0.2f;
    /** Страховка: полёт и удержание смерти не дольше этого. */
    public static final long MAX_NANOS = 3_000_000_000L;
    /** Кадр длиннее этого считаем за столько: после паузы тело не должно прыгнуть. */
    public static final float MAX_DT = 0.1f;

    public static final class Flight {
        public float dirX;
        public float dirY;
        public float speed;
        public float startSpeed;
        public float travelled;
        public long startNanos;
        public long lastNanos;
    }

    public static final Map<Object, Flight> FLIGHTS = new WeakHashMap<Object, Flight>();
    /** Кто-то летит. Пока нет — патч на update() не стоит ничего, кроме этой проверки. */
    public static volatile boolean anyFlying = false;

    public static volatile boolean broken = false;
    public static Method mGetImpulseX;
    public static Method mSetImpulseX;
    public static Method mGetImpulseY;
    public static Method mSetImpulseY;
    public static Method mGetX;
    public static Method mGetY;
    public static Method mGetZ;
    public static Method mCurrentSquare;
    public static Method mTestCollideAdjacent;
    public static Method mSquareGetCell;
    public static Method mGetGridSquare;
    public static Method mTreatAsSolidFloor;
    public static int launchLogged = 0;
    public static int landLogged = 0;
    public static long launched = 0L;
    public static long landed = 0L;
    public static long wallStops = 0L;
    public static long deathsHeld = 0L;
    public static long lastReportNanos = 0L;

    private AnimalThrow() {
    }

    /**
     * Бросить животное. Направление — от машины к животному (по нему и идёт импульс удара),
     * скорость — сколько придала машина.
     */
    public static void launch(Object animal, float dirX, float dirY, float speed) {
        if (broken || animal == null || !(speed >= MIN_START)) {
            return;
        }
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (!(len > 1.0e-4f)) {
            return;
        }
        long now = System.nanoTime();
        Flight f = new Flight();
        f.dirX = dirX / len;
        f.dirY = dirY / len;
        f.speed = speed;
        f.startSpeed = speed;
        f.startNanos = now;
        f.lastNanos = now;
        synchronized (FLIGHTS) {
            FLIGHTS.put(animal, f);
            anyFlying = true;
        }
        launched++;
        if (launchLogged < 8) {
            launchLogged++;
            float expected = speed * speed / (2.0f * DECEL);
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal thrown: %s at %.0f km/h, slides about %.1f m unless a wall stops it",
                    AnimalImpact.typeOf(animal), AnimalImpact.kmh(speed), expected));
        }
    }

    /** Шаг полёта. Зовётся из IsoAnimal.update() — до postupdate того же кадра. */
    public static void step(Object animal) {
        Flight f;
        synchronized (FLIGHTS) {
            f = FLIGHTS.get(animal);
        }
        if (f == null) {
            return;
        }
        try {
            if (mSetImpulseX == null) {
                init(animal);
            }
            long now = System.nanoTime();
            // Флаг столкновения движка (isCollidedThisFrame) тут не годится: он встаёт и от
            // толчков других персонажей, а в первые кадры тело ещё касается машины — полёт
            // обрывался бы сразу. Стены проверяет blockedAhead на каждом шаге.
            float dt = (now - f.lastNanos) / 1.0e9f;
            f.lastNanos = now;
            if (dt > MAX_DT) {
                dt = MAX_DT;
            }
            // Движок всё равно режет шаг до одной клетки за кадр — считаем так же, иначе
            // пройденное расстояние в логе врало бы на медленном тике сервера.
            float move = Math.min(f.speed * dt, 1.0f);
            if (move > 0.0f) {
                String blocked = blockedAhead(animal, f, move);
                if (blocked != null) {
                    wallStops++;
                    finish(animal, f, now, blocked);
                    return;
                }
                float ix = ((Float) mGetImpulseX.invoke(animal)).floatValue();
                float iy = ((Float) mGetImpulseY.invoke(animal)).floatValue();
                mSetImpulseX.invoke(animal, Float.valueOf(ix + f.dirX * move));
                mSetImpulseY.invoke(animal, Float.valueOf(iy + f.dirY * move));
                f.travelled += move;
            }
            f.speed -= DECEL * dt;
            if (f.speed <= STOP_SPEED || now - f.startNanos > MAX_NANOS) {
                finish(animal, f, now, "came to rest");
            }
        } catch (Throwable t) {
            broken = true;
            synchronized (FLIGHTS) {
                FLIGHTS.clear();
                anyFlying = false;
            }
            Log.info("[LabVehiclePhysics] ERROR in the animal throw, disabling: " + t);
        }
    }

    /**
     * Не упрётся ли шаг в препятствие. Своя проверка, а не только движка: лежащее
     * животное игра делает несталкиваемым ({@code AnimalOnGroundState.enter} →
     * {@code setCollidable(false)}), и тогда {@code postupdate} стены не проверяет вовсе
     * ({@code if (this.collidable) DoCollide(...)}). Мёртвое тело без этой проверки
     * проскользило бы сквозь стену.
     *
     * Проверка та же, что у движка при столкновениях, — {@code IsoGridSquare.testCollideAdjacent}:
     * стены, окна, двери, заборы, сплошные объекты вроде деревьев.
     *
     * @return причина остановки или null, если путь свободен
     */
    public static String blockedAhead(Object animal, Flight f, float move) throws Exception {
        float x = ((Float) mGetX.invoke(animal)).floatValue();
        float y = ((Float) mGetY.invoke(animal)).floatValue();
        int fx = (int) Math.floor(x);
        int fy = (int) Math.floor(y);
        int ox = (int) Math.floor(x + f.dirX * move) - fx;
        int oy = (int) Math.floor(y + f.dirY * move) - fy;
        if (ox == 0 && oy == 0) {
            return null;                    // в пределах своей клетки
        }
        Object sq = mCurrentSquare.invoke(animal);
        if (sq == null) {
            return "lost its square";
        }
        if (((Boolean) mTestCollideAdjacent.invoke(sq, animal, Integer.valueOf(ox), Integer.valueOf(oy),
                Integer.valueOf(0))).booleanValue()) {
            return "stopped by an obstacle";
        }
        int z = (int) Math.floor(((Float) mGetZ.invoke(animal)).floatValue());
        Object next = mGetGridSquare.invoke(mSquareGetCell.invoke(sq), Integer.valueOf(fx + ox),
                Integer.valueOf(fy + oy), Integer.valueOf(z));
        if (next == null) {
            return "stopped at the edge of the loaded world";
        }
        if (z > 0 && !((Boolean) mTreatAsSolidFloor.invoke(next)).booleanValue()) {
            return "stopped at the edge of the floor";
        }
        return null;
    }

    public static void finish(Object animal, Flight f, long now, String how) {
        synchronized (FLIGHTS) {
            FLIGHTS.remove(animal);
            anyFlying = !FLIGHTS.isEmpty();
        }
        landed++;
        if (landLogged < 8) {
            landLogged++;
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal landed: %s %s after %.1f m in %.1f s (thrown at %.0f km/h)",
                    AnimalImpact.typeOf(animal), how, f.travelled, (now - f.startNanos) / 1.0e9f,
                    AnimalImpact.kmh(f.startSpeed)));
        }
        report();
    }

    /**
     * Для die(): не превращать в труп, пока тело летит. Полёт кончится — die() пройдёт
     * на следующем кадре, игра зовёт его каждый кадр, пока мёртвое животное лежит.
     */
    public static boolean holdsDeath(Object chr) {
        if (broken || chr == null) {
            return false;
        }
        Flight f;
        synchronized (FLIGHTS) {
            f = FLIGHTS.get(chr);
        }
        if (f == null) {
            return false;
        }
        if (System.nanoTime() - f.startNanos > MAX_NANOS) {
            return false;
        }
        deathsHeld++;
        return true;
    }

    private static synchronized void init(Object animal) throws Exception {
        if (mSetImpulseX != null) {
            return;
        }
        ClassLoader cl = animal.getClass().getClassLoader();
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        Class<?> gs = Class.forName("zombie.iso.IsoGridSquare", false, cl);
        mGetImpulseX = mo.getMethod("getImpulsex");
        mGetImpulseY = mo.getMethod("getImpulsey");
        mGetX = mo.getMethod("getX");
        mGetY = mo.getMethod("getY");
        mGetZ = mo.getMethod("getZ");
        mCurrentSquare = mo.getMethod("getCurrentSquare");
        mTestCollideAdjacent = gs.getMethod("testCollideAdjacent", mo, int.class, int.class, int.class);
        mSquareGetCell = gs.getMethod("getCell");
        mGetGridSquare = mSquareGetCell.getReturnType().getMethod("getGridSquare", int.class, int.class, int.class);
        mTreatAsSolidFloor = gs.getMethod("TreatAsSolidFloor");
        mSetImpulseY = mo.getMethod("setImpulsey", float.class);
        mSetImpulseX = mo.getMethod("setImpulsex", float.class);
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
        if (launched + landed == 0L) {
            return;
        }
        Log.debug(String.format(
                "[LabVehiclePhysics] animal throws, last 15 s: thrown %d, landed %d (%d against obstacles), death held for %d frames",
                launched, landed, wallStops, deathsHeld));
        launched = 0L;
        landed = 0L;
        wallStops = 0L;
        deathsHeld = 0L;
    }
}
