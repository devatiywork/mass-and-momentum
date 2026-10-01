--[[
  Труп там, где упало тело, — сторона сервера.

  Рэгдолл сбитого зомби считает только клиент водителя, а труп создаёт сервер. Сервер при
  ударе отдаёт зомби водителю и откладывает смерть, а клиент водителя, когда тело легло,
  шлёт команду zombieLanded с точкой и направлением. Сервер проверяет её и создаёт труп
  там (CorpseSync.serverLanded).
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
