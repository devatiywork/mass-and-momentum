--[[
    LabVehiclePhysics - public data API, version 1.

    For vehicle mod authors: describe your vehicles with real-world passport
    values and LabVehiclePhysics turns them into game physics - mass, engine
    force, brakes, top speed, fuel tank.

    Without LabVehiclePhysics installed your data simply sits in a table and
    does nothing. No hard dependency, no "require=" in your mod.info, no second
    version of your mod.

    ------------------------------------------------------------------------
    Preferred form - works in any load order:

        LabVehiclePhysicsData = LabVehiclePhysicsData or {}
        LabVehiclePhysicsData["M60A3"] = {
            mod       = "MyTankMod",       -- your mod id, shown in logs and lists
            category  = "tracked_armour",
            mass      = 52000,             -- kg, curb or combat weight
            power     = 750,               -- hp, passport engine power
            maxSpeed  = 48,                -- km/h
            tank      = 659,               -- litres
            lowGear   = 2,                 -- torque converter stall ratio
            lowGearTo = 12,                -- km/h where lowGear fades out
        }

    Why a table and not only a function: Lua files of different mods load in an
    order you do not control. If your file runs before this one, a call like
    LabVehiclePhysics.register(...) would find nothing. A table created with
    "X = X or {}" survives any order.

    Function form, if you prefer it (only works after this file has loaded):

        if LabVehiclePhysics then
            LabVehiclePhysics.register("M60A3", { mass = 52000, power = 750 })
        end

    ------------------------------------------------------------------------
    Fields - all optional, give what you know:

        mass       kg          50 .. 200000
        power      hp          1 .. 5000        converted to engine force by the mod
        maxSpeed   km/h        1 .. 400         the game caps everything at ~122
        tank       litres      1 .. 5000
        lowGear    ratio       1 .. 10          extra pull when starting off;
                                                heavy vehicles with an automatic
                                                gearbox, typically 2 .. 2.5
        lowGearTo  km/h        1 .. 200         default 30
        category   string                       see the list below
        mod        string                       your mod id

    Brakes are not a field: they scale with mass automatically.

    Categories: car, suv, pickup, van, delivery_van, light_military,
                wheeled_armour, military_truck, tracked_armour, trailer

    Keys are vehicle script names, with or without the module: "M60A3" and
    "Base.M60A3" mean the same vehicle.

    ------------------------------------------------------------------------
    Validation happens in the mod. A bad field is dropped with a message in
    console.txt, the rest of the entry still applies. Players can override any
    of your values in their vehicle-physics.cfg - your data is the default,
    not the final word.
]]

LabVehiclePhysicsData = LabVehiclePhysicsData or {}

LabVehiclePhysics = LabVehiclePhysics or {}
LabVehiclePhysics.API_VERSION = 1

local FIELDS = {
    mass = true, power = true, maxSpeed = true, tank = true,
    lowGear = true, lowGearTo = true, category = true, mod = true,
}

local function bareName(scriptName)
    return string.match(scriptName, "([^%.]+)$") or scriptName
end

--- Register or replace the data for one vehicle script.
-- @param scriptName  "M60A3" or "Base.M60A3"
-- @param spec        table of passport values, see the header of this file
-- @return true if stored, false if the arguments were unusable
function LabVehiclePhysics.register(scriptName, spec)
    if type(scriptName) ~= "string" or scriptName == "" then
        print("[LabVehiclePhysics] register: the script name must be a non-empty string")
        return false
    end
    if type(spec) ~= "table" then
        print("[LabVehiclePhysics] register(\"" .. scriptName .. "\"): the second argument must be a table")
        return false
    end
    local bare = bareName(scriptName)
    local copy = {}
    for k, v in pairs(spec) do
        if FIELDS[k] then
            copy[k] = v
        else
            print("[LabVehiclePhysics] register(\"" .. bare .. "\"): unknown field '" .. tostring(k) .. "' ignored")
        end
    end
    LabVehiclePhysicsData[bare] = copy
    return true
end

--- The data registered for a vehicle script, or nil. Read it, do not modify it.
function LabVehiclePhysics.get(scriptName)
    if type(scriptName) ~= "string" then
        return nil
    end
    return LabVehiclePhysicsData[bareName(scriptName)]
end
