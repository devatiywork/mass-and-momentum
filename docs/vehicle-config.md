# `vehicle-physics.cfg` — reference

The file lives next to the saves: `Zomboid\vehicle-physics.cfg` in your user folder (on Windows `C:\Users\<you>\Zomboid\vehicle-physics.cfg`).
It is re-read **on the fly, every two seconds**. In multiplayer the server's file applies and
the client does not read its own; see "Multiplayer".

**Updated:** 27.09.2026

---

## Why a file and not constants in the code

The game has 280 vehicles: vanilla plus mods. Finding the right numbers is iterative work, and no iteration should cost a jar rebuild and a game restart.

---

## Format

```
<name mask>: key=value key=value ... [flags]
```

Rules are checked from top to bottom; the **first matching rule** applies.

The mask is the vehicle's script name, with `*` allowed anywhere. The name comes in two forms (`97bushAmbulance` and `Base.97bushAmbulance`) and the mod matches both, so a leading asterisk is not required.

---

## What applies when

This is the most important thing to understand; otherwise your edits "don't work".

| applies on the fly | requires a restart |
|---|---|
| `mass` with the `live` flag | `mass` without `live` |
| `power`, `powerMul`, `brakeMul` | `stiffness` |
| `lowGear`, `lowGearTo` | `travel`, `rest` |
| `tank` | `maxSpeed` |
| `service` | |

The reason: mass is fed into the physics engine **every frame** via `Bullet.setVehicleMass(getFudgedMass())`, while the suspension parameters are baked in **once**, when the script is registered in `Bullet.defineVehicleScript`. Sending them again only affects vehicles created afterwards.

In multiplayer the server table is re-applied in full every time it arrives, so there `mass` without `live` and `maxSpeed` also reach the vehicles immediately. The suspension does not, just as here.

---

## Keys

### `mass=<kg>`

Mass in kilograms. The game uses the same units: a vanilla passenger car is 800.

It changes the script field, and with the `live` flag it is also substituted every frame in `getFudgedMass()`.

**Important:** with `live` on, cargo in the trunk is **not added** to the mass: exactly the specified number is returned. Fuel does not add mass in vanilla either (the fuel level lives in `itemCapacity`, while mass is calculated from `weight`; they are independent fields).

### `live`

Substitutes the mass in the physics every frame. You need it to change mass without a restart.

For all vehicles at once, there is the "Live vehicle mass" switch (`LabVehiclePhysics.LiveMass`,
off by default) on the "Vehicle Physics" sandbox page. The `live` flag on individual rules
works even with the switch off; with the switch on, the flag is not needed.

**An easy trap to fall into:** if a large mass was already written to the script at load time, simply deleting the rule will not revert it; the mass stays until a restart. To get vanilla back in the current session, set `mass=<vanilla value> live`.

### `stiffness=auto|<number>`

Suspension stiffness. `auto` multiplies it by the same ratio as the mass.

**Usually not needed.** In Bullet, the suspension's support force scales with mass by itself; now that the limiter in `PZBullet64.dll` has been removed, there is no need to tweak stiffness. The key remains as a tool.

### `travel=<cm>`, `rest=<number>`

Suspension travel and spring rest length. Vanilla: 10 cm and 0.2 for every vehicle.

Travel is the distance over which the wheel's ray searches for the ground. Setting it too low breaks the suspension even on a light vehicle (tested: 1 cm at vanilla mass gives a sag of 0.30).

### `power=<hp>`

Rated engine power. The mod converts it into engine force by itself:

```
required force = 34.75 × hp × (script mass + 310) / mass
multiplier     = required force / script's engineForce
```

`mass` is the rated mass from the rule. `script mass + 310` is what the physics really moves: the
game adds the weight of the installed parts on top of the script mass (`BaseVehicle.updateTotalMass`,
250..330 kg on vanilla vehicles). The constant 34.75 is anchored on the vanilla passenger car as the
physics sees it: force 4000 for 800 + 310 = 1110 kg against a real 140 hp / 1350 kg sedan. Where the
suspension cap lowers the script mass, the force drops in the same proportion.

You do not need to work out the power-to-weight ratio by hand: the spec sheet is enough. Added on
26.09.2026 together with the Lua API: mod authors supply power in hp, and the same conversion is
available in the config. Until 04.10.2026 the constant was 48.2 × hp, anchored on the script mass
of 800 kg: every vehicle pulled 1.39 times too hard.

