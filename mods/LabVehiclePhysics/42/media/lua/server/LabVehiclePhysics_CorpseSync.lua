--[[
  Corpse where the body fell — the server side.

  Only the driver's client simulates the ragdoll of a hit zombie, but the server creates the
  corpse. On impact the server hands the zombie over to the driver and delays its death, and the
  driver's client, once the body has come to rest, sends a zombieLanded command with the point and
  direction. The server validates it and creates the corpse there (CorpseSync.serverLanded).
]]

if not isServer() then return end

if not LabVehiclePhysicsNet then
    print("[LabVehiclePhysics] corpse sync: the Java side (LabVehiclePhysicsNet) is not available -"
        .. " corpses of hit zombies stay where the server saw them die")
    return
end

local MODULE = "LabVehiclePhysics"

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or command ~= "zombieLanded" then return end
    LabVehiclePhysicsNet.serverZombieLanded(player, args)
end

Events.OnClientCommand.Add(onClientCommand)
