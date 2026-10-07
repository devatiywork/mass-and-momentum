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
     * main() runs before the pass. So it is enough to touch the classes here.
     *
     * EVERY class we patch must be in this list; build.sh refuses to build otherwise. Until 1.0.4
     * BaseVehicle, VehiclePart and RagdollController were missing: they seemed loaded anyway. They
     * were, but only thanks to LabRagdollMP. Its pass comes first and retransforms
     * IsoGameCharacter, and resolving that class's methods (onHitByVehicle(BaseVehicle, …)) loads
     * the vehicle classes on the side. Without LabRagdollMP, the optional mod, they get loaded on
     * the side of OUR pass instead, while IsoGameCharacter is being transformed, and a class loaded
     * during a transformation is not transformed itself. Every patch on a vehicle and its parts
     * then silently did nothing: no suspension limit lifted, so the masses from the table sank
     * the vans and pickups; no crowd, floor, trees, bushes, corpses under the hull, fuel tanks.
     * Reported from singleplayer without the ragdoll mod (Workshop, 07.10.2026).
     */
    public static final String[] PRELOAD = {
        // The vehicle itself, its parts (fuel tank) and the ragdoll under it.
        "zombie.vehicles.BaseVehicle",
        "zombie.vehicles.VehiclePart",
        "zombie.core.physics.RagdollController",
        // Loaded by the game long before us so far, listed so that this stays true.
        "zombie.characters.IsoGameCharacter",
        "zombie.iso.IsoMovingObject",
        "zombie.inventory.InventoryItem",
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
        // A heavy vehicle knocks a zombie over at any speed (its own override of the stance check).
        "zombie.characters.IsoZombie",
    };

    public static void main(String[] args) {
        Log.info("[LabVehiclePhysics] loaded (stages 1 + 1.5 + 1.6 + 1.7 + 1.8 + 1.9 + 3.0): zombie mass with spread + honest frame timing, "
                + "speed ceiling of 15 removed, reduced mass drives impact while chassis mass drives deceleration, prone bodies no longer bounce with FPS, "
                + "one zombie pushes the vehicle once and only for the speed it lacks, run-over bodies are crushed by the vehicle's weight, "
                + "tracked vehicles crush along their tracks, heavy vehicles knock zombies over at any speed, dead bodies under a vehicle stop being ragdolls, "
                + "placeholder wheel models are not added (3D renderers no longer drop tracked vehicles), "
                + "the corpse follows its ragdoll, vehicle masses from vehicle-physics.cfg, "
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