If both `power` and `powerMul` are set, `powerMul` wins: it is the manual setting.

### `powerMul=auto|<number>`

Engine force multiplier. `auto` is the ratio of the new mass to the vanilla one (both with the ~310 kg of parts the physics adds), so acceleration stays stock despite the real weight.

To work out a realistic number, use the power-to-weight ratio relative to the vanilla passenger car:

```
PZ vanilla car:           force 4000 / physics mass 1110 (800 + 310 of parts) = 3.60
1993 sedan:               140 hp / 1350 kg
Bushmaster:               300 hp / 11400 kg
ratio:                    0.254   → acceleration 3.9 times worse than the sedan
required force:           3.60 × 0.254 × (11400 + 310) = 10,709
mod's script has 4850     → powerMul = 2.21
```

### `brakeMul=auto|<number>`

The same for brakes. Vanilla brake pads will not stop eleven tonnes.

For the Bushmaster, the vehicle mod's own stock balance turned out to be truck-like already (brakes 62 / mass 998 = 0.062 versus 0.113 for a passenger car), so `auto` is enough.

### `lowGear=<multiplier>`, `lowGearTo=<km/h>`

The TOTAL engine force multiplier for pulling away. By default the extra fades out by 30 km/h.

**What vanilla already does.** In 42.21 the game drives through `CarController.control_ForwardNew`:

```java
engineForce = enginePower * gearMul * (0.3 + rpm / 30000.0) * (1 - speed / 200);   // gearMul = 1.5 in first gear
```

First gear already multiplies the force by 1.5. So `lowGear` is the total pull-away multiplier, and the mod adds only `lowGear / 1.5`: values up to 1.5 add nothing. Until 04.10.2026 this section quoted `0.5 + rpm / 24000` from `control_Forward`, which 42.21 no longer calls, concluded that the game had no low gear, and the vans' `lowGear=1.5` landed on top of vanilla's 1.5.

**Why heavy vehicles need it.** Light vehicles pull away fine on vanilla's first gear; an eleven-tonne armoured car cannot pull away inside a crowd. This is **not a cheat but a missing piece of the model put back**: a real diesel delivers its peak torque at low revs, and a torque converter multiplies it two- to threefold when pulling away. Typical values: 2..3, only for vehicles over about 5 t.

The extra is at its maximum at standstill and falls linearly to one: this is how a torque converter behaves from stall to the coupling point.

### `maxSpeed=<km/h>`

The real top speed. The game's own `maxSpeed` is not a top speed: above it the engine force fades out linearly and reaches zero 20 km/h higher (`CarController`: `F × (maxSpeed + 20 − v) / 20`), and with no air drag in the model every vehicle ends up at `maxSpeed + 20`. So the mod writes `maxSpeed − 20` into the script, and the vehicle ends up at the number from the rule. Until 04.10.2026 the value went in as is, and every vehicle ran 20 km/h faster than its passport (Bushmaster 120 instead of 100).

From the built-in data it applies to modded vehicles only: vanilla vehicles keep the game's own top speed (decision of 04.10.2026). The author data, the player's file and the sandbox table still set it on any vehicle.

Keep in mind the **hard global cap of 34 units (~122 km/h)** in `updateVelocityMultiplier`: it has nothing to do with mass, and setting a higher value is pointless. In multiplayer the server option `SpeedLimit` cuts the engine at its value (default 70), and the dashboard then shows an inflated speed.

### `tank=<litres>`

Fuel tank capacity. **All** vehicles have the vanilla car tank `BigGasTank1` with `MaxCapacity = 59`, even the eleven-tonne armoured car.

The return value of `getContainerCapacity()` is substituted; nothing is written to the save. The vanilla capacity reduction from part wear is preserved.

### `category=<category>`

Vehicle class: `car`, `suv`, `pickup`, `van`, `delivery_van`, `truck`, `bus`, `light_military`,
`wheeled_armour`, `military_truck`, `tracked_armour`, `trailer`. It does not affect physics
yet. It is needed by the built-in table and for calibrating the automatic mass estimate
(`vehicle_audit.py --calibrate`): the category travels with the vehicle's data instead of
being guessed from the mask.

### `preset=<name>`

A ready-made set of characteristics by vehicle type, for vehicles the mod knows nothing
about: they are not in the built-in table, and their mod's author has not supplied any data.
Presets live in the mod, in `42/media/vehicle-physics-presets.cfg`, in the built-in table's format.

