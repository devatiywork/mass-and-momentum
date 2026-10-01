package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Наезд на животное: общая физика для торможения машины и урона животному.
 *
 * <h2>Что было в ванили</h2>
 * У животных своя копия кода столкновения ({@code IsoAnimal.testCollideWithVehicles} →
 * {@code BaseVehicle.hitAnimal}), и все наши патчи для зомби её не касаются.
 * <pre>
 * // BaseVehicle.hitAnimal — каждый кадр контакта
 * applyImpulseFromHitObject(chr, getFudgedMass() * 7 * min(speed, 15) / 15 * |dot|);
 * // IsoAnimal.testCollideWithVehicles — тем же кадром ещё один, ни на что не влияющий
 * applyImpulseFromHitObject(this, 1.0F);
 * // IsoAnimal.Hit — в одиночной игре; в сети то же делает сервер (VehicleHitField.process)
 * setHealth(0.0F);
 * </pre>
 * Импульс пропорционален массе машины и потом на неё же делится — масса сокращается,
 * а веса животного в формуле нет вовсе. Кот тормозил двенадцатитонный броневик так же,
 * как корова — легковушку. И любое касание убивало: корова умирала от наезда на 5 км/ч.
 *
 * <h2>Как теперь</h2>
 * Один удар — один обмен импульсом, как на этапах 1.6 и 1.7 для зомби. Животное массы m
 * получает от машины массы M скорость
 * <pre>
 *   dv_животного = v * (1 + e) * M / (M + m)
 *   p            = v * (1 + e) * M * m / (M + m)     — столько же теряет машина
 * </pre>
 * где v — скорость машины в сторону животного, e — упругость удара. Кошка у броневика
 * забирает ничтожную долю, корова у легковушки — треть скорости.
 *
 * Урон — от той же dv животного, в долях здоровья (у животных оно от 0 до 1):
 * <pre>
 *   урон = (dv / dv_смертельная)^2,   dv_смертельная = K * m^P
 * </pre>
 * Это игровая модель, а не медицина. K и P подобраны по двум опорным точкам:
 * курица 4 кг гибнет от 3 м/с (11 км/ч), корова 700 кг — от 12.5 м/с (45 км/ч).
 * Корова переживает наезд легковушки на 10 км/ч (3% здоровья), курица — нет.
 *
 * Вес животного — {@code getData().getWeight()}, в игре он в килограммах: курица 2–6,
 * свинья до 350, корова до 1300.
 */
public final class AnimalImpact {

    /** Упругость: животное отлетает чуть быстрее машины, а не прилипает к бамперу. */
    public static final float RESTITUTION = 0.2f;
    /**
     * Сколько от записанного в очередь импульса доходит до машины за одно применение.
     * Игра прикладывает очередь как силу x30 ровно на один шаг Bullet в 0.01 с
     * (WorldSimulation.updatePhysic → applyAccumulatedImpulsesFromHitObjectsToPhysics
     * перед каждым stepSimulation(0.01F, 0, 0)), то есть 30 * 0.01 = 0.3.
     */
    public static final float APPLIED_FRACTION = 0.3f;
    /** Ниже этой скорости удара нет — как в ванили (speed < 0.05). */
    public static final float MIN_SPEED = 0.05f;
    /** Предел читаемой скорости, тот же, что у зомби (Patch_onHitByVehicle.REAL_SPEED_MAX). */
    public static final float SPEED_MAX = 40.0f;
    /** Пауза в контакте, после которой удар считается новым, — как у зомби (Patch_impulseBudget). */
    public static final long RESET_NANOS = 400_000_000L;
    /** dv_смертельная = K * m^P, м/с. */
    public static final float LETHAL_K = 2.05f;
    public static final float LETHAL_P = 0.276f;
    public static final float LETHAL_MIN = 1.0f;
    /** Если вес прочитать не удалось. Сообщение о нём — один раз. */
    public static final float FALLBACK_WEIGHT = 50.0f;

    public static volatile boolean broken = false;
    public static Class<?> animalClass;
    public static Method aGetData;
    public static Method dGetWeight;
    public static Method aGetCurrentState;
    public static Method aGetAnimalType;
    public static Method aGetHealth;
    public static Method aSetHealth;
    public static Method aIsOnFloor;
    public static Method aSetIsRoadKill;
    public static Method oGetX;
    public static Method oGetY;
    public static Object falldownState;
    public static Object onGroundState;
    public static Method vGetFudgedMass;
    public static Method vGetLinearVelocity;
    public static Object velOut;
    public static Field vx;
    public static Field vz;
    public static boolean weightWarned = false;

    /** Эпизод контакта с одним животным: время последнего касания и что уже выдано. */
    public static final class Episode {
        public long last;
        public boolean braked;
        public boolean damaged;
    }

    public static final Map<Object, Episode> EPISODES = new WeakHashMap<Object, Episode>();

    private AnimalImpact() {
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (animalClass != null) {
            return;
        }
        Class<?> a = Class.forName("zombie.characters.animals.IsoAnimal", false, cl);
        aGetData = a.getMethod("getData");
        dGetWeight = aGetData.getReturnType().getMethod("getWeight");
        aGetCurrentState = a.getMethod("getCurrentState");
        aGetAnimalType = a.getMethod("getAnimalType");
        aGetHealth = a.getMethod("getHealth");
        aSetHealth = a.getMethod("setHealth", float.class);
        aIsOnFloor = a.getMethod("isOnFloor");
        aSetIsRoadKill = a.getMethod("setIsRoadKill", boolean.class);
        // Координаты — у общего предка машины и животного: одним и тем же методом читаем обоих.
        Class<?> mo = Class.forName("zombie.iso.IsoMovingObject", false, cl);
        oGetX = mo.getMethod("getX");
        oGetY = mo.getMethod("getY");
        falldownState = Class.forName("zombie.ai.states.animals.AnimalFalldownState", true, cl)
                .getMethod("instance").invoke(null);
        onGroundState = Class.forName("zombie.ai.states.animals.AnimalOnGroundState", true, cl)
                .getMethod("instance").invoke(null);
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        vGetFudgedMass = bv.getMethod("getFudgedMass");
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        vGetLinearVelocity = bv.getMethod("getLinearVelocity", v3);
        velOut = v3.getConstructor().newInstance();
        vx = v3.getField("x");
        vz = v3.getField("z");
        animalClass = a;
    }

