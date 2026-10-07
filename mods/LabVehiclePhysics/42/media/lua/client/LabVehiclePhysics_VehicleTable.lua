--[[
  Vehicle table in the sandbox options — the "Vehicle Physics: vehicles" page.

  Stock sandbox options are fixed field types: checkbox, number, list, string. A table of all
  vehicles cannot be built from them, so the data lives in a single string option
  (LabVehiclePhysics.VehicleTable: vehicle-physics.cfg lines separated by ";", one per vehicle
  the player changed), and our own panel shows it instead of the standard field.

  There are three sandbox screens, and all build pages the same way: page list on the left, panel
  on the right, option controls in the controls table keyed by option name. So once a screen is
  built, we find our page, replace its panel with ours and put our panel into controls under the
  option name: the stock settingsToUI / settingsFromUI call its setText / getText like a plain field's.
    - SandboxOptionsScreen      — world creation;
    - ISServerSandboxOptionsUI  — the admin editor mid-game (and "Sandbox" in the debug menu);
    - ServerSettingsScreen      — server settings when hosting (Page3, controls by category).
  We do not hook createPanel: the hosting screen's page class is local and out of reach.

  In singleplayer the sandbox options cannot be opened after world creation by stock means, so a
  pause menu item opens the same panel in a separate window. The table is saved with the world:
  GameWindow.save writes the sandbox options to map_sand.bin on every save.

  Values are computed in Java (VehicleTable via LabVehiclePhysicsNet) by the same code the game
  uses, so the panel shows exactly what the vehicle will get.
]]

require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISLabel"
require "ISUI/ISTickBox"
require "ISUI/ISComboBox"
require "ISUI/ISTextEntryBox"
require "ISUI/ISScrollingListBox"
require "ISUI/ISCollapsableWindow"
require "OptionScreens/SandboxOptions"
require "OptionScreens/ServerSettingsScreen"
require "OptionScreens/MainScreen"
require "ISUI/AdminPanel/ISServerSandboxOptionsUI"

LabVPTable = LabVPTable or {}

local OPTION = "LabVehiclePhysics.VehicleTable"
local FONT = UIFont.Small
local FONT_HGT = getTextManager():getFontHeight(UIFont.Small)
local FONT_HGT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local PAD = 10
local ENTRY_H = FONT_HGT + 6
local ROW_H = ENTRY_H + 6
local BTN_H = math.max(25, FONT_HGT + 8)
local ENTRY_W = 90

-- Table row fields in display order. integer: digits only in the input field.
local FIELDS = {
    { key = "mass",      label = "IGUI_LabVP_Mass",      integer = true },
    { key = "power",     label = "IGUI_LabVP_Power",     integer = true },
    { key = "maxSpeed",  label = "IGUI_LabVP_MaxSpeed",  integer = true },
    { key = "tank",      label = "IGUI_LabVP_Tank",      integer = true },
    { key = "lowGear",   label = "IGUI_LabVP_LowGear",   integer = false },
    { key = "lowGearTo", label = "IGUI_LabVP_LowGearTo", integer = false },
}
local KNOWN = { preset = true }
for _, f in ipairs(FIELDS) do
    KNOWN[f.key] = true
end

-- ============================================================== table model

--- Option string -> { [script name] = { preset=, mass=, ..., extra={ other tokens } } }.
--- Unknown tokens (live, powerMul=…, if the string was edited by hand) are kept in extra, not lost.
function LabVPTable.parse(str)
    local rows = {}
    for part in string.gmatch(str or "", "[^;]+") do
        local name, tail = string.match(part, "^%s*([^:]+):(.*)$")
        if name then
            name = string.match(name, "^(.-)%s*$")
            local row = { extra = {} }
            for token in string.gmatch(tail, "%S+") do
                local k, v = string.match(token, "^([^=]+)=(.*)$")
                if k and KNOWN[k] then
                    row[k] = v
                else
                    table.insert(row.extra, token)
                end
            end
            rows[name] = row
        end
    end
    return rows