```
*SomeTank*: preset=tank                          # whole preset
*SomeTank*: preset=tank mass=38000 power=600     # preset with an override
```

Values set in the line take precedence over the preset, by the same rules as layer merging:
engine force (`power` / `powerMul`) and low gear (`lowGear` / `lowGearTo`) go in pairs. A preset
is expanded inside its own line, so a player's line with a preset overrides the built-in table
and the author data in every field the preset sets. The name is case-insensitive; an unknown
name produces one log line listing the known ones, and the line is applied without a preset.
In the audit, the source looks like this: `cfg[*SomeTank*] over preset[tank]`.

| preset | mass, kg | hp | km/h | fuel tank, L | low gear |
|---|---:|---:|---:|---:|---|
| `small_car` small car | 1,000 | 75 | 110 | 45 | — |
| `car` passenger car | 1,400 | 140 | 115 | 60 | — |
| `sports_car` sports car | 1,450 | 300 | 120 | 70 | — |
| `suv` SUV | 2,000 | 170 | 110 | 80 | ×1.5 up to 20 |
| `pickup` pickup | 2,100 | 200 | 110 | 100 | ×1.5 up to 20 |
| `van` van | 2,500 | 200 | 100 | 95 | ×1.5 up to 20 |
| `light_truck` light truck | 3,600 | 190 | 90 | 120 | ×1.5 up to 20 |
| `truck` truck | 9,000 | 280 | 90 | 300 | ×2.5 up to 25 |
| `bus` bus | 11,000 | 250 | 90 | 300 | ×2 up to 25 |
| `light_military` light military vehicle | 2,400 | 150 | 110 | 95 | ×2 up to 30 |
| `military_truck` military truck | 12,000 | 350 | 90 | 300 | ×2.5 up to 25 |
| `armored_car` armoured car | 9,000 | 250 | 100 | 300 | ×2.5 up to 30 |
| `wheeled_apc` wheeled APC | 14,000 | 300 | 100 | 300 | ×2.5 up to 30 |
| `tracked_apc` tracked APC | 12,000 | 275 | 65 | 360 | ×2.5 up to 15 |
| `light_tank` light tank, IFV | 25,000 | 550 | 65 | 600 | ×2 up to 15 |
| `tank` tank | 45,000 | 750 | 50 | 1,000 | ×2 up to 12 |
| `trailer` trailer | 400 | — | — | — | — |
| `heavy_trailer` heavy trailer | 2,000 | — | — | — | — |

All presets have `auto` brakes, derived from mass. In multiplayer a line with a preset travels
in the server table like any other; the presets themselves are the same for everyone, since they
ship with the mod.

### `service`  (server only)

One-off repair: all parts to 100, fuel tank filled to the brim, tyres inflated.

It fires **every time the file is re-read**, so remove the key right afterwards; otherwise the vehicle will be repaired on every subsequent config edit.

It runs **only on the server** and explicitly sends the changed parts to clients
(`transmitPartModData`, `transmitPartItem`, `transmitPartCondition`). Refuelling uses the
`force` flag; otherwise the amount is clipped to the capacity of the tank item. For a breakdown
of the whole chain, see `modding-notes.md` §12.

---

## Fuel consumption

It is changed not through the config but by the mod's Lua file, which replaces the vanilla dependence on mass.

Vanilla:

```lua
massMultiplier = (math.abs(1000 - vehicle:getScript():getMass()) / 300) + 1
```

The reference point is exactly 1000 kg, and the formula takes the absolute value. Because of this, **a 650 kg small car guzzles 41% more fuel than a 1160 kg delivery van**. In vanilla this went unnoticed only because the whole fleet is squeezed into 650..1160.

Our replacement is `mass^0.55`, based on real data (the Bushmaster uses 40 L/100 km versus 10 for a sedan, i.e. four times as much with an eightfold difference in mass):

| mass | vanilla | ours | consumption divided by |
|---:|---:|---:|---:|
| 650 (small car) | 2.17 | 0.79 | 2.7 |
| 998 | 1.01 | 1.00 | unchanged |
| 1350 (sedan) | 2.17 | 1.18 | 1.8 |
| 2300 (van) | 5.33 | 1.58 | 3.4 |
| 11,400 | 35.67 | 3.81 | 9.4 |
| 52,000 (tank) | 171.00 | 8.79 | 19.5 |

