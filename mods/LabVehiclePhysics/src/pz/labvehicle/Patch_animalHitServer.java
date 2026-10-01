package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Наезд на животное в мультиплеере: урон решает сервер, и тут та же модель, что в
 * одиночной игре ({@link Patch_animalHit}, {@link AnimalImpact}).
 *
 * В сети животных считает сервер: у клиента в {@code IsoAnimal.updateInternal} поведения
 * нет, только отрисовка и отправка удара, а {@code isLocalPlayer()} у животного на
 * клиенте всегда false. Клиент водителя шлёт {@code VehicleHitAnimalPacket}, сервер его
 * разбирает:
 * <pre>
 * // VehicleHitField.process
 * if (target instanceof IsoAnimal isoAnimal) isoAnimal.setHealth(0.0F);   // любой наезд — смерть
 * </pre>
 *
 * <h2>Скорость удара</h2>
 * Физики машин на сервере нет, скорость берётся из пакета. Клиент кладёт туда
 * {@code HitVars.hitSpeed}, а для стоящего животного это {@code max(2 * v, 5)}, где v —
 * полная горизонтальная скорость машины, без потолка. Выше 2.5 м/с v восстанавливается
 * точно. Ниже помогает направление удара: {@code IsoAnimal.Hit} на клиенте задаёт ему длину
 * {@code 3 * min(v, 15) / 15 = 0.2 * v}, и пакет его несёт.
 *
 * Отличие от одиночной игры: здесь v — полная скорость машины, а не её доля в сторону
 * животного, направления движения в пакете нет. При касании вскользь урон выйдет больше.
 *
 * Тот же вход — удар машиной на сервере — нужен и зомби: до урона водитель становится
 * хозяином зомби, а его труп ждёт точку приземления ({@link CorpseSync}).
 *
 * ВАЖНО: тела enter()/exit() встраиваются ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.network.fields.hit.VehicleHitField", methodName = "process", warmUp = true)
public class Patch_animalHitServer {

    @Patch.OnEnter
    public static void enter(@Patch.This Object field, @Patch.Argument(0) Object wielder,
                             @Patch.Argument(1) Object target, @Patch.Argument(2) Object vehicle) {
        // Зомби: до урона отдать его водителю и отложить труп до приземления (CorpseSync).
        CorpseSync.onServerVehicleHit(wielder, target, vehicle, field);
        Impl.enter(target);
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object field, @Patch.Argument(1) Object target,
                            @Patch.Argument(2) Object vehicle) {
        Impl.exit(field, target, vehicle);
    }

    public static final class Impl {
        /** Порог в hitSpeed = max(2v, 5): ниже него скорость в поле стоит на пределе. */
        public static final float HIT_SPEED_FLOOR = 5.0f;
        /** Длина направления удара на единицу скорости: 3 / 15. */
        public static final float DIR_PER_SPEED = 0.2f;

        public static volatile boolean broken = false;
        public static Field fServer;
        public static Field fVehicleSpeed;
        public static Field fDirX;
        public static Field fDirY;
        public static Object pending;
        public static float savedHealth = 0.0f;
        public static boolean savedStanding = false;
        public static long total = 0L;

        public static void enter(Object target) {
            pending = null;
            if (!LabGate.active() || broken || target == null || !LabSettings.animalHits()) {
                return;
            }
            try {
                if (fServer == null) {
                    fServer = Class.forName("zombie.network.GameServer").getField("server");
                }
                if (!fServer.getBoolean(null)) {
                    return;
                }
                AnimalImpact.init(target.getClass().getClassLoader());
                if (!AnimalImpact.isAnimal(target)) {
                    return;
                }
                savedHealth = ((Float) AnimalImpact.aGetHealth.invoke(target)).floatValue();
                savedStanding = !((Boolean) AnimalImpact.aIsOnFloor.invoke(target)).booleanValue()
                        && AnimalImpact.aGetCurrentState.invoke(target) != AnimalImpact.onGroundState;
                pending = target;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the server animal damage patch, disabling: " + t);
            }
        }

        public static void exit(Object field, Object target, Object vehicle) {
            if (pending == null || pending != target) {
                return;
            }
            pending = null;
            try {
                if (!(savedHealth > 0.0f) || !savedStanding || vehicle == null || field == null) {
                    return;
                }
                if (fVehicleSpeed == null) {
                    init(field);
                }
                AnimalImpact.Episode ep = AnimalImpact.touch(target);
                if (ep.damaged) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(savedHealth));
                    return;
                }
                float v = speedFromPacket(field);
                if (v < AnimalImpact.MIN_SPEED) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(savedHealth));
                    return;
                }
                float vehicleMass = AnimalImpact.fudgedMass(vehicle);
                float animalMass = AnimalImpact.weightOf(target);
                float dmg = AnimalImpact.damage(vehicleMass, animalMass, v);
                ep.damaged = true;
                float left = savedHealth - dmg;
                if (left > 0.0f) {
                    AnimalImpact.aSetHealth.invoke(target, Float.valueOf(left));
                }
                total++;
                Patch_animalHit.Impl.log(" (server)", target, vehicleMass, animalMass, v, savedHealth, left);
                AnimalImpact.throwAway(vehicle, target, vehicleMass, animalMass, v);
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the server animal damage patch, disabling: " + t);
            }
        }

        public static float speedFromPacket(Object field) throws Exception {
            float hitSpeed = fVehicleSpeed.getFloat(field);
            if (hitSpeed > HIT_SPEED_FLOOR + 0.001f) {
                return Math.min(hitSpeed / 2.0f, AnimalImpact.SPEED_MAX);
            }
            float x = fDirX.getFloat(field);
            float y = fDirY.getFloat(field);
            float fromDir = (float) Math.sqrt(x * x + y * y) / DIR_PER_SPEED;
            return Math.min(fromDir, HIT_SPEED_FLOOR / 2.0f);
        }

        private static synchronized void init(Object field) throws Exception {
            if (fVehicleSpeed != null) {
                return;
            }
            Class<?> vhf = field.getClass();
            Class<?> hit = Class.forName("zombie.network.fields.hit.Hit", false, vhf.getClassLoader());
            fDirX = hit.getDeclaredField("hitDirectionX");
            fDirY = hit.getDeclaredField("hitDirectionY");
            fDirX.setAccessible(true);
            fDirY.setAccessible(true);
            fVehicleSpeed = vhf.getField("vehicleSpeed");
            Log.debug("[LabVehiclePhysics] server animal damage ready: speed from the hit packet,"
                    + " damage by speed and masses instead of instant death");
        }
    }
}
