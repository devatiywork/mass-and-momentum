package pz.labvehicle;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Наезд на животное, сторона машины: один честный импульс вместо стены.
 *
 * Ваниль тормозит машину об животное импульсом {@code M * 7 * min(v,15)/15 * |dot|}
 * КАЖДЫЙ кадр контакта плюс лишним {@code applyImpulseFromHitObject(this, 1.0F)} тем же
 * кадром. Масса машины сокращается, веса животного нет, повторы без бюджета — кот
 * останавливал двенадцатитонный броневик. Разбор — {@code backlog.md} §3a.
 *
 * Здесь: на один удар — один импульс, по приведённой массе (формула — {@link AnimalImpact}).
 * Повторы того же удара и толчки упавшего животного отбрасываются. Остальные вызовы
 * {@code applyImpulseFromHitObject} — не животные — идут как в ванили.
 *
 * Работает там, где считается машина: у клиента водителя и в одиночной игре. На сервере
 * ваниль импульсы от животных не прикладывает вовсе ({@code !GameServer.server} в hitAnimal).
 *
 * ВАЖНО: тело enter() встраивается ByteBuddy в метод игры — только public-члены,
 * никаких лямбд.
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
        /** Вернуть это — значит пропустить ванильный импульс. */
        public static final float SKIP = -1.0f;

        public static volatile boolean broken = false;
        public static int logged = 0;
        /** Сквозная нумерация ударов в логе; applied/skipped сбрасываются каждые 15 с. */
        public static long total = 0L;
        public static long applied = 0L;
        public static long skipped = 0L;
        public static long lastReportNanos = 0L;

        /** @return новое значение mul или SKIP. */
        public static float rewrite(Object vehicle, Object obj, float vanilla) {
            if (!LabGate.active()) {
                return vanilla;
            }
            if (broken || vehicle == null || obj == null) {
                return vanilla;
            }
            try {
                // Кусты с CarSlowFactor в одиночной игре приходят сюда же, из IsoObject.Collision:
                // импульс M * скорость * CarSlowFactor / 100 — масса снова сокращается. Их
                // торможение теперь считает VegetationDrag, ванильный импульс не нужен.
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
                    // Не наезжаем, а касаемся или отъезжаем. Эпизод не закрываем:
                    // настоящий удар может прийти следующим кадром.
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
