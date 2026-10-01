package pz.labvehicle;

import java.lang.reflect.Method;

/**
 * Мостик к {@link NativePatch}.
 *
 * Зачем он нужен. {@code NativePatch} использует Foreign Function &amp; Memory API, который
 * появился в Java 22, поэтому собирается под {@code --release 25}. Весь остальной мод
 * собирается под 17 — так спокойнее для ByteBuddy, который встраивает наши advice
 * в классы игры.
 *
 * Смешивать их напрямую нельзя: javac с {@code --release 17} откажется читать class-файл
 * 25-й версии с classpath. Поэтому единственное обращение к {@code NativePatch} идёт
 * рефлексией — одна строка вместо ста пятидесяти, которые были бы, пиши мы через FFM
 * рефлексией целиком.
 *
 * Сюда же вынесена обработка отсутствия класса: если по какой-то причине {@code NativePatch}
 * не собрался или JVM старше 22, мод продолжит работать с ванильным пределом подвески.
 */
public final class NativeBridge {

    public static volatile boolean broken = false;
    public static Method mEnsure;

    private NativeBridge() {
    }

    public static void ensure() {
        if (broken) {
            return;
        }
        try {
            if (mEnsure == null) {
                mEnsure = Class.forName("pz.labvehicle.NativePatch").getMethod("ensure");
            }
            mEnsure.invoke(null);
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] in-memory suspension patch unavailable (" + t
                    + ") - the game keeps the vanilla 2400 kg ceiling");
        }
    }
}