end

--- The part after the colon: "preset=tank mass=38000". An empty string means the vehicle is unchanged.
function LabVPTable.rowTail(row)
    if not row then
        return ""
    end
    local out = {}
    if row.preset then
        table.insert(out, "preset=" .. row.preset)
    end
    for _, f in ipairs(FIELDS) do
        if row[f.key] then
            table.insert(out, f.key .. "=" .. row[f.key])
        end
    end
    for _, t in ipairs(row.extra or {}) do
        table.insert(out, t)
    end
    return table.concat(out, " ")
end

function LabVPTable.serialize(rows)
    local names = {}
    for name, row in pairs(rows) do
        if LabVPTable.rowTail(row) ~= "" then
            table.insert(names, name)
        end
    end
    table.sort(names, function(a, b) return string.lower(a) < string.lower(b) end)
    local out = {}
    for _, name in ipairs(names) do
        table.insert(out, name .. ": " .. LabVPTable.rowTail(rows[name]))
    end
    return table.concat(out, ";")
end

function LabVPTable.count(rows)
    local n = 0
    for _, row in pairs(rows) do
        if LabVPTable.rowTail(row) ~= "" then
            n = n + 1
        end
    end
    return n
end

-- ============================================================== number and label formatting

--- 45000 -> "45 000"; fractions with one decimal place.
function LabVPTable.number(v)
    if v == nil then
        return ""
    end
    if v ~= math.floor(v) then
        return string.format("%.1f", v)
    end
    local s = tostring(math.floor(v))
    local out = ""
    local len = string.len(s)
    for i = 1, len do
        out = out .. string.sub(s, i, i)
        local left = len - i
        if left > 0 and left % 3 == 0 then
            out = out .. " "
        end
    end
    return out
end

--- Number for the placeholder of an empty input field: no spaces, so it can be typed in as is.
function LabVPTable.plain(v)
    if v == nil then
        return ""
    end
    if v ~= math.floor(v) then
        return string.format("%.1f", v)
    end
    return tostring(math.floor(v))
end

function LabVPTable.presetName(id)
    if not id then
        return ""
    end
    local key = "IGUI_LabVP_Preset_" .. id
    local text = getText(key)
    if text == nil or text == key then
        return id
    end
    return text
end

function LabVPTable.displayName(name)
    local key = "IGUI_VehicleName" .. name
    local text = getText(key)
    if text == nil or text == key then
        return name
    end
    return text
end

--- What applies to a field: the layers' value or, failing that, the game's number, marked as such.
function LabVPTable.valueText(key, values, game)
    values = values or {}
    game = game or {}
    local gameMark = " (" .. getText("IGUI_LabVP_Src_game") .. ")"
    if key == "mass" then
        if values.mass then
            return LabVPTable.number(values.mass) .. " " .. getText("IGUI_LabVP_kg")
        end
        return game.mass and (LabVPTable.number(game.mass) .. " " .. getText("IGUI_LabVP_kg") .. gameMark) or "—"
    elseif key == "power" then
        if values.power then
            return LabVPTable.number(values.power) .. " " .. getText("IGUI_LabVP_hp")
        end
        if values.powerMul then
            return "×" .. tostring(values.powerMul)
        end
        -- "~", not "≈": the game fonts only have ASCII, Latin-1, Cyrillic and characters U+2000–U+2064
        return game.power and ("~" .. LabVPTable.number(game.power) .. " " .. getText("IGUI_LabVP_hp") .. gameMark) or "—"
    elseif key == "maxSpeed" then
        if values.maxSpeed then
            return LabVPTable.number(values.maxSpeed) .. " " .. getText("IGUI_LabVP_kmh")
        end
        return game.maxSpeed and (LabVPTable.number(game.maxSpeed) .. " " .. getText("IGUI_LabVP_kmh") .. gameMark) or "—"
    elseif key == "tank" then
        if values.tank then
            return LabVPTable.number(values.tank) .. " " .. getText("IGUI_LabVP_litres")
        end
        return "—" .. gameMark
    elseif key == "lowGear" then
        return values.lowGear and ("×" .. LabVPTable.number(values.lowGear)) or "—"
    elseif key == "lowGearTo" then
        return values.lowGearTo and (LabVPTable.number(values.lowGearTo) .. " " .. getText("IGUI_LabVP_kmh")) or "—"
    end
    return ""
