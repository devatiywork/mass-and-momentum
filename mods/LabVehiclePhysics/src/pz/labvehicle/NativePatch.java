package pz.labvehicle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Lifts the suspension force limit by patching memory, not the file.
 *
 * <h2>What we patch</h2>
 * {@code PZBullet64.dll} has the stock {@code btVehicleTuning} constructor inlined:
 * <pre>
 * 48 B8 00 00 00 00 00 70 B7 40    movabs rax, 6000.0      ; m_maxSuspensionForce
 * 49 89 45 28                      mov [r13+0x28], rax
 * </pre>
 * With gravity at 10, a limit of 6000 per wheel caps the suspension's load capacity at
 * 2400 kilograms. Because of this the game's entire fleet is squeezed into 650..1160 kg, and a
 * vehicle with its real mass sinks through the ground. Details: Docs/dll-suspension-limit.md.
 *
 * <h2>Why in memory and not in the file</h2>
 * Patching the file works, but it cannot be distributed: the game loads the library from the
 * install folder, not the mods folder; Steam's integrity check restores the original; a game
 * update wipes the patch. A memory patch is immune to all of this and remains a plain Java mod.
 *
 * <h2>Search by data, not by code</h2>
 * A fixed offset is only valid for 42.20.4, so we search. But searching by the instruction bytes
 * is not an option: in {@code 48 B8 ... 49 89 45 28} the registers {@code rax} and {@code r13}
 * were chosen by the compiler, not the programmer. Rebuilding the library with another MSVC
 * version, without changing anything in the sources, is enough to change the encoding.
 *
 * So we rely on the numbers themselves. The constructor sets six values in a row:
 * <pre>
 * 5.88   0.83   0.88   500   10.5   6000
 * </pre>
 * These are 8-byte {@code double} values and do not depend on registers at all. Each occurs
 * exactly once in the library, verified by searching. The algorithm: find 6000.0 and check
 * that the other five lie nearby. A match on all six numbers at once is never accidental.
 *
 * Such a search survives a change of registers, a change of compiler, and the constants moving
 * from the instruction bodies into the .rdata data pool.
 *
 * <h2>Safety gate</h2>
 * The patch is applied only if the mod is allowed (see {@link LabGate}). On someone else's
 * server without the mod the library stays untouched.
 *
 * <h2>Requirements</h2>
 * Foreign Function &amp; Memory API (Java 22+). The game runs on Java 25; this class is
 * compiled with {@code --release 25}, the rest of the mod with 17.
 * The {@code --enable-native-access=ALL-UNNAMED} flag is required, otherwise the JVM prints
 * a warning about restricted methods.
 */
public final class NativePatch {

    public static final String MODULE = "PZBullet64.dll";

    /**
     * Neighbours in the btVehicleTuning constructor: suspensionStiffness, suspensionCompression,
     * suspensionDamping, maxSuspensionTravelCm, frictionSlip. All are stock Bullet defaults.
     */
    public static final double[] NEIGHBOURS = {5.88, 0.83, 0.88, 500.0, 10.5};
    /** Neighbour search window around the constant. Covers both inlined code and the .rdata pool. */
    public static final int WINDOW = 256;
    /** How many of the five neighbours must be found. One spare in case something changes. */
    public static final int MIN_NEIGHBOURS = 4;

    /** New limit: 500 000 per wheel at gravity 10, which is 200 tonnes. */
    public static final double NEW_LIMIT = 500000.0;
    public static final double OLD_LIMIT = 6000.0;

    public static final int PAGE_EXECUTE_READWRITE = 0x40;
    /** Offsets in the PE header. */
    public static final long E_LFANEW = 0x3C;
    public static final long SIZE_OF_IMAGE = 0x50;

    public static volatile boolean done = false;
    public static volatile boolean failed = false;

    private NativePatch() {
    }

    /** Called from the physics creation patch; by then the library is certainly loaded. */
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

        // JAVA_DOUBLE needs an address divisible by eight, but the constant sits INSIDE the
        // movabs instruction, i.e. at an arbitrary address. Hence the unaligned layout.
        // Protection is restored in finally: if the write fails, the page must not be left
        // writable.
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

    /** Size of the loaded image from the PE header: e_lfanew at base+0x3C, SizeOfImage at NT+0x50. */
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
     * Finds the suspension force limit by its surroundings.
     *
     * Looks for every occurrence of the value as an 8-byte double and, for each, counts how many
     * neighbouring constructor constants lie within the window. Only a candidate with at least
     * {@link #MIN_NEIGHBOURS} neighbours passes. If there are several such candidates, it
     * refuses: better to touch nothing than to corrupt the wrong place.
     *
     * @return offset of the constant's first byte in the image, or -1
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
