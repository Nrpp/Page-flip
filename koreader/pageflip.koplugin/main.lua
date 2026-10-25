--[[--
Page Flip: mando remoto para pasar páginas desde un móvil Android.

El plugin abre un pequeño servidor TCP en el lector. La app Android se conecta
y envía una orden por línea:

    NEXT   -> página siguiente      (responde "OK")
    PREV   -> página anterior       (responde "OK")
    PING   -> mantener la conexión  (responde "PONG")
    HELLO  -> identificación        (responde "PAGEFLIP <versión>")

Para hacer pruebas desde un navegador también acepta peticiones HTTP sencillas:
    http://<ip-del-kindle>:8134/next   y   http://<ip-del-kindle>:8134/prev

Además escucha en UDP (puerto 8135) para que la app pueda encontrar el Kindle
en la red automáticamente.
--]]--

local Device = require("device")
local Event = require("ui/event")
local InfoMessage = require("ui/widget/infomessage")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local logger = require("logger")
local socket = require("socket")
local _ = require("gettext")
local T = require("ffi/util").template

local PROTOCOL_VERSION = "1"
local DEFAULT_PORT = 8134
local DISCOVERY_PORT = 8135
local POLL_INTERVAL = 0.05 -- segundos
local MAX_LINE = 4096

local PageFlip = WidgetContainer:extend{
    name = "pageflip",
    is_doc_only = true,
}

function PageFlip:init()
    self.port = G_reader_settings:readSetting("pageflip_port") or DEFAULT_PORT
    self.ui.menu:registerToMainMenu(self)
    if G_reader_settings:isTrue("pageflip_enabled") then
        UIManager:nextTick(function() self:startServer() end)
    end
end

local function getLocalIp()
    -- Truco habitual: un socket UDP "conectado" no envía nada, pero nos dice
    -- qué dirección local usaría para salir a la red.
    local ok, ip = pcall(function()
        local udp = socket.udp()
        udp:setpeername("8.8.8.8", 80)
        local addr = udp:getsockname()
        udp:close()
        return addr
    end)
    if ok and ip and ip ~= "0.0.0.0" then
        return ip
    end
end

function PageFlip:firewall(action)
    -- En Kindle el cortafuegos bloquea las conexiones entrantes por defecto.
    if not Device:isKindle() then return end
    local rules = {
        { "INPUT", "tcp", "--dport", self.port, "NEW,ESTABLISHED" },
        { "OUTPUT", "tcp", "--sport", self.port, "ESTABLISHED" },
        { "INPUT", "udp", "--dport", DISCOVERY_PORT, "NEW,ESTABLISHED" },
        { "OUTPUT", "udp", "--sport", DISCOVERY_PORT, "NEW,ESTABLISHED" },
    }
    for _, r in ipairs(rules) do
        os.execute(string.format(
            "iptables %s %s -p %s %s %d -m conntrack --ctstate %s -j ACCEPT 2>/dev/null",
            action, r[1], r[2], r[3], r[4], r[5]))
    end
end

function PageFlip:startServer()
    if self.server then return true end
    local server, err = socket.bind("*", self.port)
    if not server then
        logger.warn("PageFlip: no se pudo abrir el puerto", self.port, err)
        return false, err
    end
    server:settimeout(0)
    self.server = server
    self.clients = {}

    local udp = socket.udp()
    if udp and udp:setsockname("*", DISCOVERY_PORT) then
        udp:settimeout(0)
        self.udp = udp
    else
        logger.warn("PageFlip: descubrimiento UDP no disponible")
        if udp then udp:close() end
    end

    self:firewall("-A")
    self.poll_task = function() self:poll() end
    UIManager:scheduleIn(POLL_INTERVAL, self.poll_task)
    logger.info("PageFlip: escuchando en el puerto", self.port)
    return true
end

function PageFlip:stopServer()
    if not self.server then return end
    UIManager:unschedule(self.poll_task)
    for _, c in ipairs(self.clients) do
        c.sock:close()
    end
    self.clients = nil
    self.server:close()
    self.server = nil
    if self.udp then
        self.udp:close()
        self.udp = nil
    end
    self:firewall("-D")
    logger.info("PageFlip: servidor detenido")
end

function PageFlip:onCloseWidget()
    self:stopServer()
end

function PageFlip:onSuspend()
    -- Al dormir el Kindle se apaga el wifi: cerramos las conexiones para que
    -- la app se reconecte limpiamente al despertar.
    if not self.clients then return end
    for _, c in ipairs(self.clients) do
        c.sock:close()
    end
    self.clients = {}
end

function PageFlip:turnPage(diff)
    if not self.ui.document then return end
    self.ui:handleEvent(Event:new("GotoViewRel", diff))
    -- Que cuente como actividad del usuario para el autoapagado de KOReader.
    pcall(function() UIManager.event_hook:execute("InputEvent") end)
end