end

--- The source label from Java ("sandbox[X] over preset[tank] over builtin[*X*]") in plain words.
function LabVPTable.chainText(source)
    if not source or source == "" then
        return getText("IGUI_LabVP_Src_game")
    end
    local parts = {}
    for token in string.gmatch(source .. " over ", "(.-) over ") do
        local tag = string.match(token, "^(%a+)")
        if tag == "preset" then
            table.insert(parts, getText("IGUI_LabVP_Src_preset", LabVPTable.presetName(string.match(token, "%[(.-)%]"))))
        elseif tag == "author" or tag == "cap" then
            table.insert(parts, getText("IGUI_LabVP_Src_" .. tag))
        elseif tag == "builtin" or tag == "cfg" or tag == "server" or tag == "sandbox" then
            table.insert(parts, getText("IGUI_LabVP_Src_" .. tag))
        else
            table.insert(parts, token)
        end
    end
    return table.concat(parts, " › ")      -- the game fonts have no "→" arrow
end

--- Substring with Lua pattern special characters escaped: a name search must not break on "(" or "-".
local function escapePattern(s)
    return (string.gsub(s, "[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0"))
end

-- ============================================================== panel

LabVPTablePanel = ISPanel:derive("LabVPTablePanel")

function LabVPTablePanel:new(x, y, width, height)
    local o = ISPanel:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.3 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    -- Fields the sandbox screens expect on a page panel: search iterates over labels,
    -- page switching uses settingNames and titles.
    o.labels = {}
    o.settingNames = {}
    o.controls = {}
    o.titles = {}
    o.joypadButtonsY = {}
    o.MAX_WIDTH = 0
    o.rows = {}
    o.raw = ""
    o.selected = nil
    o.info = nil
    o.populating = false
    o.vehicleList = {}
    o.vehicleByName = {}
    return o
end

function LabVPTablePanel:createChildren()
    ISPanel.createChildren(self)
    self.java = LabVehiclePhysicsNet ~= nil and LabVehiclePhysicsNet.tableVehicles ~= nil

    self.search = ISTextEntryBox:new("", PAD, PAD, 200, ENTRY_H)
    self.search.font = FONT
    self.search:initialise()
    self.search:instantiate()
    self.search:setClearButton(true)
    self.search:setPlaceholderText(getText("IGUI_LabVP_Search"))
    self.search.onTextChangeFunction = LabVPTablePanel.onSearchChange
    self.search.target = self
    self:addChild(self.search)

    -- List filters: 1 = only changed, 2 = only from mods. The second is on from the start:
    -- there are over 150 vanilla scripts, and a single toggle covers them all,
    -- "Vanilla vehicles", on the first page.
    self.filters = ISTickBox:new(PAD, PAD + ENTRY_H + 4, 200, ENTRY_H, "", self, LabVPTablePanel.onFilterChange)
    self.filters:initialise()
    self.filters:addOption(getText("IGUI_LabVP_OnlyChanged"))
    self.filters:addOption(getText("IGUI_LabVP_OnlyMods"))
    self.filters.selected[2] = true
    self:addChild(self.filters)

    self.list = ISScrollingListBox:new(PAD, PAD, 200, 100)
    self.list:initialise()
    self.list:instantiate()
    self.list:setFont(FONT, 3)
    self.list.drawBorder = true
    self.list.doDrawItem = LabVPTablePanel.drawVehicle
    self.list.labPanel = self
    self.list:setOnMouseDownFunction(self, LabVPTablePanel.onSelectVehicle)
    self:addChild(self.list)

    self.presetCombo = ISComboBox:new(0, 0, 220, ENTRY_H, self, LabVPTablePanel.onPresetChange)
    self.presetCombo:initialise()
    self:addChild(self.presetCombo)

    self.fieldLabels = {}
    self.entries = {}
    for i, f in ipairs(FIELDS) do
        local label = ISLabel:new(0, 0, ENTRY_H, getText(f.label), 1, 1, 1, 1, FONT, true)
        label:initialise()
        self:addChild(label)
        self.fieldLabels[i] = label
        local entry = ISTextEntryBox:new("", 0, 0, ENTRY_W, ENTRY_H)
        entry.font = FONT
        entry:initialise()
        entry:instantiate()
        entry:setOnlyNumbers(f.integer == true)
        entry.onTextChangeFunction = LabVPTablePanel.onFieldChange
        entry.target = self
        entry.labField = f.key
        self:addChild(entry)
        self.entries[i] = entry
    end

    self.resetButton = ISButton:new(0, 0, 120, BTN_H, getText("IGUI_LabVP_Reset"), self, LabVPTablePanel.onReset)
    self.resetButton:initialise()
    self.resetButton:instantiate()
    self:addChild(self.resetButton)

    self:loadData()
    self:layout()
    self:refreshList()
    self:showDetail(false)
