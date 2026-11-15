import CoreBluetooth
import Foundation

/// SP611E / BanlanX frames: `A0 <op> <len> <data…>` (no checksum, ≤17 data bytes).
/// BanlanX SP630E (and, untested, SP802E) over Bluetooth: service FFE0 (or E0FF), write FFE1.
/// No per-LED command, so the whole strip shows the colour of the hand that plays.
/// Protocol from UniLED (github.com/monty68/uniled, lib/ble/banlanx_6xx.py, MIT): 53 cmd 00 01 00 len payload…
enum Sp630e {
    static func message(_ cmd: UInt8, _ payload: [Int]) -> Data {
        Data([0x53, cmd, 0x00, 0x01, 0x00, UInt8(payload.count)] + payload.map { UInt8(clamping: $0) })
    }
    static func power(_ on: Bool) -> Data { message(0x50, [on ? 1 : 0]) }
    static func brightness(_ v: Int) -> Data { message(0x51, [0, min(255, max(0, v))]) }
    /// Static colour mode, solid effect (once after connecting).
    static func staticMode() -> Data { message(0x53, [1, 1]) }
    static func color(_ rgb: UInt32, level: Int) -> Data {
        message(0x52, [Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF), min(255, max(0, level))])
    }
}

enum Sp611e {
    static func frame(_ op: UInt8, _ data: [Int]) -> Data {
        Data([0xA0, op, UInt8(data.count)] + data.map { UInt8(clamping: $0) })
    }
    static func power(_ on: Bool) -> Data { frame(0x62, [on ? 1 : 0]) }
    static func effect(_ id: Int) -> Data { frame(0x63, [id]) }
    static func brightness(_ v: Int) -> Data { frame(0x66, [min(255, max(0, v))]) }
    static func length(_ n: Int) -> Data { frame(0x68, [min(150, max(1, n))]) }
    static func rgb(_ r: Int, _ g: Int, _ b: Int, _ lv: Int = 255) -> Data { frame(0x69, [r, g, b, lv]) }
    static func sensitivity(_ v: Int) -> Data { frame(0x6B, [v]) }
    static func audioInput(_ v: Int) -> Data { frame(0x6C, [v]) }
    static func feed(_ bands: [Int]) -> Data { frame(0x6D, bands) }
    static let effectSolid = 0xBE, effectSoundSingle = 0xCA, effectSoundRGB = 0xC9
}

/// Legacy Led Organ per-key controller over Nordic UART: `7F [key,state]… 6E`.
enum NusFrame {
    static func keyByte(_ pitch: Int, offset: Int) -> Int { 88 - (offset - 21) - (pitch - 21) }
    static func state(_ hand: Hand, black: Bool) -> Int {
        hand == .left ? (black ? 0x04 : 0x03) : (black ? 0x02 : 0x01)
    }
    static func build(_ entries: [(Int, Int)]) -> Data {
        if entries.isEmpty { return Data([0x7F, 0x01, 0x00, 0x6E]) }
        var out: [UInt8] = [0x7F]
        for (k, s) in entries { out += [UInt8(truncatingIfNeeded: k), UInt8(truncatingIfNeeded: s)] }
        return Data(out + [0x6E])
    }
}

/// 16-zone energy model for the SP611E spectrum feed.
final class ZoneMapper {
    private var levels = [Float](repeating: 0, count: 16)
    private let lock = NSLock()

    func noteOn(_ pitch: Int, _ vel: Int) {
        let z = max(0, min(15, (pitch - Piano.lowest) * 16 / 88))
        let t = 0.35 + 0.65 * Float(min(max(vel, 1), 127)) / 127
        lock.lock()
        for (i, k) in [(z, Float(1)), (z - 1, 0.5), (z + 1, 0.5)] where i >= 0 && i < 16 { levels[i] = max(levels[i], t * k) }
        lock.unlock()
    }
    func tick(_ dt: Double) { let f = Float(exp(-dt / 0.18)); lock.lock(); levels = levels.map { $0 * f }; lock.unlock() }
    var hasEnergy: Bool { lock.lock(); defer { lock.unlock() }; return levels.contains { $0 > 0.01 } }
    func bands() -> [Int] { lock.lock(); defer { lock.unlock() }; return levels.map { Int($0 * 255) } }
    func reset() { lock.lock(); levels = Array(repeating: 0, count: 16); lock.unlock() }
}

