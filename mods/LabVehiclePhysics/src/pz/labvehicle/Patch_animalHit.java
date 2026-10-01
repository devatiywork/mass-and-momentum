package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Наезд на животное, сторона животного, одиночная игра: урон вместо мгновенной смерти.
 *
 * Ваниль, {@code IsoAnimal.Hit(BaseVehicle, ...)}: стоящему животному — {@code setHealth(0)}
 * при любой скорости. Корова умирала от наезда на 5 км/ч. Считаем урон по скорости удара
 * и массам (модель — {@link AnimalImpact}) и оставляем выжившему его здоровье.
 *
 * Что оставлено ванили:
 * <ul>
 *   <li>лежачее животное — переезд колесом, ваниль его добивает, мы не вмешиваемся;</li>
 *   <li>сеть. В мультиплеере {@code Hit} на клиенте здоровье не трогает вовсе, урон решает
 *       сервер — это {@link Patch_animalHitServer}.</li>
 * </ul>
 *
 * Игра зовёт {@code Hit} каждый кадр, пока машина касается животного. Урон — один раз за
 * удар; на повторных кадрах того же удара возвращаем здоровье, которое ваниль обнулила.
 * Выжившему снимаем пометку «сбит машиной»: по ней разделка туши считает его сбитым
 * ({@code ButcheringUtil.lua}: {@code modData["roadKill"] = died:isRoadKill()}), даже если
 * умрёт оно потом от другого.
 *
 * Перегрузки: {@code Hit(BaseVehicle, float, boolean, float, float, boolean, float, float)}
 * делегирует {@code Hit(BaseVehicle, float, boolean, Vector2)}, патч по имени цепляет обе.
 * Считаем вложенность и работаем на выходе из самого внешнего вызова.
 *
 * ВАЖНО: тела enter()/exit() встраиваются ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.characters.animals.IsoAnimal", methodName = "Hit", warmUp = true)
public class Patch_animalHit {

    @Patch.OnEnter
    public static void enter(@Patch.This Object animal) {
        Impl.enter(animal);
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object animal, @Patch.Argument(0) Object vehicle) {
        Impl.exit(animal, vehicle);
    }

    public static final class Impl {
        /** Вложенность перегрузок. Hit зовётся из главного потока. */
        public static int depth = 0;
        public static boolean active = false;
        public static float savedHealth = 0.0f;
        public static boolean savedStanding = false;

        public static volatile boolean broken = false;
        public static Field fClient;
        public static Field fServer;
        public static int logged = 0;
        public static long total = 0L;

        public static void enter(Object animal) {
            depth++;
            if (depth != 1) {
                return;
            }
            active = false;
            if (!LabGate.active() || broken || animal == null || !LabSettings.animalHits()) {
                return;
            }
            try {
                if (isNetwork()) {
                    return;
                }
                AnimalImpact.init(animal.getClass().getClassLoader());
                savedHealth = ((Float) AnimalImpact.aGetHealth.invoke(animal)).floatValue();
                // То же условие «стоит», что у ванили: не на полу и не в состоянии «лежит».
                savedStanding = !((Boolean) AnimalImpact.aIsOnFloor.invoke(animal)).booleanValue()
                        && AnimalImpact.aGetCurrentState.invoke(animal) != AnimalImpact.onGroundState;
                active = true;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the animal damage patch, disabling: " + t);
            }
        }

        public static void exit(Object animal, Object vehicle) {
            if (depth > 0) {
                depth--;
            }
            if (depth != 0 || !active) {
                return;
            }
            active = false;
            try {
                if (!(savedHealth > 0.0f) || !savedStanding || vehicle == null) {
                    return;
                }
                AnimalImpact.Episode ep = AnimalImpact.touch(animal);
                if (ep.damaged) {
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(savedHealth));
                    return;
                }
                float v = AnimalImpact.closingSpeed(vehicle, animal);
                if (v < AnimalImpact.MIN_SPEED) {
                    // Касание, а не удар: машина отъезжает или трётся боком.
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(savedHealth));
                    return;
                }
                float vehicleMass = AnimalImpact.fudgedMass(vehicle);
                float animalMass = AnimalImpact.weightOf(animal);
                float dmg = AnimalImpact.damage(vehicleMass, animalMass, v);
                ep.damaged = true;
                float left = savedHealth - dmg;
                if (left > 0.0f) {
                    AnimalImpact.aSetHealth.invoke(animal, Float.valueOf(left));
                    AnimalImpact.aSetIsRoadKill.invoke(animal, Boolean.FALSE);
                }
                // Иначе ваниль уже всё сделала: здоровье 0, пометка «сбит машиной».
                total++;
                log("", animal, vehicleMass, animalMass, v, savedHealth, left);
                AnimalImpact.throwAway(vehicle, animal, vehicleMass, animalMass, v);
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the animal damage patch, disabling: " + t);
            }
        }

        public static void log(String where, Object animal, float vehicleMass, float animalMass, float v,
                               float before, float after) {
            if (logged >= 12) {
                return;
            }
            logged++;
            float dv = AnimalImpact.animalDeltaV(vehicleMass, animalMass, v);
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal damage%s: %s %.1f kg hit at %.0f km/h by a %.0f kg vehicle"
                    + " -> thrown at %.0f km/h, lethal from %.0f km/h, health %.0f%% -> %s",
                    where, AnimalImpact.typeOf(animal), animalMass, AnimalImpact.kmh(v), vehicleMass,
                    AnimalImpact.kmh(dv), AnimalImpact.kmh(AnimalImpact.lethalDeltaV(animalMass)),
                    before * 100.0f, after > 0.0f ? String.format("%.0f%%", after * 100.0f) : "killed"));
        }

        /** В сети урон решает сервер, клиентский Hit здоровье не трогает — и мы не трогаем. */
        public static boolean isNetwork() throws Exception {
            if (fClient == null) {
                fServer = Class.forName("zombie.network.GameServer").getField("server");
                fClient = Class.forName("zombie.network.GameClient").getField("client");
            }
            return fClient.getBoolean(null) || fServer.getBoolean(null);
        }
    }
}