end

--- The vehicle and preset lists come from Java, once.
function LabVPTablePanel:loadData()
    self.presetCombo:clear()
    self.presetCombo:addOptionWithData(getText("IGUI_LabVP_NoPreset"), nil)
    if not self.java then
        return
    end
    local vehicles = LabVehiclePhysicsNet.tableVehicles()
    if vehicles then
        for i = 1, #vehicles do
            local v = vehicles[i]
            local entry = {
                name = v.name,
                full = v.full,
                mod = v.mod,
                vanilla = v.vanilla == true,
                display = LabVPTable.displayName(v.name),
            }
            table.insert(self.vehicleList, entry)
            self.vehicleByName[v.name] = entry
        end
    end
    local presets = LabVehiclePhysicsNet.tablePresets()
    if presets then
        for i = 1, #presets do
            local p = presets[i]
            self.presetCombo:addOptionWithData(LabVPTable.presetName(p.id), p.id)
        end
    end
end

function LabVPTablePanel:layout()
    local w, h = self.width, self.height
    self.lastW, self.lastH = w, h
    local listW = math.floor(math.max(170, math.min(260, w * 0.32)))
    self.search:setX(PAD)
    self.search:setY(PAD)
    self.search:setWidth(listW)
    self.filters:setX(PAD)
    self.filters:setY(PAD + ENTRY_H + 4)
    local listY = self.filters:getY() + self.filters:getHeight() + 6
    self.list:setX(PAD)
    self.list:setY(listY)
    self.list:setWidth(listW)
    self.list:setHeight(math.max(50, h - listY - PAD))

    self.detailX = PAD + listW + PAD * 2
    local labelW = getTextManager():MeasureStringX(FONT, getText("IGUI_LabVP_Preset"))
    for _, label in ipairs(self.fieldLabels) do
        labelW = math.max(labelW, label:getWidth())
    end
    self.entryX = self.detailX + labelW + 10
    self.textX = self.entryX + ENTRY_W + 12

    local y = PAD + FONT_HGT_MEDIUM + FONT_HGT + 16
    self.presetY = y
    self.presetCombo:setX(self.entryX)
    self.presetCombo:setY(y)
    self.presetCombo:setWidth(math.max(120, math.min(260, w - self.entryX - PAD)))
    y = y + ROW_H + 6
    self.fieldsY = y
    for i = 1, #FIELDS do
        self.fieldLabels[i]:setX(self.detailX)
        self.fieldLabels[i]:setY(y)
        self.entries[i]:setX(self.entryX)
        self.entries[i]:setY(y)
        y = y + ROW_H
    end
    self.chainY = y + 6
    self.resetButton:setX(self.detailX)
    self.resetButton:setY(self.chainY + FONT_HGT + 10)
    self.hintY = self.resetButton:getY() + BTN_H + 10
