package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Repairs and refuels a vehicle right in the game, via the {@code service} key in the config.
 *
 * Why. While we iterate over masses and power figures, the vehicle regularly becomes unusable:
 * either the engine is damaged or the tank is empty. Driving it to a repair shop or editing the
 * save every time is slow, and save editing also requires closing the game. In the save, the fuel
 * sits inside an item blob our save parser does not decode, so it cannot be fixed from outside.
 *
 * From inside the game, though, everything is public:
 * <pre>
 * vehicle.getPartCount() / getPartByIndex(i)    — iterate over parts
 * part.setCondition(100)                         — repair
 * part.setContainerContentAmount(capacity)       — refuel
 * </pre>
 *
 * It fires once per re-read of the file: add {@code service} to a rule, save, and the
 * vehicle is serviced. To avoid servicing it again on every following edit of the config,
 * remove the key.
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
    /** Already serviced in this config generation: vehicle name + generation number. */
    public static final Set<String> DONE = new HashSet<String>();

    private VehicleService() {
    }

    public static void maybe(Object vehicle) {
        if (broken || vehicle == null || !VehicleCfg.hasService) {
            return;
        }
        try {
            // Servicing changes world state, so the server does it.
            // On the client our write is overwritten by the server state anyway,
            // and before that it has time to diverge from it: the server and the client
            // see different tank capacities (95 vs 33) because the tank item is not
            // installed on both sides.
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

    /** true if we are on the client, where world state must not be changed. */
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

    /** Marks a part as changed so the server sends it out to the clients. */
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
                // The three-argument overload with force=true. The two-argument one clamps
                // the amount to getMaxCapacity() of THE ITEM ITSELF, bypassing our patch on
                // getContainerCapacity(). The battle tank's fuel-tank item holds 33 litres, so
                // 33 went in instead of 659, i.e. practically nothing.
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
                // The fuel amount lives in the part's modData and is not networked by itself.
                transmit(vehicle, tank, mTransmitModData);
                transmit(vehicle, tank, mTransmitItem);
                fuel = "fuel tank filled to " + cap;
            }
        }

        // Tyres: besides condition they need pressure, otherwise the vehicle runs on its rims.
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
