package pz.labvehicle;

import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 1.6: один зомби — один импульс.
 *
 * Ваниль зовёт BaseVehicle.applyImpulseFromHitPedestrian КАЖДЫЙ КАДР, пока длится контакт
 * (а контакт в VehiclePedestrianContactTracking держится до 3.5 секунд), и каждый раз
 * списывает с машины полный импульс m·v·0.8. Физически так нельзя: тело забирает импульс
 * ровно один раз — пока разгоняется до скорости машины. Дальше забирать нечего.
 * Отсюда «упёрся в одного зомби и встал».
 *
 * Даём каждому персонажу БЮДЖЕТ на эпизод контакта и перестаём списывать, когда он исчерпан.
 *
 * Почему бюджет измеряется временем. Игра применяет накопленное как силу с множителем 30
 * на одном шаге Bullet в 10 мс, то есть за одно применение доходит 30 * 0.01 = 0.3 от
 * положенного импульса. Значит полный импульс набирается примерно за 1/0.3 = 3.33 кадра
 * по 1/30 с, то есть за 0.111 секунды контакта. Это и есть бюджет; он в секундах, поэтому
 * не зависит от FPS — на 240 кадрах он просто растянется на больше кадров с меньшим вкладом
 * каждого (вклад нормирован патчем массы).
 *
 * Эпизод считается законченным, если персонаж не касался машины дольше RESET_SEC —
 * тогда бюджет выдаётся заново (машина отъехала и ударила снова).
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitPedestrian", warmUp = true)
public class Patch_impulseBudget {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) Object chr) {
        return Impl.shouldSkip(chr);
    }

    public static final class Impl {
        /** Сколько секунд контакта оплачивается импульсом. 0.111 ≈ один физически честный удар. */
        public static final float BUDGET_SEC = 0.111f;
        /** Пауза в контакте, после которой эпизод считается новым. */
        public static final float RESET_SEC = 0.4f;

        public static volatile boolean broken = false;
        /** персонаж -> {израсходовано секунд, время последнего касания в нс} */
        public static final Map<Object, float[]> SPENT = new WeakHashMap<Object, float[]>();
        public static final Map<Object, Long> LAST = new WeakHashMap<Object, Long>();

        public static long allowed = 0L;
        public static long blocked = 0L;
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        /** @return true = пропустить ванильное списание импульса. */
        public static boolean shouldSkip(Object chr) {
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return false;
            }
            if (broken || chr == null) {
                return false;
            }
            try {
                long now = System.nanoTime();
                float[] spent;
                Long last;
                synchronized (SPENT) {
                    spent = SPENT.get(chr);
                    last = LAST.get(chr);
                    if (spent == null) {
                        spent = new float[1];
                        SPENT.put(chr, spent);
                    }
                    LAST.put(chr, Long.valueOf(now));
                }
                if (last != null && (now - last.longValue()) > (long) (RESET_SEC * 1_000_000_000L)) {
                    spent[0] = 0.0f;   // контакт прерывался — новый эпизод, бюджет заново
                }
                if (spent[0] >= BUDGET_SEC) {
                    blocked++;
                    report();
                    return true;       // бюджет исчерпан: тело уже разогнано, отбирать нечего
                }
                spent[0] += Patch_getMass.Impl.frameFactor() / Patch_getMass.Impl.REFERENCE_HZ;
                allowed++;
                report();
                return false;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the impulse budget patch, disabling: " + t);
                return false;
            }
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] impulse budget on: one character pushes the vehicle for "
                        + "for " + BUDGET_SEC + "s of contact, after that nothing is applied");
            }
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long total = allowed + blocked;
            if (total == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] zombie impulses, last 15 s: applied %d, skipped %d (%d%% of redundant hits removed)",
                    allowed, blocked, blocked * 100 / total));
            allowed = 0L;
            blocked = 0L;
        }
    }
}
