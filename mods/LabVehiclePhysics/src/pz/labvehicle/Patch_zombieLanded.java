package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Клиент водителя: сбитое тело легло — точка приземления уходит серверу ({@link CorpseSync}).
 *
 * {@code ZombieOnGroundState} — состояние «лежит»: в него зомби переходит, когда кончился
 * рэгдолл ({@code vehicleCollision-ragdoll/to_onground.xml}: {@code !isSimulationActive}) или
 * анимация падения. У мёртвого зомби {@code enter()} тут же зовёт {@code die()}, так что вход —
 * последний момент, когда живое положение тела и есть положение будущего трупа.
 *
 * На сервере и у зомби, которых не сбивал наш водитель, стоит одну проверку volatile-флага.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.ai.states.ZombieOnGroundState", methodName = "enter", warmUp = true)
public class Patch_zombieLanded {

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) Object owner) {
        if (CorpseSync.anyFlying) {
            CorpseSync.onLanded(owner);
        }
    }
}
