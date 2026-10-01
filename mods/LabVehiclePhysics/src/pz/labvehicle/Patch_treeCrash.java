package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Удар машины о препятствие, вторая половина: исполнить повал дерева, решённый в
 * {@link Patch_treeCrashDamage} ({@link TreeBreak#afterDamageObjects}).
 *
 * {@code BaseVehicle.damageObjects(float)} игра зовёт сразу после {@code crash()} в том же
 * блоке обработки удара. Если crash() мы отменили ради повала, damageObjects всё равно
 * идёт — здесь дерево и валится.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "damageObjects", warmUp = true)
public class Patch_treeCrash {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        TreeBreak.afterDamageObjects(vehicle);
    }
}