function PageFlip:handleCommand(cmd)
    cmd = cmd:upper()
    if cmd == "NEXT" then
        self:turnPage(1)
        return "OK"
    elseif cmd == "PREV" then
        self:turnPage(-1)
        return "OK"
    elseif cmd == "PING" then
        return "PONG"
    elseif cmd == "HELLO" then
        return "PAGEFLIP " .. PROTOCOL_VERSION
    end
    return "ERR"
end

function PageFlip:handleLine(client, line)
    if client.http then
        -- Esperamos al final de las cabeceras para responder.
        if line == "" then
            local body = self:handleCommand(client.http) .. "\n"
            client.sock:send("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n"
                .. "Access-Control-Allow-Origin: *\r\nConnection: close\r\n"
                .. "Content-Length: " .. #body .. "\r\n\r\n" .. body)
            return false
        end
        return true
    end
    local path = line:match("^GET%s+/(%a*)")
    if path then
        client.http = path ~= "" and path or "HELLO"
        return true
    end
    line = line:match("^%s*(.-)%s*$")
    if line ~= "" then
        client.sock:send(self:handleCommand(line) .. "\n")
    end
    return true
end

function PageFlip:pollClient(client)
    while true do
        local data, err, partial = client.sock:receive(1024)
        local chunk = data or partial
        if chunk and #chunk > 0 then
            client.buf = client.buf .. chunk
        end
        if err == "closed" then return false end
        if not data then break end
    end
    while true do
        local line, rest = client.buf:match("^(.-)\r?\n(.*)$")
        if not line then break end
        client.buf = rest
        if not self:handleLine(client, line) then return false end
    end
    return #client.buf <= MAX_LINE
end

function PageFlip:poll()
    if not self.server then return end

    while true do
        local sock = self.server:accept()
        if not sock then break end
        sock:settimeout(0)
        sock:setoption("tcp-nodelay", true)
        table.insert(self.clients, { sock = sock, buf = "" })
    end

    for i = #self.clients, 1, -1 do
        local client = self.clients[i]
        local ok, keep = pcall(self.pollClient, self, client)
        if not ok or not keep then
            if not ok then logger.warn("PageFlip:", keep) end
            client.sock:close()
            table.remove(self.clients, i)
        end
    end

    if self.udp then
        local data, ip, port = self.udp:receivefrom()
        if data and data:match("^PAGEFLIP_DISCOVER") then
            local name = Device.model or "KOReader"
            self.udp:sendto(string.format("PAGEFLIP_HERE %d %s", self.port, name), ip, port)
        end
    end

    UIManager:scheduleIn(POLL_INTERVAL, self.poll_task)
end

function PageFlip:statusText()
    if not self.server then
        return _("Page Flip está desactivado.")
    end
    local ip = getLocalIp()
    return T(_("Page Flip activo.\n\nIP del Kindle: %1\nPuerto: %2\nMóviles conectados: %3\n\nAbre la app en el móvil y pulsa «Buscar Kindle» o escribe esta IP."),
        ip or _("sin wifi"), self.port, #self.clients)
end

function PageFlip:addToMainMenu(menu_items)
    menu_items.pageflip = {
        text = _("Page Flip (mando remoto)"),
        sorting_hint = "network",
        sub_item_table = {
            {
                text = _("Activar mando remoto"),
                checked_func = function() return self.server ~= nil end,
                callback = function()
                    if self.server then
                        self:stopServer()
                        G_reader_settings:makeFalse("pageflip_enabled")
                    else
                        local ok, err = self:startServer()
                        if not ok then
                            UIManager:show(InfoMessage:new{
                                text = T(_("No se pudo abrir el puerto %1: %2"), self.port, tostring(err)),
                            })
                            return
                        end
                        G_reader_settings:makeTrue("pageflip_enabled")
                        UIManager:show(InfoMessage:new{ text = self:statusText(), timeout = 8 })
                    end
                end,
            },
            {
                text = _("Estado e IP"),
                keep_menu_open = true,
                callback = function()
                    UIManager:show(InfoMessage:new{ text = self:statusText() })
                end,
            },
            {
                text_func = function() return T(_("Puerto: %1"), self.port) end,
                keep_menu_open = true,
                callback = function(touchmenu_instance)
                    local SpinWidget = require("ui/widget/spinwidget")
                    UIManager:show(SpinWidget:new{
                        title_text = _("Puerto TCP"),
                        value = self.port,
                        value_min = 1024,
                        value_max = 65535,
                        default_value = DEFAULT_PORT,
                        callback = function(spin)
                            local was_running = self.server ~= nil
                            self:stopServer()
                            self.port = spin.value
                            G_reader_settings:saveSetting("pageflip_port", self.port)
                            if was_running then self:startServer() end
                            touchmenu_instance:updateItems()
                        end,
                    })
                end,
            },
        },
    }
end

return PageFlip
