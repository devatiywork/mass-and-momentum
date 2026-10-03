--[[
  Military Tool Kit turrets in a 3D view (Viewpoint).

  Military Tool Kit (Papa_Chad) aims a turret by its gunner's facing. While the right mouse button
  is held, Papa_Chad_Tank_Cannon.lua turns the turret towards player:getDirectionAngle() at the
  weapon's traverse speed, and the shot leaves along the turret's angle (the global turretAngles).
  The same code serves the tanks and the M2 on the Humvee. In a vehicle the game takes that facing
  from the mouse with isometric screen maths, and so do Military Tool Kit's sight (TankCursor) and
  tracers. In Viewpoint's 3D view that points nowhere: in free look the mouse is held still and the
  turret swings to one fixed side whatever the camera looks at (always 45 degrees in our tests), and
  with the cursor out the facing misses the cursor by up to a hundred degrees.

  Where the gunner aims in a 3D view:
    - with the cursor out, at the world point under it: Viewpoint.Mouse.worldX and worldY, the public
      calls Viewpoint's own Lua uses for the mouse. They give nothing in free look and in the
      isometric view;
    - in free look, along the camera, if the look angle carries it. A 3D view that turns the look
      angle after its camera is recognised by the angle differing from the game's own formula (see
      ViewAngle.java). Viewpoint 0.1.5a does not do that while the player is seated and gives no
      other sign of its camera then, so in free look the turret keeps Military Tool Kit's own
      behaviour until Viewpoint offers the camera's heading.

  For the player in a turret's gun seat this file then:
    1. faces the gunner that way just before Military Tool Kit reads the facing, and restores the
       facing right after. In IsoPlayer.update the OnPlayerUpdate event comes before the in-vehicle
       aiming that sets the facing, so Military Tool Kit reads the previous frame's facing; ours
       stands in for that one call only;
    2. hides Military Tool Kit's isometric sight and tracers while the 3D view can be told;
    3. draws a gunner's sight there: brackets round the aim point, yellow while the turret is still
       traversing and green once it is on it, a traverse scale under the screen centre, and a small
       top view of the hull and the barrel.

  Nothing of Military Tool Kit or Viewpoint is changed on disk: their public Lua globals are wrapped
  at runtime, and only when both are loaded.
]]

if isServer() then return end

-- Military Tool Kit's seat table (a local of Papa_Chad_Tank_Cannon.lua): seat index -> seat part.
local SEATS = { [0] = "SeatFrontLeft", [1] = "SeatGunner", [2] = "SeatFrontRight", [3] = "SeatRearRight" }

local VIEW_GRACE_MS = 1500     -- the 3D view stays on this long after its last sign
local MIN_AIM_DIST = 4.0       -- squares; a nearer cursor point is our own hull or turret, not a target
local LOOK_EPSILON_DEG = 0.01  -- a look angle this far from the game's own comes from the camera
local ON_TARGET_DEG = 1.0      -- the turret is on the aim within this angle
local SCALE_DEG = 90           -- the traverse scale spans this many degrees to each side
local LOG_EVERY_MS = 1000      -- the lab's instrument line, while in a gun seat

local YELLOW = { 1.0, 0.82, 0.2, 1.0 }
local GREEN = { 0.35, 1.0, 0.45, 1.0 }
local GREY = { 0.85, 0.85, 0.85, 1.0 }
local DIM = { 0.85, 0.85, 0.85, 0.55 }

local installed = false
local broken = false
local verbose = false
local view3D = false
local lastSignMs = 0
local lastLogMs = 0
local oddLogged = false
local mtkAim = nil
local forward = nil
-- Vehicle id -> the turret's rotation on the hull by Military Tool Kit's measure; see track().
local rotation = {}

local function log(text)
    print("[LabVehiclePhysics] turret sight: " .. text)
end

-- Runs one of our steps. The first error switches the sight off for the session, and Military Tool
-- Kit goes on as it would without it.
local function guarded(what, f, ...)
    if broken then return nil end
    local ok, a, b = pcall(f, ...)
    if ok then return a, b end
    broken = true
    view3D = false
    log("ERROR in " .. what .. ", switched off until the game is reloaded: " .. tostring(a))
    return nil
end

local function wrap180(a)
    return (a + 540) % 360 - 180
end

-- Angle of a world direction in degrees: the measure of getDirectionAngle() and of turretAngles.
-- It grows clockwise as seen from above, so a larger angle lies to the right.
local function bearing(dx, dy)
    return math.deg(math.atan2(dy, dx))
end

