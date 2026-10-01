package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 1.5: подбрасывание машины на телах под колёсами.
 *
 * Ваниль, BaseVehicle.testCollisionWithProneCharacter — когда колесо наезжает на лежащего:
 * <pre>
 * impulse.impulse.set(0, 1, 0);                                  // строго ВВЕРХ
 * float speedMult = max(speedKmH, 10) / 10;
 * impulse.impulse.mul(0.065F * getFudgedMass() * speedMult * corpseSizeMul);
 * impulse.relPos.set(wheelPos - vehiclePos);                     // приложено В ТОЧКЕ КОЛЕСА
 * impulse.enable = true; impulse.applied = false;
 * </pre>
 * и затем BaseVehicle.applyAllImpulsesFromProneCharacters:
 * <pre>
 * float limit = getFudgedMass() * 0.15F;
 * if (force.lengthSquared() > limit*limit) force.mul(limit / force.length());   // потолок ТОЛЬКО на силу
 * Bullet.applyCentralForceToVehicle(id, force * 30.0F);
 * Bullet.applyTorqueToVehicle(id, torque * 30.0F);                             // момент НЕ ограничен
 * </pre>
 *
 * Два дефекта:
 *
 * 1. Импульс создаётся ПОКАДРОВО (из апдейта тела), а применяется на физическом шаге,
 *    которых ровно 100 в секунду. На 30 FPS выходит 30 приложений в секунду, на 240 — до 100.
 *    Итого вчетверо больше подъёмной силы: машину колбасит тем сильнее, чем выше FPS.
 *    Лечим домножением на реальную длительность кадра, нормированную к 1/30 c.
 *
 * 2. Сила направлена вверх, но приложена в точке КОЛЕСА, а не в центре масс — отсюда момент
 *    вращения и желание перевернуться. Ограничитель накинут только на силу; момент режется
 *    лишь заодно. Укорачиваем плечо (relPos), оставляя подъём нетронутым: машина
 *    подпрыгивает, но не кренится.
 *
 * Перегрузка testCollisionWithProneCharacter(chr, doSound, out) делегирует длинной, поэтому
 * патч по имени срабатывает дважды — считаем вложенность и масштабируем только на выходе
 * из самого внешнего вызова.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "testCollisionWithProneCharacter", warmUp = true)
public class Patch_proneImpulse {

    @Patch.OnEnter
    public static void enter() {
        // Вложенность считаем всегда, а предохранитель и переключатель проверяем на выходе.
        // Раньше проверка стояла здесь: при закрытом предохранителе счётчик не рос, и выход
        // видел ноль — масштабировал импульсы, да ещё дважды, по разу на каждую перегрузку.
        Impl.depth++;
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle, @Patch.Return(readOnly = true) int hitWheels) {
        Impl.leave(vehicle, hitWheels);
    }

    public static final class Impl {
        /** Доля плеча, которая остаётся: 1.0 — как в ванили, 0 — совсем без крена. */
        public static final float LEVER = 0.45f;
        public static final float REFERENCE_HZ = 30.0f;
        public static final float FRAME_MIN = 0.02f;
        public static final float FRAME_MAX = 2.0f;

        /** Глубина вложенности перегрузок (игра зовёт их из главного потока). */
        public static int depth = 0;

        public static volatile boolean broken = false;
        public static Field fImpulses, fImpulse, fRelPos, fEnable, fApplied, fx, fy, fz;
        public static boolean logged = false;
        public static long scaled = 0L;
        public static double sumFrame = 0.0;
        public static long lastReportNanos = 0L;

        public static void leave(Object vehicle, int hitWheels) {
            if (depth > 0) {
                depth--;
            }
            if (depth != 0 || broken || vehicle == null || hitWheels <= 0) {
                return;
            }
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return;
            }
            try {
                if (fImpulses == null) {
                    init(vehicle);
                }
                float frame = Patch_getMass.Impl.frameFactor();
                Object[] arr = (Object[]) fImpulses.get(vehicle);
                if (arr == null) {
                    return;
                }
                for (Object imp : arr) {
                    if (imp == null || !fEnable.getBoolean(imp) || fApplied.getBoolean(imp)) {
                        continue;
                    }
                    Object v = fImpulse.get(imp);
                    fx.setFloat(v, fx.getFloat(v) * frame);
                    fy.setFloat(v, fy.getFloat(v) * frame);
                    fz.setFloat(v, fz.getFloat(v) * frame);
                    Object rp = fRelPos.get(imp);
                    fx.setFloat(rp, fx.getFloat(rp) * LEVER);
                    fz.setFloat(rp, fz.getFloat(rp) * LEVER);
                    scaled++;
                    sumFrame += frame;
                }
                report();
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the prone-bodies patch, disabling: " + t);
            }
        }

        private static synchronized void init(Object vehicle) throws Exception {
            if (fImpulses != null) {
                return;
            }
            Class<?> bv = vehicle.getClass();
            Field f = null;
            Class<?> c = bv;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("impulsesFromSquishedBodies");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException("impulsesFromSquishedBodies");
            }
            f.setAccessible(true);
            Object[] probe = (Object[]) f.get(vehicle);
            Class<?> imp = Class.forName("zombie.vehicles.BaseVehicle$VehicleImpulse", false, bv.getClassLoader());
            fImpulse = imp.getDeclaredField("impulse");
            fRelPos = imp.getDeclaredField("relPos");
            fEnable = imp.getDeclaredField("enable");
            fApplied = imp.getDeclaredField("applied");
            for (Field ff : new Field[]{fImpulse, fRelPos, fEnable, fApplied}) {
                ff.setAccessible(true);
            }
            Class<?> v3 = Class.forName("org.joml.Vector3f", false, bv.getClassLoader());
            fx = v3.getField("x");
            fy = v3.getField("y");
            fz = v3.getField("z");
            fImpulses = f;
            Log.debug("[LabVehiclePhysics] prone-bodies patch ready: lift is normalised by frame time, "
                    + "torque arm reduced to " + (int) (LEVER * 100) + "% (array slots: "
                    + (probe == null ? 0 : probe.length) + ")");
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
            if (scaled == 0L) {
                return;
            }
            double avg = sumFrame / scaled;
            Log.debug(String.format(
                    "[LabVehiclePhysics] prone bodies, last 15 s: impulses %d (%.1f/s), "
                    + "mean frame factor %.3f (=> ~%.0f FPS), lift reduced %.1fx",
                    scaled, scaled / 15.0, avg, avg > 0 ? REFERENCE_HZ / avg : 0.0, avg > 0 ? 1.0 / avg : 0.0));
            scaled = 0L;
            sumFrame = 0.0;
        }
    }
}