    public static boolean isAnimal(Object o) {
        return o != null && animalClass != null && animalClass.isInstance(o);
    }

    public static float weightOf(Object animal) {
        try {
            Object data = aGetData.invoke(animal);
            if (data != null) {
                float w = ((Float) dGetWeight.invoke(data)).floatValue();
                if (w > 0.0f) {
                    return w;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!weightWarned) {
            weightWarned = true;
            Log.info("[LabVehiclePhysics] animal hit: could not read an animal's weight - using "
                    + FALLBACK_WEIGHT + " kg for it");
        }
        return FALLBACK_WEIGHT;
    }

    public static String typeOf(Object animal) {
        try {
            Object t = aGetAnimalType.invoke(animal);
            return t != null ? t.toString() : "animal";
        } catch (Throwable t) {
            return "animal";
        }
    }

    /** Упало или лежит. Такое ваниль больше не толкает, и мы тоже. */
    public static boolean isDown(Object animal) throws Exception {
        Object s = aGetCurrentState.invoke(animal);
        return s == falldownState || s == onGroundState;
    }

    public static float fudgedMass(Object vehicle) throws Exception {
        return ((Float) vGetFudgedMass.invoke(vehicle)).floatValue();
    }

    /**
     * Скорость машины в сторону животного, из физики, без ванильного потолка 15.
     * 0, если машина от него удаляется. Только там, где есть физика: у клиента и в
     * одиночной игре. На сервере Bullet нет — там скорость берётся из пакета.
     *
     * Ось Z скорости в Bullet соответствует оси Y мира — так их сопоставляет и сама
     * игра в hitAnimal: velocity.dot((dx, 0, dy)).
     */
    public static float closingSpeed(Object vehicle, Object animal) throws Exception {
        float dx = ((Float) oGetX.invoke(animal)).floatValue() - ((Float) oGetX.invoke(vehicle)).floatValue();
        float dy = ((Float) oGetY.invoke(animal)).floatValue() - ((Float) oGetY.invoke(vehicle)).floatValue();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 1.0e-4f)) {
            return 0.0f;
        }
        Object out = velOut;
        vGetLinearVelocity.invoke(vehicle, out);
        float vn = (vx.getFloat(out) * dx + vz.getFloat(out) * dy) / len;
        if (!(vn > 0.0f)) {
            return 0.0f;
        }
        return vn > SPEED_MAX ? SPEED_MAX : vn;
    }

    /** Скорость, которую получает животное. */
    public static float animalDeltaV(float vehicleMass, float animalMass, float v) {
        if (!(vehicleMass > 0.0f) || !(animalMass > 0.0f) || !(v > 0.0f)) {
            return 0.0f;
        }
        return v * (1.0f + RESTITUTION) * vehicleMass / (vehicleMass + animalMass);
    }

    /** Импульс, которым обмениваются машина и животное. */
    public static float momentum(float vehicleMass, float animalMass, float v) {
        return animalMass * animalDeltaV(vehicleMass, animalMass, v);
    }

    public static float lethalDeltaV(float animalMass) {
        float d = LETHAL_K * (float) Math.pow(Math.max(animalMass, 0.001f), LETHAL_P);
        return d < LETHAL_MIN ? LETHAL_MIN : d;
    }

    /** Урон в долях здоровья, от 0 до 1. */
    public static float damage(float vehicleMass, float animalMass, float v) {
        float dv = animalDeltaV(vehicleMass, animalMass, v);
        float r = dv / lethalDeltaV(animalMass);
        float d = r * r;
        return d > 1.0f ? 1.0f : d;
    }

    /**
     * Отметить касание и вернуть эпизод. Касание после паузы длиннее RESET_NANOS —
     * новый удар: машина отъехала и ударила снова.
     */
    public static Episode touch(Object animal) {
        long now = System.nanoTime();
        synchronized (EPISODES) {
            Episode ep = EPISODES.get(animal);
            if (ep == null) {
                ep = new Episode();
                EPISODES.put(animal, ep);
            } else if (now - ep.last > RESET_NANOS) {
                ep.braked = false;
                ep.damaged = false;
            }
            ep.last = now;
            return ep;
        }
    }

    /**
     * Отбросить животное от машины: направление — от машины к животному, по нему идёт
     * импульс удара; скорость — сколько придала машина. Зовётся там, где животное считают.
     */
    public static void throwAway(Object vehicle, Object animal, float vehicleMass, float animalMass, float v)
            throws Exception {
        float dx = ((Float) oGetX.invoke(animal)).floatValue() - ((Float) oGetX.invoke(vehicle)).floatValue();
        float dy = ((Float) oGetY.invoke(animal)).floatValue() - ((Float) oGetY.invoke(vehicle)).floatValue();
        AnimalThrow.launch(animal, dx, dy, animalDeltaV(vehicleMass, animalMass, v));
    }

    public static float kmh(float tilesPerSecond) {
        return tilesPerSecond * 3.6f;
    }
}
