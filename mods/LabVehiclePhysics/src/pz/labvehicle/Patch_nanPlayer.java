package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Растяжка на NaN, точка 2 для игрока: после каждого обновления запоминаем последнюю конечную
 * позицию, а стала NaN — возвращаем её ({@link NanGuard#player}). Иначе не-число уйдёт в
 * players.db, и при следующем входе игрок окажется в (0,0), за краем карты.
 *
 * У IsoPlayer одно update() без аргументов; advice не трогает аргументы, так что и появись
 * перегрузка — патч по имени ей не повредит.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class Patch_nanPlayer {

    @Patch.OnExit
    public static void exit(@Patch.This Object player) {
        NanGuard.player(player);
    }
}
