package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Кусты, часть 1: снять ванильный потолок скорости и списать новые объекты из списка
 * контактов — см. {@link VegetationDrag}.
 *
 * На выходе {@code BaseVehicle.breakingObjects()} поле {@code breakingSlowFactor} уже
 * посчитано; обнуляем его, и {@code updateVelocityMultiplier} оставляет только общий
 * предел скорости игры. Объекты со свойством CarSlowFactor, с которыми машина сейчас
 * в контакте, лежат в {@code breakingObjectsList} — каждый списывается один раз за контакт.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "breakingObjects", warmUp = true)
public class Patch_vegetationCap {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle) {
        VegetationDrag.afterBreakingObjects(vehicle);
    }
}