-- The world point under the mouse cursor in a 3D view, or nil (free look, the isometric view).
-- Each call is guarded: a Viewpoint that changed these calls must not take turret aiming down with it.
local function cursorPoint()
    local m = Viewpoint and Viewpoint.Mouse
    if not m or not m.worldX or not m.worldY then return nil end
    local okX, wx = pcall(m.worldX)
    local okY, wy = pcall(m.worldY)
    if not (okX and okY) or wx == nil or wy == nil then return nil end
    if type(wx) ~= "number" or type(wy) ~= "number" then
        if verbose and not oddLogged then
            oddLogged = true
            log("Viewpoint.Mouse.worldX/worldY gave " .. type(wx) .. "/" .. type(wy) .. ", not numbers")
        end
        return nil
    end
    lastSignMs = getTimestampMs()
    return wx, wy
end

-- The camera's heading in a 3D view, or nil while the look angle is the game's own.
local function cameraAngle(player)
    local net = LabVehiclePhysicsNet
    if not net or not net.lookAngle or not net.gameLookAngle then return nil end
    local okL, look = pcall(net.lookAngle, player)
    local okG, game = pcall(net.gameLookAngle, player)
    if not (okL and okG) or type(look) ~= "number" or type(game) ~= "number" then return nil end
    if look ~= look or game ~= game then return nil end   -- NaN: the Java side could not read them
    if math.abs(wrap180(look - game)) < LOOK_EPSILON_DEG then return nil end
    lastSignMs = getTimestampMs()
    return look
end

-- Where the gunner aims in a 3D view: the angle and "cursor" or "camera", or nil.
local function aimAngle(player)
    local wx, wy = cursorPoint()
    if wx then
        local dx, dy = wx - player:getX(), wy - player:getY()
        if dx * dx + dy * dy >= MIN_AIM_DIST * MIN_AIM_DIST then
            return bearing(dx, dy), "cursor"
        end
    end
    local camera = cameraAngle(player)
    if camera then return camera, "camera" end
    return nil
end

local function updateView()
    local player = getPlayer()
    if player and player:getVehicle() then
        cursorPoint()
        cameraAngle(player)
    end
    local on = lastSignMs > 0 and getTimestampMs() - lastSignMs <= VIEW_GRACE_MS
    if on == view3D then return end
    view3D = on
    if on and VisualProjectilePC then
        VisualProjectilePC.list = {}    -- tracers placed for the isometric view
    end
    if verbose then
        log(on and "3D view on" or "3D view off: no cursor point and no camera look for " .. VIEW_GRACE_MS .. " ms")
    end
end

-- Military Tool Kit's weapon for the player's seat, or nil when they do not man a turret.
local function turretWeapon(player, vehicle)
    if not vehicle or not vehicle:getPartById("Turrent") then return nil end
    local weapon = chooseWeaponPC(vehicle)
    if not weapon or SEATS[vehicle:getSeat(player)] ~= weapon.requiredSeat then return nil end
    return weapon
end

-- Faces the gunner to the aim for Military Tool Kit's turn of the turret.
-- Returns the facing to restore and the angle set, or nil when nothing was changed.
local function faceAim(player)
    if not isMouseButtonDown(1) then return nil end
    if not turretWeapon(player, player:getVehicle()) then return nil end
    local target = aimAngle(player)
    if target == nil then return nil end
    local before = player:getDirectionAngle()
    player:setDirectionAngle(target)
    return before, target
end

local function hullRotation(vehicle)
    return calculateVehicleRotation(vehicle:getAngleX(), vehicle:getAngleY(), vehicle:getAngleZ())
end

-- After Military Tool Kit has turned the turret: keep its rotation on the hull. Military Tool Kit
-- updates turretAngles only while the gunner aims, as hull rotation minus turret rotation, so with
-- the rotation kept the sight follows the turret when the hull turns under it.
local function track(player)
    if not isMouseButtonDown(1) then return end
    local vehicle = player:getVehicle()
    if not turretWeapon(player, vehicle) or not player:getCharacterActions():isEmpty() then return end
    local world = turretAngles[vehicle:getId()]
    if world ~= nil then
        rotation[vehicle:getId()] = (hullRotation(vehicle) - world + 720) % 360
    end
end

-- The turret's world angle now, or nil before the gunner has aimed in this vehicle.
local function turretAngle(vehicle)
    local rot = rotation[vehicle:getId()]
    if rot == nil then return nil end
    return (hullRotation(vehicle) - rot + 720) % 360
end

