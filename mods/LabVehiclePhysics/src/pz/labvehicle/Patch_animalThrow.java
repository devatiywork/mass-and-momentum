package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * One flight step of a hit animal, every frame; see {@link AnimalThrow}.
 *
 * Sits on the exit of {@code IsoAnimal.update()}: the offset is added to the object's impulse, and
 * {@code postupdate()} of the same frame adds it to the position and deals with walls itself.
 *
 * While nothing is flying, this costs one volatile flag check per animal per frame.
 *
 * IMPORTANT: ByteBuddy inlines the body of exit() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.characters.animals.IsoAnimal", methodName = "update", warmUp = true)
public class Patch_animalThrow {

    @Patch.OnExit
    public static void exit(@Patch.This Object animal) {
        if (AnimalThrow.anyFlying) {
            AnimalThrow.step(animal);
        }
    }
}
