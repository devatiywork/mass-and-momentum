package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Настоящий объём топливного бака, ключ {@code tank} в конфиге.
 *
 * Зачем. Массу мы машинам подняли до паспортной, а бак у всех остался ванильный
 * автомобильный: у Bushmaster стоит предмет {@code BigGasTank1} с {@code MaxCapacity = 59}.
 * У настоящего бак на 319 литров и запас хода 800 км. Без этой правки получается
 * бессмыслица: расход грузовой, а бак легковой, и машина не уезжает никуда.
 *
 * Где берётся ёмкость:
 * <pre>
 * // VehiclePart.getContainerCapacity(IsoGameCharacter)
 * return conditionAffectsCapacity
 *      ? (int) getNumberByCondition(item.getMaxCapacity(), getCondition(), 5.0F)
 *      : item.getMaxCapacity();
 * </pre>
 * Можно было бы позвать {@code item.setMaxCapacity(319)}, но это свойство предмета,
 * и оно попало бы в сейв навсегда — бак остался бы большим даже после выключения мода.
 * Поэтому подменяем возвращаемое значение, ничего не записывая.
 *
 * Метод перегружен (с персонажем и без), поэтому advice написан без обращения к
 * аргументам — только {@code @Patch.This} и возврат. Тогда обе перегрузки патчатся
 * безопасно: внешняя просто делегирует внутренней и получит то же число.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.vehicles.VehiclePart", methodName = "getContainerCapacity", warmUp = true)
public class Patch_tankCapacity {

    @Patch.OnExit
    public static void exit(@Patch.This Object part, @Patch.Return(readOnly = false) int ret) {
        ret = Impl.adjust(part, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mGetId;
        public static Method mGetVehicle;
        public static Method mGetScriptName;
        public static Method mGetCondition;
        public static final java.util.Set<String> LOGGED = new java.util.HashSet<String>();

        public static int adjust(Object part, int vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || part == null || !VehicleCfg.hasTank) {
                return vanilla;
            }
            try {
                if (mGetId == null) {
                    mGetId = part.getClass().getMethod("getId");
                    mGetVehicle = part.getClass().getMethod("getVehicle");
                    mGetCondition = part.getClass().getMethod("getCondition");
                }
                Object id = mGetId.invoke(part);
                if (!"GasTank".equals(id)) {
                    return vanilla;
                }
                Object vehicle = mGetVehicle.invoke(part);
                if (vehicle == null) {
                    return vanilla;
                }
                if (mGetScriptName == null) {
                    mGetScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mGetScriptName.invoke(vehicle);
                VehicleCfg.Rule rule = VehicleCfg.forName(name);
                if (rule == null || rule.tank <= 0.0f) {
                    return vanilla;
                }
                // Ваниль уменьшает ёмкость по состоянию детали — сохраняем это поведение.
                int cond = ((Integer) mGetCondition.invoke(part)).intValue();
                int result = Math.round(rule.tank * Math.max(cond, 5) / 100.0f);
                // По машине, а не по последней виденной: иначе две машины рядом
                // чередуются и пишут в лог каждый кадр.
                if (LOGGED.add(name)) {
                    Log.debug("[LabVehiclePhysics] fuel tank: " + name + " " + vanilla
                            + " -> " + result + " L (spec " + VehicleCfg.fmt(rule.tank)
                            + ", part condition " + cond + "%)");
                }
                return result;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the fuel tank patch, disabling: " + t);
                return vanilla;
            }
        }
    }
}
