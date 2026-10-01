package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Клиент: пока у нас идёт свой рэгдолл сбитого зомби, сообщения его хозяина к телу не
 * применяются — иначе тело мечется между своим полётом и чужим ({@link RemoteRagdoll}).
 *
 * {@code NetworkZombieAI.parse} — приём сообщения хозяина у клиента, который зомби не владеет:
 * путь к позиции хозяина, поворот, телепорт дальше 3 клеток. У владельца и на сервере не зовётся.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.characters.NetworkZombieAI", methodName = "parse", warmUp = true)
public class Patch_remoteRagdollUpdate {

    /** @return true — пропустить сообщение хозяина. */
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object networkAi, @Patch.Argument(0) Object packet) {
        return RemoteRagdoll.skipOwnerUpdate(networkAi, packet);
    }
}
