# Mass & Momentum

Real vehicle weight for **Project Zomboid Build 42**. From a 950 kg city car to a 52 t tank, and the
physics finally uses it: a tank ploughs through a horde, a hatchback gets stuck in it, and nothing
depends on your FPS any more.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3810710740
- Vanilla vs mod, side by side: https://www.youtube.com/watch?v=oLNOL5LoKUE

One Workshop item, two mods:

| Mod | ID | |
|---|---|---|
| Mass & Momentum: Vehicle Physics | `LabVehiclePhysics` | the main mod |
| Mass & Momentum: Multiplayer Ragdolls | `LabRagdollMP` | optional: lifts the game's ban on ragdolls in multiplayer |

## What it changes

- **Real masses** for the vanilla fleet and popular military and KI5 vehicle mods; any other vehicle
  gets a preset by type. The engine's hidden 2,400 kg suspension limit is lifted in memory.
- **Hitting zombies** comes from speed and the reduced mass of vehicle and body, not from FPS. Speed
  above 54 km/h finally counts, each body takes momentum from the vehicle once, by its own weight,
  and zombies have different body weights.
- **Bushes and trees**: a bush takes energy instead of capping your speed; a heavy vehicle fells
  trees by pushing or ramming them (in multiplayer the server decides).
- **Animals** brake the vehicle by their weight and take damage by speed and mass.
- **Fuel**: real tank sizes, consumption grows with mass.
- **Multiplayer**: the corpse lies where the body landed, not where it was hit.
- **Settings**: sandbox pages "Vehicle Physics" (switches for every feature, engine power) and
  "Vehicle Physics: vehicles" (per-vehicle table, 18 presets).

## How it works, and what it touches

Both mods are Java mods loaded by [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853).
At load time they hook specific methods of the game's own classes (ByteBuddy advice); every hook is
listed with a link to its code in [docs/patches.md](docs/patches.md). Game files on disk are never
modified.

If you are reading the code to decide whether to trust it:

- **No network code.** Multiplayer sync goes through the game's own client/server commands.
- **One file is written:** when you host co-op, the game starts the server as a separate Java
  process without ZombieBuddy, so the mod adds ZombieBuddy to it through an argument file in your
  temp folder (`mass-momentum-coop-server.args`, deleted when the game exits). The server gets
  `policy=deny-new`: it loads only the Java mods you already approved in the game with "remember".
  See [CoopServerAgent.java](mods/LabVehiclePhysics/src/pz/labvehicle/CoopServerAgent.java).
- **Files read:** the mod's own data files, an optional `vehicle-physics.cfg` in your Zomboid
  folder, and the hosted server's `.ini` (to check that the mod is in its mod list).
- **Native memory:** [NativePatch.java](mods/LabVehiclePhysics/src/pz/labvehicle/NativePatch.java)
  changes one number in the game's physics library in memory, the suspension force limit; details
  in [docs/patches.md](docs/patches.md).
- **Safety gate:** on a server that does not list the mod, every patch stays inactive
  ([LabGate.java](mods/LabVehiclePhysics/src/pz/labvehicle/LabGate.java)), so it gives no advantage
  anywhere it was not invited.

Code comments and the docs in `docs/` are in Russian, my native language. The code itself is plain
Java and Lua.

## Requirements

- Build 42.21.
- [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) with its
  one-time installer. Until ZombieBuddy is updated for 42.21, use its
  [temporary 42.21 fix](https://steamcommunity.com/sharedfiles/filedetails/?id=3807686870).
- Dedicated servers need ZombieBuddy on the server too. For co-op hosting the mod adds ZombieBuddy
  to the co-op server itself; just enable the mods in the main menu Mods list as well.

## Building from source

You need a JDK 25 or newer, bash (Git Bash on Windows) and `ZombieBuddy.jar`, which comes with the
ZombieBuddy Workshop item (`steamapps/workshop/content/108600/3619862853/mods/ZombieBuddy/libs/ZombieBuddy.jar`).

```bash
JDK="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin" \
ZB_JAR="/c/Program Files (x86)/Steam/steamapps/workshop/content/108600/3619862853/mods/ZombieBuddy/libs/ZombieBuddy.jar" \
./build.sh
```

The jars land in `mods/<id>/42/media/java/`, and `dist/MassAndMomentum` is a complete Workshop item.
To play your own build instead of the Workshop one, copy the folders from
`dist/MassAndMomentum/Contents/mods` into `Zomboid/mods` (ZombieBuddy will ask once to approve the
new jars). The Workshop jars were built with Microsoft OpenJDK 17.0.19 for the main code and
Temurin 25.0.4 for `NativePatch`; set `JDK17` to compile the main code with a JDK 17 the same way.
Jar files never match byte for byte because of timestamps, so compare the classes inside them.

## Docs

- [docs/patches.md](docs/patches.md): every hooked game method
- [docs/vehicle-config.md](docs/vehicle-config.md): the `vehicle-physics.cfg` format (Russian)
- [docs/vehicle-api.md](docs/vehicle-api.md): Lua API for vehicle mod authors (Russian), with an
  example in [examples/LabVehicleAuthorExample](examples/LabVehicleAuthorExample)

## License

[MIT](LICENSE).
