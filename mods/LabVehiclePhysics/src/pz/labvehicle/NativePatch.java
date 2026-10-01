package pz.labvehicle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Снятие предела силы подвески — правкой памяти, а не файла.
 *
 * <h2>Что правим</h2>
 * В {@code PZBullet64.dll} инлайнен стоковый конструктор {@code btVehicleTuning}:
 * <pre>
 * 48 B8 00 00 00 00 00 70 B7 40    movabs rax, 6000.0      ; m_maxSuspensionForce
 * 49 89 45 28                      mov [r13+0x28], rax
 * </pre>
 * При гравитации 10 предел 6000 на колесо ограничивает несущую способность подвески
 * 2400 килограммами. Из-за этого весь автопарк игры втиснут в 650..1160 кг, а машина
 * с настоящей массой проваливается сквозь землю. Подробности — Docs/dll-suspension-limit.md.
 *
 * <h2>Почему в памяти, а не в файле</h2>
 * Правка файла работает, но распространять её нельзя: игра грузит библиотеку из папки
 * установки, а не из папки модов; проверка целостности Steam вернёт оригинал; обновление
 * игры затрёт правку. Правка памяти ничего этого не боится и остаётся обычным Java-модом.
 *
 * <h2>Поиск по данным, а не по коду</h2>
 * Фиксированное смещение верно только для 42.20.4, поэтому ищем. Но искать по байтам
 * инструкции нельзя: в {@code 48 B8 ... 49 89 45 28} регистры {@code rax} и {@code r13}
 * выбрал компилятор, а не программист. Достаточно пересобрать библиотеку другой версией
 * MSVC, ничего не меняя в исходниках, и кодировка станет другой.
 *
 * Поэтому опираемся на сами числа. Конструктор выставляет шесть значений подряд:
 * <pre>
 * 5.88   0.83   0.88   500   10.5   6000
 * </pre>
 * Это 8-байтовые {@code double}, и от регистров они не зависят вообще. Каждое встречается
 * в библиотеке ровно один раз — проверено поиском. Алгоритм: найти 6000.0 и убедиться,
 * что рядом лежат остальные пять. Совпадение по шести числам сразу случайным не бывает.
 *
 * Такой поиск переживает и смену регистров, и смену компилятора, и перенос констант
 * из тела инструкций в пул данных .rdata.
 *
 * <h2>Предохранитель</h2>
 * Правка применяется, только если мод разрешён (см. {@link LabGate}). На чужом сервере
 * без мода библиотека останется нетронутой.
 *
 * <h2>Требования</h2>
 * Foreign Function &amp; Memory API (Java 22+). Игра работает на Java 25, этот класс
 * собирается под {@code --release 25}, остальной мод — под 17.
 * Нужен флаг {@code --enable-native-access=ALL-UNNAMED}, иначе JVM ругнётся
 * предупреждением на ограниченные методы.
 */
public final class NativePatch {

    public static final String MODULE = "PZBullet64.dll";

    /**
     * Соседи по конструктору btVehicleTuning: suspensionStiffness, suspensionCompression,
     * suspensionDamping, maxSuspensionTravelCm, frictionSlip. Все — стоковые дефолты Bullet.
     */
    public static final double[] NEIGHBOURS = {5.88, 0.83, 0.88, 500.0, 10.5};
    /** В каком окне вокруг константы искать соседей. Хватает и для инлайна, и для пула .rdata. */
    public static final int WINDOW = 256;
    /** Сколько соседей из пяти должно найтись. Один запас на случай, если что-то поменяют. */
    public static final int MIN_NEIGHBOURS = 4;

    /** Новый предел: 500 000 на колесо при гравитации 10 — это 200 тонн. */
    public static final double NEW_LIMIT = 500000.0;
    public static final double OLD_LIMIT = 6000.0;

    public static final int PAGE_EXECUTE_READWRITE = 0x40;
    /** Смещения в PE-заголовке. */
    public static final long E_LFANEW = 0x3C;
    public static final long SIZE_OF_IMAGE = 0x50;

    public static volatile boolean done = false;
    public static volatile boolean failed = false;

    private NativePatch() {
    }

    /** Вызывается из патча создания физики — к этому моменту библиотека точно загружена. */
    public static void ensure() {
        if (done || failed || !LabGate.active()) {
            return;
        }
        synchronized (NativePatch.class) {
            if (done || failed) {
                return;
            }
            try {
                apply();
                done = true;
            } catch (Throwable t) {
                failed = true;
                Log.info("[LabVehiclePhysics] in-memory suspension patch failed: " + t
                        + " - the game keeps the vanilla 2400 kg ceiling");
            }
        }
    }

