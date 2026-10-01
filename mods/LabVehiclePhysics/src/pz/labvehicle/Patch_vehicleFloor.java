package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Пол под машинами и замер просадки подвески.
 *
 * Почему машина вообще проваливается. Машина в PZ — это btRaycastVehicle: колёс как
 * физических тел нет, из кузова вниз пускается луч, и пружина отталкивает кузов от
 * найденной точки. Земля при этом НЕ имеет коллизии для самого кузова. Пока луч достаёт
 * до земли, всё хорошо. Но дальность луча ограничена ходом подвески, а он в скриптах
 * задан крошечным — 10 см у всей ванили, 12 у Bushmaster, 20 у самого тяжёлого мода.
 * Стоит пружине выбрать этот ход, луч перестаёт доставать до земли, опора пропадает
 * не частично, а полностью, и кузов падает в пустоту.
 *
 * Первая версия этого патча ловила кузов по высоте ноль, и это была ошибка измерения:
 * ноль — это уровень земли, а центр кузова в норме стоит ВЫШЕ него на высоту посадки.
 * Для Bushmaster это 0.47, то есть машина успевала провалиться больше чем на радиус
 * колеса, прежде чем защита срабатывала. Обе ветки эксперимента упирались в один и тот же
 * искусственный пол, и сравнивать их было бессмысленно — прибор был насыщен.
 *
 * Теперь высота посадки считается той же формулой, что и в самой игре при создании машины:
 * <pre>
 * // CarController(BaseVehicle)
 * wheelBottom   = modelOffset.y + wheel(0).offset.y - wheel(0).radius;
 * chassisBottom = centerOfMassOffset.y - extents.y / 2;
 * physicsZ      = floor(getZ()) * 3 * 0.8164967f - min(wheelBottom, chassisBottom);
 * </pre>
 * От неё и отсчитываем: просадка глубже SAG_LIMIT считается провалом, кузов возвращается
 * на SAG_LIFT ниже нормы (не на саму норму — иначе машина будет прыгать).
 *
 * И главное: в лог пишется реальная просадка, средняя и худшая. Это и есть прибор,
 * которым меряется, помогает ли увеличение хода подвески.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в update() — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "update", warmUp = true)
