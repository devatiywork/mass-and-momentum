package pz.labvehicle;

public class Main {

    /**
     * Классы, которые надо загрузить до прохода патчера.
     *
     * ZombieBuddy успешно патчит только те классы, которые на момент его прохода уже
     * загружены — в логе у них появляется пара строк «patching …» и «Transformed: …
     * (retransformed)». Классы, до которых игра ещё не добралась, он помечает как
     * «warming up class: …», но патч к ним не применяется: ни одной строки «patching»
     * за прогревом не следует, ни с warmUp, ни без него.
     *
     * Из-за этого молча не работали два патча подряд — таблица масс на
     * VehicleScript.Loaded() и множитель тяги на CarController.checkTire. Ошибок при
     * этом не было нигде: патч числится найденным, просто никогда не вызывается.
     *
     * Порядок в логе подсказывает лечение:
     * <pre>
     * [ZB] trying to load pz.labvehicle.Main
     * [LabVehiclePhysics] loaded ...      ← этот метод
     * [ZB] Scanned 22 classes in package pz.labvehicle
     * [ZB] Found patch class: ...         ← проход патчера
     * </pre>
     * main() выполняется раньше прохода. Значит достаточно тронуть классы здесь —
     * и к проходу они окажутся загруженными наравне с BaseVehicle.
     */
    public static final String[] PRELOAD = {
        "zombie.core.physics.CarController",
        "zombie.scripting.objects.VehicleScript",
        // Наезд на животных: урон в одиночной игре и на сервере.
        "zombie.characters.animals.IsoAnimal",
        "zombie.network.fields.hit.VehicleHitField",
        // Повал деревьев машиной.
        "zombie.iso.objects.IsoTree",
        // Труп там, где упало тело, в сети: хозяин зомби и момент «лёг».
        "zombie.popman.NetworkZombieManager",
        "zombie.ai.states.ZombieOnGroundState",
        // Рэгдолл у того, кто не хозяин тела: сообщения хозяина и труп с сервера.
        "zombie.characters.NetworkZombieAI",
        "zombie.network.packets.character.DeadCharacterPacket",
        // Растяжка на NaN: пакет физики машины и обновление игрока.
        "zombie.network.packets.vehicle.VehiclePhysicsPacket",
        "zombie.characters.IsoPlayer",
        // Кооп-сервер с ZombieBuddy: запуск сервера из меню «Хостинг».
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
