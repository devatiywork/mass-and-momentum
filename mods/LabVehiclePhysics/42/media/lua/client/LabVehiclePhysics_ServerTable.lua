--[[
  Серверная таблица машин — сторона клиента.

  В сети физику машин задаёт файл сервера: у кооп-хоста это файл хоста, у выделенного
  сервера — файл админа. Свой vehicle-physics.cfg клиент в сети не читает вообще, иначе
  одна машина весила бы у разных игроков по-разному: считает её клиент водителя.

  Здесь только почта. Работа — в Java (ServerTable), до неё достаём через
  LabVehiclePhysicsNet, который открывает для Lua ZombieBuddy.

  Почему просим из OnTick, а не из OnGameStart. sendClientCommand уходит в сеть только
  при GameClient.ingame, а флаг ставится в IngameState.UpdateStuff() — уже после
  OnGameStart. Раньше этого команда молча уходит по пути одиночной игры и до сервера не
  доходит. Поэтому ждём clientReady() и повторяем, пока не ответят.
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
    -- Приходит и в ответ на запрос, и рассылкой, когда на сервере поправили файл.
    received = true
    LabVehiclePhysicsNet.receiveServerTable(args)
end

Events.OnTick.Add(onTick)
Events.OnServerCommand.Add(onServerCommand)