end

--- The right-hand side is visible only when a vehicle is selected.
function LabVPTablePanel:showDetail(visible)
    self.presetCombo:setVisible(visible)
    self.resetButton:setVisible(visible)
    for i = 1, #FIELDS do
        self.fieldLabels[i]:setVisible(visible)
        self.entries[i]:setVisible(visible)
    end
end

function LabVPTablePanel:prerender()
    if self.width ~= self.lastW or self.height ~= self.lastH then
        self:layout()
    end
    ISPanel.prerender(self)
end

function LabVPTablePanel:render()
    ISPanel.render(self)
    if not self.java then
        self:drawText(getText("IGUI_LabVP_NoJava"), self.detailX, PAD, 1, 0.6, 0.6, 1, FONT)
        return
    end
    local x = self.detailX
    local changed = getText("IGUI_LabVP_Changed", tostring(LabVPTable.count(self.rows)))
    if not self.selected then
        self:drawText(getText("IGUI_LabVP_SelectVehicle"), x, PAD, 0.8, 0.8, 0.8, 1, FONT)
        self:drawText(changed, x, PAD + FONT_HGT + 6, 0.6, 0.6, 0.6, 1, FONT)
        return
    end
    local v = self.vehicleByName[self.selected]
    self:drawText(v and v.display or self.selected, x, PAD, 1, 1, 1, 1, UIFont.Medium)
    local origin = ""
    if v then
        origin = v.vanilla and getText("IGUI_LabVP_Vanilla") or getText("IGUI_LabVP_FromMod", tostring(v.mod))
    end
    self:drawText((v and v.full or self.selected) .. "  ·  " .. origin, x, PAD + FONT_HGT_MEDIUM + 2, 0.7, 0.7, 0.7, 1, FONT)
    self:drawText(getText("IGUI_LabVP_Preset"), x, self.presetY + 3, 1, 1, 1, 1, FONT)

    local info = self.info
    if info then
        local tm = getTextManager()
        for i, f in ipairs(FIELDS) do
            local y = self.fieldsY + (i - 1) * ROW_H + 3
            local result = LabVPTable.valueText(f.key, info.result, info.game)
            local base = LabVPTable.valueText(f.key, info.base, info.game)
            self:drawText(result, self.textX, y, 1, 1, 1, 1, FONT)
            if base ~= result then
                local text = getText("IGUI_LabVP_Without", base)
                local bx = self.textX + math.max(110, tm:MeasureStringX(FONT, result) + 14)
                if bx + tm:MeasureStringX(FONT, text) < self.width - PAD then
                    self:drawText(text, bx, y, 0.6, 0.6, 0.6, 1, FONT)
                end
            end
        end
        local source = info.result and info.result.source or ""
        self:drawText(getText("IGUI_LabVP_Chain", LabVPTable.chainText(source)), x, self.chainY, 0.8, 0.8, 0.8, 1, FONT)
    end
    self:drawText(getText("IGUI_LabVP_Hint"), x, self.hintY, 0.6, 0.6, 0.6, 1, FONT)
    self:drawText(changed, x, self.hintY + FONT_HGT + 6, 0.6, 0.6, 0.6, 1, FONT)
end

--- List row: the vehicle name; vehicles changed in the table are drawn in yellow.
function LabVPTablePanel.drawVehicle(list, y, item, alt)
    if not item.height then
        item.height = list.itemheight
    end
    if (y + list:getYScroll() + list.itemheight < 0) or (y + list:getYScroll() >= list.height) then
        return y + item.height
    end
    if list.selected == item.index then
        list:drawSelection(0, y, list:getWidth(), item.height - 1)
    elseif list.mouseoverselected == item.index and list:isMouseOver() and not list:isMouseOverScrollBar() then
        list:drawMouseOverHighlight(0, y, list:getWidth(), item.height - 1)
    end
    list:drawRectBorder(0, y, list:getWidth(), item.height, 0.5, list.borderColor.r, list.borderColor.g, list.borderColor.b)
    local v = item.item
    local r, g, b = 0.9, 0.9, 0.9
    if list.labPanel.rows[v.name] then
        r, g, b = 1.0, 0.85, 0.4
    end
    local padY = (item.height - list.fontHgt) / 2
    list:drawText(v.display, 10, y + padY, r, g, b, 1, list.font)
    return y + item.height
