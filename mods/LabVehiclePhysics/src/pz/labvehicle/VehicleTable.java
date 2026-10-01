package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Таблица машин в песочнице — страница «Физика транспорта: машины».
 *
 * <h2>Хранение</h2>
 * Одна строковая опция песочницы {@code LabVehiclePhysics.VehicleTable}: строки в формате
 * vehicle-physics.cfg через «;», по одной на машину, которую игрок менял.
 * <pre>
 *   97bushAmbulance: preset=tank mass=38000;M60A3: power=800
 * </pre>
 * Слева — точное имя скрипта без модуля: так правило совпадает с именем в обоих видах, в каком
 * его отдаёт игра ({@code "97bushAmbulance"} и {@code "Base.97bushAmbulance"}). Игра сама хранит
 * опцию с миром, в сети держит её на сервере, раздаёт при входе и рассылает правки админа.
 *
 * <h2>Место среди слоёв</h2>
 * Верхний слой: поверх справочника, данных автора мода и файла vehicle-physics.cfg
 * ({@link VehicleCfg#resolveLayers}). Файл остаётся инструментом лаборатории — live, service.
 *
 * <h2>Панель</h2>
 * Здесь же — данные для панели в Lua: список машин, пресеты, значения машины без строки
 * таблицы и с ней. Считает их тот же {@link VehicleCfg}, что и игру, — панель показывает ровно
 * то, что получит машина.
 */
public final class VehicleTable {

    public static final String SEPARATOR = ";";

    private VehicleTable() {
    }

    /** Строка опции -> правила, по одному на машину. Пустая строка — пустой список. */
    public static List<VehicleCfg.Rule> parse(String table) {
        List<String> lines = new ArrayList<String>();
        if (table != null) {
            String[] rows = table.split(SEPARATOR);
            for (int i = 0; i < rows.length; i++) {
                String s = rows[i].trim();
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        return VehicleCfg.parseLines(lines, "sandbox", "sandbox vehicle table");
    }

    /** Сколько машин в таблице — для строки settings: в логе. */
    public static int count(String table) {
        return parse(table).size();
    }

    // ================================================================ данные для панели

    /**
     * Все скрипты машин, по имени: {@code {name, full, mod, vanilla}} — массив Lua-таблиц.
     * {@code mod} — id мода, который первым описал машину, у ванили {@code pz-vanilla}.
     */
    public static Object vehicles() throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
        Object sm = smCls.getField("instance").get(null);
        List<?> scripts = (List<?>) smCls.getMethod("getAllVehicleScripts").invoke(sm);
        List<Object[]> rows = new ArrayList<Object[]>();
        Method mFull = null;
        Method mBodies = null;
        for (int i = 0; i < scripts.size(); i++) {
            Object script = scripts.get(i);
            if (script == null) {
                continue;
            }
            if (mFull == null) {
                mFull = script.getClass().getMethod("getScriptObjectFullType");
                mBodies = script.getClass().getMethod("getLoadedScriptBodies");
            }
            String name = VehicleCfg.scriptName(script);
            String full = (String) mFull.invoke(script);
            List<?> bodies = (List<?>) mBodies.invoke(script);
            String mod = bodies != null && !bodies.isEmpty() ? String.valueOf(bodies.get(0)) : "";
            rows.add(new Object[] {name, full != null ? full : name, mod});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((String) a[0]).compareToIgnoreCase((String) b[0]);
            }
        });
        Object out = newTable();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            Object t = newTable();
            set(t, "name", r[0]);
            set(t, "full", r[1]);
            set(t, "mod", r[2]);
            set(t, "vanilla", Boolean.valueOf("pz-vanilla".equals(r[2])));
            ServerTable.mRawset.invoke(out, Double.valueOf(i + 1), t);
        }
        return out;
    }

    /** Пресеты в порядке файла: массив {@code {id, mass, power, maxSpeed, tank, lowGear, lowGearTo, category}}. */
    public static Object presets() throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        Object out = newTable();
        int i = 0;
        for (Map.Entry<String, VehicleCfg.Rule> e : VehicleCfg.presets.entrySet()) {
            Object t = values(e.getValue());
            set(t, "id", e.getKey());
            ServerTable.mRawset.invoke(out, Double.valueOf(++i), t);
        }
        return out;
    }

    /**
     * Что получит машина: {@code {base, result, game}}.
     * <ul>
     *   <li>{@code base} — без строки таблицы: справочник, автор мода, файл;</li>
     *   <li>{@code result} — со строкой {@code row} (то, что после двоеточия: {@code "preset=tank mass=38000"});
     *       пустая — то же, что base;</li>
     *   <li>{@code game} — числа самой игры: масса, максималка, мощность в л.с. по тяге скрипта.</li>
     * </ul>
     */
    public static Object describe(String name, String row) throws Exception {
        ServerTable.init();
        VehicleCfg.reloadIfNeeded();
        String bare = VehicleCfg.bareName(name);
        VehicleCfg.Rule base = VehicleCfg.resolveLayers(name, false, null);
        VehicleCfg.Rule rowRule = null;
        if (row != null && !row.trim().isEmpty()) {
            List<VehicleCfg.Rule> one = parse(bare + ": " + row.trim());
            rowRule = one.isEmpty() ? null : one.get(0);
        }
        VehicleCfg.Rule result = rowRule != null ? VehicleCfg.resolveLayers(name, false, rowRule) : base;
        Object out = newTable();
        set(out, "base", values(base));
        set(out, "result", values(result));
        set(out, "game", game(name));
        return out;
    }

    /** Поля правила для Lua; null-правило — пустая таблица с source = "". */
    public static Object values(VehicleCfg.Rule r) throws Exception {
        Object t = newTable();
        if (r == null) {
            set(t, "source", "");
            return t;
        }
        num(t, "mass", r.mass);
        num(t, "power", r.powerHp);
        if (r.powerMul != null) {
            set(t, "powerMul", r.powerMul);
        }
        num(t, "maxSpeed", r.maxSpeed);
        num(t, "tank", r.tank);
        num(t, "lowGear", r.lowGear);
        num(t, "lowGearTo", r.lowGear > 0.0f ? r.lowGearTo : 0.0f);
        if (r.category != null) {
            set(t, "category", r.category);
        }
        set(t, "source", r.source != null ? r.source : "");
        return t;
    }

    /** Числа самой игры: из запомненных до нашей записи полей скрипта, иначе — из скрипта как есть. */
    public static Object game(String name) throws Exception {
        Object t = newTable();
        float mass = 0.0f;
        float maxSpeed = 0.0f;
        float engineForce = 0.0f;
        float[] o = VehicleCfg.ORIGINAL_FIELDS.get(VehicleCfg.bareName(name));
        if (o != null) {
            mass = o[0];
            engineForce = o[2];
            maxSpeed = o[3];
        } else {
            Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
            Object sm = smCls.getField("instance").get(null);
            Object script = smCls.getMethod("getVehicle", String.class).invoke(sm, name);
            if (script != null) {
                mass = VehicleCfg.field(script.getClass(), "mass").getFloat(script);
                engineForce = VehicleCfg.field(script.getClass(), "engineForce").getFloat(script);
                maxSpeed = VehicleCfg.field(script.getClass(), "maxSpeed").getFloat(script);
            }
        }
        num(t, "mass", mass);
        num(t, "maxSpeed", maxSpeed);
        // Сколько л.с. соответствует тяге скрипта — обратный пересчёт ключа power.
        num(t, "power", engineForce > 0.0f ? Math.round(engineForce / VehicleCfg.FORCE_PER_HP) : 0.0f);
        return t;
    }

    public static Object newTable() throws Exception {
        return ServerTable.mNewTable.invoke(ServerTable.platform);
    }

    public static void set(Object table, String key, Object value) throws Exception {
        ServerTable.mRawset.invoke(table, key, value);
    }

    /** Число в таблицу, только если задано (больше нуля): в Lua пустое поле — nil. */
    public static void num(Object table, String key, float value) throws Exception {
        if (value > 0.0f) {
            ServerTable.mRawset.invoke(table, key, Double.valueOf(value));
        }
    }
}
