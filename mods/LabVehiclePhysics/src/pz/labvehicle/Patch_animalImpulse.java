package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Vehicle hitting an animal, vehicle side: one physically correct impulse instead of a wall.
 *
 * Vanilla brakes the vehicle against an animal: an impulse of {@code M * 7 * min(v,15)/15 * |dot|}
 * on EVERY frame of contact, plus an extra {@code applyImpulseFromHitObject(this, 1.0F)} on the
 * same frame. The vehicle mass cancels out, the animal's weight is ignored, repeats have no budget:
 * a cat used to stop a twelve-tonne armoured car. Analysis: {@code backlog.md} §3a.
 *
 * Here: one impulse per impact, using the reduced mass (the formula is in {@link AnimalImpact}).
 * Repeats of the same impact and nudges from a fallen animal are dropped. All other calls of
 * {@code applyImpulseFromHitObject}, the non-animal ones, go through as in vanilla.
 *
 * Works where the vehicle is simulated: on the driver's client and in singleplayer. On the server
 * vanilla does not apply impulses from animals at all ({@code !GameServer.server} in hitAnimal).
 *
 * IMPORTANT: ByteBuddy inlines the body of enter() into the game's method: public members only,
 * no lambdas.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "applyImpulseFromHitObject", warmUp = true)
public class Patch_animalImpulse {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object vehicle,
                                @Patch.Argument(0) Object obj,
                                @Patch.Argument(value = 1, readOnly = false) float mul) {
        float r = Impl.rewrite(vehicle, obj, mul);
        if (r < 0.0f) {
            return true;
        }
        mul = r;
        return false;
    }

    public static final class Impl {
        /** Returning this means: skip the vanilla impulse. */
        public static final float SKIP = -1.0f;

        public static volatile boolean broken = false;
        public static int logged = 0;
        /** Running hit number for the log; applied/skipped are reset every 15 s. */
        public static long total = 0L;
        public static long applied = 0L;
        public static long skipped = 0L;
        public static long lastReportNanos = 0L;

        /** @return the new value of mul, or SKIP. */
        public static float rewrite(Object vehicle, Object obj, float vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || vehicle == null || obj == null) {
                return vanilla;
            }
            try {
                // Bushes with CarSlowFactor come here too in singleplayer, via IsoObject.Collision:
                // impulse M * speed * CarSlowFactor / 100, and the mass cancels out again. Their
                // braking is now computed by VegetationDrag; the vanilla impulse is not needed.
                if (VegetationDrag.skipsHitObjectImpulse(vehicle, obj)) {
                    skipped++;
                    return SKIP;
                }
                if (!LabSettings.animalHits()) {
                    return vanilla;
                }
                AnimalImpact.init(vehicle.getClass().getClassLoader());
                if (!AnimalImpact.isAnimal(obj)) {
                    return vanilla;
                }
                AnimalImpact.Episode ep = AnimalImpact.touch(obj);
                if (ep.braked || AnimalImpact.isDown(obj)) {
                    skipped++;
                    report();
                    return SKIP;
                }
                float v = AnimalImpact.closingSpeed(vehicle, obj);
                if (v < AnimalImpact.MIN_SPEED) {
                    // Not a hit, just a touch or pulling away. The episode stays open:
                    // the real impact may come on the next frame.
                    skipped++;
                    report();
                    return SKIP;
                }
                float vehicleMass = AnimalImpact.fudgedMass(vehicle);
                float animalMass = AnimalImpact.weightOf(obj);
                float p = AnimalImpact.momentum(vehicleMass, animalMass, v);
                ep.braked = true;
                applied++;
                total++;
                if (logged < 8) {
                    logged++;
                    float lost = vehicleMass > 0.0f ? p / vehicleMass : 0.0f;
                    float vanillaPerFrame = vehicleMass > 0.0f ? AnimalImpact.APPLIED_FRACTION * vanilla / vehicleMass : 0.0f;
                    Log.debug(String.format(
                            "[LabVehiclePhysics] animal hit #%d: %s %.1f kg by a %.0f kg vehicle at %.0f km/h"
                            + " -> vehicle loses %.1f km/h once (vanilla: %.1f km/h every frame of contact)",
                            total, AnimalImpact.typeOf(obj), animalMass, vehicleMass, AnimalImpact.kmh(v),
                            AnimalImpact.kmh(lost), AnimalImpact.kmh(vanillaPerFrame)));
                }
                report();
                return p / AnimalImpact.APPLIED_FRACTION;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the animal impulse patch, disabling: " + t);
                return vanilla;
            }
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            if (applied + skipped == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] animal impulses, last 15 s: applied %d, repeats and fallen animals skipped %d",
                    applied, skipped));
            applied = 0L;
            skipped = 0L;
        }
    }
}