end

function LabVPTablePanel:refreshList()
    local keep = self.selected
    self.list:clear()
    local query = escapePattern(string.lower(self.search:getInternalText() or ""))
    local onlyChanged = self.filters.selected[1] == true
    local onlyMods = self.filters.selected[2] == true
    for _, v in ipairs(self.vehicleList) do
        local changed = self.rows[v.name] ~= nil
        local match = query == ""
                or string.find(string.lower(v.name), query) ~= nil
                or string.find(string.lower(v.display), query) ~= nil
        if match and (not onlyChanged or changed) and (not onlyMods or not v.vanilla) then
            self.list:addItem(v.display, v)
            if v.name == keep then
                self.list.selected = #self.list.items
            end
        end
    end
end

function LabVPTablePanel.onSearchChange(self, entry)
    self:refreshList()
end

function LabVPTablePanel:onFilterChange(index, selected)
    self:refreshList()
end

function LabVPTablePanel:onSelectVehicle(v)
    self.selected = v.name
    self:showDetail(true)
    self:populate()
end

--- Input fields and preset are filled from the selected vehicle's table row.
function LabVPTablePanel:populate()
    self.populating = true
    local row = self.rows[self.selected] or {}
    self.presetCombo.selected = 1
    if row.preset then
        self.presetCombo:selectData(row.preset)
    end
    for i, f in ipairs(FIELDS) do
        self.entries[i]:setText(row[f.key] or "")
    end
    self.populating = false
    self:refreshInfo()
end

--- Recompute the result in Java and the empty-field placeholders: what applies if a field is left alone.
function LabVPTablePanel:refreshInfo()
    self.info = nil
    if not self.selected or not self.java then
        return
    end
    self.info = LabVehiclePhysicsNet.tableDescribe(self.selected, LabVPTable.rowTail(self.rows[self.selected]))
    for i, f in ipairs(FIELDS) do
        local v = nil
        if self.info then
            v = self.info.result and self.info.result[f.key]
            if v == nil and self.info.game then
                v = self.info.game[f.key]
            end
        end
        self.entries[i]:setPlaceholderText(v and LabVPTable.plain(v) or "")
    end
end

--- Store the selected vehicle's row; an empty row removes the vehicle from the table.
function LabVPTablePanel:storeRow(row)
    if LabVPTable.rowTail(row) == "" then
        self.rows[self.selected] = nil
    else
        self.rows[self.selected] = row
    end
    self:refreshInfo()
end

function LabVPTablePanel.onFieldChange(self, entry)
    if self.populating or not self.selected then
        return
    end
    local text = entry:getInternalText() or ""
    text = string.gsub(text, ",", ".")
    text = string.match(text, "^%s*(.-)%s*$") or ""
    local row = self.rows[self.selected] or { extra = {} }
    local n = tonumber(text)
    if text == "" then
        row[entry.labField] = nil
    elseif n and n > 0 then
        row[entry.labField] = text
    else
        return      -- not a number: still being typed, change nothing
    end
    self:storeRow(row)
end

function LabVPTablePanel:onPresetChange(combo)
    if self.populating or not self.selected then
        return
    end
    local row = self.rows[self.selected] or { extra = {} }
    row.preset = combo:getOptionData(combo.selected)
    self:storeRow(row)
end

function LabVPTablePanel:onReset()
    if not self.selected then
        return
    end
    self.rows[self.selected] = nil
    self:populate()
end

-- Acting as an input field for the sandbox screen: the option string in and out.

function LabVPTablePanel:setText(str)
    self.raw = str or ""
    self.rows = LabVPTable.parse(self.raw)
    if self.list then
        self:refreshList()
        if self.selected then
            self:populate()
        end
    end
