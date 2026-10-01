package pz.labvehicle;

public class Main {

    /**
     * Classes that must be loaded before the patcher pass.
     *
     * ZombieBuddy successfully patches only the classes that are already loaded at the
     * time of its pass: for those the log shows a pair of lines, "patching …" and "Transformed: …
     * (retransformed)". Classes the game has not reached yet get marked as
     * "warming up class: …", but the patch is never applied to them: no "patching" line
     * follows the warm-up, with or without warmUp.
     *
     * Because of this, two patches in a row silently did nothing: the mass table on
     * VehicleScript.Loaded() and the thrust multiplier on CarController.checkTire. There were
     * no errors anywhere: the patch counts as found, it just never gets called.
     *
     * The order in the log suggests the cure:
     * <pre>
     * [ZB] trying to load pz.labvehicle.Main
     * [LabVehiclePhysics] loaded ...      ← this method
     * [ZB] Scanned 22 classes in package pz.labvehicle
     * [ZB] Found patch class: ...         ← the patcher pass
     * </pre>
     * main() runs before the pass. So it is enough to touch the classes here, and by
     * the time of the pass they are loaded just like BaseVehicle.
     */
    public static final String[] PRELOAD = {
        "zombie.core.physics.CarController",
        "zombie.scripting.objects.VehicleScript",
        // Animal hits: damage in singleplayer and on the server.
        "zombie.characters.animals.IsoAnimal",
        "zombie.network.fields.hit.VehicleHitField",
        // Tree felling by vehicles.
        "zombie.iso.objects.IsoTree",
        // Corpse where the body fell, in multiplayer: the zombie's owner and the "landed" moment.
        "zombie.popman.NetworkZombieManager",
        "zombie.ai.states.ZombieOnGroundState",
        // Ragdoll on a non-owner client: the owner's messages and the corpse from the server.
        "zombie.characters.NetworkZombieAI",
        "zombie.network.packets.character.DeadCharacterPacket",
        // NaN tripwire: the vehicle physics packet and the player update.
        "zombie.network.packets.vehicle.VehiclePhysicsPacket",
        "zombie.characters.IsoPlayer",
        // Co-op server with ZombieBuddy: starting the server from the "Host" menu.
        "zombie.network.CoopMaster",
    };

    public static void main(String[] args) {
        Log.info("[LabVehiclePhysics] loaded (stages 1 + 1.5 + 1.6 + 1.7 + 1.8 + 3.0): zombie mass with spread + honest frame timing, "
                + "speed ceiling of 15 removed, reduced mass drives impact while chassis mass drives deceleration, prone bodies no longer bounce with FPS, "
                + "one zombie pushes the vehicle once, the corpse follows its ragdoll, vehicle masses from vehicle-physics.cfg, "
                + "vehicle floor, live power and brakes, animal hits by mass and speed, bushes by energy, heavy vehicles break trees, "
                + "settings on the sandbox page, in multiplayer the corpse lies where the hit body landed, "
                + "NaN tripwire on vehicle and player positions, the co-op server gets ZombieBuddy");

        for (int i = 0; i < PRELOAD.length; i++) {
            String name = PRELOAD[i];
            try {
                Class.forName(name, false, Main.class.getClassLoader());
                Log.debug("[LabVehiclePhysics] preloaded " + name + " - the patcher should see it now");
            } catch (Throwable t) {
                Log.info("[LabVehiclePhysics] failed to preload " + name + ": " + t);
            }
        }
    }
}
