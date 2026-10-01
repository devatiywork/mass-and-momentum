package pz.labvehicle;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Серверная таблица: в сети физику машин задаёт файл сервера.
 *
 * <h2>Зачем</h2>
 * В мультиплеере машину считает клиент водителя: сервер машины в Bullet не регистрирует
 * вовсе ({@code VehicleScript.Loaded()} зовёт {@code toBullet()} только при
 * {@code !GameServer.server}). Пока каждый клиент читал свой {@code vehicle-physics.cfg},
 * одна и та же машина весила по-разному — смотря кто за рулём.
 *
 * <h2>Правило</h2>
 * <ul>
 *   <li>одиночная игра — свой файл, как раньше;</li>
 *   <li>сервер — свой файл. У кооп-хоста это файл хоста: сервер там отдельный процесс,
 *       но с той же папкой Zomboid. У выделенного — файл в папке сервера, то есть админа;</li>
 *   <li>клиент в сети свой файл не читает вообще и берёт таблицу сервера. Пока она не
 *       пришла, действуют только встроенные данные и данные авторов — они у всех одинаковые.</li>
 * </ul>
 * Встроенные данные и данные авторов по сети не передаются: они лежат в модах, а список
 * модов сервер и так навязывает клиенту.
 *
 * <h2>Как ходит</h2>
 * Штатными командами модов: клиент просит ({@code sendClientCommand}), сервер отвечает
 * ({@code sendServerCommand}) и рассылает заново, когда файл изменился. Lua здесь только
 * почтальон — {@code LabVehiclePhysics_ServerTable.lua} в {@code client/} и {@code server/}.
 * До Java он дотягивается через {@link LabVehiclePhysicsNet}.
 *
 * Таблица приходит уже после загрузки мира, и раньше её не получить: команду можно
 * послать только из игры. Поэтому по приходу скрипты переприменяются, а машины, которые
 * успели появиться, обновляются — см. {@link VehicleCfg#refreshVehicles()}.
 *
 * Файл уходит строками, а не одним текстом: строка в пакете игры предваряется длиной
 * в {@code short} ({@code GameWindow.StringUTF}), то есть не длиннее 32 КБ.
 */
public final class ServerTable {

    /** Формат таблицы. Поднимать, только если меняется смысл полей. */
    public static final int PROTOCOL = 1;
    public static final int MAX_LINES = 5000;
    public static final int MAX_LINE_CHARS = 1000;
    /** Буфер пакета у игры 1 000 000 байт (UdpConnection), берём с большим запасом. */
    public static final int MAX_TOTAL_CHARS = 256 * 1024;

    public static volatile boolean broken = false;
    public static Field fClient;
    public static Field fIngame;
    public static Field fEnv;
    public static Object platform;
    public static Method mNewTable;
    public static Method mRawset;
    public static Method mRawget;
    public static Class<?> kahluaTable;

    /** Правила последней принятой таблицы. Пустой список, пока ничего не пришло. */
    public static volatile List<VehicleCfg.Rule> rules = Collections.<VehicleCfg.Rule>emptyList();
    public static volatile boolean received = false;
    /** Растёт при каждой новой таблице и при сбросе старой — по нему VehicleCfg видит перемену. */
    public static int generation = 0;
    public static double stamp = -1.0;
    public static List<String> lines = Collections.<String>emptyList();
    /**
     * Lua-окружение, в котором пришла таблица. Новое подключение пересоздаёт окружение
     * ({@code LuaManager.init()}: {@code env = platform.newEnvironment()}), так что таблица
     * прошлого сервера узнаётся по нему и не доживает до следующего. Слабая ссылка —
     * чтобы не держать в памяти весь Lua старой сессии.
     */
    public static WeakReference<Object> receivedIn = new WeakReference<Object>(null);
    public static final Set<String> WARNED = new HashSet<String>();

    private ServerTable() {
    }

    /** Флаги сети. Отдельно от Lua: режим спрашивают на каждом кадре, и Lua ему не нужен. */
    public static void initNet() throws Exception {
        if (fClient != null) {
            return;
        }
        Class<?> gc = Class.forName("zombie.network.GameClient");
        fIngame = gc.getField("ingame");
        fClient = gc.getField("client");
    }

    public static void init() throws Exception {
        initNet();
        if (mRawget != null) {
            return;
        }
        Class<?> lm = Class.forName("zombie.Lua.LuaManager");
        fEnv = lm.getField("env");
        platform = lm.getField("platform").get(null);
        mNewTable = platform.getClass().getMethod("newTable");
        kahluaTable = Class.forName("se.krka.kahlua.vm.KahluaTable");
        mRawset = kahluaTable.getMethod("rawset", Object.class, Object.class);
        mRawget = kahluaTable.getMethod("rawget", Object.class);
    }

    /**
     * Клиент в сети — в том числе клиент самого кооп-хоста: он тоже подключается
     * к своему серверу и получает таблицу оттуда, как все.
     */
    public static boolean isMpClient() {
        if (broken) {
            return false;
        }
        try {
            initNet();
            return fClient.getBoolean(null);
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] server table: cannot tell single player from multiplayer ("
                    + t + ") - the local vehicle-physics.cfg applies everywhere");
            return false;
        }
    }

    /**
     * Можно ли уже слать серверу.
     *
     * {@code sendClientCommand} уходит в сеть только при {@code GameClient.ingame}, а флаг
     * ставится в {@code IngameState.UpdateStuff()} — уже после {@code OnGameStart}. Раньше
     * этого команда молча уходит по пути одиночной игры и до сервера не доходит.
     */
    public static boolean clientReady() {
        try {
            return isMpClient() && fIngame.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- сервер

    /** Номер версии своего файла: растёт при каждом его изменении. */
    public static double serverStamp() {
        VehicleCfg.reloadIfNeeded();
        return VehicleCfg.playerStamp;
    }

    /**
     * Таблица для отправки клиентам: {@code {v, present, stamp, count, lines = {...}}}.
     * Зовётся из Lua, то есть в главном потоке, — трогать Kahlua здесь безопасно.
     *
     * @return null, если собрать не вышло; Lua тогда ничего не шлёт
     */
    public static Object build() {
        try {
            init();
            VehicleCfg.reloadIfNeeded();
            List<String> src = VehicleCfg.playerLines;
            Object out = mNewTable.invoke(platform);
            Object lt = mNewTable.invoke(platform);
            int n = 0;
            int total = 0;
            for (int i = 0; i < src.size(); i++) {
                String s = src.get(i);
                if (s.length() > MAX_LINE_CHARS) {
                    warn("long:" + i, "server table: a line of " + s.length() + " characters in vehicle-physics.cfg"
                            + " is longer than " + MAX_LINE_CHARS + " - not sent to players");
                    continue;
                }
                if (n >= MAX_LINES || total + s.length() > MAX_TOTAL_CHARS) {
                    warn("truncated", "server table: vehicle-physics.cfg is too big to send - only the first "
                            + n + " rule lines go to players");
                    break;
                }
                n++;
                total += s.length();
                mRawset.invoke(lt, Double.valueOf(n), s);
            }
            mRawset.invoke(out, "v", Double.valueOf(PROTOCOL));
            mRawset.invoke(out, "present", Boolean.valueOf(VehicleCfg.playerFilePresent));
            mRawset.invoke(out, "stamp", Double.valueOf(VehicleCfg.playerStamp));
            mRawset.invoke(out, "count", Double.valueOf(n));
            mRawset.invoke(out, "lines", lt);
            return out;
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] server table: could not build it for players: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------- клиент

    /** Принять таблицу сервера. Зовётся из Lua по команде {@code table}. */
    public static void accept(Object args) {
        try {
            init();
            if (!isMpClient()) {
                warn("not-mp", "server table: arrived outside multiplayer - ignored");
                return;
            }
            if (args == null || !kahluaTable.isInstance(args)) {
                warn("format", "server table: the server sent it in an unknown format - ignored;"
                        + " built-in and mod author data apply, the local vehicle-physics.cfg stays ignored");
                return;
            }
            Object v = mRawget.invoke(args, "v");
            if (v instanceof Double && ((Double) v).doubleValue() > PROTOCOL) {
                warn("newer", "server table: the server runs a newer LabVehiclePhysics (table format "
                        + v + ", this one knows " + PROTOCOL + ") - reading the rule lines anyway");
            }
            List<String> got = new ArrayList<String>();
            Object lt = mRawget.invoke(args, "lines");
            if (lt != null && kahluaTable.isInstance(lt)) {
                for (int i = 1; ; i++) {
                    Object s = mRawget.invoke(lt, Double.valueOf(i));
                    if (s == null) {
                        break;
                    }
                    if (i > MAX_LINES) {
                        warn("too-many", "server table: more than " + MAX_LINES + " lines - the rest ignored");
                        break;
                    }
                    if (s instanceof String && ((String) s).length() <= MAX_LINE_CHARS) {
                        got.add((String) s);
                    }
                }
            }
            boolean present = Boolean.TRUE.equals(mRawget.invoke(args, "present"));
            Object st = mRawget.invoke(args, "stamp");
            double stampIn = st instanceof Double ? ((Double) st).doubleValue() : -1.0;
            Object env = fEnv.get(null);

            synchronized (ServerTable.class) {
                // Один и тот же ответ приходит дважды, когда запрос клиента разминулся
                // с рассылкой после правки файла. Переприменять из-за этого скрипты незачем.
                if (received && receivedIn.get() == env && stampIn == stamp && got.equals(lines)) {
                    return;
                }
            }
            List<VehicleCfg.Rule> parsed = VehicleCfg.parseLines(got, "server", "server table");
            synchronized (ServerTable.class) {
                rules = Collections.unmodifiableList(parsed);
                lines = got;
                stamp = stampIn;
                receivedIn = new WeakReference<Object>(env);
                received = true;
                generation++;
            }
            // Подхватить в ближайшем reloadIfNeeded, не дожидаясь двухсекундной паузы.
            VehicleCfg.lastCheckNanos = 0L;
            Log.info("[LabVehiclePhysics] server table received: " + parsed.size() + " rule(s), version "
                    + VehicleCfg.fmt((float) stampIn)
                    + (present ? "" : " - the server has no vehicle-physics.cfg, built-in and mod author data only"));
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] server table: could not read what the server sent: " + t);
        }
    }

    /**
     * Поколение таблицы. Заодно выбрасывает таблицу прошлого подключения: если между
     * сессиями {@code GameClient.client} не успел побыть false у нас на глазах, режим
     * не переключится, и без этой проверки новый сервер начинал бы с чужих чисел.
     */
    public static synchronized int generation() {
        if (received) {
            Object env = null;
            try {
                init();
                env = fEnv.get(null);
            } catch (Throwable ignored) {
            }
            if (env != receivedIn.get()) {
                received = false;
                rules = Collections.<VehicleCfg.Rule>emptyList();
                lines = Collections.<String>emptyList();
                stamp = -1.0;
                generation++;
                Log.info("[LabVehiclePhysics] server table of the previous connection dropped"
                        + " - waiting for this server's table");
            }
        }
        return generation;
    }

    public static void warn(String id, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) {
                return;
            }
        }
        Log.info("[LabVehiclePhysics] " + message);
    }
}
