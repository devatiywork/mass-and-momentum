package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Кусты и деревца: машина теряет энергию на каждом, а не упирается в потолок скорости.
 *
 * <h2>Что было в ванили</h2>
 * Два механизма, и оба не знают массы машины.
 * <pre>
 * // 1. Потолок скорости. BaseVehicle.breakingObjects → updateVelocityMultiplier
 * slowFactor = max по объектам (33 - (10 - CarSlowFactor));      // максимум, не сумма
 * if (speed &gt; 34 - slowFactor) скорость режется до 34 - slowFactor;  // 11 - CarSlowFactor м/с
 *
 * // 2. Импульс каждый кадр контакта. BaseVehicle.checkCollisionWithPlant
 * applyImpulseFromHitPlant(obj, 0.1F);   // -скорость * 0.1 * масса: 3% скорости за кадр
 * </pre>
 * Потолок одинаков для легковушки и танка: куст на пути — и машина мгновенно, без торможения,
 * едет не быстрее 36 км/ч, а то и 4. Импульс масштабирован массой машины и на неё же делится —
 * масса снова сокращается; начисляется каждый кадр, так что на 60 FPS куст забирает
 * половину скорости, и тем больше, чем выше FPS. Разбор — {@code backlog.md} §3f.
 *
 * <h2>Как теперь</h2>
 * Каждый объект при первом контакте забирает у машины фиксированную энергию E — на то, чтобы
 * его смять или сломать:
 * <pre>
 *   v' = sqrt(v^2 - 2 * E / M)
 * </pre>
 * Лёгкая машина в кустах заметно теряет скорость, тяжёлая — почти нет, танк не замечает.
 * Объекты складываются: живая изгородь из пяти кустов — это пять списаний. Потолка больше
 * нет, остаётся общий предел скорости игры. От FPS не зависит: одно списание на контакт.
 *
 * Энергии — игровые, подобраны под ощущение, а не измерены. Первые встречи пишутся в лог
 * со спрайтом и значением CarSlowFactor — по ним и калибровать.
 *
 * Работает там, где считается машина: у клиента водителя и в одиночной игре. На сервере
 * ваниль импульсы машине не прикладывает.
 */
public final class VegetationDrag {

    /** Энергия на единицу CarSlowFactor, Дж. Легковушке на 40 км/ч один куст — около −3 км/ч. */
    public static final float E_PER_SLOW_FACTOR = 4000.0f;
    /** Куст без CarSlowFactor. */
    public static final float E_BUSH = 8000.0f;
    /**
     * Молодое дерево (IsoTree размера 1): малолитражка на 30 км/ч выходит на 19. При 40 кДж
     * она вставала намертво — для деревца перебор. Большие деревья — сплошное препятствие,
     * это другой путь, их не трогаем.
     */
    public static final float E_SMALL_TREE = 20000.0f;
    /**
     * Трава, наземные растения, листва ({@code IsoObject.isGrassLike()}). Почти даром: иначе
     * поле высокой травы, если у неё есть CarSlowFactor, стало бы сотней списаний подряд.
     */
    public static final float E_GRASS_LIKE = 500.0f;
    /** Контакт прервался дольше этого — следующий будет новым: машина отъехала и въехала снова. */
    public static final long RESET_NANOS = 1_000_000_000L;
    /** Импульс из очереди доходит за одно применение на 0.3 — см. AnimalImpact.APPLIED_FRACTION. */
    public static final float APPLIED_FRACTION = AnimalImpact.APPLIED_FRACTION;

    public static volatile boolean broken = false;
    /** Наш собственный вызов applyImpulseFromHitPlant — патч его пропускает. */
    public static volatile boolean ownCall = false;

    /** машина -> (объект -> время последнего касания, нс). Уже списанные объекты. */
    public static final Map<Object, Map<Object, long[]>> CHARGED = new WeakHashMap<Object, Map<Object, long[]>>();

    public static Field fBreakingList;
    public static Field fSlowFactor;
    public static Method mApplyPlant;
    public static Method mFudgedMass;
    public static Method mLinearVelocity;
    public static Object velOut;
    public static Field vx;
    public static Field vz;
    public static Method mGetProperties;
    public static Method mPropsHas;
    public static Method mPropsGet;
    public static Method mIsBush;
    public static Method mIsGrassLike;
    public static Class<?> treeClass;
    public static Method mTreeSize;
    public static Field fSprite;
    public static Field fSpriteName;

