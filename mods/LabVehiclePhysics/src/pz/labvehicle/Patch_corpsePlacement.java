package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Клиент: замер — насколько тело переносится, когда приходит труп с сервера
 * ({@link RemoteRagdoll#onCorpsePacket}). Сама игра это не пишет: её строка «Corpse … teleport»
 * — отладочная и в обычном логе не появляется.
 *
 * {@code DeadCharacterPacket.processClient} ставит тело на координаты трупа сервера, если клетка
 * другая, и создаёт труп. Здесь только считаем, ничего не меняем.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.network.packets.character.DeadCharacterPacket", methodName = "processClient", warmUp = true)
public class Patch_corpsePlacement {

    @Patch.OnEnter
    public static void enter(@Patch.This Object packet) {
        RemoteRagdoll.onCorpsePacket(packet);
    }
}
