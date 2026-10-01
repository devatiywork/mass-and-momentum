package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 1.7: физически осмысленная сила первого удара машины по персонажу.
 *
 * <p>Ваниль передаёт в {@code onHitByVehicle()} не настоящую скорость:</p>
 * <pre>
 * speed = min(|velocity|, 15);                         // всё выше ~54 км/ч потеряно
 * hitDir *= 3 * speed / 15;                            // длина не больше 3
 * hitForce = speed + clientForce / vehicleMass;        // масса в обратной зависимости
 * </pre>
 *
 * <p>Первая версия патча заменила это на {@code speed * vehicleMass / 800}, с потолком
 * {@code x4}. Это устранило обратную зависимость, но смешало две разные стороны
 * столкновения. После того как машина уже намного тяжелее тела, её дальнейшее утяжеление
 * почти не увеличивает импульс, полученный телом. Оно уменьшает потерю скорости самой
 * машины. Для фронтального удара правильный масштаб задаёт приведённая масса:</p>
 * <pre>
 * reducedMass = vehicleMass * bodyMass / (vehicleMass + bodyMass)
 * factor      = reducedMass / reducedMass(800 kg, bodyMass)
 * </pre>
 *
 * <p>Для тела 100 кг коэффициенты получаются: легковушка 800 кг = 1.000,
 * Bushmaster 11 400 кг = 1.115, M60A3 52 000 кг = 1.123. Поэтому при одинаковой
 * скорости все три машины причиняют телу сопоставимый удар, а разница в прохождении
 * толпы возникает на стороне обратного импульса: одна и та же передача импульса гораздо
 * слабее замедляет тяжёлую машину.</p>
 *
 * <p>Эффективную скорость для урона и стойки ограничиваем на 25. При этом значении
 * ваниль уже гарантирует падение даже устойчивого зомби, а урон многократно превышает
 * его здоровье. Дальнейший рост числа только разгонял бы квадратичную формулу урона,
 * не добавляя наблюдаемого результата. Направление толчка масштабируется отдельно по
 * реальной скорости и приведённой массе; повторного умножения скорости больше нет.</p>
 *
 * <p>Обратный импульс машине здесь не меняется. Он рассчитывается в
 * {@code BaseVehicle.applyImpulseFromHitPedestrian()} от массы тела и скорости, а
 * {@link Patch_impulseBudget} не даёт списывать его повторно весь контакт.</p>
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "onHitByVehicle", warmUp = true)
public class Patch_onHitByVehicle {

    @Patch.OnEnter
    public static void enter(@Patch.This Object character,
                             @Patch.Argument(0) Object vehicle,
                             @Patch.Argument(value = 1, readOnly = false) float hitForce,
                             @Patch.Argument(2) Object hitDir) {
        hitForce = Impl.rewrite(character, vehicle, hitForce, hitDir);
    }

    public static final class Impl {
        /** Машина, под которую откалибрована ванильная реакция тела. */
        public static final float REFERENCE_VEHICLE_MASS = 800.0f;
        /** Базовая масса персонажа в формуле PZ; разброс добавляет Patch_getMass. */
        public static final float BASE_BODY_MASS = 100.0f;
        /** Численные страховки для экзотически лёгких и ошибочно тяжёлых скриптов. */
        public static final float FACTOR_MIN = 0.5f;
        public static final float FACTOR_MAX = 1.25f;
        /** Старый потолок скорости и новый предел чтения реальной скорости. */
        public static final float VANILLA_SPEED_CAP = 15.0f;
        public static final float REAL_SPEED_MAX = 40.0f;
        /** Насыщение игровой формулы урона/падения. */
        public static final float EFFECTIVE_IMPACT_MAX = 25.0f;
        /** Защита от аномального вектора толчка. Ванильный максимум равен 3. */
        public static final float HIT_DIR_MAX = 10.0f;

        public static volatile boolean broken = false;
        public static Method vGetLinearVelocity;
        public static Method vGetFudgedMass;
        public static Object velOut;
        public static Field vx, vz;
        public static Field dirX, dirY;
        public static long hits = 0L;
        public static int logged = 0;