    public static int logged = 0;
    public static boolean capLogged = false;
    public static long charges = 0L;
    public static long capsRemoved = 0L;
    public static double speedLostKmh = 0.0;
    public static long lastReportNanos = 0L;

    private VegetationDrag() {
    }

    /** После BaseVehicle.breakingObjects(): снять потолок и списать новые объекты из списка. */
    public static void afterBreakingObjects(Object vehicle) {
        if (!LabGate.active() || broken || vehicle == null || !LabSettings.bushes()) {
            return;
        }
        try {
            init(vehicle);
            float cap = fSlowFactor.getFloat(vehicle);
            if (cap > 0.0f) {
                fSlowFactor.setFloat(vehicle, 0.0f);
                capsRemoved++;
                if (!capLogged) {
                    capLogged = true;
                    Log.debug(String.format(
                            "[LabVehiclePhysics] vegetation: vanilla speed cap removed - it would have held this vehicle at %.0f km/h",
                            (34.0f - cap) * 3.6f));
                }
            }
            List<?> list = (List<?>) fBreakingList.get(vehicle);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Object obj = list.get(i);
                    if (obj != null) {
                        charge(vehicle, obj, "slow-factor object");
                    }
                }
            }
            report();
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the vegetation patch, disabling: " + t);
        }
    }

    /**
     * Вместо ванильного applyImpulseFromHitPlant: списать объект один раз.
     *
     * @return true — пропустить ванильный импульс
     */
    public static boolean onPlantImpulse(Object vehicle, Object obj) {
        if (ownCall) {
            return false;
        }
        if (!LabGate.active() || broken || vehicle == null || obj == null || !LabSettings.bushes()) {
            return false;
        }
        try {
            init(vehicle);
            // Большие деревья идут сюда же — в checkCollisionWithPlant у них общая ветка с
            // кустами. Их не трогаем: без ванильного импульса машина проходила бы сквозь ствол.
            if (!(energyOf(obj) > 0.0f)) {
                return false;
            }
            charge(vehicle, obj, "plant hit");
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the vegetation patch, disabling: " + t);
            return false;
        }
    }

    /**
     * Импульс из IsoObject.Collision для объекта с CarSlowFactor (одиночная игра и сервер) —
     * пропустить: торможение о такие объекты считаем здесь.
     */
    public static boolean skipsHitObjectImpulse(Object vehicle, Object obj) throws Exception {
        if (broken || vehicle == null || obj == null || !LabSettings.bushes()) {
            return false;
        }
        init(vehicle);
        if (!mGetProperties.getDeclaringClass().isInstance(obj)) {
            return false;
        }
        Object props = mGetProperties.invoke(obj);
        return props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue();
    }

    /** Первый контакт в эпизоде — списать энергию; повторные касания только продлевают эпизод. */
    public static void charge(Object vehicle, Object obj, String via) throws Exception {
        long now = System.nanoTime();
        synchronized (CHARGED) {
            Map<Object, long[]> seen = CHARGED.get(vehicle);
            if (seen == null) {
                seen = new WeakHashMap<Object, long[]>();
                CHARGED.put(vehicle, seen);
            }
            long[] last = seen.get(obj);
            if (last != null && now - last[0] <= RESET_NANOS) {
                last[0] = now;
                return;
            }
            seen.put(obj, new long[] {now});
        }
        float e = energyOf(obj);
        if (!(e > 0.0f)) {
            return;
        }
        mLinearVelocity.invoke(vehicle, velOut);
        float x = vx.getFloat(velOut);
        float z = vz.getFloat(velOut);
        float v = (float) Math.sqrt(x * x + z * z);
        if (!(v > 0.05f)) {
            return;
        }
        float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
        if (!(mass > 0.0f)) {
            return;
        }
        float v2 = v * v - 2.0f * e / mass;
        float after = v2 > 0.0f ? (float) Math.sqrt(v2) : 0.0f;
        float dv = v - after;
        if (!(dv > 0.0f)) {
            return;
        }
        // applyImpulseFromHitPlant кладёт в очередь -скорость * mul * масса, до машины доходит 0.3 от этого.
        float mul = dv / (APPLIED_FRACTION * v);
        ownCall = true;
        try {
            mApplyPlant.invoke(vehicle, obj, Float.valueOf(mul));
        } finally {
            ownCall = false;
        }
        charges++;
        speedLostKmh += dv * 3.6f;
        if (logged < 12) {
            logged++;
            Log.debug(String.format(
                    "[LabVehiclePhysics] vegetation: %s (%s) takes %.0f kJ from a %.0f kg vehicle at %.0f km/h -> %.0f km/h",
                    describe(obj), via, e / 1000.0f, mass, v * 3.6f, after * 3.6f));
        }
    }

    /** Сколько энергии забирает объект, Дж. 0 — не тормозит. */
    public static float energyOf(Object obj) throws Exception {
        if (((Boolean) mIsGrassLike.invoke(obj)).booleanValue()) {
            return E_GRASS_LIKE;
        }
        Object props = mGetProperties.invoke(obj);
        if (props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue()) {
            float f = 1.0f;
            try {
                f = Float.parseFloat(String.valueOf(mPropsGet.invoke(props, "CarSlowFactor")).trim());
            } catch (NumberFormatException ignored) {
            }
            return Math.max(1.0f, f) * E_PER_SLOW_FACTOR;
        }
        if (treeClass.isInstance(obj)) {
            return ((Integer) mTreeSize.invoke(obj)).intValue() <= 1 ? E_SMALL_TREE : 0.0f;
        }
        return ((Boolean) mIsBush.invoke(obj)).booleanValue() ? E_BUSH : 0.0f;
    }

    public static String describe(Object obj) {
        StringBuilder sb = new StringBuilder();
        try {
            Object sprite = fSprite.get(obj);
            Object name = sprite != null ? fSpriteName.get(sprite) : null;
            sb.append(name != null ? name : obj.getClass().getSimpleName());
            Object props = mGetProperties.invoke(obj);
            if (props != null && ((Boolean) mPropsHas.invoke(props, "CarSlowFactor")).booleanValue()) {
                sb.append(" CarSlowFactor=").append(mPropsGet.invoke(props, "CarSlowFactor"));
            }
        } catch (Throwable t) {
            sb.append(obj.getClass().getSimpleName());
        }
        return sb.toString();
    }

    public static synchronized void init(Object vehicle) throws Exception {
        if (mApplyPlant != null) {
            return;
        }
        ClassLoader cl = vehicle.getClass().getClassLoader();
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> io = Class.forName("zombie.iso.IsoObject", false, cl);
        fBreakingList = bv.getDeclaredField("breakingObjectsList");
        fBreakingList.setAccessible(true);
        fSlowFactor = bv.getDeclaredField("breakingSlowFactor");
        fSlowFactor.setAccessible(true);
        mFudgedMass = bv.getMethod("getFudgedMass");
        Class<?> v3 = Class.forName("org.joml.Vector3f", false, cl);
        mLinearVelocity = bv.getMethod("getLinearVelocity", v3);
        velOut = v3.getConstructor().newInstance();
        vx = v3.getField("x");
        vz = v3.getField("z");
        mGetProperties = io.getMethod("getProperties");
        Class<?> pc = mGetProperties.getReturnType();
        mPropsHas = pc.getMethod("has", String.class);
        mPropsGet = pc.getMethod("get", String.class);
        mIsBush = io.getMethod("isBush");
        mIsGrassLike = io.getMethod("isGrassLike");
        treeClass = Class.forName("zombie.iso.objects.IsoTree", false, cl);
        mTreeSize = treeClass.getMethod("getSize");
        fSprite = io.getField("sprite");
        fSpriteName = fSprite.getType().getField("name");
        mApplyPlant = bv.getMethod("applyImpulseFromHitPlant", io, float.class);
        Log.debug("[LabVehiclePhysics] vegetation drag ready: no speed cap in bushes, each bush or young tree"
                + " takes a fixed energy once per contact - heavy vehicles barely notice");
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
        if (charges == 0L && capsRemoved == 0L) {
            return;
        }
        Log.debug(String.format(
                "[LabVehiclePhysics] vegetation, last 15 s: objects %d, speed lost %.0f km/h in total, vanilla cap removed on %d frames",
                charges, speedLostKmh, capsRemoved));
        charges = 0L;
        capsRemoved = 0L;
        speedLostKmh = 0.0;
    }
}