We do not rewrite the function itself; we call the original with an adjusted `elapsedMinutes`. Consumption in it is linear in time, so multiplying the time by the ratio of the multipliers gives exactly the desired result, and the change will survive changes to the rest of the formula.

---

## Known limitations

**Tracked vehicles get stuck on bodies.** This is unrelated to our changes: the engine has no
tracks, the number of wheels is hard-limited to four, and modders imitate the track with small
road wheels (radius 0.15 versus 0.55 on wheeled vehicles). Such a wheel does not roll over a
body, and its ray reaches only 0.35 down. `rest=0.5` helps as a stopgap. Details:
`backlog.md` §3g.

**Engine force cannot be derived from mass.** A tank and a truck may both weigh 50 tonnes, yet
their power differs. The `power=` key did away with manual calculation (rated hp is enough), but
someone still has to know the hp: the player, the mod author via the Lua API, or a vehicle-type
preset (`preset=`). See `vehicle-data-design.md`.

---

## Worked example

The Bushmaster PMV with its full real-world specs:

```
# the mod's built-in table (vehicle-physics-defaults.cfg)
*97bush*: mass=11400 power=300 maxSpeed=100 tank=319 lowGear=3 lowGearTo=30 brakeMul=auto category=wheeled_armour

# the player's file — the rated 300 hp felt sluggish
*97bush*: powerMul=4 live
```

| spec sheet | in game |
|---|---|
| curb weight 11,400 kg (gross 15,400) | `mass=11400` |
| Caterpillar 3126E 7.2 L, 300 hp at 2200 rpm | `power=300` (the player raised it to `powerMul=4`) |
| top speed 100 km/h | `maxSpeed=100` |
| fuel tank 319 L, range 800 km | `tank=319` |
| brakes for 11 tonnes | `brakeMul=auto` |

The range comes out realistic: 319 L at 40 L/100 km is 800 km, more than a passenger car's (59 L at 10 L/100 km = 590 km), thanks to a fuel tank five times larger.

---

## Layers: where the characteristics come from

Since 26.09.2026 they come from three places, and since 27.09.2026 from four; each one overrides
the previous one **field by field**:

| layer | where it lives | who writes it |
|---|---|---|
| 1. built-in table | `LabVehiclePhysics/42/media/vehicle-physics-defaults.cfg` | us; it ships with the mod |
| 2. vehicle author's data | the Lua table `LabVehiclePhysicsData` in their mod | the author, see `vehicle-api.md` |
| 3. this file | `Zomboid/vehicle-physics.cfg` | the player; in multiplayer, the server's file. A lab tool |
| 4. vehicle table in the sandbox | a sandbox option of the world, the "Vehicle Physics: vehicles" page | the player, in the UI; has the final say |

The sandbox table is covered in the "Sandbox settings" section below.

The built-in table uses the same format as this file, with masks. It holds only the specs:
mass, power in hp, top speed, fuel tank, low gear, category. This file is now
**only for the player's decisions**: tuning and the lab flags `live` and `service`.

Why it works this way. While the specs lived here, someone who downloaded the mod did not have
this file, so they would have had vanilla physics. The same in multiplayer: vehicle physics is
computed by the driver's client (the server does not register vehicles in Bullet:
`VehicleScript.Loaded()` calls `toBullet()` only when `!GameServer.server`), so a guest without
the file would have been driving on vanilla numbers. Now everyone who has the mod installed has
the same baseline.

Engine force (`power` / `powerMul`) and low gear (`lowGear` / `lowGearTo`) are overridden
**in pairs**: if you set engine force in any form, both values come from the upper layer.

Example: the M60A3. Its specs are in the built-in table and also come from the example mod
`LabVehicleAuthorExample`; in this file the player has kept only their own changes:

```
*M60A3*: powerMul=4 live service
```

Result: mass 52,000, fuel tank 659, top speed 48 and low gear 2 up to 12 km/h come from below;
engine force ×4 instead of the rated ×6.46 comes from the player. The source in the audit:

```
cfg[*M60A3*] over author:LabVehicleAuthorExample over builtin[*M60A3*]
```

To check the result of all the layers without running the game:

```
py -3.14 tools\vehicle-audit\vehicle_audit.py            # whole fleet, "source" column
py -3.14 tools\vehicle-audit\vehicle_audit.py --authors  # what mod authors have sent
```