    public static void apply() throws Throwable {
        Linker linker = Linker.nativeLinker();
        Arena arena = Arena.global();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

        MethodHandle getModuleHandle = linker.downcallHandle(
                kernel32.find("GetModuleHandleA").orElseThrow(
                        () -> new IllegalStateException("GetModuleHandleA not found")),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        MethodHandle virtualProtect = linker.downcallHandle(
                kernel32.find("VirtualProtect").orElseThrow(
                        () -> new IllegalStateException("VirtualProtect not found")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment name = arena.allocateFrom(MODULE);
        MemorySegment handle = (MemorySegment) getModuleHandle.invokeExact(name);
        long base = handle.address();
        if (base == 0L) {
            throw new IllegalStateException(MODULE + " is not loaded in this process");
        }

        int sizeOfImage = readImageSize(base);
        MemorySegment image = MemorySegment.ofAddress(base).reinterpret(sizeOfImage);
        byte[] bytes = image.toArray(ValueLayout.JAVA_BYTE);

        int at = findTuningConstant(bytes, OLD_LIMIT);
        if (at < 0) {
            if (findTuningConstant(bytes, NEW_LIMIT) >= 0) {
                Log.info("[LabVehiclePhysics] suspension limit already raised - leaving it alone");
                return;
            }
            throw new IllegalStateException(
                    "btVehicleTuning constants not found - the game has probably been updated "
                    + "and the constructor changed");
        }

        long target = base + at;
        MemorySegment oldProtect = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment page = MemorySegment.ofAddress(target);

        int ok = (int) virtualProtect.invokeExact(page, 8L, PAGE_EXECUTE_READWRITE, oldProtect);
        if (ok == 0) {
            throw new IllegalStateException("VirtualProtect failed to unprotect the page");
        }

        // JAVA_DOUBLE требует адрес, кратный восьми, а константа лежит ВНУТРИ инструкции
        // movabs, то есть по произвольному адресу. Поэтому невыровненная раскладка.
        // Защиту возвращаем в finally: если запись упадёт, страница не должна остаться
        // доступной на запись.
        try {
            MemorySegment.ofAddress(target).reinterpret(8L)
                    .set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0L, NEW_LIMIT);
        } finally {
            int previous = oldProtect.get(ValueLayout.JAVA_INT, 0L);
            int restored = (int) virtualProtect.invokeExact(page, 8L, previous, oldProtect);
            if (restored == 0) {
                Log.info("[LabVehiclePhysics] WARNING: failed to restore page protection");
            }
        }

        double check = MemorySegment.ofAddress(target).reinterpret(8L)
                .get(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0L);
        if (check != NEW_LIMIT) {
            throw new IllegalStateException("the write did not stick, memory holds " + check);
        }

        System.out.printf(
                "[LabVehiclePhysics] suspension force limit raised in memory: %.0f -> %.0f "
                + "(module %s, base 0x%X, offset 0x%X). The library FILE was not modified.%n",
                OLD_LIMIT, NEW_LIMIT, MODULE, base, at);
    }

    /** Размер загруженного образа из PE-заголовка: e_lfanew по base+0x3C, SizeOfImage по NT+0x50. */
    public static int readImageSize(long base) {
        MemorySegment dos = MemorySegment.ofAddress(base).reinterpret(0x1000L);
        int lfanew = dos.get(ValueLayout.JAVA_INT_UNALIGNED, E_LFANEW);
        if (lfanew <= 0 || lfanew > 0x1000 - 0x100) {
            throw new IllegalStateException("implausible e_lfanew: " + lfanew);
        }
        MemorySegment nt = MemorySegment.ofAddress(base).reinterpret(lfanew + 0x100L);
        int size = nt.get(ValueLayout.JAVA_INT_UNALIGNED, lfanew + SIZE_OF_IMAGE);
        if (size <= 0 || size > 512 * 1024 * 1024) {
            throw new IllegalStateException("implausible SizeOfImage: " + size);
        }
        return size;
    }

    /**
     * Найти предел силы подвески по окружению.
     *
     * Ищем все вхождения искомого числа как 8-байтового double и для каждого считаем,
     * сколько соседних констант конструктора лежит в пределах окна. Проходит только
     * кандидат, у которого соседей не меньше {@link #MIN_NEIGHBOURS}. Если таких
     * кандидатов несколько — отказываемся: лучше не тронуть, чем испортить не то место.
     *
     * @return смещение первого байта константы в образе, либо -1
     */
    public static int findTuningConstant(byte[] image, double value) {
        byte[] needle = doubleBytes(value);
        int found = -1;
        int matches = 0;
        for (int i = 0; i + 8 <= image.length; i++) {
            if (!matchesAt(image, i, needle)) {
                continue;
            }
            int near = 0;
            for (int n = 0; n < NEIGHBOURS.length; n++) {
                if (hasNear(image, i, doubleBytes(NEIGHBOURS[n]))) {
                    near++;
                }
            }
            if (near >= MIN_NEIGHBOURS) {
                matches++;
                if (found < 0) {
                    found = i;
                    Log.info("[LabVehiclePhysics] constant " + value + " found at offset 0x"
                            + Integer.toHexString(i) + ", neighbouring tuning constants nearby: " + near
                            + " of " + NEIGHBOURS.length);
                }
            }
        }
        if (matches > 1) {
            throw new IllegalStateException("found " + matches
                    + " equally plausible matches - refusing to patch blindly");
        }
        return found;
    }

    public static boolean hasNear(byte[] image, int centre, byte[] needle) {
        int from = Math.max(0, centre - WINDOW);
        int to = Math.min(image.length - needle.length, centre + WINDOW);
        for (int i = from; i <= to; i++) {
            if (matchesAt(image, i, needle)) {
                return true;
            }
        }
        return false;
    }

    public static byte[] doubleBytes(double v) {
        long bits = Double.doubleToRawLongBits(v);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (bits >>> (8 * i));
        }
        return out;
    }

    public static boolean matchesAt(byte[] haystack, int at, byte[] needle) {
        if (at + needle.length > haystack.length) {
            return false;
        }
        for (int j = 0; j < needle.length; j++) {
            if (haystack[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    public static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
