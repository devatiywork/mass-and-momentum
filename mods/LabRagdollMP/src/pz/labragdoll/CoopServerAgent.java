package pz.labragdoll;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Кооп-сервер с ZombieBuddy.
 *
 * <h2>Проблема</h2>
 * «Хостинг» в меню поднимает сервер отдельным процессом: {@code CoopMaster.launchServer}
 * собирает команду {@code jre64\bin\java ... zombie.network.GameServer -coop} из жёстко
 * заданного списка аргументов. {@code -agentlib:zbNative} из {@code ProjectZomboid64.json}
 * туда не попадает, а сам ZombieBuddy этот запуск не трогает. Выходит, что на кооп-сервере
 * нет ни одного Java-мода: деревья не падают (валит сервер), трупы не синхронизируются,
 * серверной таблицы машин и серверных патчей нет. В логе сервера ни строки {@code [ZB]}, а
 * наш Lua пишет «the Java side (LabVehiclePhysicsNet) is not available». В лаборатории этого
 * не видно: там выделенный сервер, которому агент передан через {@code _JAVA_OPTIONS}.
 *
 * <h2>Решение</h2>
 * Подменить, не переписывая запуск, можно только один аргумент команды — флаг сборщика
 * мусора из {@code private String getGarbageCollector()}: он встаёт одной строкой перед
 * именем главного класса. Возвращаем вместо него {@code @файл}: лаунчер java (JDK 9+, в игре
 * 25) разворачивает такой аргумент в содержимое файла. В файле — прежний флаг сборщика, флаги
 * доступа клиента ({@code --enable-native-access}, {@code --add-exports}: с ними запускается и
 * выделенный сервер лаборатории) и агент ZombieBuddy в том же виде, что у клиента, но с
 * {@code policy=deny-new}.
 *
 * {@code deny-new}: сервер загружает только те Java-моды, которые игрок уже одобрил на клиенте
 * с «запомнить» (одобрения общие — {@code ~/.zombie_buddy/mod_approvals.json}, {@code user.home}
 * сервер получает от клиента), и ни о чём не спрашивает. Спрашивать ему некого: окна у
 * кооп-сервера нет, вопрос повесил бы запуск. Выбранный игроком {@code allow-all} сохраняется.
 * Клиентские моды ({@code media/java/client/}, например Viewpoint) ZombieBuddy на сервере
 * пропускает сам.
 *
 * <h2>Предохранитель</h2>
 * Как {@link LabGate}: вмешиваемся, только если мод есть в {@code Mods=} запускаемого сервера
 * ({@code <cachedir>/Server/<имя>.ini}). Имя сервера getGarbageCollector не знает — его ловит
 * {@link Patch_coopServerName} на входе в launchServer. Не смогли прочитать ini — добавляем
 * агент и пишем об этом в лог, как LabGate при сбое.
 *
 * <h2>Два мода — один агент</h2>
 * Такой же класс есть в LabVehiclePhysics: любой из модов может стоять без другого. Кто первый, тот
 * пишет файл; второй узнаёт его по имени {@link #FILE_NAME} и ничего не меняет.
 *
 * Работает, только если Java-мод уже загружен в клиенте к моменту хостинга, то есть включён
 * в списке модов главного меню, а не только в настройках сервера.
 */
public final class CoopServerAgent {

    public static final String MOD_ID = LabGate.MOD_ID;
    /** Файл аргументов — общий для обоих модов: по имени второй видит, что агент уже добавлен. */
    public static final String FILE_NAME = "mass-momentum-coop-server.args";
    /** Политика ZombieBuddy на сервере, если игрок не выбрал allow-all или deny-new. */
    public static final String POLICY = "deny-new";

    public static volatile String serverName;
    public static volatile boolean broken = false;

    private CoopServerAgent() {
    }

    /** Вход в CoopMaster.launchServer: запомнить, какой сервер запускается. */
    public static void launching(String name) {
        serverName = name;
    }

    /**
     * Выход из CoopMaster.getGarbageCollector: {@code @файл} с агентом ZombieBuddy вместо
     * флага сборщика — или прежний флаг, если мод на этом сервере не стоит.
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
                Log.debug("[LabRagdollMP] co-op server \"" + name + "\": " + MOD_ID
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
                Log.info("[LabRagdollMP] co-op server: ZombieBuddy is not among this game's JVM arguments"
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
            Log.info("[LabRagdollMP] co-op server \"" + name + "\": ZombieBuddy added (" + serverAgent
                    + ") - the server loads only the Java mods approved in this game with \"remember\""
                    + (listed == null ? "; its Mods= could not be read, added anyway" : ""));
            return "@" + file.getAbsolutePath();
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabRagdollMP] ERROR adding ZombieBuddy to the co-op server, it starts without Java mods: " + t);
            return gc;
        }
    }

    /**
     * Агент клиента с политикой для сервера. Параметры агента идут после первого «=»
     * ({@code -agentlib:zbNative=a=b,c=d}, у {@code -javaagent:} — после «=» за «.jar»).
     * allow-all и deny-new игрока сохраняются, любая другая политика заменяется на deny-new.
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
     * Строка файла аргументов. Пробелы, кавычки, «#» и обратная косая требуют кавычек, а
     * внутри кавычек обратная косая экранирует: {@code C:\\Program Files}.
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
     * Есть ли мод в {@code Mods=} сервера. null — ini не нашли или не прочитали.
     * Читаем как ISO-8859-1: в описании сервера бывает что угодно, а id модов — ASCII.
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