-- The hull's world angle from its forward vector, the way the game faces a seated player.
local function hullAngle(vehicle)
    forward = forward or Vector3f.new()
    vehicle:getForwardVector(forward)
    return bearing(forward:x(), forward:z())
end

-- A look angle from the Java side as text, "?" when it could not be read.
local function netAngle(name, player)
    local net = LabVehiclePhysicsNet
    if not net or not net[name] then return "?" end
    local ok, v = pcall(net[name], player)
    if not ok or type(v) ~= "number" or v ~= v then return "?" end
    return string.format("%.2f", v)
end

-- Lab build only: once a second in a gun seat, what each side sees.
local function instrument(player, before, target)
    if not verbose then return end
    local now = getTimestampMs()
    if now - lastLogMs < LOG_EVERY_MS then return end
    local vehicle = player:getVehicle()
    local weapon = turretWeapon(player, vehicle)
    if not weapon then return end
    lastLogMs = now
    local cursor = "none"
    local wx, wy = cursorPoint()
    if wx then
        local dx, dy = wx - player:getX(), wy - player:getY()
        cursor = string.format("%.1f,%.1f at %.1f, angle %.1f", wx, wy, math.sqrt(dx * dx + dy * dy), bearing(dx, dy))
    end
    local _, source = aimAngle(player)
    local turret = turretAngles[vehicle:getId()]
    log(string.format("%s %s, %s view, aim by %s, right button %s, facing %.1f, ours %s, cursor %s,"
        .. " look %s, game look %s, mouse %d,%d, turret %s, hull %.1f",
        tostring(vehicle:getScriptName()), tostring(weapon.requiredPart), view3D and "3D" or "isometric",
        source or "-", tostring(isMouseButtonDown(1)), before or player:getDirectionAngle(),
        target and string.format("%.1f", target) or "-", cursor,
        netAngle("lookAngle", player), netAngle("gameLookAngle", player), getMouseX(), getMouseY(),
        turret and string.format("%.1f", turret) or "not aimed yet", hullAngle(vehicle)))
end

-- Stands in for Military Tool Kit's own OnPlayerUpdate handler: our facing, its turn, our record.
local function aim(player)
    local before, target = guarded("the aiming", faceAim, player)
    local ok, err = pcall(mtkAim, player)
    if before then player:setDirectionAngle(before) end
    if not ok then error(err, 0) end
    guarded("the turret record", track, player)
    if verbose then
        -- The lab's instrument must not take the sight down with it.
        local okI, errI = pcall(instrument, player, before, target)
        if not okI then
            verbose = false
            log("instrument line off after an error: " .. tostring(errI))
        end
    end
end

local function line(r, x1, y1, x2, y2, w, cr, cg, cb, ca)
    local dx, dy = x2 - x1, y2 - y1
    local len = math.sqrt(dx * dx + dy * dy)
    if len < 0.01 then return end
    local nx, ny = -dy / len * w / 2, dx / len * w / 2
    r:renderPoly(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, cr, cg, cb, ca)
end

-- A line with a dark edge, readable against sky and snow alike.
local function stroke(r, x1, y1, x2, y2, w, c)
    line(r, x1, y1, x2, y2, w + 2, 0, 0, 0, 0.45 * c[4])
    line(r, x1, y1, x2, y2, w, c[1], c[2], c[3], c[4])
end

-- Screen direction of an angle measured clockwise from straight up.
local function screenDir(degrees)
    local a = math.rad(degrees)
    return math.sin(a), -math.cos(a)
end