public class Patch_vehicleFloor {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        Impl.keepAboveGround(vehicle);
    }

    public static final class Impl {
        /** Насколько ниже нормы разрешено просесть, прежде чем вмешиваться. */
        public static final float SAG_LIMIT = 0.30f;
        /** На сколько ниже нормы ставим при возврате — чтобы не подбрасывало. */
        public static final float SAG_LIFT = 0.10f;
        /** Высота одного этажа в единицах физики: 3 * 0.8164967. */
        public static final float LEVEL = 2.4494901f;

        public static volatile boolean broken = false;
        public static Method mGetWorldTransform;
        public static Method mSetWorldTransform;
        public static Method mSetWorldTransformArg;
        public static Method mGetScriptName;
        public static Method mGetScript;
        public static Method mGetZ;
        public static Method mWheelCount;
        public static Method mGetWheel;
        public static Method mModelOffset;
        public static Method mComOffset;
        public static Method mExtents;
        public static Method mWheelOffset;
        public static Field fWheelRadius;
        public static Field fOrigin;
        public static Field fY;
        public static Object scratch;

        public static long clamps = 0L;
        public static long samples = 0L;
        public static double sagSum = 0.0;
        public static float worstSag = 0.0f;
        public static String worstName = "";
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        public static void init(Object vehicle) throws Exception {
            Class<?> tr = Class.forName("zombie.core.physics.Transform");
            Class<?> bv = vehicle.getClass();
            mGetWorldTransform = bv.getMethod("getWorldTransform", tr);
            mSetWorldTransform = bv.getMethod("setWorldTransform", tr);
            mGetScriptName = bv.getMethod("getScriptName");
            mGetScript = bv.getMethod("getScript");
            mGetZ = bv.getMethod("getZ");
            fOrigin = tr.getField("origin");
            fY = fOrigin.getType().getField("y");
            scratch = tr.getDeclaredConstructor().newInstance();

            Object script = mGetScript.invoke(vehicle);
            Class<?> vs = script.getClass();
            mWheelCount = vs.getMethod("getWheelCount");
            mGetWheel = vs.getMethod("getWheel", int.class);
            mModelOffset = vs.getMethod("getModelOffset");
            mComOffset = vs.getMethod("getCenterOfMassOffset");
            mExtents = vs.getMethod("getExtents");
            Class<?> wheel = Class.forName("zombie.scripting.objects.VehicleScript$Wheel");
            mWheelOffset = wheel.getMethod("getOffset");
            fWheelRadius = wheel.getField("radius");

            Log.debug("[LabVehiclePhysics] vehicle floor: measured from each vehicle's own ride height, "
                    + "sag deeper than this counts as falling through: " + SAG_LIMIT);
        }

        /** Высота, на которой центр кузова стоит в норме. Формула из CarController. */
        public static float naturalHeight(Object vehicle, Object script) throws Exception {
            float base = (float) Math.floor(((Float) mGetZ.invoke(vehicle)).floatValue()) * LEVEL;
            Object com = mComOffset.invoke(script);
            Object ext = mExtents.invoke(script);
            float chassisBottom = fY.getFloat(com) - fY.getFloat(ext) / 2.0f;
            int wheels = ((Integer) mWheelCount.invoke(script)).intValue();
            if (wheels <= 0) {
                return base + 0.1f;          // прицепы: своя ветка в игре
            }
            Object w0 = mGetWheel.invoke(script, Integer.valueOf(0));
            Object mo = mModelOffset.invoke(script);
            Object wo = mWheelOffset.invoke(w0);
            float wheelBottom = fY.getFloat(mo) + fY.getFloat(wo) - fWheelRadius.getFloat(w0);
            return base - Math.min(wheelBottom, chassisBottom);
        }

        public static void keepAboveGround(Object vehicle) {
            if (!LabGate.active()) {
                return;
            }
            // Растяжка на NaN — первой: и пол, и деревья ниже читают трансформ машины.
            // Работает, даже если пол выключился из-за ошибки.
            NanGuard.vehicle(vehicle);
            if (broken || vehicle == null) {
                return;
            }
            try {
                if (mGetWorldTransform == null) {
                    init(vehicle);
                }
                // Данные авторов модов могли прийти позже скриптов — доприменяем
                // здесь: BaseVehicle.update идёт в главном потоке, toBullet() безопасен.
                VehicleCfg.reapplyIfPending();
                // Деревья (TreeBreak): упор в ствол и возврат скорости после повала с разгона.
                TreeBreak.stepPush(vehicle);
                TreeBreak.stepPending(vehicle);
                // Починка и заправка по ключу service — только в сборке лаборатории (см. Dev).
                if (Dev.ENABLED) {
                    VehicleService.maybe(vehicle);
                }
                Object script = mGetScript.invoke(vehicle);
                if (script == null) {
                    return;
                }
                Object t = scratch;
                mGetWorldTransform.invoke(vehicle, t);
                Object origin = fOrigin.get(t);
                float height = fY.getFloat(origin);
                float natural = naturalHeight(vehicle, script);
                float sag = height - natural;

                samples++;
                sagSum += sag;
                if (sag < worstSag) {
                    worstSag = sag;
                    worstName = String.valueOf(mGetScriptName.invoke(vehicle));
                }

                if (sag < -SAG_LIMIT) {
                    fY.setFloat(origin, natural - SAG_LIFT);
                    mSetWorldTransform.invoke(vehicle, t);
                    clamps++;
                    if (!logged) {
                        logged = true;
                        Log.debug("[LabVehiclePhysics] "
                                + String.valueOf(mGetScriptName.invoke(vehicle))
                                + " sagged by " + String.format("%.2f", Float.valueOf(-sag))
                                + " from a normal ride height of " + String.format("%.2f", Float.valueOf(natural))
                                + " - lifting it back");
                    }
                }
                report();
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the vehicle floor, disabling: " + t);
            }
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L || samples == 0L) {
                return;
            }
            lastReportNanos = now;
            Log.debug(String.format(
                    "[LabVehiclePhysics] suspension, last 15 s: mean sag %.3f, worst %.3f (%s), "
                    + "floor triggered %d times out of %d samples",
                    Double.valueOf(sagSum / samples), Float.valueOf(worstSag), worstName,
                    Long.valueOf(clamps), Long.valueOf(samples)));
            clamps = 0L;
            samples = 0L;
            sagSum = 0.0;
            worstSag = 0.0f;
            worstName = "";
        }
    }
}
