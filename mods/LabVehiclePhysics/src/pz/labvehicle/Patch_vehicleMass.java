package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 3, патч 1 из 2: настоящие массы машин.
 *
 * Точка входа выбрана не случайно. Цепочка в игре такая:
 * <pre>
 * VehicleScript.Loaded()            // один раз на каждый скрипт машины при загрузке
 *   ├── масштабирование по модели
 *   └── toBullet()                  // складывает params[200] и отдаёт в нативную часть
 *          params[1] = mass
 *          params[3] = suspensionStiffness
 *          params[4] = suspensionCompression
 *          params[5] = suspensionDamping
 *          params[6] = maxSuspensionTravelCm
 *          params[7] = suspensionRestLength
 *          Bullet.defineVehicleScript(fullName, params)
 * </pre>
 * Поэтому переписывать поля надо ДО тела Loaded(): тогда изменения попадают и в Bullet,
 * и в {@code BaseVehicle.setInitialMass(script.getMass())}, то есть во всё сразу.
 *
 * Отдельно про сервер: {@code toBullet()} вызывается только под {@code !GameServer.server},
 * то есть выделенный сервер физику машин вообще не считает и в Bullet их не заводит.
 * Значит эта правка сетевой синхронизации не требует. Но сам {@code Loaded()} на сервере
 * выполняется, и масса оттуда уходит в {@code setInitialMass}, поэтому патч ставим
 * на Loaded(), а не на toBullet() — чтобы обе стороны знали одну и ту же массу.
 *
 * Числа берутся из файла, а не из кода: см. {@link VehicleCfg}.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в Loaded() — только public-члены, никаких лямбд.
 */
/*
 * warmUp намеренно выключён. С ним в логе получилось так:
 *     [ZB] warming up class: zombie.scripting.objects.VehicleScript
 *     [ZB] Loader.loadMods() took 1931 ms
 * — класс загрузили, а ни строки "patching ... with 1 advice(s)", ни "Transformed:"
 * за ней не последовало. У BaseVehicle, который на тот момент уже был загружен, обе
 * строки есть. Похоже, принудительная загрузка происходит ПОСЛЕ прохода патчера,
 * и класс проскакивает мимо него. Без warmUp класс грузится сам, когда игра доходит
 * до чтения скриптов, и попадает под трансформер штатно.
 *
 * На случай, если и это не сработает, есть запасной путь: VehicleCfg.applyToAllScripts()
 * проходит по ScriptManager вручную.
 */
@Patch(className = "zombie.scripting.objects.VehicleScript", methodName = "Loaded")
public class Patch_vehicleMass {

    @Patch.OnEnter
    public static void enter(@Patch.This Object script) {
        VehicleCfg.applyToScript(script);
    }
}
