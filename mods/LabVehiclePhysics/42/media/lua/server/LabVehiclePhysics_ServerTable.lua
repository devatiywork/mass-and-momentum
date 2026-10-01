--[[
  Server vehicle table — the server side.

  Sends the server's own vehicle-physics.cfg to clients: on request when a player joins, and to
  everyone at once when the file changes. On a co-op host the server is a separate process but
  shares the Zomboid folder, so it is the host's file; on a dedicated server, the file in its folder.

  The file is checked once per game minute: the server has no other periodic event, and
  OnTick does not fire there (only IngameState calls it). With a one-hour day a game minute
  is two and a half seconds; with longer days it is longer.
]]

if not isServer() then return end

if not LabVehiclePhysicsNet then
    print("[LabVehiclePhysics] server table: the Java side (LabVehiclePhysicsNet) is not available -"
        .. " players will not receive this server's vehicle table")
    return
end

local MODULE = "LabVehiclePhysics"
local lastStamp = nil

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or command ~= "requestTable" then return end
    local tbl = LabVehiclePhysicsNet.serverTable()
    if not tbl then return end
    sendServerCommand(player, MODULE, "table", tbl)
    local who = player and player:getUsername() or "?"
    print("[LabVehiclePhysics] server table: " .. tostring(tbl.count) .. " rule line(s), version "
        .. tostring(tbl.stamp) .. ", sent to " .. tostring(who))
end

local function onEveryOneMinute()
    local stamp = LabVehiclePhysicsNet.serverStamp()
    if lastStamp == nil then
        -- The first check after startup only remembers the version: nobody is connected yet,
        -- and whoever connects will ask on their own.
        lastStamp = stamp
        return
    end
    if stamp == lastStamp then return end
    lastStamp = stamp
    local tbl = LabVehiclePhysicsNet.serverTable()
    if not tbl then return end
    sendServerCommand(MODULE, "table", tbl)
    print("[LabVehiclePhysics] server table: vehicle-physics.cfg changed - " .. tostring(tbl.count)
        .. " rule line(s), version " .. tostring(tbl.stamp) .. ", sent to all players")
end

Events.OnClientCommand.Add(onClientCommand)
Events.EveryOneMinute.Add(onEveryOneMinute)
