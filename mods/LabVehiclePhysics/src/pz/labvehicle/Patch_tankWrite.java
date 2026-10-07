package pz.labvehicle;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Fuel writes into a tank that is bigger than its item, without making every tank item bigger.
 *
 * <h2>Why</h2>
 * The tank volume comes from {@link Patch_tankCapacity}. But every write of the fuel amount is
 * clamped by a different number, the capacity of the tank ITEM:
 * <pre>
 * // VehiclePart.setContainerContentAmount(amount, force, noUpdateMass)
 * int cap = this.scriptPart.container.capacity;
 * if (this.getInventoryItem() != null) {
 *     cap = this.getInventoryItem().getMaxCapacity();
 * }
 * if (!force) {
 *     amount = Math.min(amount, cap);
 * }
 * </pre>
 * Refuelling, fuel use and the client receiving the amount over the network all write with
 * {@code force = false}:
 * <pre>
 * // VehiclePartModData, on the client
 * part.getModData().load(bb.bb, 249);
 * if (part.isContainer()) {
 *     part.setContainerContentAmount(part.getContainerContentAmount());
 * }
 * </pre>
 * So a 319-litre Bushmaster tank would be cut to its item's 59 litres on every write, and online
 * the client would cut what the server sent.
 *
 * <h2>What 1.0.2 did, and why it was wrong</h2>
 * It raised the capacity of every gas tank item, all the time, to the largest tank in the table
 * (659 L, the M60A3). That leaked into every place where the capacity is READ: a vehicle without
 * tank data takes its tank volume from the item ({@code VehiclePart.getContainerCapacity}), so
 * every such vehicle got a tank of about 570 litres, and the mechanics window showed it.
 *
 * <h2>What we do now</h2>
 * The item shows the larger capacity to the clamp alone: from entering this method until
 * leaving it, for the item of the part being written, and only when that part is the gas tank
 * of a vehicle whose rule sets {@code tank} ({@link Patch_tankItemCapacity} asks
 * {@link Impl#allowance}). Everywhere else every item keeps its own capacity. The real limit
 * stays per vehicle: the vanilla overflow check trims the tank to the capacity that
 * {@link Patch_tankCapacity} answers.
 * <pre>
 * // BaseVehicle.update()
 * if (gasTank.getContainerContentAmount() > gasTank.getContainerCapacity()) {
 *     gasTank.setContainerContentAmount(gasTank.getContainerCapacity());
 * }
 * </pre>
 *
 * <h2>Why not set {@code force = true}</h2>
 * The method has two overloads, and ZombieBuddy patches every overload with this name; the
 * one-argument one has no {@code force} to change. So the advice uses only {@code @Patch.This},
 * like {@link Patch_tankCapacity}. The one-argument overload calls the full one, so a write enters
 * the scope twice for the same part, and a depth counter handles that. The exit advice also runs
 * when the method throws, so a scope is never left open.
 *
 * IMPORTANT: ByteBuddy inlines the bodies of enter() and exit(): public members only, no lambdas.
 */
@Patch(className = "zombie.vehicles.VehiclePart", methodName = "setContainerContentAmount", warmUp = true)
public class Patch_tankWrite {

    @Patch.OnEnter
    public static void enter(@Patch.This Object part) {
        Impl.open(part);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This Object part) {
        Impl.close(part);
    }

    /** The fuel write in progress on one thread. */
    public static final class Scope {
        public Object part;
        public Object item;
        public int allow;
        public int depth;
    }

    public static final class Impl {
        public static volatile boolean broken = false;
        /** Writes in progress on all threads. While zero, the item getter returns at once. */
        public static final AtomicInteger OPEN = new AtomicInteger();
        public static final ThreadLocal<Scope> SCOPE = new ThreadLocal<Scope>();
        public static Method mGetId;
        public static Method mGetVehicle;
        public static Method mGetInventoryItem;
        public static Method mGetScriptName;
        /** Vehicles already logged, one line each. */
        public static final java.util.Set<String> LOGGED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        public static void open(Object part) {
            if (broken || part == null || !VehicleCfg.hasTank || !LabGate.active()) {
                return;
            }
            try {
                Scope s = SCOPE.get();
                if (s != null && s.depth > 0) {
                    if (s.part == part) {
                        s.depth++;      // the one-argument overload calling the full one
                        return;
                    }
                    end(s);             // another part's scope: cannot happen, but never let it linger
                }
                if (mGetId == null) {
                    init(part);
                }
                if (!"GasTank".equals(mGetId.invoke(part))) {
                    return;
                }
                Object vehicle = mGetVehicle.invoke(part);
                Object item = mGetInventoryItem.invoke(part);
                if (vehicle == null || item == null) {
                    return;
                }
                String name = (String) mGetScriptName.invoke(vehicle);
                VehicleCfg.Rule rule = VehicleCfg.forName(name);
                if (rule == null || rule.tank <= 0.0f) {
                    return;
                }
                if (s == null) {
                    s = new Scope();
                    SCOPE.set(s);
                }
                s.part = part;
                s.item = item;
                s.allow = (int) Math.ceil(rule.tank);
                s.depth = 1;
                OPEN.incrementAndGet();
                if (LOGGED.add(name)) {
                    Log.debug("[LabVehiclePhysics] fuel tank: writes into " + name + " are clamped at "
                            + s.allow + " L instead of the tank item's own capacity");
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the fuel write patch, disabling: " + t);
            }
        }

        public static void close(Object part) {
            if (OPEN.get() == 0) {
                return;
            }
            Scope s = SCOPE.get();
            if (s == null || s.depth <= 0 || s.part != part) {
                return;
            }
            s.depth--;
            if (s.depth == 0) {
                end(s);
            }
        }

        public static void end(Scope s) {
            s.depth = 0;
            s.part = null;
            s.item = null;
            OPEN.decrementAndGet();
        }

        /**
         * The capacity the item reports: the vehicle's tank while a write into that very tank is in
         * progress on this thread, its own capacity at any other moment.
         */
        public static int allowance(Object item, int vanilla) {
            if (OPEN.get() == 0) {
                return vanilla;
            }
            Scope s = SCOPE.get();
            if (s == null || s.depth <= 0 || s.item != item || s.allow <= vanilla) {
                return vanilla;
            }
            return s.allow;
        }

        public static synchronized void init(Object part) throws Exception {
            if (mGetId != null) {
                return;
            }
            Class<?> vp = part.getClass();
            Method id = vp.getMethod("getId");
            mGetVehicle = vp.getMethod("getVehicle");
            mGetInventoryItem = vp.getMethod("getInventoryItem");
            mGetScriptName = mGetVehicle.getReturnType().getMethod("getScriptName");
            mGetId = id;
        }
    }
}