local function draw()
    if not view3D then return end
    local player = getPlayer()
    local vehicle = player and player:getVehicle()
    if not vehicle or not turretWeapon(player, vehicle) then return end

    local view, source = aimAngle(player)
    local turret = turretAngle(vehicle)
    local hull = hullAngle(vehicle)
    local delta = nil
    local color = GREY
    if view ~= nil and turret ~= nil then
        delta = wrap180(turret - view)
        color = math.abs(delta) <= ON_TARGET_DEG and GREEN or YELLOW
    end

    local r = getRenderer()
    local w, h = getCore():getScreenWidth(), getCore():getScreenHeight()
    local s = math.max(0.75, h / 1080)
    local cx, cy = w / 2, h / 2

    -- Brackets round the aim point: the cursor when it is out, the screen centre in free look.
    local ax, ay = cx, cy
    if source == "cursor" then ax, ay = getMouseX(), getMouseY() end
    local b, arm, t = 22 * s, 8 * s, 2 * s
    for _, sx in ipairs({ -1, 1 }) do
        for _, sy in ipairs({ -1, 1 }) do
            local x, y = ax + sx * b, ay + sy * b
            stroke(r, x, y, x - sx * arm, y, t, color)
            stroke(r, x, y, x, y - sy * arm, t, color)
        end
    end

    -- Traverse scale: how far the barrel is to the side of the aim, right being clockwise.
    local ly, half = cy + 46 * s, 110 * s
    stroke(r, cx - half, ly, cx + half, ly, s, DIM)
    for _, f in ipairs({ -1, -0.5, 0, 0.5, 1 }) do
        local tick = (f == 0) and 7 * s or 4 * s
        stroke(r, cx + f * half, ly - tick, cx + f * half, ly + tick, s, DIM)
    end
    if delta ~= nil then
        local mx = cx + math.max(-1, math.min(1, delta / SCALE_DEG)) * half
        stroke(r, mx, ly - 9 * s, mx, ly + 9 * s, 3 * s, color)
        if math.abs(delta) > ON_TARGET_DEG then
            getTextManager():DrawStringCentre(UIFont.Small, mx, ly + 11 * s,
                tostring(math.floor(math.abs(delta) + 0.5)), color[1], color[2], color[3], 1)
        end
    end

    -- Top view: the hull and the barrel, with the aim up (the hull up while the aim is unknown).
    local ref = view or hull
    local ox, oy = cx, ly + 64 * s
    local fx, fy = screenDir(hull - ref)
    local rx, ry = -fy, fx
    local hl, hw = 15 * s, 9 * s
    local frx, fry = ox + fx * hl + rx * hw, oy + fy * hl + ry * hw
    local flx, fly = ox + fx * hl - rx * hw, oy + fy * hl - ry * hw
    local blx, bly = ox - fx * hl - rx * hw, oy - fy * hl - ry * hw
    local brx, bry = ox - fx * hl + rx * hw, oy - fy * hl + ry * hw
    stroke(r, flx, fly, frx, fry, 2 * s, GREY)
    stroke(r, frx, fry, brx, bry, s, DIM)
    stroke(r, brx, bry, blx, bly, s, DIM)
    stroke(r, blx, bly, flx, fly, s, DIM)
    if turret ~= nil then
        local tx, ty = screenDir(turret - ref)
        stroke(r, ox, oy, ox + tx * 22 * s, oy + ty * 22 * s, 3 * s, color)
    end
    if view ~= nil then
        stroke(r, ox, oy - 27 * s, ox, oy - 33 * s, 2 * s, GREY)
    end
end

-- A table's keys, to see what a Viewpoint version offers to Lua.
local function keys(t)
    local list = {}
    local ok = pcall(function()
        for k in pairs(t) do list[#list + 1] = tostring(k) end
    end)
    if not ok then return "(not listable)" end
    table.sort(list)
    return table.concat(list, ", ")
end

local function install()
    if installed then return end
    installed = true
    if not (Viewpoint and Viewpoint.Mouse) then return end
    if type(tankAimSystemPC) ~= "function" or type(turretAngles) ~= "table"
        or type(chooseWeaponPC) ~= "function" or type(calculateVehicleRotation) ~= "function" then
        return
    end
    if LabVehiclePhysicsNet and LabVehiclePhysicsNet.verbose then
        local ok, on = pcall(LabVehiclePhysicsNet.verbose)
        verbose = ok and on == true
    end

    -- Military Tool Kit registered this very function; ours calls it in its place.
    mtkAim = tankAimSystemPC
    Events.OnPlayerUpdate.Remove(mtkAim)
    Events.OnPlayerUpdate.Add(aim)

    if TankCursor and TankCursor.prerender then
        local prerender = TankCursor.prerender
        TankCursor.prerender = function(self)
            if view3D then return end
            return prerender(self)
        end
    end
    if VisualProjectilePC and VisualProjectilePC.addFromWeapon then
        local add = VisualProjectilePC.addFromWeapon
        VisualProjectilePC.addFromWeapon = function(self, ...)
            if view3D then return end
            return add(self, ...)
        end
    end

    Events.OnTick.Add(function() guarded("the view check", updateView) end)
    Events.OnPreUIDraw.Add(function() guarded("the sight", draw) end)
    log("on: in Viewpoint's 3D view Military Tool Kit turrets aim where you look and get a sight")
    if verbose then
        log("Viewpoint offers to Lua: " .. keys(Viewpoint) .. "; Viewpoint.Mouse: " .. keys(Viewpoint.Mouse))
    end
end

Events.OnGameStart.Add(install)