end

function LabVPTablePanel:getText()
    if not self.java then
        return self.raw      -- without Java we cannot see inside, so return it as it was
    end
    return LabVPTable.serialize(self.rows)
end

function LabVPTablePanel:settingsToUI(options)
    local option = options and options:getOptionByName(OPTION)
    if option then
        self:setText(option:getValue())
    end
end

-- ============================================================== embedding into the sandbox screens

local function ourItem(listbox)
    for _, entry in ipairs(listbox.items or {}) do
        local item = entry.item
        if item and item.page and item.page.settings then
            for _, setting in ipairs(item.page.settings) do
                if setting.name == OPTION then
                    return item
                end
            end
        end
    end
    return nil
end

--- Replace our page's panel. The current value is taken from the stock field being replaced:
--- the screen has already filled it in.
function LabVPTable.replacePanel(screen, listbox, controls, x, y, w, h)
    if not listbox or not controls then
        return nil
    end
    local item = ourItem(listbox)
    if not item then
        return nil
    end
    local old = controls[OPTION]
    local current = ""
    if old and old.getText then
        current = old:getText() or ""
    end
    local panel = LabVPTablePanel:new(x, y, w, h)
    panel:initialise()
    panel:instantiate()
    panel:setAnchorRight(true)
    panel:setAnchorBottom(true)
    panel:setText(current)
    if screen.currentPanel ~= nil and screen.currentPanel == item.panel then
        screen:removeChild(item.panel)
        screen:addChild(panel)
        screen.currentPanel = panel
    end
    item.panel = panel
    controls[OPTION] = panel
    return panel
end

local function guarded(where, fn, ...)
    local ok, err = pcall(fn, ...)
    if not ok then
        print("[LabVehiclePhysics] vehicle table page: could not attach to " .. where .. ": " .. tostring(err))
    end
end

local SP = UI_BORDER_SPACING or 10

if SandboxOptionsScreen and SandboxOptionsScreen.create then
    local original = SandboxOptionsScreen.create
    function SandboxOptionsScreen:create()
        original(self)
        local lb = self.listbox
        guarded("the new world screen", LabVPTable.replacePanel, self, lb, self.controls,
                lb:getRight() + SP, lb:getY(), self.width - lb:getRight() - SP * 2 - 1, lb:getHeight())
    end
end

if ISServerSandboxOptionsUI and ISServerSandboxOptionsUI.createChildren then
    local original = ISServerSandboxOptionsUI.createChildren
    function ISServerSandboxOptionsUI:createChildren()
        original(self)
        local lb = self.listbox
        guarded("the admin sandbox editor", LabVPTable.replacePanel, self, lb, self.controls,
                lb:getRight() + SP, self.searchEntry:getY(), self.width - lb:getRight() - SP * 2 - 1,
                lb:getHeight() + self.searchEntry:getHeight() + SP)
    end
end

if ServerSettingsScreen and ServerSettingsScreen.create then
    local original = ServerSettingsScreen.create
    function ServerSettingsScreen:create()
        original(self)
        local page = self.pageEdit
        if page and page.listbox and page.controls and page.controls["Sandbox"] then
            local lb = page.listbox
            guarded("the host settings screen", LabVPTable.replacePanel, page, lb, page.controls["Sandbox"],
                    lb:getRight() + SP, lb:getY(), page.width - lb:getRight() - SP * 2 - 1, lb:getHeight())
        end
    end
end

-- ============================================================== singleplayer: window from the pause menu

LabVPTableWindow = ISCollapsableWindow:derive("LabVPTableWindow")

function LabVPTableWindow:new(x, y, width, height)
    local o = ISCollapsableWindow:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.title = getText("IGUI_LabVP_Window")
    o:setResizable(true)
    o.minimumWidth = 620
    o.minimumHeight = 420
    return o
end

