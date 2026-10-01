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
 * Table of real vehicle masses and of rules for rescaling the other characteristics.
 *
 * Why a file and not constants in code. The game has 280 vehicles (vanilla + mods), and not one
 * of them weighs what it should: the whole fleet is squeezed into 650..1160 kg, and the M60A3
 * tank from a mod weighs 1104 kg, exactly as much as the vanilla pickup van, because the
 * modder copied its template and left the mass alone. Finding the right numbers is iterative
 * work, and no iteration should cost a jar rebuild.
 *
 * Line format:
 * <pre>
 *   &lt;name mask&gt;: mass=&lt;kg&gt; [stiffness=auto|&lt;number&gt;] [engine=auto|keep|&lt;number&gt;]
 *                   [maxSpeed=&lt;number&gt;] [live]
 * </pre>
 * The mask is a vehicle script name, with {@code *} allowed anywhere. Rules are checked
 * top to bottom; the first matching one applies.
 *
 * Values:
 * <ul>
 *   <li>{@code mass}: mass in kilograms. The game stores mass in the same units
 *       (the vanilla passenger car is 800), so no conversion is needed.</li>
 *   <li>{@code stiffness=auto}: suspension stiffness is multiplied by the same ratio as the
 *       mass. This is a hypothesis the first iteration is meant to test: if Bullet already
 *       normalizes the spring force by chassis mass internally, there is no need to multiply.</li>
 *   <li>{@code engine=auto}: thrust is multiplied by the same ratio, so acceleration
 *       stays the same. {@code engine=keep} (the default): thrust is left alone,
 *       and the heavier vehicle accelerates worse, as in real life.</li>
 *   <li>{@code live}: the mass is also substituted on the fly in {@code getFudgedMass()},
 *       which the game hands to Bullet every frame. The file is re-read every two
 *       seconds, so the number can be tuned right during play, without a restart.
 *       Suspension stiffness cannot be tuned this way: it is baked in once at load.</li>
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
        /** Suspension travel in centimeters. 0 = leave as is. */
        public final float travel;
        /** Spring rest length. 0 = leave as is. */
        public final float rest;
        /** Thrust multiplier: a number, "auto" (by the mass ratio) or null. */
        public final String powerMul;
        /** Braking multiplier: a number, "auto" or null. */
        public final String brakeMul;
        /** Thrust multiplier at pull-away (torque converter + low gear). 0 = off. */
        public final float lowGear;
        /** Speed in km/h at which the multiplier tapers down to one. */
        public final float lowGearTo;
        /** One-off repair and refuel when the config is re-read. */
        public final boolean service;
        /** Fuel tank capacity in liters. 0 = leave as is. */
        public final float tank;
        /**
         * Ratio of the new mass to the vanilla one: the fallback path for {@code auto}.
         *
         * It should be computed through {@link VehicleCfg#autoRatio}, which takes the
         * vanilla mass from {@link VehicleCfg#VANILLA}. This field remains for the
         * case when the script name never made it to the calculation.
         */
        public volatile float ratio = 1.0f;
        public final boolean live;
        /**
         * Rated power in hp, 0 = not set. It is converted into a thrust multiplier
         * at use time, see {@link VehicleCfg#powerMultiplier}: that needs the vanilla
         * thrust of the specific script, which is not visible at parse time.
         */
        public final float powerHp;
        /** lowGearTo as given, without the default of 30 filled in: needed for merging layers. */
        public final float lowGearToRaw;
        /** Vehicle category from the mod author. For now only shown in the audit. */
        public final String category;
        /** Where the rule comes from: "cfg", "author:&lt;mod&gt;" or a merge of both. */
        public final String source;
        /**
         * Preset name ({@code preset=tank}) or null. Expanded when the layers are resolved
         * ({@link VehicleCfg#withPreset}): the line's fields go on top of the preset's fields.
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

        /** Whether the rule sets thrust in any form at all. */
        public boolean hasPower() {
            return powerMul != null || powerHp > 0.0f;
        }

        /**
         * Merge of two layers: the upper one over the lower one. The chain is: mod author over
         * the built-in data, player over whatever came out of that (vehicle-data-design.md).
         *
         * Each field comes from the upper layer if set there, else from the lower one. The player
         * can thus fix just the mass and leave the rest to the author and the reference data.
         *
         * Two settings merge IN PAIRS rather than field by field:
         * <ul>
         *   <li>thrust: {@code powerMul} and {@code power}. Otherwise the player could set
         *       power in hp and still lose to the author's multiplier, because the multiplier
         *       is checked first. Whoever sets thrust in any form owns the pair;</li>
         *   <li>low gear: {@code lowGear} and {@code lowGearTo}. The taper speed makes no
         *       sense without its own multiplier.</li>
         * </ul>
         *
         * {@code live} and {@code service} are lab tools. Only the player sets them; the author
         * and the built-in data do not have them. They are combined with "or".
         */
        public static Rule merge(Rule top, Rule base) {
            return merge(top, base, base.glob);
        }

        /** @param glob mask of the resulting rule, for the log line; it no longer affects rule choice. */
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
    /** volatile: zeroed from Lua when the server table arrives, so it is picked up at once. */
    public static volatile long lastCheckNanos = 0L;
    public static long lastModified = -1L;
    /**
     * Upper rule layer: the player's file, or the server table on a multiplayer client
     * ({@link ServerTable}). The rest of the code does not see the difference.
     */
    public static List<Rule> rules = new ArrayList<Rule>();
    /** Multiplayer client: our own file is not read, the upper layer is the server table. */
    public static volatile boolean mpMode = false;
    /** Which generation of the server table has already been picked up. */
    public static int seenServerGeneration = -1;
    /** Lines of the player's file minus blanks and comments: the server sends these to clients. */
    public static volatile List<String> playerLines = new ArrayList<String>();
    public static volatile boolean playerFilePresent = false;
    /** Grows on every re-read of the player's file: this tells the server it is time to resend. */
    public static volatile int playerStamp = 0;
    /** Why the scripts are being re-applied, for the log line. */
    public static volatile String reapplyReason = "";
    public static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    public static Method mGetName;
    public static boolean summaryPrinted = false;
    /** Count of scripts actually rewritten. 0 after a few seconds = patcher missed the class. */
    public static volatile int appliedCount = 0;
    public static volatile boolean fallbackDone = false;
    public static long firstSeenNanos = 0L;
    /** Whether any rule has {@code live}. Until one does, live substitution costs nothing. */
    public static volatile boolean hasLive = false;
    /** Whether any rules change thrust or brakes. Until then, the thrust patch costs nothing. */
    public static volatile boolean hasTuning = false;
    /** Whether any rules have a one-off repair. */
    public static volatile boolean hasService = false;
    /** Whether any rules change the tank capacity. */
    public static volatile boolean hasTank = false;
    /** The largest tank capacity among the rules: the clamp on tank items is raised up to it. */
    public static volatile int largestTank = 0;
    /** Grows on every file re-read: consumers reset their caches by it. */
    public static volatile int generation = 0;

    /**
     * Vanilla script masses, remembered BEFORE the first overwrite. Key: the script name.
     *
     * <h2>Why this was needed</h2>
     * {@code brakeMul=auto} means "scale the brake the same way as the mass", i.e.
     * multiply by the ratio of the new mass to the vanilla one. It used to be computed like this:
     * <pre>
     * float oldMass = fMass.getFloat(script);
     * float k = rule.mass / oldMass;
     * rule.ratio = k;
     * fMass.setFloat(script, rule.mass);   // and at once overwrite what k was computed from
     * </pre>
     * The first pass got it right. But {@code applyToScript} is called again on every
     * config re-read, and there {@code oldMass} is already OUR mass, which gives
     * {@code 12300/12300 = 1.0}. On top of that, if the script was never reached at all,
     * {@code ratio} just kept its default value, which is also 1.0. Both paths led
     * to 1.0, and {@code auto} could not work either way.
     *
     * The map lives for the whole session and is NOT cleared when the file is re-read; otherwise
     * we would be back to exactly the same bug. Writes follow the "first one wins" rule.
     *
     * The same class of bug as with {@code hasLive}: a value was computed from something we
     * ourselves overwrite one line later. Analysis: {@code modding-notes.md} §12.
     */
    public static final Map<String, Float> VANILLA =
            java.util.Collections.synchronizedMap(new HashMap<String, Float>());

    private VehicleCfg() {
    }

    /**
     * Key of the vanilla value map: script name plus field name.
     *
     * The module prefix is stripped on purpose. The game gives the name in two forms: the script
     * object's {@code getName()} is {@code "M113_APC"}, the vehicle's {@code getScriptName()} is
     * {@code "Base.M113_APC"}. One place writes to the map, another reads from it, and without
     * normalization they would miss each other.
     */
    public static String vanillaKey(String name, String field) {
        String bare = name;
        int dot = bare.lastIndexOf('.');
        if (dot >= 0) {
            bare = bare.substring(dot + 1);
        }
        return bare + "|" + field;
    }

    /** Reflection cache for {@link #noteVanillaMassFromVehicle}. */
    public static Method mVehicleGetScript;

    /**
     * Peek at the vanilla mass through the vehicle's script before anyone overwrites it.
     *
     * Needed for the case when {@code applyToScript} has not run for this script:
     * then the script field still holds the vanilla number, and this is the last moment
     * it can be seen. If it was already applied, the key is in the map and we return at once:
     * writes follow the "first one wins" rule.
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
            // failed: auto and power simply fall back to one, which is safe
        }
    }

    /**
     * Remember the script's vanilla mass and thrust. The {@code power} key needs the thrust:
     * rated hp are converted into a multiplier relative to the script's vanilla thrust.
     */
    public static void noteVanillaFromScript(Object script, String name) throws Exception {
        if (script == null || name == null) {
            return;
        }
        Class<?> cls = script.getClass();
        rememberVanilla(name, "mass", field(cls, "mass").getFloat(script));
        rememberVanilla(name, "engineForce", field(cls, "engineForce").getFloat(script));
    }

    /** Remembered vanilla value of a field, 0 if not seen yet. */
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
     * How much in-game thrust corresponds to one horsepower.
     *
     * Derived from the vanilla passenger car: it has 4000 units of thrust for 800 kg, i.e. 5.00
     * per kg, against 140 hp for 1350 kg in a real sedan. Required thrust for any vehicle:
     * <pre>
     *   5.00 x [(hp/kg) / (140/1350)] x kg  =  5.00 x 1350/140 x hp  =  48.2 x hp
     * </pre>
     * The mass cancels out: in-game thrust should simply be proportional to power.
     */
    public static final float FORCE_PER_HP = 5.0f * 1350.0f / 140.0f;

    /**
     * Thrust multiplier, in whatever form thrust was specified.
     *
     * {@code powerMul} (a number or auto) takes precedence: it is the manual setting. Otherwise
     * {@code power} in hp is converted relative to the script's vanilla thrust. While that is
     * not known yet, we return one: leaving things alone is safer than multiplying at random.
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
     * Remember the vanilla value of a script field if it is not known yet.
     *
     * This is not only about mass: {@code stiffness=auto} and {@code engine=auto} also
     * multiply by {@code k}, and if computed from an already changed value they run
     * away: 35 -> 224 -> 1434 on every file re-read. This only stayed hidden before
     * because {@code k} was broken and equal to one: one bug covered for
     * the other.
     *
     * @param current the value visible right now. The caller must pass it
     *                BEFORE its own overwrite; if a value was already recorded, the one
     *                remembered earlier is returned, not the one passed in.
     * @return the vanilla value, 0 if nothing meaningful was passed
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

    /** Script fields that {@link #applyToScript} writes. */
    public static final String[] SCRIPT_FIELDS = {
        "mass", "suspensionStiffness", "engineForce", "maxSpeed", "maxSuspensionTravelCm", "suspensionRestLength",
    };
    /**
     * Original values of the {@link #SCRIPT_FIELDS} fields by bare script name, whatever they
     * are, even zero. Needed to return a script to the game's values when its rule is gone. Writes
     * follow the "first one wins" rule: the first time we see a script is before our own write.
     */
    public static final Map<String, float[]> ORIGINAL_FIELDS = new java.util.concurrent.ConcurrentHashMap<String, float[]>();
    /** Bare names of the scripts we have written to. */
    public static final java.util.Set<String> WRITTEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** How many scripts were returned to the game's values, for the log line. */
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
     * Script mass as the game or the mod author set it, before our reference data. Used for
     * "as in vanilla" fuel consumption ({@code LabVehicleFuel.lua}). 0: script not seen yet.
     */
    public static float originalMass(String name) {
        float[] o = name != null ? ORIGINAL_FIELDS.get(bareName(name)) : null;
        if (o != null && o[0] > 0.0f) {
            return o[0];
        }
        return vanillaValue(name, "mass");
    }

    /**
     * Ratio of the new mass to the vanilla one, for {@code auto}.
     *
     * If the script's vanilla mass is not remembered yet, fall back to {@code rule.ratio}
     * and then to one, i.e. to "change nothing", which is always safer
     * than multiplying by a random number.
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

    /** File path: next to the lab's saves, so that it can be edited without admin rights. */
    public static File resolveFile() {
        if (file == null) {
            String home = System.getProperty("user.home");
            file = new File(new File(home, "Zomboid"), FILE_NAME);
        }
        return file;
    }

    /**
     * Built-in data, layer 3 (vehicle-data-design.md): the vehicle specs that the mod
     * ships with. The file sits inside the mod, next to the jar, in the same format as
     * the player's config, with masks.
     *
     * Why. Until 26.09.2026 all specs lived in vehicle-physics.cfg in the player's Zomboid
     * folder. Someone who downloads the mod does not have that file, so they would get
     * vanilla physics. And in multiplayer the vehicle physics is computed by the driver's client
     * (the server does not register vehicles in Bullet at all: VehicleScript.Loaded() calls
     * toBullet() only when !GameServer.server), so a guest without the file would drive
     * with vanilla values even on a server running the mod.
     */
    public static final String DEFAULTS_NAME = "vehicle-physics-defaults.cfg";
    public static File defaultsFile;
    public static long defaultsModified = -1L;
    public static List<Rule> defaults = new ArrayList<Rule>();

    public static File resolveDefaultsFile() {
        if (defaultsFile != null) {
            return defaultsFile;
        }
        // the jar lives in <mod>/42/media/java/, the file in <mod>/42/media/
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
        // Fallback: the mod folder by its id.
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
     * Presets by vehicle type: tank, armored car, passenger car, trailer, etc. Ready-made sets for
     * vehicles the mod knows nothing about. The player picks a preset with the {@code preset=} key
     * in their line; the line's fields take precedence over the preset's ({@link #withPreset}).
     */
    public static final String PRESETS_NAME = "vehicle-physics-presets.cfg";
    public static long presetsModified = -1L;
    /** Lower-case preset name -> rule. Replaced as a whole on re-read. */
    public static volatile Map<String, Rule> presets = new HashMap<String, Rule>();
    public static final java.util.Set<String> WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Expand a preset reference: line fields over preset fields. No reference: returned as is. */
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
     * Re-reads the sources if they have changed. Checks at most once every two seconds.
     *
     * The upper layer comes from one of two places: the player's file in singleplayer and on the
     * server, the server table ({@link ServerTable}) on a multiplayer client. A multiplayer client
     * never reads its own file, or the same vehicle would weigh differently for different players.
     */
    public static void reloadIfNeeded() {
        // The mode is checked on every call, bypassing the two-second pause: vehicle scripts
        // load in a batch within a fraction of a second right after connecting, and all of them
        // must see that we are already in multiplayer, not just those that come two seconds later.
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
                lastModified = -1L;      // back in singleplayer: re-read our own file
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
                // On joining a multiplayer game the scripts have yet to load, so there is nothing
                // to re-apply. A table that arrives during play, however, needs a re-apply.
                serverChanged = !modeChanged;
                seenServerGeneration = g;
                rules = ServerTable.rules;
                fileChanged = true;
            }
        }
        // The mod's built-in data is also re-read by date: in the lab it is handy to edit it
        // on the fly, and for a player the file simply never changes, so the check costs nothing.
        File df = resolveDefaultsFile();
        long dmod = (df != null && df.exists()) ? df.lastModified() : 0L;
        if (dmod != defaultsModified) {
            defaultsModified = dmod;
            defaults = df != null ? parse(df, "builtin") : new ArrayList<Rule>();
            fileChanged = true;
            Log.debug("[LabVehiclePhysics] built-in vehicle data: rules loaded " + defaults.size());
        }
        // Presets by vehicle type sit next to the reference data and are re-read the same way.
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
        // Mod authors' data lives in Lua and may show up later than the file: mod Lua files
        // load in their own order, and vehicle scripts in theirs. So the table is checked
        // at the same two-second rate rather than once at startup.
        boolean authorsChanged = LuaRegistry.refresh();
        // The "Vanilla vehicles" sandbox switch turns the built-in reference data on and off
        // for vanilla scripts: the same kind of layer change as new author data.
        // World values arrive later than the scripts load (from the save or from the server), so
        // the change is caught here and not at load time.
        boolean custom = LabSettings.vanillaCustom();
        boolean vanillaChanged = custom != useBuiltinForVanilla;
        if (vanillaChanged) {
            useBuiltinForVanilla = custom;
        }
        // The sandbox vehicle table is the top layer; it arrives as late as the switch does.
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
            // The scripts may have loaded before the author data or the server table arrived,
            // and then the numbers never reached them. Re-applying right here is not allowed:
            // this method is called from patches in any context, including mid-physics.
            // So we set a flag, and BaseVehicle.update handles it on the main thread.
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
     * Whether the built-in reference data applies to vanilla vehicles: the value of the sandbox
     * switch as accepted in {@link #reloadIfNeeded}. Read in {@link #resolve}, which needs
     * the value the rule cache was reset for, not the freshest one.
     */
    public static volatile boolean useBuiltinForVanilla = true;

    /** Bare script name -> vanilla or not. A script's origin does not change within a session. */
    public static final Map<String, Boolean> VANILLA_SCRIPTS = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    public static Object scriptManager;
    public static Method mSmGetVehicle;
    public static Method mLoadedBodies;

    /**
     * Whether the vehicle is vanilla. A script remembers where each of its bodies came from, as
     * "mod id, text" pairs in {@code getLoadedScriptBodies()}; the game's own files have the id
     * {@code pz-vanilla}. Vanilla means first defined by the game; a mod adding to it keeps it so.
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
                return false;      // script not loaded yet: do not cache, ask again later
            }
            return noteScriptOrigin(script, name);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Remember the script's origin while we have it in hand. @return whether it is vanilla */
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
            // failed: treat it as modded, so the reference data applies to it as before
        }
        VANILLA_SCRIPTS.put(bareName(name), Boolean.valueOf(vanilla));
        return vanilla;
    }

    /**
     * On/off flags for the patches, computed over both sources at once.
     *
     * They used to be computed from the file only. With author data that would be a silent
     * failure: a mod registers a 659-liter tank, but {@code hasTank} stays false, and the tank
     * patch does not even look in that vehicle's direction.
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
        // Presets referenced by rules: their thrust and tank must switch the patches on too.
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

    /** Merged-rule cache by script name. {@link #NONE}: no rule. Cleared when generation changes. */
    public static final Map<String, Object> MERGED = new java.util.concurrent.ConcurrentHashMap<String, Object>();
    public static final Object NONE = new Object();
    /** Author data or the server table changed; the scripts must be re-applied from the main thread. */
    public static volatile boolean reapplyPending = false;

    /**
     * Re-apply the scripts after author data appeared or changed, or the server table
     * arrived. Called from {@code BaseVehicle.update}: the main thread, where calling
     * toBullet() is safe.
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
     * Carry the rewritten script over to the vehicles that already stand in the world.
     *
     * A vehicle copies mass and top speed from its script exactly once, on physics creation:
     * <pre>
     * // BaseVehicle.createPhysics(boolean)
     * this.setMaxSpeed(this.getScript().maxSpeed);
     * this.setInitialMass(this.getScript().getMass());
     * ...
     * this.updateTotalMass();   // mass = initialMass + cargo, also pushed into Bullet
     * </pre>
     * The game writes them nowhere else. Rewriting the script after that is not enough: a vehicle
     * near the player would keep the old numbers until its chunk unloads. In multiplayer this is
     * the rule, not the exception: the server table arrives after the world has loaded.
     *
     * Thrust, brakes and tank are not needed here: they are read from the rules on the fly.
     *
     * @return how many vehicles were updated, -1 on failure
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

    /** @param layer "cfg" for the player's file, "builtin" for built-in mod data; used in the source label. */
    public static List<Rule> parse(File f, String layer) {
        return parseLines(readLines(f), layer, f.getName());
    }

    /** File lines as they are. An empty list if the file is missing or unreadable. */
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
     * Rule lines, without blanks and comments. These are what the server sends out: comments
     * are the file owner's notes, and players have no use for them.
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
     * Parse rules from lines. The source of the lines does not matter (our own file, the mod's
     * built-in data or a table sent by the server); the format is the same.
     *
     * @param label where the lines come from, for error messages: a file name or "server table"
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
                    // service is a lab tool; in the Workshop build it is just an
                    // unknown word, and a log line will say so.
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
                    // An error in one number loses only this key. The exception used to fly
                    // into the outer catch and abort parsing of the whole file: a typo in one
                    // line silently disabled every rule below it.
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
     * The rule for a vehicle: the player's config over the mod author's data.
     *
     * Called every frame for every vehicle from several patches, so the result
     * is cached by name and reset together with {@link #generation}.
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

    /** Script name without the module prefix: "Base.M60A3" -> "M60A3". */
    public static String bareName(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot + 1 < name.length()) ? name.substring(dot + 1) : name;
    }

    /**
     * Uncached: four layers, each one overriding the previous one field by field.
     * <pre>
     *   mod's built-in data       (vehicle-physics-defaults.cfg inside the mod)
     *   vehicle author's data     (Lua table LabVehiclePhysicsData in the author's mod)
     *   player's file             (vehicle-physics.cfg in the Zomboid folder;
     *                              on a multiplayer client, the server table)
     *   sandbox table             (the "Vehicle Physics: vehicles" page, {@link VehicleTable})
     * </pre>
     * The author knows their vehicle better than our general reference data; the player owns
     * their game and has the last word. In multiplayer the server owns the game. The sandbox table
     * is what the player chose in the UI, so it goes on top; the file remains a lab tool. A preset
     * reference is expanded within its own layer ({@link #withPreset}).
     *
     * The sandbox switch "Vanilla vehicles: Standard" strips only the built-in reference data from
     * vanilla vehicles: author data, the file and the table are explicit choices and still apply.
     */
    public static Rule resolve(String name) {
        return resolveLayers(name, true, null);
    }

    /**
     * Layers in order, the top one last: reference data, mod author, file, sandbox table.
     *
     * @param withTable whether to take the sandbox table line into account ({@link VehicleTable})
     * @param tableRow  a custom line instead of the table line (panel preview); null = none
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

    /** Lines of the sandbox table ({@link VehicleTable}), the top layer. Replaced as a whole. */
    public static volatile List<Rule> sandboxRules = new ArrayList<Rule>();
    /** The option string that {@link #sandboxRules} were parsed from. */
    public static volatile String appliedTable = "";

    /**
     * First matching rule of a layer. The name comes in two forms: VehicleScript.getName()
     * returns "97bushAmbulance", and BaseVehicle.getScriptName() returns "Base.97bushAmbulance",
     * with the module prefix (the game even has an assert name.contains(".") for this). We try both
     * so that a mask in the file does not have to be written with a leading asterisk.
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
     * Rewrites VehicleScript fields before {@code Loaded()} sends them to Bullet.
     * Called once per vehicle script when the game loads.
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
            // Original values of all the fields we touch, from before our first write.
            float[] original = rememberOriginalFields(script, name);
            Rule rule = forName(name);
            if (rule == null || (rule.mass <= 0.0f && rule.travel <= 0.0f && rule.rest <= 0.0f
                    && rule.stiffness == null && rule.engine == null && rule.maxSpeed <= 0.0f)) {
                // The rule is gone: a sandbox switch, a file edit, a different server
                // table. If we wrote to this script, give it back the game's values.
                if (WRITTEN.remove(bareName(name))) {
                    restoreFields(script, original);
                    restoredCount++;
                    appliedCount++;      // so that the walk resends the script to Bullet
                }
                return;
            }
            Class<?> cls = script.getClass();
            // First everything back to the original values: a field the rule no longer sets must
            // not keep our previous value.
            restoreFields(script, original);
            Field fMass = field(cls, "mass");
            float oldMass = fMass.getFloat(script);
            if (oldMass <= 0.0f) {
                return;
            }
            // All vanilla values come from the map, not from the script: this method
            // is called again on every config re-read, and by the second time the script
            // fields already hold our numbers. Computing from them means either getting
            // a ratio of 1.0 (mass) or a runaway (stiffness, thrust).
            float vanillaMass = rememberVanilla(name, "mass", oldMass);
            // Thrust is always remembered, not only when the rule changes it: the power key
            // converts rated hp into a multiplier relative to exactly this value.
            rememberVanilla(name, "engineForce", field(cls, "engineForce").getFloat(script));
            // Mass is written only if it is set. A rule without mass but with
            // maxSpeed or suspension travel used to pass the check above and reach
            // fMass.setFloat(script, 0): the vehicle got zero mass. The config had no such
            // rules, but for mod authors mass is optional: a mod is free to send
            // only a tank capacity and a top speed.
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
     * Fallback path: walk all vehicle scripts ourselves, without the patcher's help.
     *
     * Needed if ZombieBuddy fails to hook {@code VehicleScript.Loaded()}. The walk gives
     * the same result with one caveat: {@code toBullet()} has already run by this point,
     * so the parameters baked into the native side when the script was registered (including
     * suspension stiffness) have to be sent again; that is why {@code toBullet()}
     * is called a second time. This does not affect vehicles already created, only those
     * that appear later.
     *
     * The mass gets through either way: the game takes it from the script when a vehicle is
     * created and, separately, every frame through getFudgedMass().
     *
     * @return how many scripts were processed, -1 on failure
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
     * If three seconds after the first vehicle the patcher still has not touched a single
     * script, then {@code VehicleScript.Loaded()} is not intercepted, so we do it by hand.
     */
    public static void maybeFallback() {
        ensureApplied();
    }

    /**
     * Apply the table to all vehicle scripts. Runs once per launch.
     *
     * Called from the patch on {@code BaseVehicle.createPhysics()}, i.e. right before
     * the first vehicle gets registered in Bullet. There used to be a three-second delay here
     * "in case the regular patch fires"; because of it the suspension had time to be baked in
     * the old way, and the stiffness fix never reached the vehicle the player was already sitting
     * in. There was nothing to wait for: the regular patch on VehicleScript.Loaded() did not fire
     * back then. Now it does (the class is warmed up in {@code Main.PRELOAD}; the log shows
     * {@code patching zombie.scripting.objects.VehicleScript.Loaded}), and this walk remains
     * as insurance in case the warm-up stops helping.
     */
    public static void ensureApplied() {
        if (!LabGate.active() || fallbackDone) {
            return;
        }
        reloadIfNeeded();
        // Look at all layers, not just the player's file. The check used to be rules.isEmpty(),
        // and without a player file the walk never started at all: the built-in data and the
        // author data would never reach the scripts. It did not show up because the lab always
        // has the file, and the regular patch on Loaded() now fires. But on a multiplayer client
        // the top layer is empty until the server table arrives, and that is normal.
        if (rules.isEmpty() && defaults.isEmpty() && LuaRegistry.entries.isEmpty()) {
            return;
        }
        fallbackDone = true;
        if (appliedCount > 0) {
            return;      // the regular patch did run after all, no walk needed
        }
        Log.debug("[LabVehiclePhysics] applying the mass table by walking vehicle scripts "
                + "(the regular patch on VehicleScript.Loaded() never fires)");
        applyToAllScripts();
    }

    /** Final summary, printed once when the first vehicle appears in the world. */
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
     * Resolved values for every vehicle script: a check of the whole fleet without driving.
     *
     * <h2>Why</h2>
     * The multipliers used to be printed only once the player got behind the wheel of a specific
     * vehicle: {@code Patch_enginePower} sits on {@code CarController.checkTire}, which is called
     * while driving. Checking thirty vehicles meant taking each one for a drive.
     *
     * Here we walk {@code ScriptManager.getAllVehicleScripts()} and print the same thing
     * the patch would get: which rule matched and what the multipliers resolved to.
     * One world load checks the whole fleet.
     *
     * Cases where a multiplier is set but resolved to one are flagged separately: that is
     * exactly the silent failure that kept {@code brakeMul=auto} broken for who knows
     * how long.
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

    /** Parse a multiplier: a number, "auto" (the mass ratio) or nothing. */
    public static float multiplier(String spec, Rule rule) {
        return multiplier(spec, rule, null);
    }

    /**
     * @param scriptName vehicle script name. Needed only for {@code auto}: it is used to
     *                   look up the vanilla mass. Without a name {@code auto} falls back
     *                   to {@code rule.ratio}, which is only filled in {@code applyToScript}.
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
     * Thrust multiplier at pull-away.
     *
     * In the game, thrust depends on rpm linearly and at idle equals half the rated value:
     * {@code engineForce = power * (0.5 + rpm / 24000)}. The model has neither a low gear
     * nor a torque converter, so it is specifically heavy vehicles that lack low-end
     * thrust; for light vehicles half the rated value is enough.
     *
     * We return a multiplier that peaks at standstill and tapers linearly to one
     * at the speed {@code lowGearTo}. That is how a torque converter behaves from stall
     * to the coupling point.
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

    /** The largest tank capacity among the rules. Zero if no capacities were set. */
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
