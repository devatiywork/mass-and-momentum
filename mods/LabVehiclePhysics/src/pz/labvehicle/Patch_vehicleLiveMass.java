package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 3, патч 2 из 2: подстройка массы на ходу, без перезапуска игры.
 *
 * Зачем. Жёсткость подвески зашивается один раз при загрузке (Bullet.defineVehicleScript),
 * а вот масса уходит в физику КАЖДЫЙ кадр:
 * <pre>
 * // BaseVehicle.update(), под !GameServer.server
 * Bullet.setVehicleMass(this.vehicleId, this.getFudgedMass());
 * </pre>
 * Значит, подменив возврат getFudgedMass(), массу можно крутить прямо во время игры.
 * Это нужно, чтобы найти потолок: сейчас известно только со слов, что выше ~9-10 тонн
 * колёса уходят под землю, а причина не установлена. Гадать тут нечего — надо померить,
 * и мерить удобнее одним заездом, а не десятью перезапусками.
 *
 * Тот же getFudgedMass() читает наш патч силы удара с этапа 1, так что живая масса
 * влияет и на то, как машина отбрасывает зомби — это тоже видно сразу.
 *
 * Пока в файле нет ни одного правила с пометкой {@code live} и выключен переключатель
 * «Масса на лету» на странице песочницы ({@link LabSettings#liveMass}), патч не делает
 * ничего и не стоит ни такта: проверяются два volatile-флага. Переключатель включает
 * подмену для всех машин, у которых масса задана, — груз тогда вес не добавляет.
 *
 * ВАЖНО: тело exit() встраивается ByteBuddy в getFudgedMass() — только public-члены,
 * никаких лямбд.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "getFudgedMass", warmUp = true)
public class Patch_vehicleLiveMass {

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) float ret) {
        ret = Impl.adjust(self, ret);
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        public static Method mScriptName;
        public static final Map<String, VehicleCfg.Rule> CACHE = new HashMap<String, VehicleCfg.Rule>();
        public static int cachedGeneration = -1;
        /**
         * Какие пары «машина — масса» уже напечатаны. Каждая печатается один раз.
         *
         * Раньше сравнивали только с последней напечатанной машиной. Пока {@code live}
         * стоял у двух машин, это было терпимо. Когда правила с {@code live} получил весь
         * парк, каждая машина вокруг игрока сбивала «последнюю» и печаталась каждый кадр:
         * 2 688 строк в секунду, 98% лога клиента. Лог обрезался и терял первые двадцать
         * минут сессии — вместе со строками, ради которых его читают. Та же ошибка уже
         * была в логе бака и там была исправлена множеством; сюда урок не перенесли.
         */
        public static final java.util.Set<String> LOGGED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        public static float adjust(Object vehicle, float vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || vehicle == null) {
                return vanilla;
            }
            try {
                // Сначала перечитать файл, и только потом смотреть на hasLive.
                // Обратный порядок был тупиком: флаг выставляется внутри reloadIfNeeded(),
                // а до него не доходило, потому что дорогу закрывал сам флаг.
                VehicleCfg.reloadIfNeeded();
                VehicleCfg.maybeFallback();
                VehicleCfg.printSummaryOnce();
                // Масса на лету: у отдельных правил — флаг live в vehicle-physics.cfg,
                // у всех машин разом — переключатель на странице песочницы.
                boolean all = LabSettings.liveMass();
                if (!VehicleCfg.hasLive && !all) {
                    return vanilla;
                }
                if (mScriptName == null) {
                    mScriptName = vehicle.getClass().getMethod("getScriptName");
                }
                String name = (String) mScriptName.invoke(vehicle);
                if (name == null) {
                    return vanilla;
                }
                VehicleCfg.Rule rule;
                synchronized (CACHE) {
                    if (cachedGeneration != VehicleCfg.generation) {
                        cachedGeneration = VehicleCfg.generation;
                        CACHE.clear();
                    }
                    if (CACHE.containsKey(name)) {
                        rule = CACHE.get(name);
                    } else {
                        rule = VehicleCfg.forName(name);
                        CACHE.put(name, rule);
                    }
                }
                if (rule == null || !(rule.live || all) || rule.mass <= 0.0f) {
                    return vanilla;
                }
                // Груз в ключ не входит: он меняется при каждом обыске багажника,
                // и лог снова наполнился бы повторами.
                if (LOGGED.add(name + "|" + rule.mass)) {
                    Log.debug("[LabVehiclePhysics] live mass: " + name + " = "
                            + VehicleCfg.fmt(rule.mass) + " kg (game computed " + VehicleCfg.fmt(vanilla) + ")");
                }
                return rule.mass;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in live mass, disabling: " + t);
                return vanilla;
            }
        }
    }
}
