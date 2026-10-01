package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Данные о машинах от авторов модов — слой 1 из {@code Docs/vehicle-data-design.md}.
 *
 * <h2>Контракт</h2>
 * Автор техники кладёт в свой мод Lua-файл:
 * <pre>
 * LabVehiclePhysicsData = LabVehiclePhysicsData or {}
 * LabVehiclePhysicsData["M60A3"] = { mass = 52000, power = 750, maxSpeed = 48, tank = 659 }
 * </pre>
 * Без нашего мода эта таблица просто лежит в памяти и ничего не делает — второй версии
 * мода автору не нужно, зависимости от нас тоже.
 *
 * <h2>Почему глобальная таблица, а не только функция</h2>
 * Вызов {@code LabVehiclePhysics.register(...)} сработает, только если наш Lua-файл
 * загрузился раньше авторского. Порядок загрузки Lua между модами автор не
 * контролирует: файл {@code AAA_tank.lua} выполнится раньше нашего, и
 * {@code if LabVehiclePhysics then} молча ничего не сделает. Таблица же создаётся тем,
 * кто пришёл первым ({@code X = X or {}}), и переживает любой порядок. Функция
 * {@code register} оставлена как удобная обёртка — она пишет в ту же таблицу.
 *
 * <h2>Как читаем</h2>
 * Из Java, через {@code zombie.Lua.LuaManager.env} — ровно так сама игра читает
 * {@code SmashedCarDefinitions} в {@code BaseVehicle.setSmashed}. Всё рефлексией:
 * мод собирается без классов игры.
 *
 * Таблица перечитывается на той же двухсекундной частоте, что и файл конфига, потому
 * что Lua-файлы модов и скрипты машин грузятся каждый своим порядком. Снимок
 * копируется в Java целиком — ссылки на Lua-объекты не держим.
 *
 * <h2>Проверка значений</h2>
 * Проверяем здесь, а не в Lua: здесь данные потребляются, и сюда же придут записи,
 * сделанные прямой записью в таблицу в обход {@code register}. Негодное поле
 * отбрасывается, остальные поля записи остаются; о каждой проблеме пишем в лог
 * один раз.
 */
public final class LuaRegistry {

    /** Имя глобальной таблицы — это публичный контракт, менять нельзя. */
    public static final String TABLE = "LabVehiclePhysicsData";

    /** Известные категории. Незнакомую принимаем, но предупреждаем: вероятно опечатка. */
    public static final Set<String> CATEGORIES = new HashSet<String>(java.util.Arrays.asList(
            "car", "suv", "pickup", "van", "delivery_van", "light_military",
            "wheeled_armour", "military_truck", "tracked_armour", "trailer"));

    public static volatile boolean broken = false;
    public static Field fEnv;
    public static Method mRawget;
    public static Method mIterator;
    public static Method mAdvance;
    public static Method mGetKey;
    public static Method mGetValue;

    /** Текущий снимок: имя скрипта без модуля -> правило автора. Подменяется целиком. */
    public static volatile Map<String, VehicleCfg.Rule> entries =
            Collections.<String, VehicleCfg.Rule>emptyMap();
    /** Отпечаток содержимого: по нему понимаем, что таблица изменилась. */
    public static String signature = "";
    /** О каких проблемах уже написали — чтобы не повторять каждые две секунды. */
    public static final Set<String> WARNED = new HashSet<String>();

    private LuaRegistry() {
    }

