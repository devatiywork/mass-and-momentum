package pz.labvehicle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Таблица реальных масс машин и правил пересчёта остальных характеристик.
 *
 * Зачем файл, а не константы в коде. Машин в игре 280 штук (ваниль + моды), и ни одна
 * из них не весит столько, сколько должна: весь парк сжат в 650..1160 кг, а танк M60A3
 * из мода весит 1104 кг — ровно столько же, сколько ванильный пикап-фургон, потому что
 * моддер скопировал его шаблон и массу не тронул. Подбор правильных чисел — это работа
 * итерациями, и каждая итерация не должна стоить пересборки jar.
 *
 * Формат строки:
 * <pre>
 *   &lt;маска имени&gt;: mass=&lt;кг&gt; [stiffness=auto|&lt;число&gt;] [engine=auto|keep|&lt;число&gt;]
 *                   [maxSpeed=&lt;число&gt;] [live]
 * </pre>
 * Маска — имя скрипта машины, допускается {@code *} в любом месте. Правила проверяются
 * сверху вниз, срабатывает первое подходящее.
 *
 * Значения:
 * <ul>
 *   <li>{@code mass} — масса в килограммах. Игра хранит массу в тех же единицах
 *       (ванильная легковушка 800), так что пересчёт не нужен.</li>
 *   <li>{@code stiffness=auto} — жёсткость подвески умножается на то же отношение,
 *       что и масса. Это гипотеза, которую первая итерация как раз и проверяет:
 *       если Bullet внутри уже нормирует силу пружины на массу шасси, множить не надо.</li>
 *   <li>{@code engine=auto} — тяга умножается на то же отношение, то есть разгон
 *       остаётся прежним. {@code engine=keep} (по умолчанию) — тяга не трогается,
 *       и потяжелевшая машина разгоняется хуже, как в жизни.</li>
 *   <li>{@code live} — масса ещё и подменяется на лету в {@code getFudgedMass()},
 *       который игра отдаёт в Bullet каждый кадр. Файл перечитывается раз в две
 *       секунды, так что число можно крутить прямо во время игры, без перезапуска.
 *       Жёсткость подвески так не подкрутишь — она зашивается один раз при загрузке.</li>
 * </ul>
 */
public final class VehicleCfg {

    public static final String FILE_NAME = "vehicle-physics.cfg";
    public static final long RELOAD_NANOS = 2_000_000_000L;

    public static final class Rule {
        public final String glob;
        public final java.util.regex.Pattern pattern;
        public final float mass;
        public final String stiffness;
        public final String engine;
        public final float maxSpeed;
        /** Ход подвески в сантиметрах. 0 = не трогать. */
        public final float travel;
        /** Длина пружины в покое. 0 = не трогать. */
        public final float rest;
        /** Множитель тяги: число, "auto" (по отношению масс) или null. */
        public final String powerMul;
        /** Множитель торможения: число, "auto" или null. */
        public final String brakeMul;
        /** Множитель тяги на трогании (гидротрансформатор + понижающая). 0 = выключено. */
        public final float lowGear;
        /** Скорость в км/ч, на которой множитель сходит к единице. */
        public final float lowGearTo;
        /** Разовая починка и заправка при перечитывании конфига. */
        public final boolean service;
        /** Объём топливного бака в литрах. 0 = не трогать. */
        public final float tank;
        /**
         * Отношение новой массы к ванильной — запасной путь для {@code auto}.
         *
         * Считать его следует через {@link VehicleCfg#autoRatio}, который берёт
         * ванильную массу из {@link VehicleCfg#VANILLA}. Это поле остаётся
         * на случай, когда имя скрипта до расчёта не дошло.
         */
        public volatile float ratio = 1.0f;
        public final boolean live;
        /**
         * Паспортная мощность в л.с., 0 = не задана. В множитель тяги переводится
         * при использовании, см. {@link VehicleCfg#powerMultiplier}: для этого нужна
         * ванильная тяга конкретного скрипта, а на момент разбора её не видно.
         */
        public final float powerHp;
        /** lowGearTo как задан, без подстановки 30 по умолчанию: нужен для слияния слоёв. */
        public final float lowGearToRaw;
        /** Категория техники от автора мода. Пока только показывается в аудите. */
        public final String category;
        /** Откуда правило: "cfg", "author:&lt;мод&gt;" или слияние обоих. */
        public final String source;
        /**
         * Имя пресета ({@code preset=tank}) или null. Раскрывается при разрешении слоёв
         * ({@link VehicleCfg#withPreset}): поля строки поверх полей пресета.
         */
        public String preset;
        public int matched;
        public boolean printed;

        public Rule(String glob, float mass, String stiffness, String engine, float maxSpeed,
                    float travel, float rest, String powerMul, String brakeMul,
                    float lowGear, float lowGearTo, boolean service, float tank, boolean live) {
            this(glob, mass, stiffness, engine, maxSpeed, travel, rest, powerMul, 0.0f, brakeMul,
                 lowGear, lowGearTo, service, tank, live, null, "cfg");
        }

        public Rule(String glob, float mass, String stiffness, String engine, float maxSpeed,
                    float travel, float rest, String powerMul, float powerHp, String brakeMul,
                    float lowGear, float lowGearTo, boolean service, float tank, boolean live,
                    String category, String source) {
            this.glob = glob;
            this.pattern = java.util.regex.Pattern.compile(globToRegex(glob));
            this.mass = mass;
            this.stiffness = stiffness;
            this.engine = engine;
            this.maxSpeed = maxSpeed;
            this.travel = travel;
            this.rest = rest;
            this.powerMul = powerMul;
            this.powerHp = powerHp;
            this.brakeMul = brakeMul;
            this.lowGear = lowGear;
            this.lowGearToRaw = lowGearTo;
            this.lowGearTo = lowGearTo > 0.0f ? lowGearTo : 30.0f;
            this.service = service;
            this.tank = tank;
            this.live = live;
            this.category = category;
            this.source = source;
        }

        public boolean matches(String name) {
            return name != null && this.pattern.matcher(name).matches();
        }

        /** Задаёт ли правило тягу хоть в каком-то виде. */
        public boolean hasPower() {
            return powerMul != null || powerHp > 0.0f;
        }

        /**
         * Слияние двух слоёв: верхний поверх нижнего. Цепочка такая: автор мода поверх
         * встроенных данных, игрок поверх того, что вышло (vehicle-data-design.md).
         *
         * Поле берётся у верхнего слоя, если он его задал, иначе у нижнего. Так игрок
         * может поправить одну массу и оставить остальное автору и справочнику.
         *
         * Два поля сливаются ПАРАМИ, а не по отдельности:
         * <ul>
         *   <li>тяга — {@code powerMul} и {@code power}. Иначе вышло бы, что игрок задал
         *       мощность в л.с., а победил авторский множитель, потому что множитель
         *       проверяется первым. Кто задал тягу хоть как-то — тот и владеет парой;</li>
         *   <li>понижающая — {@code lowGear} и {@code lowGearTo}: скорость схода без
         *       своего множителя смысла не имеет.</li>
         * </ul>
         *
         * {@code live} и {@code service} — инструменты лаборатории. Их задаёт только игрок,
         * у автора и у встроенных данных их нет; объединяются через «или».
         */
        public static Rule merge(Rule top, Rule base) {
            return merge(top, base, base.glob);
        }

        /** @param glob маска итогового правила — для строки в логе, на выбор правила она уже не влияет. */
        public static Rule merge(Rule top, Rule base, String glob) {
            boolean topPower = top.hasPower();
            boolean topLowGear = top.lowGear > 0.0f;
            return new Rule(
                    glob,
                    top.mass > 0.0f ? top.mass : base.mass,
                    top.stiffness != null ? top.stiffness : base.stiffness,
                    top.engine != null ? top.engine : base.engine,
                    top.maxSpeed > 0.0f ? top.maxSpeed : base.maxSpeed,
                    top.travel > 0.0f ? top.travel : base.travel,
                    top.rest > 0.0f ? top.rest : base.rest,
                    topPower ? top.powerMul : base.powerMul,
                    topPower ? top.powerHp : base.powerHp,
                    top.brakeMul != null ? top.brakeMul : base.brakeMul,
                    topLowGear ? top.lowGear : base.lowGear,
                    topLowGear ? top.lowGearToRaw : base.lowGearToRaw,
                    top.service || base.service,
                    top.tank > 0.0f ? top.tank : base.tank,
                    top.live || base.live,
                    top.category != null ? top.category : base.category,
                    top.source + " over " + base.source);
        }
    }

