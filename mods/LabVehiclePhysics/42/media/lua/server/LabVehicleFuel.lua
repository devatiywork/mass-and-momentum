--[[
  Расход топлива по массе — починка ванильной формулы.

  Ваниль (media/lua/server/Vehicles/Vehicles.lua, Vehicles.Update.GasTank):

      massMultiplier = (math.abs(1000 - vehicle:getScript():getMass()) / 300) + 1
      gasMultiplier  = gasMultiplier / qualityMultiplier / massMultiplier
      newAmount      = (speedMultiplier / gasMultiplier) * SandboxVars.CarGasConsumption
      amount         = amount - elapsedMinutes * newAmount

  Формула странная сама по себе: точка отсчёта ровно 1000 кг, и стоит модуль, поэтому
  двухсоткилограммовый прицеп по ней «ест» больше легковушки. В ванили это не вылезало,
  потому что весь автопарк был втиснут в 650..1160 кг. Как только машины получили
  настоящие массы, множитель для 11 400 кг стал 35.7 вместо 1.0 — расход вырос в 35 раз.

  В жизни зависимость гораздо слабее: Bushmaster ест около 40 л/100 км против 10 у седана,
  то есть вчетверо при восьмикратной разнице масс. Это примерно масса^0.55.

  Саму функцию не переписываем — она может поменяться с обновлением игры. Вместо этого
  зовём оригинал с поправленным elapsedMinutes: расход в ней линеен по времени, так что
  умножение времени на отношение множителей даёт ровно нужный итог.

  Переключатель песочницы «Расход топлива по массе» выключен — расход как в игре: та же
  ванильная формула, но от массы машины до нашего справочника. Просто не трогать функцию
  нельзя: от настоящей массы та же формула дала бы танку расход в 170 раз больше.
]]

-- Страховка: если таблицы почему-то нет, молча ничего не делаем, а не роняем загрузку.
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

-- Спрашиваем Java (LabSettings), как и все остальные переключатели: SandboxVars после правки
-- из отладочного меню одиночной игры не обновляется до перезахода. Без Java — SandboxVars,
-- а опции нет вовсе — включено, как по умолчанию.
local function fuelByMass()
    if LabVehiclePhysicsNet and LabVehiclePhysicsNet.fuelByMass then
        return LabVehiclePhysicsNet.fuelByMass()
    end
    local vars = SandboxVars and SandboxVars.LabVehiclePhysics
    return not vars or vars.FuelByMass ~= false
end

-- Масса скрипта до нашего справочника; не знаем — текущая.
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
