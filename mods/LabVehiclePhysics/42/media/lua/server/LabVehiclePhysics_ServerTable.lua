--[[
  Серверная таблица машин — сторона сервера.

  Отдаёт клиентам свой vehicle-physics.cfg: по запросу при входе в игру и всем сразу,
  когда файл поменяли. У кооп-хоста сервер — отдельный процесс, но с той же папкой
  Zomboid, так что это файл хоста; у выделенного сервера — файл в папке сервера.

  Файл проверяем раз в игровую минуту: другого периодического события на сервере нет,
  OnTick там не срабатывает (его зовёт только IngameState). При сутках длиной в час
  игровая минута — две с половиной секунды, при более длинных — дольше.
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
        -- Первая проверка после запуска только запоминает версию: подключённых ещё нет,
        -- а кто подключится, спросит сам.
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
