package pz.labragdoll;

public class Main {

    /**
     * Классы, которые надо загрузить до прохода патчера: ZombieBuddy патчит только уже
     * загруженные (подробно — pz.labvehicle.Main и modding-notes.md, раздел 1).
     */
    public static final String[] PRELOAD = {
        // Кооп-сервер с ZombieBuddy: запуск сервера из меню «Хостинг».
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
