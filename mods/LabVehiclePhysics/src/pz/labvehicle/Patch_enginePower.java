package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Мощность двигателя и тормоза — на лету, без перезапуска и без порчи сейва.
 *
 * Почему не через мощность двигателя напрямую. Очевидный путь — подменить
 * {@code BaseVehicle.getEnginePower()} — не годится: его читает не только физика, но и
 * сериализация.
 * <pre>
 * // BaseVehicle, запись машины
 * output.putInt(this.getEnginePower());
 * // VehicleEngine, сетевой пакет
 * b.putInt(this.getVehicle().getEnginePower());
 * </pre>
 * Завышенное число ушло бы в сейв и в сеть, и осталось бы там навсегда — даже после
 * выключения мода. Плюс для уже существующих машин мощность и так берётся из сейва,
 * а не из скрипта, поэтому правка скрипта на них всё равно не подействовала бы.
 *
 * Поэтому цепляемся к последней точке перед применением:
 * <pre>
 * // CarController.update()
 * BulletVariables bv = bulletVariables.set(vehicleObject, engineForce, brakingForce, steering);
 * this.checkTire(bv);              // ← сюда
 * this.engineForce = bv.engineForce;
 * ...
 * Bullet.controlVehicle(vehicleId, this.engineForce, this.brakingForce, this.vehicleSteering);
 * </pre>
 * {@code checkTire} и сам занимается ровно этим — делит тягу и торможение за спущенные
 * колёса. Мы просто домножаем те же два поля после него. В сейв не попадает ничего.
 *
 * Значения берутся из файла и перечитываются на ходу: {@code powerMul} и {@code brakeMul},
 * число либо {@code auto}. {@code auto} — это отношение новой массы к ванильной, то есть
 * разгон и тормозной путь остаются как у стоковой машины, несмотря на настоящий вес.
 *
 * Сверху — общий множитель мощности из песочницы ({@link LabSettings#powerMul}): он действует
 * на все машины, включая те, для которых правил нет, и не трогает тормоза.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в checkTire — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.core.physics.CarController", methodName = "checkTire", warmUp = true)
public class Patch_enginePower {

    @Patch.OnExit
    public static void exit(@Patch.This Object controller, @Patch.Argument(0) Object bv) {
        Impl.scale(controller, bv);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Field fVehicleObject;
        public static Field fSpeed;
        public static Field fEngineForce;
        public static Field fBrakingForce;
        public static Method mScriptName;
        /**
         * Какие сочетания «машина — множители» уже напечатаны. Сравнение с последним
         * напечатанным, как было раньше, при двух машинах сразу печатает каждый кадр —
         * на сервере, где едут несколько игроков, это случилось бы сразу. Так уже
         * заваливали лог масса и бак.
         */
        public static final java.util.Set<String> LOGGED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        /**
         * Диагностика: на каком досрочном выходе разворачивается метод.
         *
         * Строка "power and brakes" не печаталась НИ РАЗУ, хотя ZombieBuddy рапортует
         * "patching zombie.core.physics.CarController.checkTire" и ошибок нет. Значит
         * тело выполняется, но выходит раньше применения множителей — и тяга с тормозами
         * не применяются вообще, ни у одной машины.
         *
         * Каждая причина печатается один раз, чтобы не залить лог: метод зовётся
         * каждый кадр на каждую машину.
         */
        public static final java.util.Set<String> BAILED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        public static void bail(String why) {
            if (BAILED.add(why)) {
                Log.info("[LabVehiclePhysics] engine patch bailed out: " + why);
            }
        }

        /** Отличает «advice не выполняется вовсе» от «выполняется и выходит раньше». */
        public static volatile boolean sawFirstCall = false;

        public static void scale(Object controller, Object bv) {
            if (!sawFirstCall) {
                sawFirstCall = true;
                Log.debug("[LabVehiclePhysics] engine patch: the checkTire advice is running");
            }
            if (!LabGate.active()) {
                bail("LabGate is closed - the mod is not in the active mod list");
                return;
            }
            if (broken || controller == null || bv == null) {
                bail("broken=" + broken + ", controller=" + (controller == null ? "null" : "ok")
                        + ", bulletVariables=" + (bv == null ? "null" : "ok"));
                return;
            }
            try {
                VehicleCfg.reloadIfNeeded();
                // Общий множитель мощности из песочницы — на все машины, и на те, у которых
                // правил нет. Тормоза он не трогает.
                float global = LabSettings.powerMul();
                boolean globalOn = Math.abs(global - 1.0f) > 0.001f;
                if (!VehicleCfg.hasTuning && !globalOn) {
                    bail("VehicleCfg.hasTuning is false - no rule carries powerMul/brakeMul/lowGear, "
                            + "and the sandbox power multiplier is 1");
                    return;
                }
                if (fVehicleObject == null) {
                    fVehicleObject = controller.getClass().getField("vehicleObject");
                    fSpeed = controller.getClass().getField("speed");
                    fEngineForce = bv.getClass().getDeclaredField("engineForce");
                    fEngineForce.setAccessible(true);
                    fBrakingForce = bv.getClass().getDeclaredField("brakingForce");
                    fBrakingForce.setAccessible(true);
                }
                Object vehicle = fVehicleObject.get(controller);
                if (vehicle == null) {
                    bail("controller.vehicleObject is null");
                    return;
                }
                if (mScriptName == null) {
                    mScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mScriptName.invoke(vehicle);
                VehicleCfg.Rule rule = VehicleCfg.forName(name);
                if (rule == null && !globalOn) {
                    bail("no rule matches script name \"" + name + "\"");
                    return;
                }
                float ruled = 1.0f;
                float brake = 1.0f;
                float boost = 1.0f;
                float speedKmh = fSpeed.getFloat(controller);
                if (rule != null) {
                    // Последний момент, когда ванильную массу ещё видно, если правило
                    // до скрипта не дошло. Без этого brakeMul=auto молча вырождается в 1.0.
                    VehicleCfg.noteVanillaMassFromVehicle(vehicle, name);
                    ruled = VehicleCfg.powerMultiplier(rule, name);
                    brake = VehicleCfg.multiplier(rule.brakeMul, rule, name);
                    boost = VehicleCfg.lowGearBoost(rule, speedKmh);
                }
                float power = ruled * boost * global;
                if (power == 1.0f && brake == 1.0f) {
                    bail("both multipliers resolved to 1.0 for \"" + name + "\"" + (rule == null ? ""
                            : " (powerMul=" + rule.powerMul + ", power=" + rule.powerHp + "hp, brakeMul=" + rule.brakeMul
                            + ", lowGear=" + rule.lowGear + ", low-gear boost=" + boost + ", speed=" + speedKmh + ")"));
                    return;
                }
                if (power != 1.0f) {
                    fEngineForce.setFloat(bv, fEngineForce.getFloat(bv) * power);
                }
                if (brake != 1.0f) {
                    fBrakingForce.setFloat(bv, fBrakingForce.getFloat(bv) * brake);
                }
                String stamp = name + "|" + ruled + "|" + brake + "|" + (rule != null ? rule.lowGear : 0.0f) + "|" + global;
                if (LOGGED.add(stamp)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[LabVehiclePhysics] power and brakes: ").append(name)
                      .append(" power x").append(VehicleCfg.fmt(ruled));
                    if (globalOn) {
                        sb.append(", sandbox multiplier x").append(VehicleCfg.fmt(global));
                    }
                    sb.append(", braking x").append(VehicleCfg.fmt(brake));
                    if (rule != null && rule.lowGear > 1.0f) {
                        sb.append(", low-gear boost x").append(VehicleCfg.fmt(rule.lowGear))
                          .append(" fading out at ").append(VehicleCfg.fmt(rule.lowGearTo)).append(" km/h");
                    }
                    Log.debug(sb.toString());
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the engine power patch, disabling: " + t);
            }
        }
    }
}
