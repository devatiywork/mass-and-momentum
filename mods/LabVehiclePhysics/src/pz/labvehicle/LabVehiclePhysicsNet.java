package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Exposer;

/**
 * Вход из Lua в Java для серверной таблицы ({@link ServerTable}).
 *
 * ZombieBuddy открывает класс для Lua, статические методы зовутся через точку:
 * {@code LabVehiclePhysicsNet.serverTable()}. Имя в аннотации не задано — тогда класс идёт
 * штатным путём игры ({@code exposeLikeJavaRecursively} в корень окружения), и Kahlua
 * кладёт его под простым именем, если оно свободно, — как классы самой игры.
 *
 * Всё публичное статическое здесь видно из Lua любого мода, поэтому логики тут нет —
 * только входы, которые нужны нашим Lua-файлам: {@code LabVehiclePhysics_ServerTable.lua},
 * {@code LabVehiclePhysics_TreeBreak.lua} и {@code LabVehicleFuel.lua}.
 */
@Exposer.LuaClass
public final class LabVehiclePhysicsNet {

    private LabVehiclePhysicsNet() {
    }

    /** Клиент: уже в игре и может слать команды серверу. */
    public static boolean clientReady() {
        return ServerTable.clientReady();
    }

    /** Сервер: номер версии своего vehicle-physics.cfg, растёт при каждом изменении. */
    public static double serverStamp() {
        return ServerTable.serverStamp();
    }

    /** Сервер: таблица для отправки клиентам, null если собрать не вышло. */
    public static Object serverTable() {
        return ServerTable.build();
    }

    /** Клиент: принять таблицу, присланную сервером. */
    public static void receiveServerTable(Object args) {
        ServerTable.accept(args);
    }

    /** Сервер: клиент водителя сообщил об ударе в дерево — см. {@link TreeBreak#serverTreeHit}. */
    public static void serverTreeHit(Object player, Object args) {
        TreeBreak.serverTreeHit(player, args);
    }

    /** Сервер: клиент водителя сообщил, где легло сбитое тело — см. {@link CorpseSync#serverLanded}. */
    public static void serverZombieLanded(Object player, Object args) {
        CorpseSync.serverLanded(player, args);
    }

    /**
     * Масса скрипта машины до нашего справочника, кг; 0 — скрипт ещё не встречался.
     * Для расхода топлива «как в игре» в {@code LabVehicleFuel.lua}.
     */
    public static double originalMass(String scriptName) {
        return VehicleCfg.originalMass(scriptName);
    }

    /**
     * Переключатель песочницы «Расход топлива по массе». Lua спрашивает здесь, а не в
     * {@code SandboxVars}: правка из отладочного меню одиночной игры меняет сами опции, а
     * таблицу {@code SandboxVars} не обновляет до перезахода.
     */
    public static boolean fuelByMass() {
        return LabSettings.fuelByMass();
    }

    // ---- панель «Физика транспорта: машины» (LabVehiclePhysics_VehicleTable.lua), см. VehicleTable

    /** Все машины: массив {name, full, mod, vanilla}; null — не вышло, причина в логе. */
    public static Object tableVehicles() {
        try {
            return VehicleTable.vehicles();
        } catch (Throwable t) {
            tableFailed("vehicle list", t);
            return null;
        }
    }

    /** Пресеты по типу техники: массив {id, mass, power, maxSpeed, tank, lowGear, lowGearTo, category}. */
    public static Object tablePresets() {
        try {
            return VehicleTable.presets();
        } catch (Throwable t) {
            tableFailed("presets", t);
            return null;
        }
    }

    /** Значения машины без строки таблицы и со строкой {@code row}: {base, result, game}. */
    public static Object tableDescribe(String name, String row) {
        try {
            return VehicleTable.describe(name, row);
        } catch (Throwable t) {
            tableFailed("vehicle values", t);
            return null;
        }
    }

    public static final java.util.Set<String> TABLE_FAILED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static void tableFailed(String what, Throwable t) {
        if (TABLE_FAILED.add(what)) {
            Log.info("[LabVehiclePhysics] vehicle table panel: could not get the " + what + ": " + t);
        }
    }
}