    /**
     * Перечитать таблицу авторов.
     *
     * @return true, если содержимое изменилось с прошлого раза
     */
    public static boolean refresh() {
        if (broken) {
            return false;
        }
        try {
            Object table = globalTable();
            // TreeMap: порядок обхода Lua-таблицы не гарантирован, а отпечаток должен
            // от него не зависеть, иначе мы бы "видели изменения" на пустом месте.
            TreeMap<String, VehicleCfg.Rule> fresh = new TreeMap<String, VehicleCfg.Rule>();
            TreeMap<String, String> prints = new TreeMap<String, String>();
            if (table != null) {
                Object it = mIterator.invoke(table);
                while (((Boolean) mAdvance.invoke(it)).booleanValue()) {
                    Object key = mGetKey.invoke(it);
                    Object value = mGetValue.invoke(it);
                    if (!(key instanceof String)) {
                        warn("key:" + key, TABLE + " has a non-string key (" + describe(key)
                                + ") - entries must be keyed by vehicle script name, e.g. "
                                + TABLE + "[\"M60A3\"] = { ... }");
                        continue;
                    }
                    String bare = VehicleCfg.bareName((String) key);
                    if (value == null || !isTable(value)) {
                        warn(bare + ":entry", TABLE + "[\"" + key + "\"] must be a table, got "
                                + describe(value) + " - entry ignored");
                        continue;
                    }
                    StringBuilder print = new StringBuilder();
                    VehicleCfg.Rule rule = parseEntry(bare, value, print);
                    if (rule != null) {
                        if (fresh.containsKey(bare)) {
                            // "Base.M60A3" и "M60A3" — одна машина. Какая запись победит,
                            // зависит от порядка обхода таблицы, то есть от случая.
                            warn(bare + ":dup", TABLE + " has two entries for the same vehicle '" + bare
                                    + "' (with and without the module prefix) - one of them wins at random, keep one key");
                        }
                        fresh.put(bare, rule);
                        prints.put(bare, print.toString());
                    }
                }
            }
            String sig = prints.toString();
            if (sig.equals(signature)) {
                return false;
            }
            signature = sig;
            entries = Collections.unmodifiableMap(new HashMap<String, VehicleCfg.Rule>(fresh));
            report(fresh);
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR reading " + TABLE + ", author data disabled: " + t);
            return false;
        }
    }

    /** Глобальная таблица авторов или null, если её ещё нет (или Lua ещё не поднят). */
    public static Object globalTable() throws Exception {
        if (fEnv == null) {
            Class<?> lm = Class.forName("zombie.Lua.LuaManager");
            fEnv = lm.getField("env");
            Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable");
            mRawget = kt.getMethod("rawget", Object.class);
            mIterator = kt.getMethod("iterator");
            Class<?> kit = Class.forName("se.krka.kahlua.vm.KahluaTableIterator");
            mAdvance = kit.getMethod("advance");
            mGetKey = kit.getMethod("getKey");
            mGetValue = kit.getMethod("getValue");
        }
        Object env = fEnv.get(null);
        if (env == null) {
            return null;
        }
        Object table = mRawget.invoke(env, TABLE);
        return isTable(table) ? table : null;
    }