/// Bluetooth LED strip: scanning, connection with auto-reconnect, guidance dimming,
/// key lighting, light test and the alignment wizard's single-key preview.
final class LedController: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    enum State { case disconnected, connecting, connected }

    struct Found: Identifiable { let id: UUID; let name: String; let rssi: Int }

    @Published private(set) var state: State = .disconnected
    @Published private(set) var deviceName: String?
    @Published private(set) var found: [Found] = []
    @Published var message: String?
    /// ESP32 status (firmware 2): bit 0 piano connected, bit 1 home Wi-Fi, bit 2 song stored.
    @Published private(set) var esp32Flags = 0
    @Published private(set) var esp32Ip: String?
    /// Notes per song the ESP32 holds (its STATUS says: 3000, or 20000 on an ESP32-S3 N16R8).
    private(set) var esp32SongMax = Ws2812.maxSongNotes
    /// STATUS bit 3: the board is an ESP32-S3 (e.g. ESP32-S3-DevKitC N16R8).
    var esp32IsS3: Bool { esp32Flags & 8 != 0 }
    /// STATUS bit 4: the board is an ESP32-C3 (e.g. ESP32-C3 Super Mini).
    var esp32IsC3: Bool { esp32Flags & 16 != 0 }
    /// Notes from a Bluetooth MIDI piano paired with the ESP32: (pitch, velocity; 0 = released). Main thread.
    var onPianoNote: ((Int, Int) -> Void)?
    /// Called when `esp32Flags` changes. Main thread.
    var onEsp32Status: (() -> Void)?

    private let settings: Settings
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var userDisconnected = false
    private var retries = 0
    private var autoTried = false
    private let mapper = ZoneMapper()
    private var active: [Int: LitKey] = [:]
    private let queue = DispatchQueue(label: "led")
    private var timer: Timer?
    var playing = false
    private(set) var manual = false

    /// One exact key can be lit (Led Organ box or WS2812B strip).
    var perKey: Bool { settings.ledTransport == "legacy_nus" || isWs }
    /// ESP32 + WS2812B, WLED, SP801E (Art-Net), E1.31, DDP or an Arduino (Adalight): the app sets each LED itself.
    var isWs: Bool { Self.pixelTypes.contains(settings.ledTransport) }
    static let pixelTypes: Set<String> = ["ws2812", "wled", "sp801e", "e131", "ddp", "adalight"]
    /// BanlanX SP630E / SP802E: the whole strip shows the colour of the hand that plays.
    var wholeStrip: Bool { settings.ledTransport == "sp630e" || settings.ledTransport == "sp802e" }
    /// Last whole-strip colour sent (nil = send again).
    private var lastStripRgb: UInt32?
    /// WLED controller over Wi-Fi (see WledLink); nil when using Bluetooth.
    private var wled: WledLink?
    #if os(macOS)
    /// The ESP32 strip on a USB cable (Mac only, see SerialLink); nil when using Bluetooth / Wi-Fi.
    private var serial: SerialLink?
    #endif
    /// The ESP32 strip over Wi-Fi (TCP, see StripLink); nil otherwise.
    private var strip: StripLink?
    private var onUsb: Bool {
        #if os(macOS)
        return serial != nil || strip != nil
        #else
        return strip != nil
        #endif
    }
    private var wledToken = UUID()

    var guidance: Double = 1 {
        didSet { if oldValue != guidance { applyGuidance() } }
    }

    private static let spService = CBUUID(string: "FFE0"), spWrite = CBUUID(string: "FFE1")
    private static let nusService = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let nusWrite = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let wsService = CBUUID(string: "8F1B0001-6C3E-4C1A-9F2E-5A1E0D2C7701")
    private static let wsWrite = CBUUID(string: "8F1B0002-6C3E-4C1A-9F2E-5A1E0D2C7701")
    private static let wsNotify = CBUUID(string: "8F1B0003-6C3E-4C1A-9F2E-5A1E0D2C7701")

    // Learning aids on the WS2812B strip (guarded by `queue`, like `active`).
    private var velocities: [Int: Int] = [:]
    private var preview: [(pitch: Int, hand: Hand)] = []
    /// pitch → (colour, until uptime): short green / orange / red flashes.
    private var flashes: [Int: (rgb: UInt32, until: TimeInterval)] = [:]
    /// left? → (pitch, LED) last shown, to blink when a new key reuses the same LED (sparse strips).
    private var lastLedByHand: [Bool: (pitch: Int, led: Int)] = [:]
    private var lastExpect: Set<Int>?
    private var rx = Ws2812.Parser()
    /// Bluetooth pieces waiting to be written (sent as fast as CoreBluetooth accepts them).
    private var outbox: [Data] = []

    /// True for the ESP32 "LED Piano Strip" over Bluetooth (it can store songs and pair a piano).
    var isEsp32: Bool {
        state == .connected && settings.ledTransport == "ws2812" && (onUsb || (wled == nil && writeChar?.uuid == Self.wsWrite))
    }
    var esp32PianoConnected: Bool { state == .connected && esp32Flags & 1 != 0 }

    init(settings: Settings) {
        self.settings = settings
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
        timer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in self?.tickFeed() }
    }

    // MARK: scanning / connecting

    func startScan() {
        found.removeAll()
        guard central.state == .poweredOn else { message = "Bạn cần bật Bluetooth trước khi kết nối"; return }
        central.scanForPeripherals(withServices: nil, options: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in self?.central.stopScan() }
    }

    func stopScan() { central.stopScan() }

    func connect(id: UUID, name: String?) {
        userDisconnected = false
        retries = 0
        startConnect(id: id, name: name)
    }

    private func startConnect(id: UUID, name: String?) {
        guard central.state == .poweredOn else { return }
        if let w = wled { w.close(); wled = nil; wledToken = UUID() }
        closeSerial()
        closeStrip()
        if ["wled", "sp801e", "e131", "ddp", "adalight"].contains(settings.ledTransport) { settings.ledTransport = "legacy_nus" } // Bluetooth: auto-detected
        // BanlanX controllers share one Bluetooth service, so the name tells which protocol they speak.
        let upper = (name ?? "").uppercased()
        if upper.hasPrefix("SP630E") { settings.ledTransport = "sp630e" }
        else if upper.hasPrefix("SP802E") { settings.ledTransport = "sp802e" }
        else if upper.hasPrefix("SP611E") { settings.ledTransport = "sp611e" }
        lastStripRgb = nil
        central.stopScan()
        let p = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
        guard let p else { message = "Không tìm thấy thiết bị"; return }
        peripheral = p
        p.delegate = self
        deviceName = name ?? p.name
        state = .connecting
        central.connect(p, options: nil)
    }

    func autoConnect() {
        guard !autoTried, state == .disconnected, settings.autoReconnect, let s = settings.ledAddress else { return }
        #if os(macOS)
        if s.hasPrefix(SerialLink.prefix) {
            autoTried = true
            connectSerial(String(s.dropFirst(SerialLink.prefix.count)))
            return
        }
        #endif
        if s.hasPrefix(WledLink.prefix) {
            autoTried = true
            connectWled(String(s.dropFirst(WledLink.prefix.count)))
            return
        }
        if s.hasPrefix(WledLink.artNetPrefix) {
            autoTried = true
            connectArtNet(String(s.dropFirst(WledLink.artNetPrefix.count)))
            return
        }
        if s.hasPrefix(WledLink.e131Prefix) || s.hasPrefix(WledLink.ddpPrefix) {
            autoTried = true
            connectPixelWifi(s)
            return
        }
        if s.hasPrefix(StripLink.prefix) {
            autoTried = true
            connectStrip(String(s.dropFirst(StripLink.prefix.count)))
            return
        }
        #if os(macOS)
        if s.hasPrefix(WledLink.adaPrefix + SerialLink.prefix) {
            autoTried = true
            connectAdalight(String(s.dropFirst((WledLink.adaPrefix + SerialLink.prefix).count)))
            return
        }
        #endif
        guard let id = UUID(uuidString: s) else { return }
        autoTried = true
        connect(id: id, name: settings.ledName)
    }

    /// Connects to an Art-Net node, e.g. BanlanX SP801E ("192.168.4.1", or "192.168.4.1/1" for universe 1).
    func connectArtNet(_ ip: String) { connectWled(ip, artNet: true) }

    /// Connects to a WLED controller on the same Wi-Fi ("192.168.1.50"); the phone then sets every LED.
    func connectWled(_ ip: String, artNet: Bool = false) {
        connectPixelWifi((artNet ? WledLink.artNetPrefix : WledLink.prefix) + ip.trimmingCharacters(in: .whitespaces))
    }

    /// E1.31 / sACN controller ("192.168.1.50", "192.168.1.50/2" = starts at universe 2).
    func connectE131(_ ip: String) { connectPixelWifi(WledLink.e131Prefix + ip.trimmingCharacters(in: .whitespaces)) }

    /// DDP controller ("192.168.1.50").
    func connectDdp(_ ip: String) { connectPixelWifi(WledLink.ddpPrefix + ip.trimmingCharacters(in: .whitespaces)) }

    /// Any Wi-Fi pixel protocol by address: "wled:…", "artnet:…", "e131:…", "ddp:…".
    func connectPixelWifi(_ address: String) {
        let artNet = address.hasPrefix(WledLink.artNetPrefix)
        let prefix = [WledLink.artNetPrefix, WledLink.e131Prefix, WledLink.ddpPrefix].first { address.hasPrefix($0) } ?? WledLink.prefix
        let ip = String(address.dropFirst(address.hasPrefix(prefix) ? prefix.count : 0))
        let kindName = prefix == WledLink.e131Prefix ? "e131" : prefix == WledLink.ddpPrefix ? "ddp" : "wled"
        userDisconnected = false
        closeSerial()
        closeStrip()
        wled?.close()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        writeChar = nil
        settings.ledTransport = artNet && settings.ledTransport == "sp801e" ? "sp801e" : kindName
        deviceName = (artNet ? "Art-Net " : kindName == "e131" ? "E1.31 " : kindName == "ddp" ? "DDP " : "WLED ") + ip
        state = .connecting
        let token = UUID()
        wledToken = token
        wled = WledLink(address: prefix + ip, numLeds: { [weak self] in self?.settings.wsNumLeds ?? 177 }) { [weak self] ok, err in
            // Ignore news from an older link that is closing.
            guard let self, self.wledToken == token else { return }
            if ok {
                self.state = .connected
                self.settings.ledAddress = prefix + ip
                self.settings.ledName = self.deviceName
                self.lastFrame = nil
                self.send(Ws2812.brightness(self.settings.ledBrightness))
                self.send(Ws2812.off)
            } else {
                self.state = .disconnected
                if let err { self.message = err }
            }
        }
        if wled == nil { state = .disconnected; message = "Địa chỉ IP không hợp lệ" }
    }

    func disconnect() {
        userDisconnected = true
        wledToken = UUID()
        if let w = wled { w.close(); wled = nil }
        closeSerial()
        closeStrip()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        state = .disconnected
    }

    private func scheduleRetry() {
        guard !userDisconnected, settings.autoReconnect, retries < 3, let p = peripheral else { return }
        retries += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.state == .disconnected, !self.userDisconnected else { return }
            self.startConnect(id: p.identifier, name: self.deviceName)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { autoConnect() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber) {
        guard let name = p.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String, !name.isEmpty else { return }
        peripherals[p.identifier] = p
        if !found.contains(where: { $0.id == p.identifier }) {
            found.append(Found(id: p.identifier, name: name, rssi: rssi.intValue))
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        p.discoverServices([Self.spService, Self.nusService, Self.wsService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        state = .disconnected
        message = "Kết nối Bluetooth thất bại"
        scheduleRetry()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard wled == nil, !onUsb else { return } // switched to WLED over Wi-Fi / USB
        let was = state == .connected
        writeChar = nil
        outbox.removeAll()
        setEsp32Status(0, nil)
        state = .disconnected
        if was { message = "Mất kết nối với thiết bị" }
        scheduleRetry()
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        let services = p.services ?? []
        // An LED Piano Strip (ESP32 + WS2812B) also speaks Led Organ; prefer its own pixel mode.
        if let ws = services.first(where: { $0.uuid == Self.wsService }) {
            p.discoverCharacteristics([Self.wsWrite, Self.wsNotify], for: ws)
            return
        }
        for s in services { p.discoverCharacteristics([Self.spWrite, Self.nusWrite], for: s) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        // Firmware 2: piano notes and status come back on the notify characteristic.
        if let nc = service.characteristics?.first(where: { $0.uuid == Self.wsNotify }) { p.setNotifyValue(true, for: nc) }
        // Use whichever protocol the controller really speaks (Led Organ box = per-key
        // Nordic UART, BanlanX = SP611E), whatever was picked in Settings.
        guard writeChar == nil,
              let c = service.characteristics?.first(where: { [Self.wsWrite, Self.nusWrite, Self.spWrite].contains($0.uuid) })
        else { return }
        // FFE1 = BanlanX: SP611E, or SP630E / SP802E when that was chosen (by name or in Settings).
        let proto = c.uuid == Self.wsWrite ? "ws2812" : c.uuid == Self.nusWrite ? "legacy_nus" : wholeStrip ? settings.ledTransport : "sp611e"
        if settings.ledTransport != proto { settings.ledTransport = proto }
        writeChar = c
        state = .connected
        retries = 0
        settings.ledAddress = p.identifier.uuidString
        settings.ledName = deviceName
        if isWs {
            lastFrame = nil
            send(Ws2812.brightness(settings.ledBrightness))
            send(Ws2812.off)
            sendConfig()
        } else if perKey {
            lastFrame = nil
            send(NusFrame.build([]))
        } else if wholeStrip {
            lastStripRgb = 0
            for f in [Sp630e.power(true), Sp630e.staticMode(), Sp630e.brightness(settings.ledBrightness),
                      Sp630e.color(0, level: settings.ledBrightness)] { send(f) } // dark until a key plays
        } else {
            let eff = ["single": Sp611e.effectSolid, "full": Sp611e.effectSoundRGB][settings.ledEffect] ?? Sp611e.effectSoundSingle
            for f in [Sp611e.power(true), Sp611e.effect(eff), Sp611e.brightness(Int(Double(settings.ledBrightness) * guidance)),
                      Sp611e.length(settings.ledLength), Sp611e.audioInput(1), Sp611e.sensitivity(16), Sp611e.rgb(255, 255, 255)] {
                send(f)
            }
        }
    }

    // MARK: sending

    func send(_ data: Data) {
        if let w = wled {
            if state == .connected { w.send(data) }
            return
        }
        #if os(macOS)
        if let s = serial {
            if state == .connected { s.send(data) }
            return
        }
        #endif
        if let t = strip {
            if state == .connected { t.send(data) }
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.peripheral != nil, self.writeChar != nil, self.state == .connected else { return }
            let bytes = [UInt8](data)
            var i = 0
            while i < bytes.count {
                let end = min(bytes.count, i + 20)
                self.outbox.append(Data(bytes[i..<end]))
                i = end
            }
            self.pump()
        }
    }

    /// Writes waiting pieces while CoreBluetooth can take them (a song for the ESP32 is hundreds of pieces;
    /// writing them all at once would lose some).
    private func pump() {
        guard let p = peripheral, let c = writeChar, state == .connected else { outbox.removeAll(); return }
        let noResponse = c.properties.contains(.writeWithoutResponse)
        while !outbox.isEmpty {
            if noResponse && !p.canSendWriteWithoutResponse { return } // peripheralIsReady(…) continues
            p.writeValue(outbox.removeFirst(), for: c, type: noResponse ? .withoutResponse : .withResponse)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) { pump() }

    /// Messages from the ESP32 (firmware 2): piano notes and status.
    func peripheral(_ p: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.wsNotify, let value = characteristic.value else { return }
        esp32Bytes(value)
    }

    /// Bytes from the ESP32 (Bluetooth notify or USB): piano notes and status. Main thread.
    private func esp32Bytes(_ value: Data) {
        for b in value {
            guard rx.feed(b) else { continue }
            let d = rx.data
            if rx.op == Ws2812.opNote, d.count >= 2 {
                onPianoNote?(Int(d[0] & 0x7F), Int(d[1] & 0x7F))
            } else if rx.op == Ws2812.opStatus, d.count >= 5 {
                if d.count >= 9 {
                    let cap = Int(d[7]) | Int(d[8]) << 8
                    esp32SongMax = cap > 0 ? cap : Ws2812.maxSongNotes
                }
                let ip = d[1...4].map { String($0) }
                setEsp32Status(Int(d[0]), d[1...4].contains { $0 != 0 } ? ip.joined(separator: ".") : nil)
            }
        }
    }

    // MARK: USB cable (Mac)

    /// Connects to the ESP32 strip on a USB port ("/dev/cu.usbserial-0001"). Mac only.
    func connectSerial(_ path: String) {
        #if os(macOS)
        userDisconnected = false
        wledToken = UUID()
        if let w = wled { w.close(); wled = nil }
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        writeChar = nil
        outbox.removeAll()
        closeSerial()
        closeStrip()
        settings.ledTransport = "ws2812"
        deviceName = "LED Piano Strip (USB)"
        state = .connecting
        let token = UUID()
        wledToken = token
        serial = SerialLink(path: path, onData: { [weak self] d in
            guard let self, self.wledToken == token else { return }
            self.esp32Bytes(d)
        }, onState: { [weak self] ok, err in
            guard let self, self.wledToken == token else { return }
            if ok {
                self.state = .connected
                self.settings.ledAddress = SerialLink.prefix + path
                self.settings.ledName = self.deviceName
                self.lastFrame = nil
                self.send(Ws2812.brightness(self.settings.ledBrightness))
                self.send(Ws2812.off)
                self.sendConfig()
            } else {
                self.closeSerial()
                self.setEsp32Status(0, nil)
                self.state = .disconnected
                if let err { self.message = err }
            }
        })
        #endif
    }

    private func closeSerial() {
        #if os(macOS)
        serial?.close()
        serial = nil
        #endif
    }

    // MARK: ESP32 over Wi-Fi, Arduino (Adalight)

    /// Connects to the ESP32 strip over Wi-Fi ("192.168.1.60" or "ledpiano.local"): every feature, like Bluetooth.
    func connectStrip(_ host: String) {
        let host = host.trimmingCharacters(in: .whitespaces)
        userDisconnected = false
        wledToken = UUID()
        if let w = wled { w.close(); wled = nil }
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        writeChar = nil
        outbox.removeAll()
        closeSerial()
        closeStrip()
        settings.ledTransport = "ws2812"
        deviceName = "LED Piano Strip (Wi-Fi) " + host
        state = .connecting
        let token = UUID()
        wledToken = token
        strip = StripLink(host: host, onData: { [weak self] d in
            guard let self, self.wledToken == token else { return }
            self.esp32Bytes(d)
        }, onState: { [weak self] ok, err in
            guard let self, self.wledToken == token else { return }
            if ok {
                self.state = .connected
                self.settings.ledAddress = StripLink.prefix + host
                self.settings.ledName = self.deviceName
                self.lastFrame = nil
                self.send(Ws2812.brightness(self.settings.ledBrightness))
                self.send(Ws2812.off)
                self.sendConfig()
            } else {
                self.closeStrip()
                self.setEsp32Status(0, nil)
                self.state = .disconnected
                if let err { self.message = err }
            }
        })
        if strip == nil { state = .disconnected; message = "Địa chỉ IP không hợp lệ" }
    }

    private func closeStrip() {
        strip?.close()
        strip = nil
    }

    #if os(macOS)
    /// Arduino Uno/Nano running LedPianoAdalight.ino on a USB port or a paired HC-05 ("/dev/cu.HC-05"). Mac only.
    func connectAdalight(_ path: String) {
        userDisconnected = false
        wledToken = UUID()
        if let w = wled { w.close(); wled = nil }
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        writeChar = nil
        outbox.removeAll()
        closeSerial()
        closeStrip()
        settings.ledTransport = "adalight"
        deviceName = "Arduino (Adalight) " + (path as NSString).lastPathComponent
        state = .connecting
        let token = UUID()
        wledToken = token
        let link = SerialLink(path: path, onData: { _ in }, onState: { [weak self] ok, err in
            guard let self, self.wledToken == token else { return }
            if ok {
                guard let ser = self.serial else { return }
                self.wled = WledLink(adalight: { [weak ser] d in ser?.send(d) },
                                     numLeds: { [weak self] in self?.settings.wsNumLeds ?? 177 })
                self.state = .connected
                self.settings.ledAddress = WledLink.adaPrefix + SerialLink.prefix + path
                self.settings.ledName = self.deviceName
                self.lastFrame = nil
                self.send(Ws2812.brightness(self.settings.ledBrightness))
                self.send(Ws2812.off)
            } else {
                self.wled?.close(); self.wled = nil
                self.closeSerial()
                self.state = .disconnected
                if let err { self.message = err }
            }
        })
        serial = link
    }
    #endif

    private func setEsp32Status(_ flags: Int, _ ip: String?) {
        guard flags != esp32Flags || ip != esp32Ip else { return }
        esp32Flags = flags
        esp32Ip = ip
        onEsp32Status?()
    }

    /// Tells the ESP32 the strip geometry and colours (so it can light keys itself).
    func sendConfig() {
        guard isEsp32 else { return }
        send(Ws2812.config(settings.wsConfig, right: Ws2812.rgb(hex: settings.colorRight), left: Ws2812.rgb(hex: settings.colorLeft)))
    }

    /// Stores `song` on the ESP32 to practise without the phone (BOOT button). Returns the notes sent, 0 if not possible.
    func sendSong(_ song: Song) -> Int {
        guard isEsp32 else { return 0 }
        sendConfig()
        let notes = song.byTime.map { Ws2812.SongNote(start: $0.start, pitch: $0.pitch, left: $0.hand == .left) }
        let cap = esp32SongMax
        for m in Ws2812.songMessages(notes, title: song.title, maxNotes: cap) { send(m) }
        return min(notes.count, cap)
    }

    // MARK: learning aids

    /// Next key of each hand, shown dimly before it is due (only with the "led preview" option).
    func setPreview(_ items: [(pitch: Int, hand: Hand)]) {
        let changed: Bool = queue.sync {
            let same = items.count == preview.count && zip(items, preview).allSatisfy { $0.pitch == $1.pitch && $0.hand == $1.hand }
            if !same { preview = items }
            return !same
        }
        if changed && isWs && !manual && settings.ledPreview { flush() }
    }

    /// Short coloured flash on one key: red = wrong key, green = on time, orange = early / late.
    func flash(_ pitch: Int, _ rgb: UInt32, ms: Double = 250) {
        guard isWs, !manual, settings.ledFeedback else { return }
        let until = ProcessInfo.processInfo.systemUptime + ms / 1000
        queue.sync { flashes[pitch] = (rgb, until) }
        flush()
        DispatchQueue.main.asyncAfter(deadline: .now() + ms / 1000 + 0.02) { [weak self] in
            guard let self else { return }
            let now = ProcessInfo.processInfo.systemUptime
            self.queue.sync { self.flashes = self.flashes.filter { $0.value.until > now } }
            if !self.manual { self.flush() }
        }
    }

    private func tickFeed() {
        mapper.tick(0.04)
        if state == .connected && !perKey && !wholeStrip && (playing || mapper.hasEnergy) { send(Sp611e.feed(mapper.bands())) }
    }

    // MARK: keys

    /// A key starts sounding. Only ONE LED per hand is lit: the newest note of each
    /// hand. In a chord (notes starting within 40 ms) the right hand shows its top note
    /// (melody) and the left hand its bottom note (bass). When that key ends, the hand's
    /// LED moves to the newest note still held.
    func keyOn(_ pitch: Int, hand: Hand, velocity: Int = 100) {
        guard !manual else { return }
        let lit = LitKey(pitch: pitch, hand: hand, black: [1, 3, 6, 8, 10].contains(pitch % 12),
                         start: ProcessInfo.processInfo.systemUptime)
        let shown: Bool = queue.sync {
            active[pitch] = lit
            velocities[pitch] = velocity
            return LitKey.onePerHand(Array(active.values)).contains { $0.pitch == pitch }
        }
        if guidance > 0 && shown { mapper.noteOn(pitch, velocity) }
        if perKey || wholeStrip { flush() }
    }

    func keyOff(_ pitch: Int) {
        guard !manual else { return }
        let removed = queue.sync { active.removeValue(forKey: pitch) != nil }
        if (perKey || wholeStrip) && removed { flush() }
    }

    func allOff() {
        mapper.reset()
        queue.sync {
            active.removeAll()
            velocities.removeAll(); preview = []; flashes.removeAll(); lastLedByHand.removeAll()
        }
        lastFrame = nil
        if isWs {
            send(Ws2812.off)
            if wled == nil { lastExpect = []; send(Ws2812.expect([])) }
        } else if perKey { send(NusFrame.build([])) } else if wholeStrip { flush() }
    }

    private var lastFrame: Data?

    private func flush() {
        var shown: [LitKey] = []
        if guidance >= 0.5 { shown = queue.sync { LitKey.onePerHand(Array(active.values)) }.sorted { $0.pitch < $1.pitch } }
        if wholeStrip {
            // One colour for the whole strip: the hand of the newest key, dark when nothing plays.
            let newest = shown.max { $0.start < $1.start }
            let rgb: UInt32 = newest.map { Ws2812.rgb(hex: $0.hand == .left ? settings.colorLeft : settings.colorRight) } ?? 0
            if rgb != lastStripRgb {
                lastStripRgb = rgb
                send(Sp630e.color(rgb, level: settings.ledBrightness))
            }
            return
        }
        let frame: Data
        if isWs {
            // Sparse strips: a new key of the same hand can sit on the same LED. Blink it off for
            // 70 ms so the learner notices the new key.
            var blink = Set<Int>()
            let (pv, fl, vel, expect): ([(pitch: Int, hand: Hand)], [Int: (rgb: UInt32, until: TimeInterval)], [Int: Int], Set<Int>) = queue.sync {
                for k in shown {
                    guard let q = wsPixel(k.pitch, k.hand) else { continue }
                    let left = k.hand == .left
                    if let last = lastLedByHand[left], last.pitch != k.pitch, last.led == q.index { blink.insert(q.index) }
                    lastLedByHand[left] = (k.pitch, q.index)
                }
                return (preview, flashes, velocities, Set(active.keys))
            }
            let px = wsPixels(shown, skip: blink, preview: pv, flashes: fl, velocities: vel)
            if !blink.isEmpty {
                send(Ws2812.pixels(px))
                lastFrame = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.07) { [weak self] in
                    guard let self, !self.manual else { return }
                    self.flush()
                }
            } else {
                frame = Ws2812.pixels(px)
                if frame != lastFrame {
                    lastFrame = frame
                    send(frame)
                }
            }
            if wled == nil && expect != lastExpect {
                lastExpect = expect
                send(Ws2812.expect(expect))
            }
            return
        }
        let off = settings.organOffset
        frame = NusFrame.build(shown.map { (NusFrame.keyByte($0.pitch, offset: off), NusFrame.state($0.hand, black: $0.black)) })
        if frame == lastFrame { return }
        lastFrame = frame
        send(frame)
    }

    /// All pixels for the WS2812B strip: dim preview, the shown keys (loudness), then flashes on top.
    private func wsPixels(_ shown: [LitKey], skip: Set<Int>, preview: [(pitch: Int, hand: Hand)],
                          flashes: [Int: (rgb: UInt32, until: TimeInterval)], velocities: [Int: Int]) -> [Ws2812.Pixel] {
        var px: [Int: UInt32] = [:]
        if settings.ledPreview && guidance >= 0.5 {
            for item in preview { if let q = wsPixel(item.pitch, item.hand) { px[q.index] = Ws2812.scale(q.rgb, 0.18) } }
        }
        for k in shown {
            guard let q = wsPixel(k.pitch, k.hand), !skip.contains(q.index) else { continue }
            px[q.index] = settings.ledDynamics ? Ws2812.scale(q.rgb, Learning.dynScale(velocities[k.pitch] ?? 100)) : q.rgb
        }
        let now = ProcessInfo.processInfo.systemUptime
        let cfg = settings.wsConfig
        for (p, f) in flashes where f.until > now {
            let led = Ws2812.led(forPitch: p, cfg)
            if led >= 0 { px[led] = f.rgb }
        }
        return px.keys.sorted().map { Ws2812.Pixel(index: $0, rgb: px[$0]!) }
    }

    /// The LED and colour for one key on the WS2812B strip (hand colours from Settings).
    private func wsPixel(_ pitch: Int, _ hand: Hand) -> Ws2812.Pixel? {
        let led = Ws2812.led(forPitch: pitch, settings.wsConfig)
        guard led >= 0 else { return nil }
        let base = Ws2812.rgb(hex: hand == .left ? settings.colorLeft : settings.colorRight)
        return Ws2812.Pixel(index: led, rgb: Ws2812.isBlack(pitch) ? Ws2812.blackKeyColor(base) : base)
    }

    private func applyGuidance() {
        if isWs { send(Ws2812.brightness(settings.ledBrightness)); lastFrame = nil; flush(); return }
        if perKey { flush(); return }
        if wholeStrip { lastStripRgb = nil; flush(); return }
        let lv = Int(Double(settings.ledBrightness) * (0.15 + 0.85 * guidance))
        send(Sp611e.brightness(guidance <= 0 ? 0 : lv))
    }

    func setBrightness(_ v: Int) { settings.ledBrightness = v; applyGuidance() }

    func setLength(_ n: Int) {
        settings.ledLength = n
        if !perKey && !wholeStrip { send(Sp611e.length(n)) }
    }

    func showKeys(_ pitches: [Int]) {
        manual = true
        if isWs {
            send(Ws2812.pixels(pitches.compactMap { wsPixel($0, .right) }))
        } else if perKey {
            send(NusFrame.build(pitches.sorted().map { (NusFrame.keyByte($0, offset: settings.organOffset), 0x01) }))
        } else if wholeStrip {
            lastStripRgb = nil
            send(Sp630e.color(pitches.isEmpty ? 0 : Ws2812.rgb(hex: settings.colorRight), level: settings.ledBrightness))
        } else {
            for p in pitches { mapper.noteOn(p, 127) }
        }
    }

    func endManual() { manual = false; allOff() }

    func testLights() {
        guard state == .connected else { message = "Hãy kết nối đèn LED trước"; return }
        manual = true
        if isWs { tutorialTest(); return }
        if wholeStrip {
            // Whole-strip controller: right-hand colour, left-hand colour, then off.
            send(Sp630e.color(Ws2812.rgb(hex: settings.colorRight), level: settings.ledBrightness))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { [weak self] in
                guard let self else { return }
                self.send(Sp630e.color(Ws2812.rgb(hex: self.settings.colorLeft), level: self.settings.ledBrightness))
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self] in
                guard let self else { return }
                self.manual = false
                self.lastStripRgb = nil
                self.allOff()
            }
            return
        }
        var p = Piano.lowest
        Timer.scheduledTimer(withTimeInterval: 0.035, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            if p > Piano.highest {
                t.invalidate()
                self.manual = false
                self.allOff()
                return
            }
            if self.perKey {
                self.send(NusFrame.build([(NusFrame.keyByte(p, offset: self.settings.organOffset), 0x01)]))
            } else {
                self.mapper.noteOn(p, 127)
            }
            p += 2
        }
    }
}

/// One sounding key, as the LED controller keeps it.
struct LitKey {
    let pitch: Int
    let hand: Hand
    let black: Bool
    let start: TimeInterval

    /// Notes that start within this window count as one chord.
    static let chordWindow: TimeInterval = 0.04

    /// The one note per hand that gets an LED (unknown hand counts as right hand,
    /// like Led Organ does).
    static func onePerHand(_ keys: [LitKey]) -> [LitKey] {
        let groups = Dictionary(grouping: keys) { $0.hand == .left }
        return groups.compactMap { isLeft, notes in
            guard let newest = notes.map(\.start).max() else { return nil }
            let chord = notes.filter { newest - $0.start <= chordWindow }
            return isLeft ? chord.min { $0.pitch < $1.pitch } : chord.max { $0.pitch < $1.pitch }
        }
    }
}

extension LedController {
    /// WS2812B light test: exactly the HowToMechatronics FastLED example — LEDs 0, 1, 2, 5, 9, 14 and 19
    /// turn on one after another, 500 ms apart, in its colours — then a light runs over every key.
    fileprivate func tutorialTest() {
        let steps = Ws2812.tutorial.count
        for n in 1...steps {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5 * Double(n - 1)) { [weak self] in
                guard let self, self.state == .connected else { return }
                self.send(Ws2812.pixels(Array(Ws2812.tutorial.prefix(n))))
            }
        }
        let c = settings.wsConfig
        let start = 0.5 * Double(steps) + 0.5
        for (i, p) in (c.lowestPitch..<(c.lowestPitch + c.numKeys)).enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + start + 0.03 * Double(i)) { [weak self] in
                guard let self, self.state == .connected else { return }
                let led = Ws2812.led(forPitch: p, c)
                if led >= 0 { self.send(Ws2812.pixels([Ws2812.Pixel(index: led, rgb: Ws2812.rgb(hex: self.settings.colorRight))])) }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + start + 0.03 * Double(c.numKeys) + 0.3) { [weak self] in
            self?.endManual()
        }
    }
}
