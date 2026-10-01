package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Растяжка на NaN, точка 4: сервер получил пакет физики машины. processServer пишет из него
 * x, y, z, поворот и скорость в машину без единой проверки; пакет с не-числом не применяем
 * вовсе ({@link NanGuard#packetIn}) — машина остаётся в последнем нормальном состоянии.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.network.packets.vehicle.VehiclePhysicsPacket", methodName = "processServer", warmUp = true)
public class Patch_nanPacketIn {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object packet) {
        return NanGuard.packetIn(packet);
    }
}
