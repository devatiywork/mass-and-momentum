package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Снятие обрезки топлива по ёмкости предмета-бака.
 *
 * <h2>Зачем</h2>
 * Мы поднимаем объём бака через {@link Patch_tankCapacity}, подменяя возврат
 * {@code VehiclePart.getContainerCapacity()}. Но количество топлива при записи режется
 * по другому значению — по ёмкости самого ПРЕДМЕТА:
 * <pre>
 * // VehiclePart.setContainerContentAmount(amount, force, noUpdateMass)
 * int cap = this.scriptPart.container.capacity;
 * if (this.getInventoryItem() != null) {
 *     cap = this.getInventoryItem().getMaxCapacity();   // мимо нашего патча
 * }
 * if (!force) {
 *     amount = Math.min(amount, cap);
 * }
 * </pre>
 *
 * В сервисе мы зовём перегрузку с {@code force = true} и обрезку обходим. Но на клиенте
 * есть путь, где {@code force} нам не подконтролен — приём данных по сети:
 * <pre>
 * // VehiclePartModData
 * part.getModData().load(bb.bb, 249);
 * if (part.isContainer()) {
 *     part.setContainerContentAmount(part.getContainerContentAmount());   // force = false
 * }
 * </pre>
 * То есть клиент получает от сервера честные 659 литров и тут же сам себе их обрезает
 * до ёмкости своего предмета. У танка это 33 литра — практически пустой бак.
 *
 * <h2>Почему патчим геттер, а не поле</h2>
 * Очевидное решение — позвать {@code item.setMaxCapacity()} — не годится: поле
 * сериализуется ({@code InventoryItem} пишет его в сейв), и увеличенная ёмкость осталась
 * бы у предмета навсегда, даже после выключения мода. Запись идёт напрямую через поле,
 * мимо геттера, поэтому подмена возврата в сейв не попадает.
 *
 * <h2>Почему это безопасно</h2>
 * Мы делаем обрезку лишь ПЕРМИССИВНОЙ, а не задаём настоящую ёмкость. Настоящая остаётся
 * за {@link Patch_tankCapacity}, и её же использует ванильная проверка переполнения:
 * <pre>
 * // BaseVehicle.update()
 * if (gasTank.getContainerContentAmount() > gasTank.getContainerCapacity()) {
 *     gasTank.setContainerContentAmount(gasTank.getContainerCapacity());
 * }
 * </pre>
 * Так что лишнего топлива в баке не окажется — просто перестанет срезаться нужное.
 *
 * Трогаем только предметы-баки и только когда в конфиге есть правила с объёмом.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy — только public-члены, никаких лямбд.
 */
@Patch(className = "zombie.inventory.InventoryItem", methodName = "getMaxCapacity", warmUp = true)
public class Patch_tankItemCapacity {

    @Patch.OnExit
    public static void exit(@Patch.This Object item, @Patch.Return(readOnly = false) int ret) {
        ret = Impl.adjust(item, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mGetType;
        public static boolean logged = false;

        public static int adjust(Object item, int vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || item == null || !VehicleCfg.hasTank) {
                return vanilla;
            }
            int allow = VehicleCfg.largestTank();
            if (allow <= vanilla) {
                return vanilla;
            }
            try {
                if (mGetType == null) {
                    mGetType = item.getClass().getMethod("getType");
                }
                Object type = mGetType.invoke(item);
                if (!(type instanceof String) || ((String) type).indexOf("GasTank") < 0) {
                    return vanilla;
                }
                if (!logged) {
                    logged = true;
                    Log.debug("[LabVehiclePhysics] fuel tank items: clamp raised from "
                            + vanilla + " to " + allow
                            + " so that received fuel is not truncated (real capacity is still per-vehicle)");
                }
                return allow;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the tank item patch, disabling: " + t);
                return vanilla;
            }
        }
    }
}
