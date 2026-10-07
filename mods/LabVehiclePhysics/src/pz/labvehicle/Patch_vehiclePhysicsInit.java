package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Makes sure the suspension parameters are baked in BEFORE the first vehicle enters physics.
 *
 * Background. The right hook point is {@code VehicleScript.Loaded()}, but ZombieBuddy does not
 * intercept that class: the log has only the warm-up line, and no "patching … with 1 advice(s)"
 * follows it, with or without {@code warmUp}. So we have to apply the mass table ourselves, by
 * walking {@code ScriptManager.getAllVehicleScripts()}.
 *
 * The first version ran this walk with a delay, which produced exactly the problem it was meant
 * to solve. The vehicle creation chain is:
 * <pre>
 * BaseVehicle.createPhysics()
 *   └── new CarController(vehicle)
 *          └── Bullet.addVehicle(id, x, y, z, rot…, script.getFullName())
 * </pre>
 * {@code addVehicle} builds the vehicle by the NAME of an already registered script, and that
 * script's parameters (suspension stiffness, travel and length) go to the native side once, via
 * {@code defineVehicleScript}. So they must be rewritten before the first {@code addVehicle};
 * otherwise the vehicle is created with the old suspension and it is too late to redefine: a
 * repeated {@code defineVehicleScript} affects only vehicles created after it.
 *
 * Mass is not affected by this: the game passes it to Bullet every frame in a separate call,
 * {@code setVehicleMass}, so it can be changed at any time. The suspension, though, only here.
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into createPhysics: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "createPhysics", warmUp = true)
public class Patch_vehiclePhysicsInit {

    @Patch.OnEnter
    public static void enter() {
        NativeBridge.ensure();
        // Where the limit stays, the masses get capped (SuspensionCap); on Windows that is known
        // only now, after the masses were already written, so they are applied once more.
        SuspensionCap.afterNativeAttempt();
        VehicleCfg.ensureApplied();
    }
}
