package pz.labragdoll;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Кооп-сервер с ZombieBuddy, патч 1 из 2: какой сервер запускается. По имени
 * {@link CoopServerAgent} найдёт его ini и проверит, стоит ли там мод.
 *
 * Под имя подходят обе перегрузки launchServer — публичная и приватная, через которую идёт
 * и softreset; имя сервера у обеих первым аргументом.
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.network.CoopMaster", methodName = "launchServer")
public class Patch_coopServerName {

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String serverName) {
        CoopServerAgent.launching(serverName);
    }
}
