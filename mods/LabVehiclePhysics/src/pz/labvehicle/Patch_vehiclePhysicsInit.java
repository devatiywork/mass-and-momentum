package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Гарантия, что параметры подвески зашиты ДО создания первой машины в физике.
 *
 * Предыстория. Правильная точка — {@code VehicleScript.Loaded()}, но ZombieBuddy этот класс
 * не перехватывает: в логе есть только строка прогрева, а «patching … with 1 advice(s)»
 * за ней не появляется ни с {@code warmUp}, ни без него. Поэтому таблицу масс приходится
 * применять самим, обходом {@code ScriptManager.getAllVehicleScripts()}.
 *
 * Первая версия запускала этот обход с задержкой, из чего вышла ровно та проблема, которую
 * он должен был решить. Цепочка создания машины такая:
 * <pre>
 * BaseVehicle.createPhysics()
 *   └── new CarController(vehicle)
 *          └── Bullet.addVehicle(id, x, y, z, rot…, script.getFullName())
 * </pre>
 * {@code addVehicle} строит машину по ИМЕНИ уже зарегистрированного скрипта, а параметры
 * этого скрипта (жёсткость, ход и длина подвески) уходят в нативную часть один раз, через
 * {@code defineVehicleScript}. Значит переписать их нужно до первого {@code addVehicle},
 * иначе машина создастся со старой подвеской и переопределять будет поздно: повторный
 * {@code defineVehicleScript} действует только на машины, созданные после него.
 *
 * Масса этим не затрагивается — её игра отдаёт в Bullet каждый кадр отдельным вызовом
 * {@code setVehicleMass}, поэтому её можно менять когда угодно. А вот подвеска — только здесь.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в createPhysics — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "createPhysics", warmUp = true)
public class Patch_vehiclePhysicsInit {

    @Patch.OnEnter
    public static void enter() {
        NativeBridge.ensure();
        VehicleCfg.ensureApplied();
    }
}
