package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Клиент: касание тела с машиной, за рулём которой другой игрок, проверяется честно, а не
 * «всегда да» — иначе рэгдолл от чужой машины не кончается ({@link RemoteRagdoll}).
 *
 * {@code BaseVehicle.isCollided} зовёт только {@code RagdollController.vehicleCollision}: касание
 * продлевает симуляцию тела. Для своего водителя ваниль и так проверяет геометрией — не трогаем.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "isCollided", warmUp = true)
public class Patch_remoteVehicleContact {

    @Patch.OnExit
    public static void exit(@Patch.This Object vehicle, @Patch.Argument(0) Object character,
                            @Patch.Return(readOnly = false) boolean ret) {
        ret = RemoteRagdoll.contact(vehicle, character, ret);
    }
}
