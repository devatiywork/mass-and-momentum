package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * Предохранитель: мод действует только там, где он разрешён.
 *
 * Проблема, ради которой это написано. Java-моды грузятся агентом ZombieBuddy при старте
 * JVM — до того, как игра вообще узнает, на какой сервер игрок собирается. Поэтому патчи
 * классов оказываются в памяти всегда, независимо от того, стоит ли мод на сервере.
 * Lua и скрипты так не проедут: сервер сверяет их контрольные суммы
 * ({@code ChecksumPacket: okLua && okScript && okAnim}) и рвёт соединение при расхождении.
 * А вот нативные классы и библиотеки в эту сверку не входят вовсе.
 *
 * Без предохранителя вышло бы вот что: заходишь с модом на чужой сервер, где его нет, и
 * сбиваешь зомби одиннадцатитонной машиной, пока все остальные ездят на ванильной тонне.
 * Технически это никем не ловится, но по сути — преимущество, которого никто не давал.
 *
 * Решение опирается на штатный механизм самой игры: спрашиваем, какие моды реально
 * загружены в этой сессии.
 *
 * ВАЖНО, какой именно список спрашивать. Первая версия смотрела в {@code ActiveMods},
 * и это была ошибка — он на клиенте не заполняется вовсе:
 * <pre>
 * // GameLoadingState:449
 * if (!GameClient.client) {
 *     ActiveMods activeMods = ActiveMods.getById("currentGame");
 *     ActiveMods.setLoadedMods(activeMods);
 * }
 * </pre>
 * Предохранитель честно решал, что мода нет, и глушил весь мод на любом сервере, включая
 * свой собственный. Мультиплеер у клиента устроен иначе:
 * <pre>
 * // ZomboidFileSystem.loadMods(String)
 * if (GameClient.client) {
 *     toLoad.addAll(GameClient.instance.serverMods);   // список модов СЕРВЕРА
 *     this.loadMods(toLoad);
 * }
 * </pre>
 * Поэтому спрашиваем {@code ZomboidFileSystem.instance.getModIDs()} — он возвращает то,
 * что реально загружено, одинаково на клиенте, на сервере и в одиночной игре. На клиенте
 * этот список приходит от сервера, что нам и нужно.
 *
 * <ul>
 *   <li>одиночная игра — в списке то, что выбрал игрок, мод там есть, работаем;</li>
 *   <li>свой сервер с модом — он в списке сервера, работаем;</li>
 *   <li>чужой сервер без мода — его в списке нет, все патчи молчат.</li>
 * </ul>
 *
 * Отдельно приятно, что это не проверка «а не читер ли ты», а ровно тот же список, по
 * которому игра решает, какой контент грузить. Договориться с админом сервера — значит
 * просто добавить мод в список, и всё заработает само.
 */
public final class LabGate {

    public static final String MOD_ID = "LabVehiclePhysics";
    /** Как часто перепроверять. Список меняется между сессиями, а не в течение кадра. */
    public static final long RECHECK_NANOS = 2_000_000_000L;

    public static volatile boolean broken = false;
    public static volatile boolean active = false;
    public static long lastCheckNanos = 0L;
    public static Method mGetById;
    public static Method mIsModActive;
    public static java.lang.reflect.Field fInstance;
    public static Method mGetModIDs;
    public static boolean logged = false;
    public static boolean lastLogged = false;

    private LabGate() {
    }

    /** @return true, если моду разрешено вмешиваться в игру. */
    public static boolean active() {
        if (broken) {
            return true;      // не смогли определить — ведём себя как раньше, но об этом сказано в логе
        }
        long now = System.nanoTime();
        if (lastCheckNanos != 0L && now - lastCheckNanos < RECHECK_NANOS) {
            return active;
        }
        lastCheckNanos = now;
        try {
            if (mGetModIDs == null) {
                Class<?> zfs = Class.forName("zombie.ZomboidFileSystem");
                fInstance = zfs.getField("instance");
                mGetModIDs = zfs.getMethod("getModIDs");
                Class<?> cls = Class.forName("zombie.modding.ActiveMods");
                mGetById = cls.getMethod("getById", String.class);
                mIsModActive = cls.getMethod("isModActive", String.class);
            }
            boolean on = isLoaded() || isActiveIn("loaded") || isActiveIn("currentGame");
            active = on;
            if (!logged || on != lastLogged) {
                logged = true;
                lastLogged = on;
                Log.info("[LabVehiclePhysics] safety gate: mod " + MOD_ID
                        + (on ? " is active - running"
                             : " is NOT in the active mod list of this game - all changes disabled"));
            }
            return active;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] safety gate could not read the mod list ("
                    + t + ") - changes stay enabled");
            return true;
        }
    }

    /** Моды, реально загруженные в этой сессии. На клиенте — пришедшие от сервера. */
    public static boolean isLoaded() {
        try {
            Object zfs = fInstance.get(null);
            if (zfs == null) {
                return false;
            }
            Object ids = mGetModIDs.invoke(zfs);
            return ids instanceof java.util.Collection && ((java.util.Collection<?>) ids).contains(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isActiveIn(String listId) {
        try {
            Object list = mGetById.invoke(null, listId);
            if (list == null) {
                return false;
            }
            return ((Boolean) mIsModActive.invoke(list, MOD_ID)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }
}