### Why the built-in table is a file with masks, not a Lua table

For authors, the contract is a table keyed by exact script names. For the built-in table, masks
are more convenient: vanilla has 157 scripts for 26 body types, and TIS adds new liveries in
updates. The mask `Van*` will cover a new van by itself; an exact name will not.

### What switching to hp achieved

In the built-in table, engine force is given as power (`power=200`), not as a multiplier. The
multiplier is calculated for each script from its own engine force, and this by itself fixed the
rough approximations of the old masks: liveries of the same body can have different engine force
in their scripts. During the migration on 26.09.2026, engine force moved towards the spec for
14 scripts; for example, six service pickups had 167 hp instead of 200, and `StepVanMail` had
205 instead of 190. Mass and brakes did not shift for any of the 190 vehicles.

---

## Sandbox settings

Since 27.09.2026 the mod has its own page in the sandbox settings, "Vehicle Physics"
(`42/media/sandbox-options.txt`, EN and RU translations). The game stores the values: in
singleplayer, in the world save; in multiplayer, on the server, which also hands them out on join
and sends out changes mid-game. The mod re-reads them once a second (`LabSettings`) and logs a
`settings:` line on every change.

| option | default | what happens if you turn it off |
|---|---|---|
| Vanilla vehicles | Mod values | "Standard": the built-in table (layer 1) does not apply to vanilla vehicles; mass, power, top speed and fuel tank are as in the game |
| Engine power multiplier | 1.0, from 0.5 to 3.0 | cannot be turned off; it multiplies the engine force of all vehicles on top of their settings, including vehicles without rules; brakes are not affected |
| Realistic zombie impacts | on | zombie mass, uncapped impacts, the push from zombies and lying bodies: all as in the game |
| Corpse follows the ragdoll | on | the corpse appears at the point of impact |
| Animal hits by mass and speed | on | slowdown and damage as in the game |
| Fuel consumption by mass | on | consumption as in the game: the vanilla formula applied to the vehicle's original mass |
| Bushes slow vehicles by mass | on | the vanilla speed cap in bushes |
| Heavy vehicles knock down trees | on | a tree is a solid obstacle |

**"Vanilla vehicles: Standard"** removes only the built-in table, and only for vanilla vehicles.
Mod authors' data and this file always apply: they are an explicit choice by the author and the
player. The switch does not touch modded vehicles. A vanilla vehicle is one whose script was
first defined by the game itself: the script's first body comes from `pz-vanilla`
(`getLoadedScriptBodies()`); a mod that adds something to a vanilla vehicle leaves it vanilla.

Switching on the fly: scripts return to the game's numbers (the mod remembers the original field
values before its first write), vehicles in the world get the new mass and top speed immediately,
and the suspension on the next chunk load. In the log:

```
[LabVehiclePhysics] vanilla vehicles switched to standard - vehicle scripts re-applied: N (returned to game values: N), ...
```

**Consumption "as in the game"** is not simply our correction switched off. The vanilla formula
applied to the real mass would give a tank 171 times the consumption of a passenger car, so it is
calculated from the vehicle's mass before the built-in table, exactly as it would be without the mod.

**Where to change them.** In a new world, at creation, on the "Vehicle Physics" page. In an
existing save, the mod's options get their default values. Mid-game: in multiplayer, the admin
does it via "Admin Panel → Sandbox Options"; in singleplayer, only from the debug menu
(`PZ-Lab-Debug.bat`, the "Sandbox" item). In singleplayer the vehicle table can also be edited
from the pause menu; see below.

### Vehicle table — the "Vehicle Physics: vehicles" page

The mod's second sandbox page: values for each vehicle. On the left, all vehicles, with search
and an "Only changed" checkbox (changed ones are highlighted in yellow). On the right, the
selected vehicle: a vehicle-type preset, the fields "mass, power, top speed, fuel tank, pull-away
boost, boost fades by", and a "Reset" button. Next to each field: what will apply and what it
would be without the table; below them, where the result comes from:
"this table › preset "Tank" › built-in table". An empty field takes its value from the preset or
from the layers below; a grey hint in the empty field shows which value.

The values are calculated by the same Java code that runs the game (`VehicleTable` via
`LabVehiclePhysicsNet`), so the panel shows exactly what the vehicle will get.

**Storage.** A single string sandbox option, `LabVehiclePhysics.VehicleTable`: lines in this
file's format separated by ";", one for each vehicle that was changed, with the exact script name
on the left:

```
97bushAmbulance: preset=tank mass=38000;M60A3: power=800
```

The game itself stores it with the world, and in multiplayer keeps it on the server and sends it
out. This is the **top layer**: above the built-in table, the author data and this file. A change
applies on the fly; in the log:

```
[LabVehiclePhysics] sandbox vehicle table: 2 vehicle(s) - 97bushAmbulance: preset=tank mass=38000;M60A3: power=800
[LabVehiclePhysics] sandbox vehicle table changed - vehicle scripts re-applied: N, vehicles already in the world updated: N
```

**Where to open it.** In all three sandbox screens: world creation, the server settings when
hosting, and the admin editor mid-game. The stock game has no such page (sandbox options are
fixed fields), so the screen builds the page as usual, and the mod replaces its panel with its own
and registers it as the option's field: the stock save code calls `getText`/`setText` on it.
In singleplayer there is also a "Vehicle physics" item in the pause menu: the same window, where
"Apply" writes the table into the world's sandbox options; it reaches the save file the next time
the game saves (`GameWindow.save` writes `map_sand.bin` every time).

---

## Multiplayer: the server's file applies

Since 27.09.2026. In multiplayer **only the server** reads this file, and it hands the file out
to everyone who connects. In multiplayer a client does not read its own file at all.

| where you play | whose file applies |
|---|---|
| singleplayer | your own |
| co-op, you are the host | yours: the co-op host's server is a separate process, but with the same `Zomboid` folder |
| co-op, you joined someone else | the host's |
| dedicated server | the one in the server's `Zomboid` folder, i.e. the admin's |

If the server has no file, only the mod's built-in table and the author data apply for everyone.

**Why.** In multiplayer, a vehicle is simulated by the driver's client. While everyone read their
own file, the same vehicle weighed differently for different players, depending on who was driving.

**How it travels.** On entering the game, the client asks for the table and the server replies.
If the file on the server is edited during play, the server sends it to everyone again after one
in-game minute (with a one-hour day, that is 2.5 seconds). Only the rule lines are sent, without
comments. The built-in table and the author data are not sent over the network: they live in the
mods, and the server enforces the mod list anyway.

**The table arrives only after the world has loaded**, because a command can be sent to the
server only from within the game. Until it arrives, the built-in table and the author data apply.
When it arrives, the scripts are re-applied, and vehicles already standing nearby get the new mass
and top speed immediately. Without that, they would keep the old numbers until their chunk
unloads: a vehicle copies them from the script once, at creation (`BaseVehicle.createPhysics`).

**Client log:**

```
[LabVehiclePhysics] multiplayer: the local vehicle-physics.cfg is not used on a server - waiting for the server's table, ...
[LabVehiclePhysics] server table received: 15 rule(s), version 1
[LabVehiclePhysics] author data and server table changed - vehicle scripts re-applied: 262, vehicles already in the world updated: 1
...
[LabVehiclePhysics] server table received: 15 rule(s), version 2
[LabVehiclePhysics] server table changed - vehicle scripts re-applied: 262, vehicles already in the world updated: 11
```

In the audit, the top layer's source is `server[mask]` instead of `cfg[mask]`.

The lines above come from a test on 27.09.2026 on the test server (`PZ-Lab-Server.bat`): its file
had `powerMul=2` for the Bushmaster, the client's had 4, and the client got 2. The version number
starts over with every server launch: it counts re-reads of the file, it is not a content version.

**Server log** (for a co-op host it is `coop-console.txt`):

```
[LabVehiclePhysics] server table: 15 rule line(s), version 1, sent to <player>
[LabVehiclePhysics] server table: vehicle-physics.cfg changed - 15 rule line(s), version 2, sent to all players
```

**Limitation.** A rule deleted from the file during play is not rolled back on the vehicles; a
rejoin is needed. The mod rewrites a script according to its rule, but does not touch the script
of a vehicle without a rule, so the old numbers remain in it. Added and changed rules arrive at once.

Implementation: `ServerTable.java`; messaging: `LabVehiclePhysics_ServerTable.lua` in
`client/` and `server/`.

---

## Safety gate

All changes take effect **only if the mod is on the active mod list of the current game**. In multiplayer this list comes from the server, so on someone else's server without the mod, everything stays inactive. Details are in `modding-notes.md`, section 6.