    public static volatile boolean broken = false;
    public static File file;
    /** volatile: обнуляется из Lua, когда пришла таблица сервера, — чтобы подхватить сразу. */
    public static volatile long lastCheckNanos = 0L;
    public static long lastModified = -1L;
    /**
     * Верхний слой правил: файл игрока, а у клиента в сети — таблица сервера
     * ({@link ServerTable}). Остальной код разницы не видит.
     */
    public static List<Rule> rules = new ArrayList<Rule>();
    /** Клиент в сети: свой файл не читаем, верхний слой — таблица сервера. */
    public static volatile boolean mpMode = false;
    /** Какое поколение таблицы сервера уже подхвачено. */
    public static int seenServerGeneration = -1;
    /** Строки файла игрока без пустых и комментариев — их сервер рассылает клиентам. */
    public static volatile List<String> playerLines = new ArrayList<String>();
    public static volatile boolean playerFilePresent = false;
    /** Растёт при каждом перечитывании файла игрока: по нему сервер видит, что пора разослать заново. */
    public static volatile int playerStamp = 0;
    /** Почему скрипты переприменяются — для строки в логе. */
    public static volatile String reapplyReason = "";
    public static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    public static Method mGetName;
    public static boolean summaryPrinted = false;
    /** Сколько скриптов реально переписано. 0 через несколько секунд = патчер не зацепил класс. */
    public static volatile int appliedCount = 0;
    public static volatile boolean fallbackDone = false;
    public static long firstSeenNanos = 0L;
    /** Есть ли хоть одно правило с {@code live}. Пока нет — живая подмена не стоит ничего. */
    public static volatile boolean hasLive = false;
    /** Есть ли правила, меняющие тягу или тормоза. Пока нет — патч тяги ничего не стоит. */
    public static volatile boolean hasTuning = false;
    /** Есть ли правила с разовой починкой. */
    public static volatile boolean hasService = false;
    /** Есть ли правила, меняющие объём бака. */
    public static volatile boolean hasTank = false;
    /** Самый большой объём бака из правил — до него поднимается обрезка у предметов. */
    public static volatile int largestTank = 0;
    /** Растёт при каждом перечитывании файла: по нему сбрасываются кэши потребителей. */
    public static volatile int generation = 0;

    /**
     * Ванильные массы скриптов, запомненные ДО первой перезаписи. Ключ — имя скрипта.
     *
     * <h2>Зачем понадобилось</h2>
     * {@code brakeMul=auto} означает «отмасштабировать тормоз так же, как массу», то есть
     * умножить на отношение новой массы к ванильной. Раньше это отношение считалось так:
     * <pre>
     * float oldMass = fMass.getFloat(script);
     * float k = rule.mass / oldMass;
     * rule.ratio = k;
     * fMass.setFloat(script, rule.mass);   // и тут же затираем то, из чего считали
     * </pre>
     * Первый проход давал правду. Но {@code applyToScript} зовётся заново при каждом
     * перечитывании конфига, а там {@code oldMass} — уже НАША масса, и выходит
     * {@code 12300/12300 = 1.0}. Плюс если до скрипта дело вообще не дошло, {@code ratio}
     * так и оставался своим значением по умолчанию, а оно тоже 1.0. Обе дороги вели
     * в единицу, и {@code auto} не работал никак.
     *
     * Карта живёт всю сессию и НЕ чистится при перечитывании файла — иначе вернулись бы
     * ровно к той же ошибке. Запись по принципу «кто первый, тот и прав».
     *
     * Тот же класс ошибки, что и с {@code hasLive}: величина считалась из того, что мы
     * сами через строку затираем. Разбор — в {@code modding-notes.md} §12.
     */
    public static final Map<String, Float> VANILLA =
            java.util.Collections.synchronizedMap(new HashMap<String, Float>());

    private VehicleCfg() {
    }

    /**
     * Ключ карты ванильных значений: имя скрипта плюс имя поля.
     *
     * Префикс модуля срезаем намеренно. Игра отдаёт имя в двух видах: у объекта скрипта
     * {@code getName()} это {@code "M113_APC"}, а у машины {@code getScriptName()} —
     * {@code "Base.M113_APC"}. Кладёт в карту одно место, читает другое, и без
     * нормализации они бы промахивались мимо друг друга.
     */
    public static String vanillaKey(String name, String field) {
        String bare = name;
        int dot = bare.lastIndexOf('.');
        if (dot >= 0) {
            bare = bare.substring(dot + 1);
        }
        return bare + "|" + field;
    }

    /** Кэш рефлексии для {@link #noteVanillaMassFromVehicle}. */
    public static Method mVehicleGetScript;

    /**
     * Подсмотреть ванильную массу через скрипт машины, пока её никто не перезаписал.
     *
     * Нужно на случай, когда {@code applyToScript} для этого скрипта не отработал:
     * тогда в поле скрипта всё ещё ванильное число, и это последний момент, когда
     * его видно. Если применение уже было, ключ в карте есть и мы выходим сразу —
     * запись работает по принципу «кто первый, тот и прав».
     */
    public static void noteVanillaMassFromVehicle(Object vehicle, String name) {
        if (vehicle == null || name == null) {
            return;
        }
        synchronized (VANILLA) {
            if (VANILLA.containsKey(vanillaKey(name, "mass"))
                    && VANILLA.containsKey(vanillaKey(name, "engineForce"))) {
                return;
            }
        }
        try {
            if (mVehicleGetScript == null) {
                mVehicleGetScript = vehicle.getClass().getMethod("getScript");
            }
            Object script = mVehicleGetScript.invoke(vehicle);
            noteVanillaFromScript(script, name);
        } catch (Throwable ignored) {
            // не смогли — auto и power просто откатятся на единицу, это безопасно
        }
    }

    /**
     * Запомнить ванильные массу и тягу скрипта. Тяга нужна ключу {@code power}:
     * паспортные л.с. переводятся в множитель относительно ванильной тяги скрипта.
     */
    public static void noteVanillaFromScript(Object script, String name) throws Exception {
        if (script == null || name == null) {
            return;
        }
        Class<?> cls = script.getClass();
        rememberVanilla(name, "mass", field(cls, "mass").getFloat(script));
        rememberVanilla(name, "engineForce", field(cls, "engineForce").getFloat(script));
    }

    /** Запомненное ванильное значение поля, 0 если ещё не видели. */
    public static float vanillaValue(String name, String fieldName) {
        if (name == null) {
            return 0.0f;
        }
        synchronized (VANILLA) {
            Float known = VANILLA.get(vanillaKey(name, fieldName));
            return known != null ? known.floatValue() : 0.0f;
        }
    }

    /**
     * Сколько игровой тяги соответствует одной лошадиной силе.
     *
     * Выводится из ванильной легковушки: у неё 4000 единиц тяги на 800 кг, то есть 5.00
     * на кг, при настоящем седане 140 л.с. на 1350 кг. Нужная тяга для любой машины:
     * <pre>
     *   5.00 x [(л.с./кг) / (140/1350)] x кг  =  5.00 x 1350/140 x л.с.  =  48.2 x л.с.
     * </pre>
     * Масса сокращается — тяга в игре должна быть просто пропорциональна мощности.
     */
    public static final float FORCE_PER_HP = 5.0f * 1350.0f / 140.0f;

