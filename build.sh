#!/usr/bin/env bash
# Builds both Java mods and assembles a ready Steam Workshop item.
#
#   JDK="/path/to/jdk-25/bin" ZB_JAR="/path/to/ZombieBuddy.jar" ./build.sh
#
# JDK     bin folder of a JDK 25+. The main code is compiled with --release 17 (ByteBuddy
#         inlines our advice into the game's methods, conservative bytecode is safer there),
#         NativePatch.java with --release 25 (it needs the Foreign Function & Memory API).
# ZB_JAR  ZombieBuddy.jar. Only its @Patch annotations are needed to compile; everything in the
#         game itself is reached through reflection, so the game jar is not needed.
# JDK17   optional bin folder of a JDK 17 for the main code and for packing the jars. The Workshop
#         build used Microsoft OpenJDK 17.0.19 for both and Temurin 25.0.4 for NativePatch; with
#         the same compilers the jars come out byte for byte the same (see "Verifying the Workshop
#         jars" in README.md).
#
# Output:
#   mods/<id>/42/media/java/<id>.jar   the jars, in place, as the Workshop layout expects
#   dist/MassAndMomentum/              a complete Workshop item (Contents/mods/... + workshop.txt)
set -euo pipefail
cd "$(dirname "$0")"

: "${JDK:?set JDK to the bin folder of a JDK 25 or newer}"
: "${ZB_JAR:?set ZB_JAR to the path of ZombieBuddy.jar}"
JDK17="${JDK17:-$JDK}"
for d in "$JDK" "$JDK17"; do
    [ -x "$d/javac" ] || [ -x "$d/javac.exe" ] || { echo "no javac in $d" >&2; exit 1; }
done
[ -f "$ZB_JAR" ] || { echo "ZombieBuddy.jar not found: $ZB_JAR" >&2; exit 1; }

# javac on Windows wants ';' between classpath entries, everywhere else ':'. Git Bash converts
# an argument to a Windows path only when it is a single path, so a joined classpath is
# converted entry by entry.
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

rm -rf build dist
mkdir -p build

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# Reproducible jars: the same classes always give the same bytes, so a rebuild can be compared
# with the Workshop jar by SHA-256. Entries go in as a sorted list (not in file system order),
# with a fixed date, our own manifest (no jar tool version in it) and no compression (no zlib
# version in the bytes).
JAR_DATE="2026-01-01T00:00:00Z"
printf 'Manifest-Version: 1.0\r\nCreated-By: Mass & Momentum reproducible build\r\n\r\n' > build/MANIFEST.MF

# The same classes as the Workshop jars. Lab-only tools are left out: DevBuild (marker that
# turns on the detailed log) and VehicleService (repair/refuel by a cfg key, for testing).
release_jar() {  # <id> <package dir>
    local id="$1" pkg="$2" jar out manifest
    jar="mods/$id/42/media/java/$id.jar"
    rm -f "build/$id/classes/pz/$pkg"/DevBuild*.class "build/$id/classes/pz/$pkg"/VehicleService*.class
    mkdir -p "mods/$id/42/media/java"
    rm -f "$jar"
    out="$(winpath "$(pwd)/$jar")"
    manifest="$(winpath "$(pwd)/build/MANIFEST.MF")"
    # shellcheck disable=SC2046
    (cd "build/$id/classes" && "$JDK17/jar" --create --no-compress --date="$JAR_DATE" --manifest="$manifest" \
        --file "$out" $(find . -type f -name '*.class' | sed 's|^\./||' | LC_ALL=C sort))
    echo "    $id.jar: $("$JDK17/jar" --list --file "$out" | grep -c '\.class$') classes, SHA-256 $(sha256 "$jar")"
}

# Every patched class must be in Main.PRELOAD: ZombieBuddy patches only the classes that are
# already loaded at its pass (see Main.java). Up to 1.0.3 BaseVehicle and VehiclePart were missing
# there, and the vehicle patches worked only when the ragdoll mod happened to load them first.
check_preload() {  # <id> <package dir>
    local missing="" c
    for c in $(grep -ho '@Patch(className = "[^"]*"' "mods/$1/src/pz/$2"/*.java | sed 's/.*"\(.*\)"/\1/' | sort -u); do
        grep -q "\"$c\"" "mods/$1/src/pz/$2/Main.java" || missing="$missing $c"
    done
    [ -z "$missing" ] || { echo "patched classes missing from $1 Main.PRELOAD:$missing" >&2; exit 1; }
}
check_preload LabVehiclePhysics labvehicle
check_preload LabRagdollMP labragdoll

echo "--- Mass & Momentum: Vehicle Physics"
mkdir -p build/LabVehiclePhysics/classes
MAIN_SRC=$(ls mods/LabVehiclePhysics/src/pz/labvehicle/*.java | grep -v 'NativePatch.java')
# shellcheck disable=SC2086
"$JDK17/javac" -encoding UTF-8 --release 17 -Xlint:all -cp "$ZB_JAR" \
    -d build/LabVehiclePhysics/classes $MAIN_SRC
"$JDK/javac" -encoding UTF-8 --release 25 -Xlint:all,-restricted \
    -cp "$(winpath "$ZB_JAR")${SEP}$(winpath build/LabVehiclePhysics/classes)" \
    -d build/LabVehiclePhysics/classes mods/LabVehiclePhysics/src/pz/labvehicle/NativePatch.java
release_jar LabVehiclePhysics labvehicle

echo "--- Mass & Momentum: Multiplayer Ragdolls"
mkdir -p build/LabRagdollMP/classes
"$JDK17/javac" -encoding UTF-8 --release 17 -Xlint:all -cp "$ZB_JAR" \
    -d build/LabRagdollMP/classes mods/LabRagdollMP/src/pz/labragdoll/*.java
release_jar LabRagdollMP labragdoll

echo "--- Workshop item: dist/MassAndMomentum"
ITEM=dist/MassAndMomentum
for id in LabVehiclePhysics LabRagdollMP; do
    mkdir -p "$ITEM/Contents/mods/$id"
    cp -r "mods/$id/common" "mods/$id/42" "$ITEM/Contents/mods/$id/"
done
cp workshop/workshop.txt workshop/preview.png "$ITEM/"
echo "OK. To play your own build, copy the folders from $ITEM/Contents/mods into Zomboid/mods"
echo "(ZombieBuddy will ask once to approve the new jars)."
