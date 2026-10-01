# LabVehiclePhysics — data API for vehicle mod authors

**API version:** 1 (`LabVehiclePhysics.API_VERSION`)

LabVehiclePhysics gives vehicles their real mass, engine power, brakes, top speed
and fuel tank. Out of the box most modded vehicles carry numbers copied from a
vanilla template — a 52-tonne tank weighs 1104 kg, exactly like the vanilla van it
was cloned from. This API lets you, the author, say what your vehicle really is.

**Your mod does not depend on LabVehiclePhysics.** No `require=` in `mod.info`, no
second version of your mod. Without LabVehiclePhysics your data is an unused Lua
table and nothing happens.

---

## Quick start

Add one Lua file to your mod, in `media/lua/shared/` (and `42/media/lua/shared/`
if you ship a B42 folder):

```lua
LabVehiclePhysicsData = LabVehiclePhysicsData or {}

LabVehiclePhysicsData["M60A3"] = {
    mod       = "MyTankMod",
    category  = "tracked_armour",
    mass      = 52000,     -- kg
    power     = 750,       -- hp
    maxSpeed  = 48,        -- km/h
    tank      = 659,       -- litres
    lowGear   = 2,
    lowGearTo = 12,
}
```

That's all. A complete working example is the mod `LabVehicleAuthorExample`.

---

## Why a table and not a function

Lua files of different mods load in an order you do not control. If your file
runs before LabVehiclePhysics, a function call like `LabVehiclePhysics.register(...)`
finds nothing and silently does nothing. A table created with `X = X or {}` works
in any order: whoever comes first creates it, everyone else adds to it.

A function form exists too, for when you know LabVehiclePhysics is already loaded
(for example inside an event handler):

```lua
if LabVehiclePhysics then
    LabVehiclePhysics.register("M60A3", { mass = 52000, power = 750 })
end
```

It writes into the same table.

---

## Fields

All fields are optional — give what you know. Use **real-world values**; the mod
converts them to game units.

| field | unit | range | notes |
|---|---|---|---|
| `mass` | kg | 50 – 200 000 | curb weight, or combat weight for armour |
| `power` | hp | 1 – 5 000 | passport engine power |
| `maxSpeed` | km/h | 1 – 400 | the game caps every vehicle at about 122 km/h |
| `tank` | litres | 1 – 5 000 | fuel tank capacity |
| `lowGear` | ratio | 1 – 10 | extra pull when starting off, see below |
| `lowGearTo` | km/h | 1 – 200 | speed where `lowGear` has faded out; default 30 |
| `category` | text | | see the list below |
| `mod` | text | | your mod id, shown in logs and in the vehicle list |

**Brakes** are not a field: they scale with mass automatically.

**`lowGear`.** Project Zomboid's engine model has no torque converter and no low
range, so a heavy vehicle has half its rated pull at idle and may not start moving
against resistance. If your vehicle has an automatic gearbox, give the torque
converter's stall ratio — typically 2 to 2.5. The multiplier is full at standstill
and fades linearly to 1 at `lowGearTo`. Light vehicles do not need it.

### Categories

`car`, `suv`, `pickup`, `van`, `delivery_van`, `light_military`, `wheeled_armour`,
`military_truck`, `tracked_armour`, `trailer`

Not used for physics yet. It helps the mod and players sort the vehicle list, and
it will matter for future automatic estimates of unknown vehicles.

### Keys

The key is the vehicle **script name** — the name after `vehicle` in your script
file. With or without the module: `"M60A3"` and `"Base.M60A3"` are the same vehicle.
Use one of them, not both.

---

## How your numbers are used

| your field | what the mod does |
|---|---|
| `mass` | becomes the vehicle's physics mass; brakes and fuel consumption scale with it |
| `power` | engine force = 48.2 × hp, compared with your script's `engineForce` |
| `maxSpeed` | written to the vehicle script |
| `tank` | fuel tank capacity, kept out of the save file |
| `lowGear` | extra engine force at low speed |

You do not need to know game units. If your script says `engineForce = 5600` and
you register `power = 750`, the mod works out that the engine needs ×6.46.

---

## Validation

Values are checked by the mod. A bad field — wrong type, out of range, unknown
name — is dropped with one message in `console.txt`, and **the rest of the entry
still applies**. Messages start with `[LabVehiclePhysics] author data:`.

```
[LabVehiclePhysics] author data: LabVehiclePhysicsData["M60A3"].mass = -5.0 kg is outside 50.0..200000.0 - field ignored
[LabVehiclePhysics] author data: LabVehiclePhysicsData["M60A3"]: unknown field 'powerMul' ignored - give passport horsepower as 'power' instead, the mod converts it
```

When your data is picked up:

```
[LabVehiclePhysics] author data: 1 vehicle(s) from {author:MyTankMod=1}: [M60A3]
```

And the resolved values for every vehicle, once the world loads:

```
[LabVehiclePhysics] vehicle audit - resolved values, no driving needed:
    M60A3  [author:MyTankMod]  mass 52000  power x6.46  brake x47.1  tank 659
```

---

## Where your data sits

LabVehiclePhysics ships its own reference data for popular vehicle mods. Your
registration overrides it field by field for your vehicle — you know your vehicle
better than a shared reference does.

## Players have the last word

Your data is the default, not the final word. A player can override any field in
their `vehicle-physics.cfg`, and only that field — the rest stays yours:

```
*M60A3*: powerMul=4
```

This player keeps your mass, tank and top speed but prefers a gentler engine. The
audit then shows `[cfg[*M60A3*] over author:MyTankMod]`.

Engine power and `lowGear` are overridden as pairs: if the player sets any form of
engine power, both `power` and `powerMul` come from the player; if they set
`lowGear`, `lowGearTo` comes from them too.

---

## Multiplayer

Nothing to do. Server and clients load the same mods, so they read the same table.

Player overrides work differently online: the server's `vehicle-physics.cfg` applies
to everyone and a player's own file is not read, so one vehicle behaves the same for
every driver. Your data is unaffected — it sits below the player layer either way.

---

## Compatibility

LabVehiclePhysics is incompatible with other mods that change vehicle physics.
That does not affect you: registering data does not make your mod depend on or
conflict with anything.

`LabVehiclePhysics.API_VERSION` is 1. New optional fields may be added without
changing the version; the version changes only if existing fields change meaning.
