--[[
  Server vehicle table — the client side.

  In multiplayer, vehicle physics comes from the server's file: the host's file on a co-op host,
  the admin's file on a dedicated server. The client never reads its own vehicle-physics.cfg in
  multiplayer, or one vehicle would weigh differently per player (the driver's client simulates it).

  Only the messaging lives here. The work is done in Java (ServerTable), reached through
  LabVehiclePhysicsNet, which ZombieBuddy exposes to Lua.

  Why we request from OnTick and not from OnGameStart: sendClientCommand goes over the network
  only when GameClient.ingame is set, and that flag is set in IngameState.UpdateStuff(), after
  OnGameStart. Before that the command silently takes the singleplayer path and never reaches
  the server. So we wait for clientReady() and repeat until the server answers.
]]

if not isClient() then return end

if not LabVehiclePhysicsNet then
    print("[LabVehiclePhysics] server table: the Java side (LabVehiclePhysicsNet) is not available -"
        .. " vehicles use built-in and mod author data only, the local vehicle-physics.cfg stays ignored")
    return
end

local MODULE = "LabVehiclePhysics"
local RETRY_MS = 5000
local MAX_ATTEMPTS = 6

local received = false
local gaveUp = false
local attempts = 0
local nextAt = 0

local function onTick()
    if received or gaveUp then return end
    local now = getTimestampMs()
    if now < nextAt then return end
    if attempts >= MAX_ATTEMPTS then
        gaveUp = true
        print("[LabVehiclePhysics] server table: no answer to " .. attempts .. " requests - the server may run"
            .. " an older LabVehiclePhysics without server tables. Vehicles use built-in and mod author data,"
            .. " the local vehicle-physics.cfg stays ignored")
        return
    end
    if not LabVehiclePhysicsNet.clientReady() then return end
    local player = getPlayer()
    if not player then return end
    attempts = attempts + 1
    nextAt = now + RETRY_MS
    sendClientCommand(player, MODULE, "requestTable", {})
end

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= "table" then return end
    -- Arrives both as a reply to the request and as a broadcast when the file is edited on the server.
    received = true
    LabVehiclePhysicsNet.receiveServerTable(args)
end

Events.OnTick.Add(onTick)
Events.OnServerCommand.Add(onServerCommand)
