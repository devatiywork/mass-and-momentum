package pz.labvehicle;

import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Вариант 2: отложить превращение в труп, пока тело прижато к машине.
 *
 * Почему предыдущие попытки не сработали. В мультиплеере труп создаёт СЕРВЕР:
 * <pre>
 * public final void die() {
 *     if (GameClient.client) this.getNetworkCharacterAI().onDied();  // клиент только принимает пакет
 *     else                   this.becomeCorpse();                    // труп рождается здесь
 * }
 * </pre>
 * Рэгдолл при этом живёт только на клиенте — сервер о полёте тела не знает вообще
 * и ставит труп там, где зомби умер, то есть на месте удара.
 *
 * Здесь заходим не со стороны картинки, а со стороны сервера: не трогаем ни урон,
 * ни смерть, а ОТКЛАДЫВАЕМ момент создания трупа, пока зомби в контакте с машиной.
 * Контакт закончился — die() проходит штатно, и труп рождается уже на той позиции,
 * куда зомби к этому моменту утащило. Серверу для этого ничего знать про рэгдолл не нужно.
 *
 * Страховки: отсрочка не дольше MAX_DEFER_SEC (тело не может остаться неупокоенным
 * навсегда) и короткая пауза GRACE_SEC после потери контакта, чтобы не создать труп
 * в момент, когда тело ещё отлипает от бампера.
 *
 * Для зомби эта отсрочка так и не включается (backlog.md §1a). Зато тем же входом
 * пользуется отлёт сбитого животного: пока оно скользит, die() пропускается, и труп
 * рождается в конце пути ({@link AnimalThrow#holdsDeath}, не дольше 3 с).
 *
 * С 28.09.2026 тем же входом откладывает смерть и {@link CorpseSync}: на сервере сбитый машиной
 * зомби ждёт точку приземления от водителя.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "die", warmUp = true)
public class Patch_deferCorpse {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object chr) {
        return Impl.shouldDefer(chr);
    }

    public static final class Impl {
        /** Максимальная суммарная отсрочка. */
        public static final float MAX_DEFER_SEC = 4.0f;
        /** Сколько ждать после потери контакта, прежде чем отпустить. */
        public static final float GRACE_SEC = 0.35f;

        public static volatile boolean broken = false;
        public static java.lang.reflect.Method mVehicleCollision;
        /** персонаж -> {когда отсрочка началась, когда последний раз был контакт} */
        public static final Map<Object, long[]> STATE = new WeakHashMap<Object, long[]>();

        public static long deferred = 0L;
        public static long released = 0L;
        public static double maxHeldSec = 0.0;
        public static boolean logged = false;
        public static long lastReportNanos = 0L;

        /** @return true = отложить смерть (пропустить die() в этом вызове). */
        public static boolean shouldDefer(Object chr) {
            if (!LabGate.active()) {
                return false;
            }
            if (broken || chr == null) {
                return false;
            }
            // Сбитое животное ещё летит: труп пусть родится там, где тело остановится.
            // Это отдельный от зомби путь — см. AnimalThrow.
            if (AnimalThrow.holdsDeath(chr)) {
                return true;
            }
            if (!LabSettings.corpseFollows()) {
                return false;
            }
            // Сервер в сети: сбитый машиной зомби ещё летит у водителя — труп родится там,
            // где тело ляжет, когда водитель пришлёт точку (CorpseSync). Это то, чего не смог
            // вариант 2 ниже: признак «летит» ставит сервер сам, при ударе, а не кадровый флаг.
            if (CorpseSync.deferDeath(chr)) {
                return true;
            }
            try {
                if (mVehicleCollision == null) {
                    mVehicleCollision = chr.getClass().getMethod("isVehicleCollision");
                    Log.debug("[LabVehiclePhysics] corpse deferral on: while the body is pinned to the vehicle, "
                            + "die() is skipped (at most " + MAX_DEFER_SEC + " s)");
                }
                boolean inContact = ((Boolean) mVehicleCollision.invoke(chr)).booleanValue();
                long now = System.nanoTime();
                long[] st;
                synchronized (STATE) {
                    st = STATE.get(chr);
                    if (st == null) {
                        if (!inContact) {
                            return false;      // обычная смерть, машина ни при чём
                        }
                        st = new long[]{now, now};
                        STATE.put(chr, st);
                    }
                }
                if (inContact) {
                    st[1] = now;
                }
                double heldSec = (now - st[0]) / 1.0e9;
                double sinceContactSec = (now - st[1]) / 1.0e9;

                if (heldSec > MAX_DEFER_SEC || sinceContactSec > GRACE_SEC) {
                    synchronized (STATE) {
                        STATE.remove(chr);
                    }
                    released++;
                    if (heldSec > maxHeldSec) {
                        maxHeldSec = heldSec;
                    }
                    report();
                    return false;              // отпускаем: труп создастся на текущем месте
                }
                deferred++;
                report();
                return true;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in corpse deferral, disabling: " + t);
                return false;
            }
        }

        public static void report() {
            if (!logged) {
                logged = true;
                Log.debug("[LabVehiclePhysics] death deferred for the first time - the body is pinned to the vehicle");
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
            if (deferred == 0L && released == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] corpse deferral, last 15 s: calls deferred %d, bodies released %d, "
                    + "longest deferral %.2f s",
                    deferred, released, maxHeldSec));
            deferred = 0L;
            released = 0L;
            maxHeldSec = 0.0;
        }
    }
}
