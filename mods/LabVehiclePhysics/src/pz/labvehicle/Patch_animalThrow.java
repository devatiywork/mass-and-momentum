package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Шаг полёта сбитого животного, каждый кадр — см. {@link AnimalThrow}.
 *
 * Висит на выходе {@code IsoAnimal.update()}: смещение дописывается в импульс объекта, и
 * {@code postupdate()} того же кадра прибавит его к позиции и сам разберёт стены.
 *
 * Пока никто не летит, стоит одну проверку volatile-флага на животное за кадр.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.characters.animals.IsoAnimal", methodName = "update", warmUp = true)
public class Patch_animalThrow {

    @Patch.OnExit
    public static void exit(@Patch.This Object animal) {
        if (AnimalThrow.anyFlying) {
            AnimalThrow.step(animal);
        }
    }
}