        /** @return новое значение hitForce. Побочно исправляет длину вектора толчка. */
        public static float rewrite(Object character, Object vehicle, float vanillaForce, Object hitDir) {
            if (!LabGate.active()) {
                return vanillaForce;
            }
            // Сеть: наш водитель сбил зомби — когда тело ляжет, серверу уйдёт точка (CorpseSync).
            // У этого свой выключатель — «Труп следует за рэгдоллом», а не физика наезда.
            CorpseSync.onLocalHit(character, vehicle);
            if (!LabSettings.zombieImpact()) {
                return vanillaForce;
            }
            if (broken || vehicle == null) {
                return vanillaForce;
            }
            try {
                if (vGetFudgedMass == null) {
                    init(vehicle);
                }
                float speed = speedOf(vehicle);
                if (!(speed > 0.0f)) {
                    return vanillaForce;
                }
                if (speed > REAL_SPEED_MAX) {
                    speed = REAL_SPEED_MAX;
                }

                float vehicleMass = ((Float) vGetFudgedMass.invoke(vehicle)).floatValue();
                float bodyMass = bodyMassFor(character);
                float factor = reducedMassFactor(vehicleMass, bodyMass);
                float uncappedImpact = speed * factor;
                float impact = Math.min(uncappedImpact, EFFECTIVE_IMPACT_MAX);
                float dirLength = rewriteDirection(hitDir, speed, factor);

                hits++;
                if (logged < 8) {
                    logged++;
                    Log.debug(String.format(
                            "[LabVehiclePhysics] hit #%d: speed %.1f tiles/s (~%.0f km/h), vehicle %.0f kg, "
                            + "body %.0f kg -> reduced-mass factor %.3f, effective impact %.1f%s, hit-dir %.2f "
                            + "(vanilla impact %.1f)",
                            hits, speed, speed * 3.6f, vehicleMass, bodyMass, factor, impact,
                            uncappedImpact > EFFECTIVE_IMPACT_MAX ? " (saturated)" : "", dirLength, vanillaForce));
                }
                return impact;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the hit patch, disabling: " + t);
                return vanillaForce;
            }
        }

        /** Масса тела без покадрового множителя: тот же разброс, что у обратного импульса. */
        public static float bodyMassFor(Object character) {
            float spread = character == null ? 1.0f : Patch_getMass.Impl.spreadFor(character);
            return BASE_BODY_MASS * spread;
        }

        public static float reducedMass(float firstMass, float secondMass) {
            if (!(firstMass > 0.0f) || !(secondMass > 0.0f)) {
                return 0.0f;
            }
            return firstMass * secondMass / (firstMass + secondMass);
        }

        /** Чистая функция вынесена отдельно, чтобы формулу можно было проверить без игры. */
        public static float reducedMassFactor(float vehicleMass, float bodyMass) {
            float actual = reducedMass(vehicleMass, bodyMass);
            float reference = reducedMass(REFERENCE_VEHICLE_MASS, bodyMass);
            if (!(actual > 0.0f) || !(reference > 0.0f)) {
                return 1.0f;
            }
            float factor = actual / reference;
            if (factor < FACTOR_MIN) {
                return FACTOR_MIN;
            }
            return factor > FACTOR_MAX ? FACTOR_MAX : factor;
        }

        /**
         * BaseVehicle передал уже масштабированный вектор длиной min(speed,15)/5.
         * Нормализуем его и задаём длину заново: speed/5 * reducedMassFactor.
         */
        public static float rewriteDirection(Object hitDir, float speed, float factor) throws Exception {
            if (hitDir == null || dirX == null) {
                return 0.0f;
            }
            float x = dirX.getFloat(hitDir);
            float y = dirY.getFloat(hitDir);
            float oldLength = (float) Math.sqrt(x * x + y * y);
            if (!(oldLength > 0.0001f)) {
                return 0.0f;
            }
            float target = 3.0f * speed / VANILLA_SPEED_CAP * factor;
            if (target > HIT_DIR_MAX) {
                target = HIT_DIR_MAX;
            }
            float scale = target / oldLength;
            dirX.setFloat(hitDir, x * scale);
            dirY.setFloat(hitDir, y * scale);
            return target;
        }

        /** Горизонтальная скорость машины из физики, без ванильного потолка. */
        public static float speedOf(Object vehicle) throws Exception {
            Object out = velOut;
            vGetLinearVelocity.invoke(vehicle, out);
            float x = vx.getFloat(out);
            float z = vz.getFloat(out);
            return (float) Math.sqrt(x * x + z * z);
        }

        private static synchronized void init(Object vehicle) throws Exception {
            if (vGetFudgedMass != null) {
                return;
            }
            Class<?> vc = vehicle.getClass();
            vGetFudgedMass = vc.getMethod("getFudgedMass");
            Class<?> v3 = Class.forName("org.joml.Vector3f", false, vc.getClassLoader());
            vGetLinearVelocity = vc.getMethod("getLinearVelocity", v3);
            velOut = v3.getConstructor().newInstance();
            vx = v3.getField("x");
            vz = v3.getField("z");
            Class<?> v2 = Class.forName("zombie.iso.Vector2", false, vc.getClassLoader());
            dirX = v2.getField("x");
            dirY = v2.getField("y");
            Log.debug("[LabVehiclePhysics] hit patch ready: real speed up to " + REAL_SPEED_MAX
                    + " tiles/s, reduced mass instead of linear vehicle-mass multiplier, impact saturates at "
                    + EFFECTIVE_IMPACT_MAX);
        }
    }
}
