package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Сервер: пока сбитый машиной зомби летит, его хозяин — водитель, и менять его нельзя
 * ({@link CorpseSync}).
 *
 * {@code NetworkZombieManager.updateAuth} каждый кадр сервера на каждого зомби пересматривает
 * хозяина: раз в 2 секунды отдаёт зомби игроку, за которым тот гонится, или ближайшему. Тело
 * летит дольше — и хозяином стал бы игрок, у которого рэгдолла нет, а сообщения водителя сервер
 * перестал бы принимать ({@code NetworkZombiePacker.parseZombie}: только от хозяина).
 *
 * Пока никого не ждём, стоит одну проверку volatile-флага на зомби за кадр.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.popman.NetworkZombieManager", methodName = "updateAuth", warmUp = true)
public class Patch_zombieOwnerHold {

    /** @return true — пропустить пересмотр хозяина. */
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) Object zombie) {
        return CorpseSync.anyPending && CorpseSync.holdOwner(zombie);
    }
}
