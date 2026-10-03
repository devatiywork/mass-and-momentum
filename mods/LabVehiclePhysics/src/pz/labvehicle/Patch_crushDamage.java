package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Stage 1.9: running over a body crushes it by the vehicle's weight.
 *
 * Vanilla, IsoGameCharacter.onHitByVehicleApplyDamage, for a body lying down:
 * <pre>
 * damage = 0.5 * ((impactSpeed - 0.2) / 9.8)^2;      // calculateDamageFromVehicleRunOver
 * // persistent contact: damage * 0.2 * GameTime.getMultiplier() every frame
 * </pre>
 * Speed only, the weight never enters: at 15 km/h a city car and a 52-tonne tank both take about
 * 0.1 off a zombie whose health is 1.8..2.1 (normal toughness; 3.5..3.8 tough). So bodies under a
 * heavy vehicle stay alive, get up as ragdolls under it and the vehicle bogs down in them.
 *
 * What kills under a wheel or a track is the load it carries, so the run-over damage gets a floor
 * proportional to the vehicle's mass: CRUSH_PER_KG · M, i.e. 0.4 per 1.5 t. A city car (1–1.5 t)
 * keeps about the vanilla result and needs several passes; a Humvee (3 t) takes most of a zombie's
 * health in one; the Bushmaster (11 t, ~3) and every tank (23–52 t, 6–14) crush any zombie at once.
 * The floor is applied by raising the impact speed fed to the vanilla formula to the speed that
 * gives this damage, so everything after it (the persistent-contact share, blood, the kill credit)
 * works as in vanilla. Bodies lying down get it, and so do bodies in a ragdoll that the game does
 * not count as prone yet (it then uses the impact formula, 10 * ((speed - 2) / 18)^2, so the speed
 * is inverted from that one): a body tumbling under a heavy vehicle is being run over, not hit.
 * A standing zombie's hit is Patch_onHitByVehicle's business.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "onHitByVehicleApplyDamage", warmUp = true)
public class Patch_crushDamage {

    @Patch.OnEnter
    public static void enter(@Patch.This Object character, @Patch.Argument(0) Object vehicle,
                             @Patch.Argument(value = 1, readOnly = false) float impactSpeed) {
        impactSpeed = Impl.rewrite(character, vehicle, impactSpeed);
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object character, @Patch.Argument(0) Object vehicle) {
        Impl.after(character, vehicle);
    }

    public static final class Impl {
        /** Run-over damage per kilogram of vehicle: 0.4 for 1.5 t. */
        public static final float CRUSH_PER_KG = 0.4f / 1500.0f;
        /** The vanilla run-over formula: 0.5 * ((speed - 0.2) / 9.8)^2. */
        public static final float RUNOVER_MAX = 0.5f;
        public static final float RUNOVER_MIN_SPEED = 0.2f;
        public static final float RUNOVER_SPAN = 9.8f;
        /** The vanilla impact formula (used for a body that is not prone): 10 * ((speed - 2) / 18)^2. */
        public static final float IMPACT_MAX = 10.0f;
        public static final float IMPACT_MIN_SPEED = 2.0f;
        public static final float IMPACT_SPAN = 18.0f;

        public static volatile boolean broken = false;
        public static Method mProne, mDead, mFudgedMass, mRagdoll;
        /** The character being crushed now (set on entry, read on exit). */
        public static Object crushing;
        public static boolean wasAlive;
        public static long crushes = 0L, kills = 0L;
        public static int logged = 0;
        public static long lastReportNanos = 0L;

        public static float rewrite(Object character, Object vehicle, float impactSpeed) {
            crushing = null;
            if (!LabGate.active() || !LabSettings.zombieImpact()) {
                return impactSpeed;
            }
            if (broken || character == null || vehicle == null) {
                return impactSpeed;
            }
            try {
                if (mProne == null) {
                    init(character, vehicle);
                }
                boolean prone = ((Boolean) mProne.invoke(character)).booleanValue();
                if (!prone && !((Boolean) mRagdoll.invoke(character)).booleanValue()) {
                    return impactSpeed;
                }
                float mass = ((Float) mFudgedMass.invoke(vehicle)).floatValue();
                float floor = CRUSH_PER_KG * mass;
                float speedForFloor = prone
                        ? RUNOVER_MIN_SPEED + RUNOVER_SPAN * (float) Math.sqrt(floor / RUNOVER_MAX)
                        : IMPACT_MIN_SPEED + IMPACT_SPAN * (float) Math.sqrt(floor / IMPACT_MAX);
                crushing = character;
                wasAlive = !((Boolean) mDead.invoke(character)).booleanValue();
                crushes++;
                if (logged < 4) {
                    logged++;
                    float vanilla = prone ? runOver(impactSpeed) : impact(impactSpeed);
                    Log.debug(String.format("[LabVehiclePhysics] crush #%d: vehicle %.0f kg, impact %.1f -> "
                                    + "vanilla damage %.2f (%s), weight floor %.2f%s", crushes, mass, impactSpeed, vanilla, prone ? "prone" : "ragdoll",
                            floor, speedForFloor > impactSpeed ? " (applied)" : ""));
                }
                return speedForFloor > impactSpeed ? speedForFloor : impactSpeed;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the crush patch, disabling: " + t);
                return impactSpeed;
            }
        }

        /** Counts the kill if the body died under this crush. */
        public static void after(Object character, Object vehicle) {
            if (crushing == null || crushing != character) {
                return;
            }
            crushing = null;
            try {
                boolean killed = wasAlive && ((Boolean) mDead.invoke(character)).booleanValue();
                if (killed) {
                    kills++;
                }
                HordeReport.crush(vehicle, killed);
                report();
            } catch (Throwable t) {
                // statistics only
            }
        }

        public static float impact(float impactSpeed) {
            float a = (impactSpeed - IMPACT_MIN_SPEED) / IMPACT_SPAN;
            return a < 0.0f ? 0.0f : IMPACT_MAX * a * a;
        }

        public static float runOver(float impactSpeed) {
            float a = (impactSpeed - RUNOVER_MIN_SPEED) / RUNOVER_SPAN;
            return a < 0.0f ? 0.0f : RUNOVER_MAX * a * a;
        }

        private static synchronized void init(Object character, Object vehicle) throws Exception {
            if (mProne != null) {
                return;
            }
            ClassLoader cl = character.getClass().getClassLoader();
            Class<?> gc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
            mDead = gc.getMethod("isDead");
            mFudgedMass = bv.getMethod("getFudgedMass");
            mRagdoll = gc.getMethod("isRagdollSimulationActive");
            mProne = gc.getMethod("isProne");
            Log.debug("[LabVehiclePhysics] crush patch ready: a body run over takes at least "
                    + String.format("%.2f", CRUSH_PER_KG * 1500.0f) + " per 1.5 t of vehicle");
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
            Log.debug(String.format("[LabVehiclePhysics] crushing, last 15 s: run-over hits %d, killed %d", crushes, kills));
            crushes = 0L;
            kills = 0L;
        }
    }
}
