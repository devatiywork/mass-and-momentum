package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Починка и заправка машины прямо в игре, по ключу {@code service} в конфиге.
 *
 * Зачем. Пока мы перебираем массы и мощности, машина регулярно приходит в негодность:
 * то двигатель побит, то бак пустой. Возить её в мастерскую или лезть в сейв каждый раз —
 * долго, а сейв вдобавок требует закрывать игру. Топливо там лежит внутри блоба предмета,
 * который наш разбор сейва не декодирует, так что снаружи его не поправить.
 *
 * Зато изнутри игры всё публично:
 * <pre>
 * vehicle.getPartCount() / getPartByIndex(i)    — обход деталей
 * part.setCondition(100)                         — починка
 * part.setContainerContentAmount(capacity)       — заправка
 * </pre>
 *
 * Срабатывает один раз на каждое перечитывание файла: дописал {@code service} к правилу,
 * сохранил — машина обслужена. Чтобы не обслуживать её повторно при каждой следующей
 * правке конфига, ключ надо убрать.
 */
public final class VehicleService {

    public static volatile boolean broken = false;
    public static Method mGetPartCount;
    public static Method mGetPartByIndex;
    public static Method mGetPartById;
    public static Method mSetCondition;
    public static Method mGetPartId;
    public static Method mGetContainerCapacity;
    public static Method mSetContainerContentAmount;
    public static Method mGetScriptName;
    public static Method mTransmitModData;
    public static Method mTransmitItem;
    public static Method mTransmitCondition;
    public static Boolean onClient;
    /** Уже обслуженные в этом поколении конфига: имя машины + номер поколения. */
    public static final Set<String> DONE = new HashSet<String>();

    private VehicleService() {
    }

    public static void maybe(Object vehicle) {
        if (broken || vehicle == null || !VehicleCfg.hasService) {
            return;
        }
        try {
            // Обслуживание меняет состояние мира, значит делает его сервер.
            // На клиенте наша запись всё равно перетирается серверным состоянием,
            // а до этого успевает разойтись с ним: сервер и клиент видят у бака
            // разную ёмкость (95 против 33), потому что предмет-бак установлен
            // не на обеих сторонах.
            if (isClient()) {
                return;
            }
            if (mGetScriptName == null) {
                Class<?> bv = vehicle.getClass();
                mGetScriptName = bv.getMethod("getScriptName");
                mGetPartCount = bv.getMethod("getPartCount");
                mGetPartByIndex = bv.getMethod("getPartByIndex", int.class);
                mGetPartById = bv.getMethod("getPartById", String.class);
            }
            String name = (String) mGetScriptName.invoke(vehicle);
            if (name == null) {
                return;
            }
            String key = name + "#" + VehicleCfg.generation;
            synchronized (DONE) {
                if (DONE.contains(key)) {
                    return;
                }
            }
            VehicleCfg.Rule rule = VehicleCfg.forName(name);
            if (rule == null || !rule.service) {
                return;
            }
            synchronized (DONE) {
                if (!DONE.add(key)) {
                    return;
                }
            }
            service(vehicle, name);
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in vehicle servicing, disabling: " + t);
        }
    }

    /** true, если мы на клиенте: там менять состояние мира нельзя. */
    public static boolean isClient() {
        if (onClient == null) {
            try {
                onClient = Boolean.valueOf(
                        Class.forName("zombie.network.GameClient").getField("client").getBoolean(null));
            } catch (Throwable t) {
                onClient = Boolean.FALSE;
            }
        }
        return onClient.booleanValue();
    }

    /** Пометить деталь как изменённую, чтобы сервер разослал её клиентам. */
    public static void transmit(Object vehicle, Object part, Method m) {
        if (m == null) {
            return;
        }
        try {
            m.invoke(vehicle, part);
        } catch (Throwable ignored) {
        }
    }

    public static void service(Object vehicle, String name) throws Exception {
        if (mTransmitModData == null) {
            Class<?> bv = vehicle.getClass();
            Class<?> vp = Class.forName("zombie.vehicles.VehiclePart");
            mTransmitModData = bv.getMethod("transmitPartModData", vp);
            mTransmitItem = bv.getMethod("transmitPartItem", vp);
            mTransmitCondition = bv.getMethod("transmitPartCondition", vp);
        }
        int count = ((Integer) mGetPartCount.invoke(vehicle)).intValue();
        int repaired = 0;
        for (int i = 0; i < count; i++) {
            Object part = mGetPartByIndex.invoke(vehicle, Integer.valueOf(i));
            if (part == null) {
                continue;
            }
            if (mSetCondition == null) {
                mSetCondition = part.getClass().getMethod("setCondition", int.class);
                mGetPartId = part.getClass().getMethod("getId");
                mGetContainerCapacity = part.getClass().getMethod("getContainerCapacity");
                // Трёхаргументная перегрузка с force=true. Двухаргументная обрезает
                // количество по getMaxCapacity() САМОГО ПРЕДМЕТА, минуя наш патч на
                // getContainerCapacity(). У танка предмет-бак на 33 литра, поэтому
                // вместо 659 заливалось 33 — то есть практически ничего.
                mSetContainerContentAmount = part.getClass().getMethod(
                        "setContainerContentAmount", float.class, boolean.class, boolean.class);
            }
            mSetCondition.invoke(part, Integer.valueOf(100));
            transmit(vehicle, part, mTransmitCondition);
            repaired++;
        }

        String fuel = "no fuel tank found";
        Object tank = mGetPartById.invoke(vehicle, "GasTank");
        if (tank != null && mGetContainerCapacity != null) {
            int cap = ((Integer) mGetContainerCapacity.invoke(tank)).intValue();
            if (cap > 0) {
                mSetContainerContentAmount.invoke(tank, Float.valueOf(cap), Boolean.TRUE, Boolean.FALSE);
                // Количество топлива лежит в modData детали и само по сети не уезжает.
                transmit(vehicle, tank, mTransmitModData);
                transmit(vehicle, tank, mTransmitItem);
                fuel = "fuel tank filled to " + cap;
            }
        }

        // Колёса: помимо состояния им нужно давление, иначе машина едет на ободах.
        String[] tires = {"TireFrontLeft", "TireFrontRight", "TireRearLeft", "TireRearRight"};
        int pumped = 0;
        for (int i = 0; i < tires.length; i++) {
            Object t = mGetPartById.invoke(vehicle, tires[i]);
            if (t == null) {
                continue;
            }
            int cap = ((Integer) mGetContainerCapacity.invoke(t)).intValue();
            if (cap > 0) {
                mSetContainerContentAmount.invoke(t, Float.valueOf(cap), Boolean.TRUE, Boolean.FALSE);
                transmit(vehicle, t, mTransmitModData);
                transmit(vehicle, t, mTransmitItem);
                pumped++;
            }
        }

        Log.debug("[LabVehiclePhysics] serviced " + name + ": parts repaired " + repaired
                + ", tyres inflated " + pumped + ", " + fuel
                + " - remove the 'service' key from the config, otherwise this repeats on every file edit");
    }
}
