package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 1, патч 1 из 2: масса персонажа в формуле торможения машины.
 *
 * Ваниль: {@code IsoGameCharacter.getMass() { return 100.0F; }} — хардкод для всех.
 * Единственный потребитель этого метода во всей игре — {@code BaseVehicle.applyImpulseFromHitPedestrian}:
 * <pre>impulseStrength = -dot * characterMass * (isProne ? 0.2 : 0.8) * vehicleSpeed;</pre>
 * Проверено поиском по декомпилированному исходнику: других вызовов нет, поэтому правка
 * не задевает ничего, кроме наезда.
 *
 * Делаем две вещи:
 *
 * 1. РАЗБРОС ПО ТЕЛОСЛОЖЕНИЮ. Поля веса тела у зомби в сейве нет, поэтому берём
 *    детерминированный разброс 0.75..1.35 от личности персонажа (стабилен в пределах
 *    сессии). Среднее намеренно оставлено равным 1.0: на этом этапе мы НЕ меняем силу
 *    торможения в среднем, чтобы эффект остальных правок был виден чисто.
 *
 * 2. ЧЕСТНОЕ ВРЕМЯ КАДРА. Импульс кладётся в список каждый кадр отрисовки, а разгребается
 *    с зашитой константой 30 (в игре для этого даже заведена переменная fpsScale, которую
 *    забыли использовать). Из-за этого на 240 FPS в машину прилетает в 8 раз больше
 *    торможения в секунду, чем на 30. Домножаем вклад кадра на его реальную длительность,
 *    нормированную к 1/30 c: на 30 FPS коэффициент 1.0, на 240 — 0.125. Суммарный импульс
 *    за секунду становится одинаковым при любом FPS и равным ванильному на 30 FPS.
 *
 * Математически это тождественно умножению самого импульса — просто точка приложения
 * удобнее: getMass() короткий и без побочных потребителей.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в getMass(), поэтому только public-члены
 * и никаких лямбд.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getMass", warmUp = true)
public class Patch_getMass {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) float ret) {
        ret = Impl.adjust(self, ret);
    }

    public static final class Impl {
        /** Границы разброса массы. Среднее ≈ 1.0 — средняя сила торможения не меняется. */
        public static final float SPREAD_MIN = 0.75f;
        public static final float SPREAD_MAX = 1.35f;
        /** Эталонная частота, под которую откалибрована ваниль. */
        public static final float REFERENCE_HZ = 30.0f;
        /** Ограничители коэффициента кадра: защита от пауз, загрузок и фризов. */
        public static final float FRAME_MIN = 0.02f;
        public static final float FRAME_MAX = 2.0f;

        public static volatile boolean broken = false;
        public static Method gtGetInstance;
        public static Method gtRealSeconds;
        public static boolean logged = false;
        public static long calls = 0L;
        public static double sumFrame = 0.0;
        public static float lastSpread = 1.0f;
        public static long lastReportNanos = 0L;

        public static float adjust(Object chr, float vanilla) {
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return vanilla;
            }
            if (broken || chr == null) {
                return vanilla;
            }
            try {
                float frame = frameFactor();
                float spread = spreadFor(chr);
                lastSpread = spread;
                calls++;
                sumFrame += frame;
                report();
                return vanilla * spread * frame;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the mass patch, disabling: " + t);
                return vanilla;
            }
        }

        /** Длительность кадра, нормированная к 1/30 с. */
        public static float frameFactor() throws Exception {
            if (gtRealSeconds == null) {
                Class<?> gt = Class.forName("zombie.GameTime");
                gtGetInstance = gt.getMethod("getInstance");
                gtRealSeconds = gt.getMethod("getRealworldSecondsSinceLastUpdate");
            }
            Object inst = gtGetInstance.invoke(null);
            if (inst == null) {
                return 1.0f;
            }
            float sec = ((Float) gtRealSeconds.invoke(inst)).floatValue();
            float f = sec * REFERENCE_HZ;
            if (!(f > FRAME_MIN)) {
                return FRAME_MIN;
            }
            return f > FRAME_MAX ? FRAME_MAX : f;
        }

        /** Детерминированный разброс массы для конкретного персонажа. */
        public static float spreadFor(Object chr) {
            int h = System.identityHashCode(chr);
            h ^= (h >>> 16);
            h *= 0x7feb352d;
            h ^= (h >>> 15);
            float t = (h & 0xffff) / 65535.0f;
            return SPREAD_MIN + (SPREAD_MAX - SPREAD_MIN) * t;
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] character mass: vanilla 100 -> spread "
                        + SPREAD_MIN + ".." + SPREAD_MAX + " x frame factor (30 Hz = 1.0)");
            }
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos >= 15_000_000_000L) {
                double avg = calls > 0 ? sumFrame / calls : 0.0;
                Log.debug(String.format(
                        "[LabVehiclePhysics] last 15 s: mass calls=%d, mean frame factor=%.3f (=> ~%.0f FPS)",
                        calls, avg, avg > 0 ? REFERENCE_HZ / avg : 0.0));
                calls = 0L;
                sumFrame = 0.0;
                lastReportNanos = now;
            }
        }
    }
}