function LabVPTableWindow:createChildren()
    ISCollapsableWindow.createChildren(self)
    local th = self:titleBarHeight()
    local btnW = 110
    local btnY = self.height - BTN_H - PAD
    self.panel = LabVPTablePanel:new(0, th, self.width, btnY - PAD - th)
    self.panel:initialise()
    self.panel:instantiate()
    self.panel:setAnchorRight(true)
    self.panel:setAnchorBottom(true)
    self:addChild(self.panel)
    local option = getSandboxOptions():getOptionByName(OPTION)
    self.panel:setText(option and option:getValue() or "")

    self.closeBtn = ISButton:new(self.width - PAD - btnW, btnY, btnW, BTN_H, getText("IGUI_LabVP_Close"), self, LabVPTableWindow.onCloseButton)
    self.closeBtn:initialise()
    self.closeBtn:instantiate()
    self.closeBtn:setAnchorLeft(false)
    self.closeBtn:setAnchorRight(true)
    self.closeBtn:setAnchorTop(false)
    self.closeBtn:setAnchorBottom(true)
    self:addChild(self.closeBtn)

    self.applyBtn = ISButton:new(self.width - PAD * 2 - btnW * 2, btnY, btnW, BTN_H, getText("IGUI_LabVP_Apply"), self, LabVPTableWindow.onApply)
    self.applyBtn:initialise()
    self.applyBtn:instantiate()
    self.applyBtn:setAnchorLeft(false)
    self.applyBtn:setAnchorRight(true)
    self.applyBtn:setAnchorTop(false)
    self.applyBtn:setAnchorBottom(true)
    self:addChild(self.applyBtn)
end

--- Write the table to the world's sandbox options. Java picks it up within a second, and it
--- reaches the save file with the next world save.
function LabVPTableWindow:onApply()
    local options = getSandboxOptions()
    if options:getOptionByName(OPTION) then
        options:set(OPTION, self.panel:getText())
        options:toLua()
    end
end

function LabVPTableWindow:onCloseButton()
    self:close()
end

function LabVPTableWindow:close()
    self:setVisible(false)
    self:removeFromUIManager()
    LabVPTableWindow.instance = nil
end

function LabVPTable.openWindow()
    if LabVPTableWindow.instance then
        LabVPTableWindow.instance:close()
    end
    local w = math.min(900, getCore():getScreenWidth() - 100)
    local h = math.min(640, getCore():getScreenHeight() - 100)
    local window = LabVPTableWindow:new((getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h)
    window:initialise()
    window:addToUIManager()
    window:setAlwaysOnTop(true)
    LabVPTableWindow.instance = window
end

--- The "Vehicle physics" item in the singleplayer pause menu, right below "Options".
function LabVPTable.addPauseMenuItem(screen)
    local options = screen.optionsOption
    if not options or not screen.bottomPanel then
        return
    end
    local hgt = options:getHeight()
    local item = ISLabel:new(options:getX(), options:getY() + hgt, hgt, getText("IGUI_LabVP_Menu"), 1, 1, 1, 1, UIFont.Large, true)
    item.internal = "LABVP_TABLE"
    item:initialise()
    item.onMouseDown = function(_item, x, y)
        getSoundManager():playUISound("UIActivateMainMenuItem")
        LabVPTable.openWindow()
    end
    -- hover highlight, same as the other items
    item.fade = UITransition.new()
    item.fade:setFadeIn(false)
    item.prerender = MainScreen.prerenderBottomPanelLabel
    for _, child in pairs(screen.bottomPanel:getChildren()) do
        if child:getY() > options:getY() then
            child:setY(child:getY() + hgt)
        end
    end
    screen.bottomPanel:addChild(item)
    item:setWidth(options:getWidth())
    screen.bottomPanel:setHeight(screen.bottomPanel:getHeight() + hgt)
end

if MainScreen and MainScreen.instantiate then
    local original = MainScreen.instantiate
    function MainScreen:instantiate()
        original(self)
        if self.inGame and not isClient() then
            guarded("the pause menu", LabVPTable.addPauseMenuItem, self)
        end
    end
end
