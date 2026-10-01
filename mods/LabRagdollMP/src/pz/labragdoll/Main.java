package pz.labragdoll;

public class Main {

    /**
     * Classes that must be loaded before the patcher's pass: ZombieBuddy patches only classes that
     * are already loaded (details in pz.labvehicle.Main and modding-notes.md, section 1).
     */
    public static final String[] PRELOAD = {
        // Co-op server with ZombieBuddy: launching the server from the "Host" menu.
        "zombie.network.CoopMaster",
    };

    public static void main(String[] args) {
        Log.info("[LabRagdollMP] loaded (v3): three locks removed - IsoGameCharacter.canRagdoll, Core.getOptionUsePhysicsHitReaction "
                + "and IsoGameCharacter.canUseCurrentPoseForCorpse - ragdolls are no longer blocked in multiplayer, "
                + "all other vanilla conditions are preserved; safety gate: only where LabRagdollMP is in the game's mod list; "
                + "the co-op server gets ZombieBuddy");

        for (int i = 0; i < PRELOAD.length; i++) {
            String name = PRELOAD[i];
            try {
                Class.forName(name, false, Main.class.getClassLoader());
                Log.debug("[LabRagdollMP] preloaded " + name + " - the patcher should see it now");
            } catch (Throwable t) {
                Log.info("[LabRagdollMP] failed to preload " + name + ": " + t);
            }
        }
    }
}
