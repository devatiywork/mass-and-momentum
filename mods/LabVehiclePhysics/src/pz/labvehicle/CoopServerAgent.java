package pz.labvehicle;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Co-op server with ZombieBuddy.
 *
 * <h2>The problem</h2>
 * "Host" in the menu starts the server as a separate process: {@code CoopMaster.launchServer}
 * builds the command {@code jre64\bin\java ... zombie.network.GameServer -coop} from a hard-coded
 * argument list. {@code -agentlib:zbNative} from {@code ProjectZomboid64.json} does not make it
 * in, and ZombieBuddy itself does not touch this launch. As a result the co-op server has
 * no Java mods at all: trees do not fall (the server does the felling), corpses are not synced,
 * and there is no server-side vehicle table or server patches. The server log has no {@code [ZB]}
 * line at all, and our Lua reports "the Java side (LabVehiclePhysicsNet) is not available". This
 * does not show in the lab, whose dedicated server gets the agent via {@code _JAVA_OPTIONS}.
 *
 * <h2>The solution</h2>
 * Without rewriting the launch, only one argument of the command can be swapped: the garbage
 * collector flag from {@code private String getGarbageCollector()}, a single entry placed right
 * before the main class name. We return {@code @file} in its place: the java launcher (JDK 9+,
 * 25 in the game) expands such an argument into the file's contents. The file holds the original
 * GC flag, the client's access flags ({@code --enable-native-access}, {@code --add-exports}: the
 * lab's dedicated server also starts with them) and the ZombieBuddy agent in the same form as on
 * the client, but with {@code policy=deny-new}.
 *
 * {@code deny-new}: the server loads only the Java mods the player has already approved on the
 * client with "remember" (approvals are shared: {@code ~/.zombie_buddy/mod_approvals.json}; the
 * server gets {@code user.home} from the client), and asks nothing. There is no one to ask:
 * the co-op server has no window, so a prompt would hang the launch. A player's {@code allow-all}
 * choice is kept. Client mods ({@code media/java/client/}, e.g. Viewpoint) are skipped on the
 * server by ZombieBuddy itself.
 *
 * <h2>Safety gate</h2>
 * Like {@link LabGate}: we intervene only if the mod is in the {@code Mods=} of the server being
 * launched ({@code <cachedir>/Server/<name>.ini}). getGarbageCollector does not know the server
 * name; {@link Patch_coopServerName} catches it on entry to launchServer. If the ini cannot be
 * read, we add the agent and log that, as LabGate does on failure.
 *
 * <h2>Two mods, one agent</h2>
 * LabRagdollMP has the same class: either mod can be installed without the other. The first
 * writes the file; the second recognises it by its name {@link #FILE_NAME} and changes nothing.
 *
 * Works only if the Java mod is already loaded in the client by the time of hosting, i.e. enabled
 * in the main menu's mod list, not just in the server settings.
 */
public final class CoopServerAgent {

    public static final String MOD_ID = LabGate.MOD_ID;
    /** Argument file shared by both mods: by its name the second sees the agent is already added. */
    public static final String FILE_NAME = "mass-momentum-coop-server.args";
    /** ZombieBuddy policy on the server unless the player chose allow-all or deny-new. */
    public static final String POLICY = "deny-new";

    public static volatile String serverName;
    public static volatile boolean broken = false;

    private CoopServerAgent() {
    }

    /** Entry to CoopMaster.launchServer: remembers which server is being launched. */
    public static void launching(String name) {
        serverName = name;
    }

    /**
     * Exit from CoopMaster.getGarbageCollector: an {@code @file} with the ZombieBuddy agent
     * instead of the GC flag, or the original flag if the mod is not installed on this server.
     */
    public static String argument(String gc) {
        if (broken) {
            return gc;
        }
        try {
            if (gc != null && gc.startsWith("@") && gc.endsWith(FILE_NAME)) {
                return gc;
            }
            String name = serverName;
            Boolean listed = listed(name);
            if (listed != null && !listed.booleanValue()) {
                Log.debug("[LabVehiclePhysics] co-op server \"" + name + "\": " + MOD_ID
                        + " is not in its Mods=, ZombieBuddy is not added for this mod");
                return gc;
            }
            String agent = null;
            List<String> lines = new ArrayList<String>();
            if (gc != null) {
                lines.add(gc);
            }
            List<String> client = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (int i = 0; i < client.size(); i++) {
                String a = client.get(i);
                if (a.startsWith("-agentlib:zbNative") || (a.startsWith("-javaagent:") && a.contains("ZombieBuddy"))) {
                    agent = a;
                } else if (a.startsWith("--enable-native-access=") || a.startsWith("--add-exports=")
                        || a.startsWith("--add-opens=")) {
                    lines.add(a);
                }
            }
            if (agent == null) {
                Log.info("[LabVehiclePhysics] co-op server: ZombieBuddy is not among this game's JVM arguments"
                        + " - the server starts without Java mods");
                return gc;
            }
            String serverAgent = withPolicy(agent);
            lines.add(serverAgent);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(quote(lines.get(i))).append(System.lineSeparator());
            }
            File file = new File(System.getProperty("java.io.tmpdir"), FILE_NAME);
            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            file.deleteOnExit();
            Log.info("[LabVehiclePhysics] co-op server \"" + name + "\": ZombieBuddy added (" + serverAgent
                    + ") - the server loads only the Java mods approved in this game with \"remember\""
                    + (listed == null ? "; its Mods= could not be read, added anyway" : ""));
            return "@" + file.getAbsolutePath();
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR adding ZombieBuddy to the co-op server, it starts without Java mods: " + t);
            return gc;
        }
    }

    /**
     * The client's agent with the policy for the server. Agent options follow the first "="
     * ({@code -agentlib:zbNative=a=b,c=d}; for {@code -javaagent:}, after the "=" past ".jar").
     * The player's allow-all and deny-new are kept; any other policy is replaced with deny-new.
     */
    public static String withPolicy(String agent) {
        int from = 0;
        if (agent.startsWith("-javaagent:")) {
            int jar = agent.toLowerCase(Locale.ROOT).indexOf(".jar");
            if (jar >= 0) {
                from = jar;
            }
        }
        int eq = agent.indexOf('=', from);
        String head = eq < 0 ? agent : agent.substring(0, eq);
        String opts = eq < 0 ? "" : agent.substring(eq + 1);
        String policy = POLICY;
        StringBuilder out = new StringBuilder();
        String[] parts = opts.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("policy=")) {
                String v = p.substring("policy=".length());
                if ("allow-all".equals(v) || "deny-new".equals(v)) {
                    policy = v;
                }
                continue;
            }
            out.append(p).append(',');
        }
        out.append("policy=").append(policy);
        return head + "=" + out;
    }

    /**
     * A line of the argument file. Spaces, quotes, "#" and backslashes require quoting, and
     * inside quotes a backslash escapes: {@code C:\\Program Files}.
     */
    public static String quote(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '\\' || c == '#') {
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
        return s;
    }

    /**
     * Whether the mod is in the server's {@code Mods=}; null if the ini could not be found or read.
     * Read as ISO-8859-1: the server description may contain anything, but mod ids are ASCII.
     */
    public static Boolean listed(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem");
            Object instance = zfs.getField("instance").get(null);
            String cache = (String) zfs.getMethod("getCacheDir").invoke(instance);
            File ini = new File(new File(cache, "Server"), name + ".ini");
            if (!ini.isFile()) {
                return null;
            }
            String text = new String(Files.readAllBytes(ini.toPath()), StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r?\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("Mods=")) {
                    continue;
                }
                String[] ids = lines[i].substring("Mods=".length()).split(";");
                for (int j = 0; j < ids.length; j++) {
                    String id = ids[j].trim();
                    if (id.startsWith("\\")) {
                        id = id.substring(1);
                    }
                    if (MOD_ID.equals(id)) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
    }
}
