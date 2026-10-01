package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Удар машины о препятствие, первая половина: если по ходу стоит дерево, которое этот удар
 * валит, — отменить {@code BaseVehicle.crash()} целиком ({@link TreeBreak#beforeCrash}).
 *
 * crash() бьёт машину по резкости остановки и не знает ни массы, ни того, что ствол сломался:
 * танк получал урон от ёлки. В сети он же шлёт серверу команду «vehicle/crash», и урон
 * наносит сервер, — пропуск crash() на клиенте водителя отменяет и его.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "crash", warmUp = true)
public class Patch_treeCrashDamage {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle) {
        return TreeBreak.beforeCrash(vehicle);
    }
}
