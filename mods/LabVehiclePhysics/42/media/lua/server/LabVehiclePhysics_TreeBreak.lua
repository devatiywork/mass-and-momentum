--[[
  Повал деревьев машиной — сторона сервера.

  Удар замечает клиент водителя: физика машин есть только у него. А убрать дерево из мира
  может только сервер — IsoTree.toppleTree начинается с if (!GameClient.client). Поэтому
  клиент шлёт команду treeHit с координатами дерева и энергией удара, а сервер сам проверяет,
  правдоподобно ли это, и решает по своему здоровью дерева (TreeBreak.serverTreeHit).
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
