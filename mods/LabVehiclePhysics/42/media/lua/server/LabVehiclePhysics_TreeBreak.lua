--[[
  Tree felling by vehicles — the server side.

  The driver's client detects the impact: only it has vehicle physics. But only the server can
  remove a tree from the world: IsoTree.toppleTree starts with if (!GameClient.client). So the
  client sends a treeHit command with the tree coordinates and impact energy, and the server itself
  checks whether that is plausible and decides by its own tree health data (TreeBreak.serverTreeHit).
]]

if not isServer() then return end

if not LabVehiclePhysicsNet then
    print("[LabVehiclePhysics] tree breaking: the Java side (LabVehiclePhysicsNet) is not available -"
        .. " vehicles will not break trees on this server")
    return
end

local MODULE = "LabVehiclePhysics"

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or command ~= "treeHit" then return end
    LabVehiclePhysicsNet.serverTreeHit(player, args)
end

Events.OnClientCommand.Add(onClientCommand)
