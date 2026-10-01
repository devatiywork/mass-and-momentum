package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Растяжка на NaN, точка 3: клиент собрал пакет физики машины ({@code VehiclePhysicsPacket.set}).
 * Если в нём не-число — пишем, что именно, и заменяем последним нормальным состоянием машины
 * ({@link NanGuard#packetOut}): сервер применяет пакет без проверок, и NaN из него уходит в сейв.
 *
 * Класс пакета грузится поздно — он в {@link Main#PRELOAD}.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.network.packets.vehicle.VehiclePhysicsPacket", methodName = "set", warmUp = true)
public class Patch_nanPacketOut {

    @Patch.OnExit
    public static void exit(@Patch.This Object packet) {
        NanGuard.packetOut(packet);
    }
}
