--[[
    Reference example: vehicle data for LabVehiclePhysics.

    Copy this file into your own vehicle mod and put in your vehicle's values.

    Your mod.info does NOT need "require=LabVehiclePhysics". Without that mod
    this table just sits in memory and nothing happens, so your mod keeps
    working exactly as before for everyone who does not use it.

    All values are real-world passport data, not game units - the mod does the
    conversion. Full field list: LabVehiclePhysics_API.lua in LabVehiclePhysics.
]]

LabVehiclePhysicsData = LabVehiclePhysicsData or {}

-- U.S. M60A3 Patton main battle tank.
--   combat weight 57.3 US tons       -> 52000 kg
--   Continental AVDS-1790-2, 750 hp
--   top speed 48 km/h on road
--   fuel 174 US gallons              -> 659 litres
--   Allison CD-850 cross-drive automatic with a torque converter
LabVehiclePhysicsData["M60A3"] = {
    mod       = "LabVehicleAuthorExample",
    category  = "tracked_armour",
    mass      = 52000,
    power     = 750,
    maxSpeed  = 48,
    tank      = 659,
    lowGear   = 2,
    lowGearTo = 12,
}
