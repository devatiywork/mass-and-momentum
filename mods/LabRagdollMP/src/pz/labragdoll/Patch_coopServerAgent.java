package pz.labragdoll;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Кооп-сервер с ZombieBuddy, патч 2 из 2: вместо флага сборщика мусора — {@code @файл} с
 * агентом ZombieBuddy (см. {@link CoopServerAgent}). Это единственный аргумент команды
 * запуска сервера, который можно подменить, не переписывая сам запуск.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.network.CoopMaster", methodName = "getGarbageCollector")
public class Patch_coopServerAgent {

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String ret) {
        ret = CoopServerAgent.argument(ret);
    }
}