    /**
     * Множитель тяги, в каком бы виде она ни была задана.
     *
     * {@code powerMul} — число или auto — главнее: это ручная настройка. Иначе
     * {@code power} в л.с. переводится относительно ванильной тяги скрипта. Пока та
     * не известна, возвращаем единицу: не трогать безопаснее, чем умножить наугад.
     */
    public static float powerMultiplier(Rule rule, String scriptName) {
        if (rule == null) {
            return 1.0f;
        }
        if (rule.powerMul != null) {
            return multiplier(rule.powerMul, rule, scriptName);
        }
        if (rule.powerHp > 0.0f) {
            float vanillaForce = vanillaValue(scriptName, "engineForce");
            if (vanillaForce > 0.0f) {
                return FORCE_PER_HP * rule.powerHp / vanillaForce;
            }
        }
        return 1.0f;
    }

    /**
     * Запомнить ванильное значение поля скрипта, если оно ещё не известно.
     *
     * Касается не только массы: {@code stiffness=auto} и {@code engine=auto} тоже
     * умножают на {@code k}, и если считать их от уже изменённого значения, они пойдут
     * вразнос — 35 -> 224 -> 1434 при каждом перечитывании файла. Раньше это не
     * вылезало только потому, что {@code k} был сломан и равнялся единице: одна ошибка
     * прикрывала другую.
     *
     * @param current значение, которое видно сейчас. Вызывающий обязан передавать его
     *                ДО собственной перезаписи; если запись уже была, вернётся
     *                запомненное ранее, а не подсунутое.
     * @return ванильное значение, 0 если ничего осмысленного не передали
     */
    public static float rememberVanilla(String name, String field, float current) {
        if (name == null) {
            return current;
        }
        String key = vanillaKey(name, field);
        synchronized (VANILLA) {
            Float known = VANILLA.get(key);
            if (known != null) {
                return known.floatValue();
            }
            if (current > 0.0f) {
                VANILLA.put(key, Float.valueOf(current));
                return current;
            }
        }
        return 0.0f;
    }

    /** Поля скрипта, которые пишет {@link #applyToScript}. */
    public static final String[] SCRIPT_FIELDS = {
        "mass", "suspensionStiffness", "engineForce", "maxSpeed", "maxSuspensionTravelCm", "suspensionRestLength",
    };
    /**
     * Исходные числа полей {@link #SCRIPT_FIELDS} по голому имени скрипта, какие бы они ни были,
     * хоть нулевые. Нужны, чтобы вернуть скрипт к игре, когда правило пропало. Запись по принципу
     * «кто первый, тот и прав»: первый раз мы видим скрипт до собственной записи.
     */
    public static final Map<String, float[]> ORIGINAL_FIELDS = new java.util.concurrent.ConcurrentHashMap<String, float[]>();
    /** Голые имена скриптов, в которые мы писали. */
    public static final java.util.Set<String> WRITTEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Сколько скриптов возвращено к числам игры — для строки в логе. */
    public static volatile int restoredCount = 0;

    public static float[] rememberOriginalFields(Object script, String name) throws Exception {
        String bare = bareName(name);
        float[] known = ORIGINAL_FIELDS.get(bare);
        if (known != null) {
            return known;
        }
        Class<?> cls = script.getClass();
        float[] values = new float[SCRIPT_FIELDS.length];
        for (int i = 0; i < SCRIPT_FIELDS.length; i++) {
            values[i] = field(cls, SCRIPT_FIELDS[i]).getFloat(script);
        }
        float[] prev = ORIGINAL_FIELDS.putIfAbsent(bare, values);
        return prev != null ? prev : values;
    }

    public static void restoreFields(Object script, float[] original) throws Exception {
        Class<?> cls = script.getClass();
        for (int i = 0; i < SCRIPT_FIELDS.length; i++) {
            field(cls, SCRIPT_FIELDS[i]).setFloat(script, original[i]);
        }
    }

    /**
     * Масса скрипта, какой её задала игра или автор мода, — до нашего справочника. Для расхода
     * топлива «как в игре» ({@code LabVehicleFuel.lua}). 0 — скрипт ещё не видели.
     */
    public static float originalMass(String name) {
        float[] o = name != null ? ORIGINAL_FIELDS.get(bareName(name)) : null;
        if (o != null && o[0] > 0.0f) {
            return o[0];
        }
        return vanillaValue(name, "mass");
    }

    /**
     * Отношение новой массы к ванильной для {@code auto}.
     *
     * Если ванильная масса скрипта ещё не запомнена, откатываемся на {@code rule.ratio}
     * и дальше на единицу — то есть на «ничего не меняем», что всегда безопаснее,
     * чем умножить на случайное число.
     */
    public static float autoRatio(Rule rule, String scriptName) {
        if (rule == null || rule.mass <= 0.0f) {
            return 1.0f;
        }
        float vanilla = 0.0f;
        if (scriptName != null) {
            synchronized (VANILLA) {
                Float known = VANILLA.get(vanillaKey(scriptName, "mass"));
                if (known != null) {
                    vanilla = known.floatValue();
                }
            }
        }
        if (vanilla > 0.0f) {
            return rule.mass / vanilla;
        }
        return rule.ratio > 0.0f ? rule.ratio : 1.0f;
    }

