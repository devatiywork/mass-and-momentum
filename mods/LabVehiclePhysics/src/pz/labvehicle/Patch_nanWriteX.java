package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Растяжка на NaN, точка 1: кто записал не-число в координату X ({@link NanGuard#badWrite}).
 *
 * setX не переопределён ни у машины, ни у персонажей, ни у зомби, поэтому патч на
 * IsoMovingObject видит все записи через сеттер — в том числе
 * {@code VehiclePhysicsPacket.processServer}, который пишет в машину координаты из пакета.
 * setX зовётся тысячи раз за кадр: на быстром пути только сравнение числа, предохранитель
 * проверяется уже в badWrite.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "setX")
public class Patch_nanWriteX {

    @Patch.OnEnter
    public static void enter(@Patch.This Object self, @Patch.Argument(0) float value) {
        if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
            NanGuard.badWrite(self, "setX", value);
        }
    }
}
