--[[
  Fuel consumption by mass — a fix for the vanilla formula.

  Vanilla (media/lua/server/Vehicles/Vehicles.lua, Vehicles.Update.GasTank):

      massMultiplier = (math.abs(1000 - vehicle:getScript():getMass()) / 300) + 1
      gasMultiplier  = gasMultiplier / qualityMultiplier / massMultiplier
      newAmount      = (speedMultiplier / gasMultiplier) * SandboxVars.CarGasConsumption
      amount         = amount - elapsedMinutes * newAmount

  The formula is odd in itself: the reference point is exactly 1000 kg and it takes the absolute
  value, so by this formula a 200 kg trailer "burns" more than a passenger car. In vanilla this
  never surfaced, because the whole fleet was squeezed into 650..1160 kg. As soon as vehicles got
  real masses, the multiplier for 11,400 kg became 35.7 instead of 1.0: consumption grew 35-fold.

  In real life the dependency is much weaker: a Bushmaster burns about 40 L/100 km against 10 for
  a sedan, i.e. four times as much at an eightfold mass difference. That is roughly mass^0.55.

  We do not rewrite the function itself: it may change with a game update. Instead we call
  the original with an adjusted elapsedMinutes: consumption in it is linear in time, so
  multiplying the time by the ratio of the multipliers gives exactly the desired result.

  With the sandbox toggle "Fuel consumption by mass" off, consumption is as in the game: the same
  vanilla formula, but applied to the vehicle's mass before our reference data. Leaving the function
  alone will not do: with the real mass the same formula would give a tank 170 times the consumption.
]]

-- Safeguard: if the table is somehow missing, quietly do nothing rather than break loading.
if not Vehicles or not Vehicles.Update or not Vehicles.Update.GasTank then
    print("[LabVehiclePhysics] fuel consumption: Vehicles.Update.GasTank not found, patch skipped")
    return
end

local originalGasTank = Vehicles.Update.GasTank

local function vanillaMassMultiplier(mass)
    return (math.abs(1000 - mass) / 300) + 1
end

local function realisticMassMultiplier(mass)
    if mass <= 0 then return 1 end
    return (mass / 1000) ^ 0.55
end

-- Ask Java (LabSettings), like all the other toggles: after an edit from the singleplayer debug
-- menu, SandboxVars is not updated until the game is reloaded. Without Java, use SandboxVars;
-- if the option does not exist at all, treat it as on, the default.
local function fuelByMass()
    if LabVehiclePhysicsNet and LabVehiclePhysicsNet.fuelByMass then
        return LabVehiclePhysicsNet.fuelByMass()
    end
    local vars = SandboxVars and SandboxVars.LabVehiclePhysics
    return not vars or vars.FuelByMass ~= false
end

-- The script's mass before our reference data; if unknown, the current one.
local function originalMass(script, current)
    if LabVehiclePhysicsNet and LabVehiclePhysicsNet.originalMass then
        local m = LabVehiclePhysicsNet.originalMass(script:getName())
        if m and m > 0 then return m end
    end
    return current
end

Vehicles.Update.GasTank = function(vehicle, part, elapsedMinutes)
    if elapsedMinutes and elapsedMinutes > 0 and vehicle and vehicle:getScript() then
        local script = vehicle:getScript()
        local mass = script:getMass()
        local vanilla = vanillaMassMultiplier(mass)
        local wanted
        if fuelByMass() then
            wanted = realisticMassMultiplier(mass)
        else
            wanted = vanillaMassMultiplier(originalMass(script, mass))
        end
        if vanilla > 0 then
            elapsedMinutes = elapsedMinutes * (wanted / vanilla)
        end
    end
    return originalGasTank(vehicle, part, elapsedMinutes)
end

print("[LabVehiclePhysics] fuel consumption: mass dependency replaced with mass^0.55")