    public static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("\\A");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.append("\\z").toString();
    }

    /** Путь к файлу: рядом с сейвами лаборатории, чтобы правился без прав админа. */
    public static File resolveFile() {
        if (file == null) {
            String home = System.getProperty("user.home");
            file = new File(new File(home, "Zomboid"), FILE_NAME);
        }
        return file;
    }

    /**
     * Встроенные данные — слой 3 (vehicle-data-design.md): паспорта машин, которые
     * мод везёт с собой. Файл лежит внутри мода, рядом с jar, в том же формате, что
     * и конфиг игрока, с масками.
     *
     * Зачем. До 26.09.2026 все паспорта лежали в vehicle-physics.cfg в папке Zomboid
     * игрока. У человека, который скачает мод, этого файла нет — у него была бы
     * ванильная физика. А в мультиплеере физику машины считает клиент водителя
     * (сервер машины в Bullet вообще не регистрирует: VehicleScript.Loaded() зовёт
     * toBullet() только при !GameServer.server), так что гость без файла ездил бы
     * на ванили даже на сервере с модом.
     */
    public static final String DEFAULTS_NAME = "vehicle-physics-defaults.cfg";
    public static File defaultsFile;
    public static long defaultsModified = -1L;
    public static List<Rule> defaults = new ArrayList<Rule>();

    public static File resolveDefaultsFile() {
        if (defaultsFile != null) {
            return defaultsFile;
        }
        // jar лежит в <мод>/42/media/java/, файл — в <мод>/42/media/
        try {
            java.security.CodeSource cs = VehicleCfg.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File jar = new File(cs.getLocation().toURI());
                File candidate = new File(jar.getParentFile().getParentFile(), DEFAULTS_NAME);
                if (candidate.exists()) {
                    defaultsFile = candidate;
                    Log.debug("[LabVehiclePhysics] built-in vehicle data: " + candidate.getAbsolutePath());
                    return defaultsFile;
                }
            }
        } catch (Throwable ignored) {
        }
        // Запасной путь — папка мода по его id.
        try {
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem");
            Object inst = zfs.getField("instance").get(null);
            Object dir = zfs.getMethod("getModDir", String.class).invoke(inst, LabGate.MOD_ID);
            if (dir != null) {
                for (String sub : new String[] {"42/media", "media"}) {
                    File candidate = new File(new File((String) dir, sub), DEFAULTS_NAME);
                    if (candidate.exists()) {
                        defaultsFile = candidate;
                        Log.debug("[LabVehiclePhysics] built-in vehicle data: " + candidate.getAbsolutePath());
                        return defaultsFile;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!defaultsMissingLogged) {
            defaultsMissingLogged = true;
            Log.info("[LabVehiclePhysics] built-in vehicle data NOT FOUND (" + DEFAULTS_NAME
                    + " next to the jar) - only the player's file and mod authors' data will apply");
        }
        return null;
    }

    public static boolean defaultsMissingLogged = false;

    /**
     * Пресеты по типу техники: танк, броневик, легковая, прицеп и т.д. Готовые наборы для
     * машин, о которых мод ничего не знает. Игрок выбирает пресет ключом {@code preset=}
     * в своей строке, поля строки главнее полей пресета ({@link #withPreset}).
     */
    public static final String PRESETS_NAME = "vehicle-physics-presets.cfg";
    public static long presetsModified = -1L;
    /** Имя пресета в нижнем регистре -> правило. Заменяется целиком при перечитывании. */
    public static volatile Map<String, Rule> presets = new HashMap<String, Rule>();
    public static final java.util.Set<String> WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Раскрыть ссылку на пресет: поля строки поверх полей пресета. Без ссылки — как есть. */
    public static Rule withPreset(Rule rule) {
        if (rule == null || rule.preset == null) {
            return rule;
        }
        Rule p = presets.get(rule.preset);
        if (p == null) {
            if (WARNED.add("preset:" + rule.preset)) {
                Log.info("[LabVehiclePhysics] vehicle presets: unknown preset '" + rule.preset + "' in rule "
                        + rule.glob + " - ignored, known: " + String.join(", ", presets.keySet()));
            }
            return rule;
        }
        return Rule.merge(rule, p, rule.glob);
    }

    /**
     * Перечитывает источники, если они изменились. Проверка не чаще раза в две секунды.
     *
     * Верхний слой берётся из одного из двух мест: в одиночной игре и на сервере — файл
     * игрока, у клиента в сети — таблица сервера ({@link ServerTable}). Свой файл клиент
     * в сети не читает совсем: иначе одна машина весила бы по-разному у разных игроков.
     */
    public static void reloadIfNeeded() {
        // Режим смотрим на каждом вызове, в обход двухсекундной паузы: скрипты машин
        // грузятся пачкой за доли секунды сразу после подключения, и все они должны
        // увидеть, что мы уже в сети, а не только те, что придут через две секунды.
        boolean mp = ServerTable.isMpClient();
        boolean modeChanged = mp != mpMode;
        long now = System.nanoTime();
        if (!modeChanged && lastCheckNanos != 0L && now - lastCheckNanos < RELOAD_NANOS) {
            return;
        }
        lastCheckNanos = now;
        boolean fileChanged = false;
        boolean serverChanged = false;
        File f = resolveFile();
        if (modeChanged) {
            mpMode = mp;
            fileChanged = true;
            if (mp) {
                Log.info("[LabVehiclePhysics] multiplayer: the local " + FILE_NAME
                        + " is not used on a server - waiting for the server's table,"
                        + " built-in and mod author data apply meanwhile");
            } else {
                lastModified = -1L;      // вернулись в одиночную игру — перечитать свой файл
            }
        }
        if (!mpMode) {
            long mod = f.exists() ? f.lastModified() : 0L;
            if (mod != lastModified) {
                lastModified = mod;
                List<String> lines = readLines(f);
                rules = parseLines(lines, "cfg", FILE_NAME);
                playerLines = meaningfulLines(lines);
                playerFilePresent = f.exists();
                playerStamp++;
                fileChanged = true;
            }
        } else {
            int g = ServerTable.generation();
            if (modeChanged || g != seenServerGeneration) {
                // При входе в сеть скрипты ещё только будут грузиться — переприменять
                // нечего. А вот таблица, пришедшая в игре, требует переприменения.
                serverChanged = !modeChanged;
                seenServerGeneration = g;
                rules = ServerTable.rules;
                fileChanged = true;
            }
        }
        // Встроенные данные мода тоже перечитываем по дате: в лаборатории их удобно
        // править на ходу, а у игрока файл просто не меняется, и проверка ничего не стоит.
        File df = resolveDefaultsFile();
        long dmod = (df != null && df.exists()) ? df.lastModified() : 0L;
        if (dmod != defaultsModified) {
            defaultsModified = dmod;
            defaults = df != null ? parse(df, "builtin") : new ArrayList<Rule>();
            fileChanged = true;
            Log.debug("[LabVehiclePhysics] built-in vehicle data: rules loaded " + defaults.size());
        }
        // Пресеты по типу техники лежат рядом со справочником и перечитываются так же.
        File pf = df != null ? new File(df.getParentFile(), PRESETS_NAME) : null;
        long pmod = (pf != null && pf.exists()) ? pf.lastModified() : 0L;
        if (pmod != presetsModified) {
            presetsModified = pmod;
            Map<String, Rule> loaded = new java.util.LinkedHashMap<String, Rule>();
            if (pf != null && pf.exists()) {
                List<Rule> list = parse(pf, "preset");
                for (int i = 0; i < list.size(); i++) {
                    loaded.put(list.get(i).glob.toLowerCase(java.util.Locale.ROOT), list.get(i));
                }
            }
            presets = loaded;
            fileChanged = true;
            Log.debug("[LabVehiclePhysics] vehicle presets: " + loaded.size() + " loaded"
                    + (loaded.isEmpty() ? " (" + PRESETS_NAME + " not found next to the built-in data)"
                                        : " - " + String.join(", ", loaded.keySet())));
        }
        // Данные авторов модов живут в Lua и могут появиться позже файла: Lua-файлы
        // модов грузятся своим порядком, а скрипты машин — своим. Поэтому таблицу
        // смотрим на той же двухсекундной частоте, а не один раз при старте.
        boolean authorsChanged = LuaRegistry.refresh();
        // Переключатель «Ванильные машины» в песочнице включает и выключает встроенный
        // справочник для ванильных скриптов — та же смена слоя, что и новые данные авторов.
        // Значения мира приходят позже загрузки скриптов (из сейва или от сервера), поэтому
        // смену ловим здесь, а не при загрузке.
        boolean custom = LabSettings.vanillaCustom();
        boolean vanillaChanged = custom != useBuiltinForVanilla;
        if (vanillaChanged) {
            useBuiltinForVanilla = custom;
        }
        // Таблица машин из песочницы — верхний слой; приходит так же поздно, как переключатель.
        String table = LabSettings.vehicleTable();
        boolean tableChanged = !table.equals(appliedTable);
        if (tableChanged) {
            appliedTable = table;
            sandboxRules = VehicleTable.parse(table);
            Log.debug("[LabVehiclePhysics] sandbox vehicle table: " + sandboxRules.size() + " vehicle(s)"
                    + (sandboxRules.isEmpty() ? "" : " - " + table));
        }
        if (!fileChanged && !authorsChanged && !vanillaChanged && !tableChanged) {
            return;
        }
        recomputeFlags();
        MERGED.clear();
        generation++;
        if (fileChanged && !mpMode) {
            Log.debug("[LabVehiclePhysics] vehicle masses: rules loaded " + rules.size()
                    + " of " + f.getAbsolutePath() + (hasLive ? ", live tuning active" : ""));
        }
        if (authorsChanged || serverChanged || vanillaChanged || tableChanged) {
            // Скрипты могли загрузиться раньше, чем пришли данные авторов или таблица
            // сервера, и тогда числа до скрипта не дошли. Переприменять прямо здесь нельзя:
            // метод зовётся из патчей в любом контексте, в том числе посреди физики.
            // Ставим флажок — отработает BaseVehicle.update, это главный поток.
            List<String> why = new ArrayList<String>();
            if (authorsChanged) {
                why.add("author data changed");
            }
            if (serverChanged) {
                why.add("server table changed");
            }
            if (vanillaChanged) {
                why.add("vanilla vehicles switched to " + (custom ? "mod values" : "standard"));
            }
            if (tableChanged) {
                why.add("sandbox vehicle table changed");
            }
            reapplyReason = String.join(", ", why);
            reapplyPending = true;
        }
    }

    /**
     * Действует ли встроенный справочник на ванильные машины — значение переключателя
     * песочницы, принятое в {@link #reloadIfNeeded}. Читается в {@link #resolve}: там нужно
     * то значение, под которое сброшен кэш правил, а не свежее.
     */
    public static volatile boolean useBuiltinForVanilla = true;

    /** Голое имя скрипта -> ванильный ли он. Происхождение скрипта за сессию не меняется. */
    public static final Map<String, Boolean> VANILLA_SCRIPTS = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    public static Object scriptManager;
    public static Method mSmGetVehicle;
    public static Method mLoadedBodies;

    /**
     * Ванильная ли машина. Скрипт помнит, откуда пришло каждое его тело: пары «id мода, текст»
     * в {@code getLoadedScriptBodies()}, у файлов самой игры id — {@code pz-vanilla}. Ванильная —
     * та, что впервые описана игрой; мод, дописавший к ней что-то, её такой и оставляет.
     */
    public static boolean isVanillaScript(String name) {
        if (name == null) {
            return false;
        }
        String bare = bareName(name);
        Boolean known = VANILLA_SCRIPTS.get(bare);
        if (known != null) {
            return known.booleanValue();
        }
        try {
            if (mSmGetVehicle == null) {
                Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
                scriptManager = smCls.getField("instance").get(null);
                mSmGetVehicle = smCls.getMethod("getVehicle", String.class);
            }
            Object script = mSmGetVehicle.invoke(scriptManager, name);
            if (script == null) {
                return false;      // скрипт ещё не загружен — не кэшируем, спросим позже
            }
            return noteScriptOrigin(script, name);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Запомнить происхождение скрипта, пока он в руках. @return ванильный ли он */
    public static boolean noteScriptOrigin(Object script, String name) {
        boolean vanilla = false;
        try {
            if (mLoadedBodies == null) {
                mLoadedBodies = script.getClass().getMethod("getLoadedScriptBodies");
            }
            Object bodies = mLoadedBodies.invoke(script);
            vanilla = bodies instanceof List && !((List<?>) bodies).isEmpty()
                    && "pz-vanilla".equals(((List<?>) bodies).get(0));
        } catch (Throwable ignored) {
            // не смогли — считаем модовой: справочник к ней применится, как и раньше
        }
        VANILLA_SCRIPTS.put(bareName(name), Boolean.valueOf(vanilla));
        return vanilla;
    }

    /**
     * Флаги-выключатели для патчей, по обоим источникам сразу.
     *
     * Раньше считались только по файлу. С данными авторов это дало бы тихий отказ:
     * мод регистрирует бак на 659 литров, а {@code hasTank} остаётся false, и патч
     * бака даже не смотрит в сторону этой машины.
     */
    public static void recomputeFlags() {
        boolean live = false;
        boolean tuning = false;
        boolean svc = false;
        boolean tank = false;
        float biggest = 0.0f;
        List<Rule> all = new ArrayList<Rule>(rules);
        all.addAll(defaults);
        all.addAll(LuaRegistry.entries.values());
        all.addAll(sandboxRules);
        // Пресеты, на которые ссылаются правила: их тяга и бак тоже должны включить патчи.
        int own = all.size();
        for (int i = 0; i < own; i++) {
            String id = all.get(i).preset;
            Rule p = id != null ? presets.get(id) : null;
            if (p != null) {
                all.add(p);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Rule r0 = all.get(i);
            live |= r0.live;
            tuning |= r0.hasPower() || r0.brakeMul != null || r0.lowGear > 0.0f;
            svc |= r0.service;
            tank |= r0.tank > 0.0f;
            if (r0.tank > biggest) {
                biggest = r0.tank;
            }
        }
        hasLive = live;
        hasTuning = tuning;
        hasService = svc;
        hasTank = tank;
        largestTank = Math.round(biggest);
    }

    /** Кэш слитых правил по имени скрипта. {@link #NONE} — правила нет. Чистится вместе с generation. */
    public static final Map<String, Object> MERGED = new java.util.concurrent.ConcurrentHashMap<String, Object>();
    public static final Object NONE = new Object();
    /** Данные авторов или таблица сервера изменились, скрипты надо переприменить из главного потока. */
    public static volatile boolean reapplyPending = false;

    /**
     * Переприменить скрипты после того, как появились или изменились данные авторов
     * или пришла таблица сервера. Зовётся из {@code BaseVehicle.update} — главный поток,
     * безопасно звать toBullet().
     */
    public static void reapplyIfPending() {
        if (!reapplyPending) {
            return;
        }
        reapplyPending = false;
        int restoredBefore = restoredCount;
        int done = applyToAllScripts();
        int refreshed = refreshVehicles();
        int restored = restoredCount - restoredBefore;
        Log.debug("[LabVehiclePhysics] " + reapplyReason + " - vehicle scripts re-applied: " + done
                + (restored > 0 ? " (returned to game values: " + restored + ")" : "")
                + ", vehicles already in the world updated: " + refreshed);
        printAudit();
    }

    public static Method mVehScript;
    public static Method mSetMaxSpeed;
    public static Method mSetInitialMass;
    public static Method mUpdateTotalMass;
    public static Method mScriptGetMass;
    public static boolean refreshFailedLogged = false;

    /**
     * Донести переписанный скрипт до машин, которые уже стоят в мире.
     *
     * Машина копирует массу и максималку из скрипта ровно один раз — при создании физики:
     * <pre>
     * // BaseVehicle.createPhysics(boolean)
     * this.setMaxSpeed(this.getScript().maxSpeed);
     * this.setInitialMass(this.getScript().getMass());
     * ...
     * this.updateTotalMass();   // масса = initialMass + груз, и в Bullet
     * </pre>
     * Других мест, где их пишут, в игре нет. Переписать скрипт после этого мало: машина
     * рядом с игроком осталась бы со старыми числами, пока чанк не выгрузится. В сети это
     * правило, а не исключение: таблица сервера приходит уже после загрузки мира.
     *
     * Тяга, тормоза и бак сюда не нужны: они читаются из правил на ходу.
     *
     * @return сколько машин обновлено, -1 если не получилось
     */
    public static int refreshVehicles() {
        try {
            Object world = Class.forName("zombie.iso.IsoWorld").getField("instance").get(null);
            Object cell = world.getClass().getMethod("getCell").invoke(world);
            if (cell == null) {
                return 0;
            }
            Object set = cell.getClass().getMethod("getVehicles").invoke(cell);
            if (!(set instanceof java.util.Collection)) {
                return 0;
            }
            int n = 0;
            for (Object v : new ArrayList<Object>((java.util.Collection<?>) set)) {
                if (v == null) {
                    continue;
                }
                if (mVehScript == null) {
                    Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle");
                    mVehScript = bv.getMethod("getScript");
                    mSetMaxSpeed = bv.getMethod("setMaxSpeed", float.class);
                    mSetInitialMass = bv.getMethod("setInitialMass", float.class);
                    mUpdateTotalMass = bv.getMethod("updateTotalMass");
                }
                Object script = mVehScript.invoke(v);
                if (script == null) {
                    continue;
                }
                if (mScriptGetMass == null) {
                    mScriptGetMass = script.getClass().getMethod("getMass");
                }
                mSetMaxSpeed.invoke(v, Float.valueOf(field(script.getClass(), "maxSpeed").getFloat(script)));
                mSetInitialMass.invoke(v, mScriptGetMass.invoke(script));
                mUpdateTotalMass.invoke(v);
                n++;
            }
            return n;
        } catch (Throwable t) {
            if (!refreshFailedLogged) {
                refreshFailedLogged = true;
                Log.info("[LabVehiclePhysics] could not update vehicles already in the world ("
                        + t + ") - they get the new values when their chunk reloads");
            }
            return -1;
        }
    }

    /** @param layer "cfg" для файла игрока, "builtin" для встроенных данных мода — идёт в подпись источника. */
    public static List<Rule> parse(File f, String layer) {
        return parseLines(readLines(f), layer, f.getName());
    }

    /** Строки файла как есть. Пустой список, если файла нет или он не читается. */
    public static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (!f.exists()) {
            return out;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] vehicle masses: could not read " + f + ": " + t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /**
     * Строки с правилами — без пустых и комментариев. Их сервер и рассылает: комментарии
     * хозяина файла — его заметки, игрокам они ни к чему.
     */
    public static List<String> meaningfulLines(List<String> lines) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Разбор правил из строк. Источник строк неважен — свой файл, встроенные данные мода
     * или таблица, присланная сервером; формат один.
     *
     * @param label откуда строки, для сообщений об ошибках: имя файла или "server table"
     */
    public static List<Rule> parseLines(List<String> lines, String layer, String label) {
        List<Rule> out = new ArrayList<Rule>();
        try {
            int lineNo = 0;
            for (int li = 0; li < lines.size(); li++) {
                String line = lines.get(li);
                lineNo++;
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int colon = s.indexOf(':');
                if (colon <= 0) {
                    Log.info("[LabVehiclePhysics] vehicle masses: " + label + " line " + lineNo + " has no colon, skipping: " + s);
                    continue;
                }
                String glob = s.substring(0, colon).trim();
                String tail = s.substring(colon + 1).trim();
                float mass = 0.0f;
                float maxSpeed = 0.0f;
                float travel = 0.0f;
                float rest = 0.0f;
                float lowGear = 0.0f;
                float lowGearTo = 0.0f;
                String stiffness = null;
                String engine = null;
                String powerMul = null;
                float powerHp = 0.0f;
                String brakeMul = null;
                boolean live = false;
                boolean service = false;
                float tank = 0.0f;
                String category = null;
                String preset = null;
                String[] parts = tail.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i];
                    if (p.isEmpty()) {
                        continue;
                    }
                    if ("live".equalsIgnoreCase(p)) {
                        live = true;
                        continue;
                    }
                    // service — инструмент лаборатории; в сборке для Мастерской это просто
                    // неизвестное слово, и строка лога об этом скажет.
                    if (Dev.ENABLED && "service".equalsIgnoreCase(p)) {
                        service = true;
                        continue;
                    }
                    int eq = p.indexOf('=');
                    if (eq <= 0) {
                        Log.info("[LabVehiclePhysics] vehicle masses: " + label + " line " + lineNo + ", unrecognised token '" + p + "'");
                        continue;
                    }
                    String k = p.substring(0, eq).trim();
                    String v = p.substring(eq + 1).trim();
                    // Ошибка в одном числе теряет только этот ключ. Раньше исключение
                    // улетало в общий catch и обрывало разбор всего файла: опечатка в одной
                    // строке молча выключала все правила ниже неё.
                    try {
                        if ("mass".equalsIgnoreCase(k)) {
                            mass = Float.parseFloat(v);
                        } else if ("stiffness".equalsIgnoreCase(k)) {
                            stiffness = v;
                        } else if ("engine".equalsIgnoreCase(k)) {
                            engine = v;
                        } else if ("maxSpeed".equalsIgnoreCase(k)) {
                            maxSpeed = Float.parseFloat(v);
                        } else if ("travel".equalsIgnoreCase(k)) {
                            travel = Float.parseFloat(v);
                        } else if ("rest".equalsIgnoreCase(k)) {
                            rest = Float.parseFloat(v);
                        } else if ("powerMul".equalsIgnoreCase(k)) {
                            powerMul = v;
                        } else if ("power".equalsIgnoreCase(k)) {
                            powerHp = Float.parseFloat(v);
                        } else if ("brakeMul".equalsIgnoreCase(k)) {
                            brakeMul = v;
                        } else if ("lowGear".equalsIgnoreCase(k)) {
                            lowGear = Float.parseFloat(v);
                        } else if ("lowGearTo".equalsIgnoreCase(k)) {
                            lowGearTo = Float.parseFloat(v);
                        } else if ("tank".equalsIgnoreCase(k)) {
                            tank = Float.parseFloat(v);
                        } else if ("category".equalsIgnoreCase(k)) {
                            category = v;
                        } else if ("preset".equalsIgnoreCase(k)) {
                            preset = v.toLowerCase(java.util.Locale.ROOT);
                        } else {
                            Log.info("[LabVehiclePhysics] vehicle masses: " + label + " line " + lineNo + ", unknown key '" + k + "'");
                        }
                    } catch (NumberFormatException e) {
                        Log.info("[LabVehiclePhysics] vehicle masses: " + label + " line " + lineNo
                                + ", '" + k + "' is not a number: '" + v + "' - key ignored, the rest of the line still applies");
                    }
                }
                Rule rule = new Rule(glob, mass, stiffness, engine, maxSpeed, travel, rest,
                                     powerMul, powerHp, brakeMul, lowGear, lowGearTo, service, tank, live,
                                     category, layer + "[" + glob + "]");
                rule.preset = preset;
                out.add(rule);
            }
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] vehicle masses: could not parse " + label + ": " + t);
        }
        return out;
    }

    /**
     * Правило для машины: конфиг игрока поверх данных автора мода.
     *
     * Зовётся каждый кадр на каждую машину из нескольких патчей, поэтому результат
     * кэшируется по имени и сбрасывается вместе с {@link #generation}.
     */
    public static Rule forName(String name) {
        reloadIfNeeded();
        if (name == null) {
            return null;
        }
        Object cached = MERGED.get(name);
        if (cached != null) {
            return cached == NONE ? null : (Rule) cached;
        }
        Rule rule = resolve(name);
        MERGED.put(name, rule == null ? NONE : rule);
        return rule;
    }

    /** Имя скрипта без префикса модуля: "Base.M60A3" -> "M60A3". */
    public static String bareName(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot + 1 < name.length()) ? name.substring(dot + 1) : name;
    }

    /**
     * Без кэша: четыре слоя, каждый следующий перекрывает предыдущий по полю.
     * <pre>
     *   встроенные данные мода   (vehicle-physics-defaults.cfg внутри мода)
     *   данные автора техники     (Lua-таблица LabVehiclePhysicsData в его моде)
     *   файл игрока               (vehicle-physics.cfg в папке Zomboid;
     *                              у клиента в сети — таблица сервера)
     *   таблица песочницы         (страница «Физика транспорта: машины», {@link VehicleTable})
     * </pre>
     * Автор знает свою машину точнее, чем наш общий справочник; игрок — хозяин
     * своей игры и решает последним. В сети хозяин игры — сервер. Таблица песочницы — то,
     * что игрок выбрал в интерфейсе, поэтому она верхняя; файл остаётся инструментом
     * лаборатории. Ссылка на пресет раскрывается внутри своего слоя ({@link #withPreset}).
     *
     * Переключатель песочницы «Ванильные машины: стандарт» убирает у ванильных машин только
     * встроенный справочник: данные авторов модов, файл и таблица — явный выбор, они действуют.
     */
    public static Rule resolve(String name) {
        return resolveLayers(name, true, null);
    }

    /**
     * Слои по порядку, верхний последним: справочник, автор мода, файл, таблица песочницы.
     *
     * @param withTable учитывать ли строку таблицы песочницы ({@link VehicleTable})
     * @param tableRow  своя строка вместо строки таблицы — предпросмотр в панели; null — нет
     */
    public static Rule resolveLayers(String name, boolean withTable, Rule tableRow) {
        String bare = bareName(name);
        Rule builtin = withPreset(!useBuiltinForVanilla && isVanillaScript(name) ? null : firstMatch(defaults, name, bare));
        Rule author = LuaRegistry.entries.get(bare);
        Rule player = withPreset(firstMatch(rules, name, bare));
        Rule table = tableRow != null ? withPreset(tableRow)
                : withTable ? withPreset(firstMatch(sandboxRules, name, bare)) : null;
        Rule r = builtin;
        if (author != null) {
            r = r == null ? author : Rule.merge(author, r);
        }
        if (player != null) {
            r = r == null ? player : Rule.merge(player, r);
        }
        if (table != null) {
            r = r == null ? table : Rule.merge(table, r);
        }
        return r;
    }

    /** Строки таблицы песочницы ({@link VehicleTable}), верхний слой. Заменяется целиком. */
    public static volatile List<Rule> sandboxRules = new ArrayList<Rule>();
    /** Строка опции, под которую разобраны {@link #sandboxRules}. */
    public static volatile String appliedTable = "";

    /**
     * Первое подходящее правило слоя. Имя приходит в двух видах: VehicleScript.getName()
     * отдаёт "97bushAmbulance", а BaseVehicle.getScriptName() — "Base.97bushAmbulance",
     * с префиксом модуля (в игре на это даже стоит assert name.contains(".")). Пробуем оба,
     * чтобы маску в файле не приходилось писать с ведущей звёздочкой.
     */
    public static Rule firstMatch(List<Rule> rs, String name, String bare) {
        for (int i = 0; i < rs.size(); i++) {
            Rule rule = rs.get(i);
            if (rule.matches(name) || (!bare.equals(name) && rule.matches(bare))) {
                return rule;
            }
        }
        return null;
    }

    public static Field field(Class<?> cls, String name) throws Exception {
        Field f = FIELDS.get(name);
        if (f == null) {
            f = cls.getDeclaredField(name);
            f.setAccessible(true);
            FIELDS.put(name, f);
        }
        return f;
    }

    public static String scriptName(Object script) throws Exception {
        if (mGetName == null) {
            mGetName = script.getClass().getMethod("getName");
        }
        return (String) mGetName.invoke(script);
    }

    /**
     * Переписывает поля VehicleScript до того, как {@code Loaded()} отправит их в Bullet.
     * Вызывается один раз на каждый скрипт машины при загрузке игры.
     */
    public static void applyToScript(Object script) {
        if (!LabGate.active()) {
            return;
        }
        if (broken || script == null) {
            return;
        }
        try {
            String name = scriptName(script);
            noteScriptOrigin(script, name);
            // Исходные числа всех полей, которые мы трогаем, — до первой нашей записи.
            float[] original = rememberOriginalFields(script, name);
            Rule rule = forName(name);
            if (rule == null || (rule.mass <= 0.0f && rule.travel <= 0.0f && rule.rest <= 0.0f
                    && rule.stiffness == null && rule.engine == null && rule.maxSpeed <= 0.0f)) {
                // Правила больше нет — переключатель в песочнице, правка файла, другая таблица
                // сервера. Если мы в этот скрипт писали, вернуть ему числа игры.
                if (WRITTEN.remove(bareName(name))) {
                    restoreFields(script, original);
                    restoredCount++;
                    appliedCount++;      // чтобы обход переслал скрипт в Bullet
                }
                return;
            }
            Class<?> cls = script.getClass();
            // Сначала всё к исходным числам: поле, которое правило больше не задаёт, не должно
            // остаться с прошлым нашим значением.
            restoreFields(script, original);
            Field fMass = field(cls, "mass");
            float oldMass = fMass.getFloat(script);
            if (oldMass <= 0.0f) {
                return;
            }
            // Все ванильные значения берём из карты, а не из скрипта: этот метод
            // вызывается заново при каждом перечитывании конфига, и в полях скрипта
            // ко второму разу лежат уже наши числа. Считать от них — значит либо
            // получить отношение 1.0 (масса), либо пойти вразнос (жёсткость, тяга).
            float vanillaMass = rememberVanilla(name, "mass", oldMass);
            // Тягу запоминаем всегда, а не только когда правило её меняет: ключ power
            // переводит паспортные л.с. в множитель именно относительно неё.
            rememberVanilla(name, "engineForce", field(cls, "engineForce").getFloat(script));
            // Массу пишем, только если она задана. Раньше правило без массы, но с
            // maxSpeed или ходом подвески проходило верхнюю проверку и доходило до
            // fMass.setFloat(script, 0) — машина получала нулевую массу. В конфиге таких
            // правил не было, а у авторов модов масса не обязательна: мод вправе прислать
            // только бак и максималку.
            float k = (rule.mass > 0.0f && vanillaMass > 0.0f) ? rule.mass / vanillaMass : 1.0f;
            rule.ratio = k;
            if (rule.mass > 0.0f) {
                fMass.setFloat(script, rule.mass);
            }

            float oldStiff = 0.0f;
            float newStiff = 0.0f;
            if (rule.stiffness != null) {
                Field fStiff = field(cls, "suspensionStiffness");
                oldStiff = rememberVanilla(name, "suspensionStiffness", fStiff.getFloat(script));
                newStiff = "auto".equalsIgnoreCase(rule.stiffness) ? oldStiff * k : Float.parseFloat(rule.stiffness);
                fStiff.setFloat(script, newStiff);
            }

            float oldEngine = 0.0f;
            float newEngine = 0.0f;
            if (rule.engine != null && !"keep".equalsIgnoreCase(rule.engine)) {
                Field fEngine = field(cls, "engineForce");
                oldEngine = rememberVanilla(name, "engineForce", fEngine.getFloat(script));
                newEngine = "auto".equalsIgnoreCase(rule.engine) ? oldEngine * k : Float.parseFloat(rule.engine);
                fEngine.setFloat(script, newEngine);
            }

            if (rule.maxSpeed > 0.0f) {
                Field fSpeed = field(cls, "maxSpeed");
                fSpeed.setFloat(script, rule.maxSpeed);
            }

            float oldTravel = 0.0f;
            if (rule.travel > 0.0f) {
                Field fTravel = field(cls, "maxSuspensionTravelCm");
                oldTravel = fTravel.getFloat(script);
                fTravel.setFloat(script, rule.travel);
            }
            float oldRest = 0.0f;
            if (rule.rest > 0.0f) {
                Field fRest = field(cls, "suspensionRestLength");
                oldRest = fRest.getFloat(script);
                fRest.setFloat(script, rule.rest);
            }
            WRITTEN.add(bareName(name));

            rule.matched++;
            appliedCount++;
            if (!rule.printed) {
                rule.printed = true;
                StringBuilder sb = new StringBuilder();
                sb.append("[LabVehiclePhysics] mass: ").append(name)
                  .append("  ").append(fmt(oldMass)).append(" -> ").append(fmt(rule.mass))
                  .append(" kg (x").append(fmt(k)).append(")");
                if (rule.stiffness != null) {
                    sb.append(", suspension ").append(fmt(oldStiff)).append(" -> ").append(fmt(newStiff));
                }
                if (rule.engine != null && !"keep".equalsIgnoreCase(rule.engine)) {
                    sb.append(", power ").append(fmt(oldEngine)).append(" -> ").append(fmt(newEngine));
                }
                if (rule.travel > 0.0f) {
                    sb.append(", travel ").append(fmt(oldTravel)).append(" -> ").append(fmt(rule.travel)).append(" cm");
                }
                if (rule.rest > 0.0f) {
                    sb.append(", spring ").append(fmt(oldRest)).append(" -> ").append(fmt(rule.rest));
                }
                if (rule.live) {
                    sb.append(", live tuning enabled");
                }
                sb.append("   [rule ").append(rule.glob).append("]");
                Log.debug(sb.toString());
            }
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the mass table, disabling: " + t);
        }
    }

    /**
     * Запасной путь: пройти по всем скриптам машин самим, без помощи патчера.
     *
     * Нужен, если ZombieBuddy не зацепит {@code VehicleScript.Loaded()}. Проход даёт
     * то же самое, но с одной оговоркой: {@code toBullet()} к этому моменту уже отработал,
     * поэтому параметры, зашитые в нативную часть при регистрации скрипта (в том числе
     * жёсткость подвески), приходится отправлять заново — для этого {@code toBullet()}
     * вызывается повторно. На уже созданные машины это не подействует, только на те,
     * что появятся позже.
     *
     * Масса при этом в любом случае доходит: её игра берёт из скрипта при создании машины
     * и, отдельно, каждый кадр через getFudgedMass().
     *
     * @return сколько скриптов обработано, -1 если не получилось
     */
    public static int applyToAllScripts() {
        try {
            Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
            Object sm = smCls.getField("instance").get(null);
            Object list = smCls.getMethod("getAllVehicleScripts").invoke(sm);
            if (!(list instanceof java.util.List)) {
                return -1;
            }
            java.util.List<?> scripts = (java.util.List<?>) list;
            boolean onServer = false;
            try {
                onServer = Class.forName("zombie.network.GameServer").getField("server").getBoolean(null);
            } catch (Throwable ignored) {
            }
            int done = 0;
            for (int i = 0; i < scripts.size(); i++) {
                Object script = scripts.get(i);
                if (script == null) {
                    continue;
                }
                int before = appliedCount;
                applyToScript(script);
                if (appliedCount > before) {
                    done++;
                    if (!onServer) {
                        try {
                            script.getClass().getMethod("toBullet").invoke(script);
                        } catch (Throwable t) {
                            Log.info("[LabVehiclePhysics] failed to resend parameters to Bullet: " + t);
                        }
                    }
                }
            }
            Log.debug("[LabVehiclePhysics] fallback pass over vehicle scripts: total " + scripts.size()
                    + ", changed " + done + (onServer ? " (server: nothing sent to Bullet)" : ""));
            return done;
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] fallback pass over vehicle scripts failed: " + t);
            return -1;
        }
    }

    /**
     * Если через три секунды после первой машины патчер так и не тронул ни одного
     * скрипта, значит {@code VehicleScript.Loaded()} не перехвачен — идём вручную.
     */
    public static void maybeFallback() {
        ensureApplied();
    }

    /**
     * Применить таблицу ко всем скриптам машин. Выполняется один раз за запуск.
     *
     * Вызывается из патча на {@code BaseVehicle.createPhysics()}, то есть непосредственно
     * перед тем, как первая машина будет зарегистрирована в Bullet. Раньше здесь стояла
     * задержка в три секунды «на случай, если сработает штатный патч» — из-за неё подвеска
     * успевала зашиться по-старому, и правка жёсткости не доезжала до машины, в которой
     * игрок уже сидел. Ждать было нечего: штатный патч на VehicleScript.Loaded() тогда не
     * срабатывал. Теперь срабатывает (класс прогревается в {@code Main.PRELOAD}, в логе
     * {@code patching zombie.scripting.objects.VehicleScript.Loaded}), и этот обход остался
     * страховкой на случай, если прогрев перестанет помогать.
     */
    public static void ensureApplied() {
        if (!LabGate.active() || fallbackDone) {
            return;
        }
        reloadIfNeeded();
        // Смотрим все слои, а не только файл игрока. Раньше проверка была
        // rules.isEmpty(), и без своего файла обход не запускался вовсе: встроенные
        // данные и данные авторов не доходили бы до скриптов. Не вылезало, потому что
        // в лаборатории файл есть всегда, а штатный патч на Loaded() теперь срабатывает.
        // Но у клиента в сети верхний слой пуст до прихода таблицы сервера — это норма.
        if (rules.isEmpty() && defaults.isEmpty() && LuaRegistry.entries.isEmpty()) {
            return;
        }
        fallbackDone = true;
        if (appliedCount > 0) {
            return;      // штатный патч всё-таки отработал, обход не нужен
        }
        Log.debug("[LabVehiclePhysics] applying the mass table by walking vehicle scripts "
                + "(the regular patch on VehicleScript.Loaded() never fires)");
        applyToAllScripts();
    }

    /** Итоговая сводка — печатается один раз, когда в мире появилась первая машина. */
    public static void printSummaryOnce() {
        if (summaryPrinted) {
            return;
        }
        summaryPrinted = true;
        String top = !mpMode ? "player file rules " + rules.size()
                : ServerTable.received ? "server table rules " + rules.size()
                : "server table not received yet";
        Log.info("[LabVehiclePhysics] vehicle data: built-in rules " + defaults.size()
                + ", " + top + ", mod author entries " + LuaRegistry.entries.size()
                + ", vehicle scripts rewritten " + appliedCount);
        printAudit();
    }

    /**
     * Разрешённые значения по каждому скрипту машины — проверка всего парка без поездок.
     *
     * <h2>Зачем</h2>
     * Множители печатались только тогда, когда игрок сядет за руль конкретной машины:
     * {@code Patch_enginePower} висит на {@code CarController.checkTire}, а тот зовётся
     * при управлении. Проверить тридцать машин значило прокатиться на каждой.
     *
     * Здесь мы обходим {@code ScriptManager.getAllVehicleScripts()} и печатаем то же,
     * что получил бы патч: какое правило совпало и во что разрешились множители.
     * Одна загрузка мира проверяет весь парк.
     *
     * Отдельно помечаем случаи, когда множитель задан, но разрешился в единицу — это
     * ровно тот молчаливый отказ, на котором {@code brakeMul=auto} простоял сломанным
     * неизвестно сколько.
     */
    public static void printAudit() {
        try {
            Class<?> smCls = Class.forName("zombie.scripting.ScriptManager");
            Object sm = smCls.getField("instance").get(null);
            Object list = smCls.getMethod("getAllVehicleScripts").invoke(sm);
            if (!(list instanceof List)) {
                Log.debug("[LabVehiclePhysics] vehicle audit: no script list available");
                return;
            }
            List<?> scripts = (List<?>) list;
            StringBuilder sb = new StringBuilder();
            sb.append("[LabVehiclePhysics] vehicle audit - resolved values, no driving needed:");
            int covered = 0;
            int suspicious = 0;
            for (int i = 0; i < scripts.size(); i++) {
                Object script = scripts.get(i);
                if (script == null) {
                    continue;
                }
                String name;
                try {
                    name = scriptName(script);
                } catch (Throwable t) {
                    continue;
                }
                Rule rule = forName(name);
                if (rule == null) {
                    continue;
                }
                covered++;
                try {
                    noteVanillaFromScript(script, name);
                } catch (Throwable ignored) {
                }
                float power = powerMultiplier(rule, name);
                float brake = multiplier(rule.brakeMul, rule, name);
                String flag = "";
                if (rule.hasPower() && Math.abs(power - 1.0f) < 0.001f) {
                    flag = flag + "   <-- POWER NOT CHANGED";
                    suspicious++;
                }
                if (rule.brakeMul != null && Math.abs(brake - 1.0f) < 0.001f) {
                    flag = flag + "   <-- BRAKES NOT CHANGED";
                    suspicious++;
                }
                sb.append("\n    ").append(name)
                  .append("  [").append(rule.source).append("]")
                  .append("  mass ").append(fmt(rule.mass))
                  .append("  power x").append(fmt(power))
                  .append("  brake x").append(fmt(brake));
                if (rule.tank > 0.0f) {
                    sb.append("  tank ").append(fmt(rule.tank));
                }
                sb.append(flag);
            }
            sb.append("\n    covered ").append(covered).append(" of ").append(scripts.size())
              .append(" vehicle scripts");
            if (suspicious > 0) {
                sb.append(", PROBLEMS ").append(suspicious);
            }
            Log.debug(sb.toString());
        } catch (Throwable t) {
            Log.info("[LabVehiclePhysics] vehicle audit failed: " + t);
        }
    }

    /** Разбор множителя: число, "auto" (отношение масс) или ничего. */
    public static float multiplier(String spec, Rule rule) {
        return multiplier(spec, rule, null);
    }

    /**
     * @param scriptName имя скрипта машины. Нужно только для {@code auto}: по нему
     *                   находится ванильная масса. Без имени {@code auto} откатывается
     *                   на {@code rule.ratio}, а тот заполняется лишь в {@code applyToScript}.
     */
    public static float multiplier(String spec, Rule rule, String scriptName) {
        if (spec == null) {
            return 1.0f;
        }
        if ("auto".equalsIgnoreCase(spec)) {
            return autoRatio(rule, scriptName);
        }
        try {
            return Float.parseFloat(spec);
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    /**
     * Множитель тяги на трогании.
     *
     * В игре тяга зависит от оборотов линейно и на холостых равна половине номинала:
     * {@code engineForce = power * (0.5 + обороты / 24000)}. Ни понижающей передачи,
     * ни гидротрансформатора в модели нет, поэтому низовой тяги не хватает именно
     * тяжёлой технике — лёгким машинам половины номинала достаточно.
     *
     * Возвращаем множитель, максимальный на месте и линейно сходящий к единице
     * к скорости {@code lowGearTo}. Так ведёт себя гидротрансформатор от стопора
     * до точки сцепления.
     */
    public static float lowGearBoost(Rule rule, float speedKmh) {
        if (rule.lowGear <= 1.0f) {
            return 1.0f;
        }
        float v = Math.abs(speedKmh);
        if (v >= rule.lowGearTo) {
            return 1.0f;
        }
        float t = 1.0f - v / rule.lowGearTo;
        return 1.0f + (rule.lowGear - 1.0f) * t;
    }

    /** Самый большой объём бака среди правил. Ноль, если объёмы не задавались. */
    public static int largestTank() {
        return largestTank;
    }

    public static String fmt(float v) {
        if (v == (long) v) {
            return Long.toString((long) v);
        }
        return String.format("%.2f", Float.valueOf(v));
    }
}
