package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 3, patch 1 of 2: real vehicle masses.
 *
 * The entry point is chosen deliberately. The chain in the game is:
 * <pre>
 * VehicleScript.Loaded()            // once per vehicle script, at load time
 *   ├── model scaling
 *   └── toBullet()                  // packs params[200] and hands it to the native side
 *          params[1] = mass
 *          params[3] = suspensionStiffness
 *          params[4] = suspensionCompression
 *          params[5] = suspensionDamping
 *          params[6] = maxSuspensionTravelCm
 *          params[7] = suspensionRestLength
 *          Bullet.defineVehicleScript(fullName, params)
 * </pre>
 * So the fields must be rewritten BEFORE the body of Loaded(): then the changes reach both Bullet
 * and {@code BaseVehicle.setInitialMass(script.getMass())}, i.e. everything at once.
 *
 * A note on the server: {@code toBullet()} is called only under {@code !GameServer.server},
 * i.e. a dedicated server does not simulate vehicle physics at all and never adds vehicles to
 * Bullet. So this change needs no network sync. But {@code Loaded()} itself does run on the
 * server, and the mass goes from there into {@code setInitialMass}, so the patch sits on
 * Loaded() rather than toBullet(): that way both sides know the same mass.
 *
 * The numbers come from a file, not from code: see {@link VehicleCfg}.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into Loaded(): public members only, no lambdas.
 */
/*
 * warmUp is deliberately off. With it, the log came out like this:
 *     [ZB] warming up class: zombie.scripting.objects.VehicleScript
 *     [ZB] Loader.loadMods() took 1931 ms
 * The class got loaded, but neither a "patching ... with 1 advice(s)" line nor "Transformed:"
 * followed it. BaseVehicle, which was already loaded at that point, has both lines. It
 * looks like the forced load happens AFTER the patcher's pass, and the class slips past
 * it. Without warmUp the class loads by itself when the game gets to reading scripts,
 * and goes through the transformer the normal way.
 *
 * In case that does not work either, there is a fallback: VehicleCfg.applyToAllScripts()
 * walks the ScriptManager by hand.
 */
@Patch(className = "zombie.scripting.objects.VehicleScript", methodName = "Loaded")
public class Patch_vehicleMass {

    @Patch.OnEnter
    public static void enter(@Patch.This Object script) {
        VehicleCfg.applyToScript(script);
    }
}