    public static boolean isTable(Object o) {
        if (o == null) {
            return false;
        }
        try {
            return Class.forName("se.krka.kahlua.vm.KahluaTable").isInstance(o);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Поля, которые принимаем от авторов. Всё остальное — предупреждение. */
    public static final Set<String> KNOWN = new HashSet<String>(java.util.Arrays.asList(
            "mass", "power", "maxSpeed", "tank", "lowGear", "lowGearTo", "category", "mod"));

    /**
     * Разобрать одну запись. Негодные поля отбрасываются по одному, запись остаётся.
     *
     * Принимаем только паспортные величины. {@code powerMul}, {@code brakeMul} и прочие
     * игровые множители автору не положены: он знает свою машину, но не обязан знать,
     * что тяга в PZ отсчитывается от ванильной легковушки. Тормоза масштабируются от
     * массы сами ({@code brakeMul=auto}), игрок может переопределить в конфиге.
     */
    public static VehicleCfg.Rule parseEntry(String bare, Object entry, StringBuilder print) throws Exception {
        Object it = mIterator.invoke(entry);
        while (((Boolean) mAdvance.invoke(it)).booleanValue()) {
            Object k = mGetKey.invoke(it);
            if (!(k instanceof String) || !KNOWN.contains(k)) {
                String hint = "powerMul".equals(k)
                        ? " - give passport horsepower as 'power' instead, the mod converts it"
                        : " - known fields: mass, power, maxSpeed, tank, lowGear, lowGearTo, category, mod";
                warn(bare + ":field:" + k, TABLE + "[\"" + bare + "\"]: unknown field '" + k + "' ignored" + hint);
            }
        }

        float mass = number(bare, entry, "mass", 50.0, 200000.0, "kg");
        float power = number(bare, entry, "power", 1.0, 5000.0, "hp");
        float maxSpeed = number(bare, entry, "maxSpeed", 1.0, 400.0, "km/h");
        float tank = number(bare, entry, "tank", 1.0, 5000.0, "litres");
        float lowGear = number(bare, entry, "lowGear", 1.0, 10.0, "x");
        float lowGearTo = number(bare, entry, "lowGearTo", 1.0, 200.0, "km/h");
        String category = text(bare, entry, "category", 64);
        String mod = text(bare, entry, "mod", 128);

        if (maxSpeed > 122.0f) {
            warn(bare + ":speedcap", TABLE + "[\"" + bare + "\"]: maxSpeed " + VehicleCfg.fmt(maxSpeed)
                    + " km/h is above the game's hard global limit of about 122 km/h - accepted, but it will not go faster");
        }
        if (lowGearTo > 0.0f && lowGear <= 0.0f) {
            warn(bare + ":lowGearTo", TABLE + "[\"" + bare + "\"]: lowGearTo without lowGear has no effect");
        }
        if (category != null && !CATEGORIES.contains(category)) {
            warn(bare + ":category", TABLE + "[\"" + bare + "\"]: unknown category '" + category
                    + "' kept as is - known: " + new java.util.TreeSet<String>(CATEGORIES));
        }
        if (mass <= 0.0f && power <= 0.0f && maxSpeed <= 0.0f && tank <= 0.0f && lowGear <= 0.0f) {
            warn(bare + ":empty", TABLE + "[\"" + bare + "\"]: no usable values - entry ignored");
            return null;
        }

        print.append("mass=").append(mass).append(" power=").append(power)
             .append(" maxSpeed=").append(maxSpeed).append(" tank=").append(tank)
             .append(" lowGear=").append(lowGear).append('/').append(lowGearTo)
             .append(" category=").append(category).append(" mod=").append(mod);

        return new VehicleCfg.Rule(
                bare,
                mass,
                null,                               // stiffness — ослабитель Bullet снят, не нужен
                null,                               // engine — тягу задаёт power
                maxSpeed,
                0.0f,                               // travel — геометрию подвески не трогаем
                0.0f,                               // rest
                null,                               // powerMul — не принимаем от авторов
                power,
                mass > 0.0f ? "auto" : null,        // тормоза масштабируются вместе с массой
                lowGear,
                lowGearTo,
                false,                              // service — инструмент лаборатории
                tank,
                false,                              // live — инструмент лаборатории
                category,
                "author:" + (mod != null ? mod : "?"));
    }

    public static float number(String bare, Object entry, String key, double min, double max, String unit)
            throws Exception {
        Object v = mRawget.invoke(entry, key);
        if (v == null) {
            return 0.0f;
        }
        if (!(v instanceof Number)) {
            warn(bare + ":" + key + ":type", TABLE + "[\"" + bare + "\"]." + key + " must be a number ("
                    + unit + "), got " + describe(v) + " - field ignored");
            return 0.0f;
        }
        double d = ((Number) v).doubleValue();
        if (!(d >= min && d <= max)) {
            warn(bare + ":" + key + ":range", TABLE + "[\"" + bare + "\"]." + key + " = " + d + " " + unit
                    + " is outside " + min + ".." + max + " - field ignored");
            return 0.0f;
        }
        return (float) d;
    }

    public static String text(String bare, Object entry, String key, int maxLen) throws Exception {
        Object v = mRawget.invoke(entry, key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > maxLen) {
            warn(bare + ":" + key + ":text", TABLE + "[\"" + bare + "\"]." + key
                    + " must be a non-empty string up to " + maxLen + " chars - field ignored");
            return null;
        }
        return (String) v;
    }

    public static void warn(String id, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) {
                return;
            }
        }
        Log.info("[LabVehiclePhysics] author data: " + message);
    }

    public static String describe(Object o) {
        if (o == null) {
            return "nil";
        }
        if (o instanceof String) {
            return "string \"" + o + "\"";
        }
        if (o instanceof Number) {
            return "number " + o;
        }
        if (o instanceof Boolean) {
            return "boolean " + o;
        }
        return isTable(o) ? "table" : o.getClass().getSimpleName();
    }

    /** Сводка при каждом изменении: сколько машин и от каких модов. */
    public static void report(TreeMap<String, VehicleCfg.Rule> fresh) {
        if (fresh.isEmpty()) {
            Log.debug("[LabVehiclePhysics] author data: " + TABLE + " is empty");
            return;
        }
        TreeMap<String, Integer> byMod = new TreeMap<String, Integer>();
        for (VehicleCfg.Rule r : fresh.values()) {
            Integer n = byMod.get(r.source);
            byMod.put(r.source, n == null ? 1 : n + 1);
        }
        Log.info("[LabVehiclePhysics] author data: " + fresh.size() + " vehicle(s) from "
                + byMod + ": " + fresh.keySet());
    }
}
