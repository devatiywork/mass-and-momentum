package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Кусты, часть 2: вместо ванильного импульса каждый кадр — одно списание энергии за
 * контакт, см. {@link VegetationDrag}.
 *
 * Ваниль зовёт {@code applyImpulseFromHitPlant(obj, 0.025 или 0.1)} из
 * {@code checkCollisionWithPlant} каждый кадр, пока машина трётся о куст или молодое дерево.
 * Пропускаем все эти вызовы; при первом касании VegetationDrag сам зовёт тот же метод с
 * посчитанной величиной — такой вызов помечен {@code VegetationDrag.ownCall} и проходит.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitPlant", warmUp = true)
public class Patch_plantImpulse {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle, @Patch.Argument(0) Object obj) {
        return VegetationDrag.onPlantImpulse(vehicle, obj);
    }
}
