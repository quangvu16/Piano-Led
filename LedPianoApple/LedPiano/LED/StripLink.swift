import Foundation
import Network
#if os(macOS)
import Darwin
#endif

/// The ESP32 "LED Piano Strip" over Wi-Fi: TCP port 7777 carrying the same "A5 op len … chk" messages
/// as Bluetooth and USB, both ways — so songs on the ESP32, its Bluetooth piano and instant green/red all
/// work over Wi-Fi too. Same as StripWifiTransport.kt (Android) and led.py "strip:" (Python).
///
/// Address: "strip:192.168.1.60" or "strip:ledpiano.local". The Mac finds boards by itself (a
/// "LEDPIANO?" broadcast on UDP 7778). iPhone/iPad: type the IP or "ledpiano.local" (iOS lets apps
/// broadcast only with a special Apple permission).
final class StripLink {
    static let prefix = "strip:"
    static let port: UInt16 = 7777
    static let discoveryPort: UInt16 = 7778

    private let conn: NWConnection
    private let q = DispatchQueue(label: "strip.wifi")
    private var reported = false

    /// `onData` and `onState(connected, error)` are called on the main queue.
    init?(host hostText: String, onData: @escaping (Data) -> Void, onState: @escaping (Bool, String?) -> Void) {
        var host = hostText.trimmingCharacters(in: .whitespaces)
        if host.hasPrefix(Self.prefix) { host = String(host.dropFirst(Self.prefix.count)) }
        var port = Self.port
        let parts = host.split(separator: ":")
        if parts.count == 2, let p = UInt16(parts[1]) { host = String(parts[0]); port = p }
        guard !host.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: NWParameters(tls: nil, tcp: tcp))
        conn.stateUpdateHandler = { [weak self] s in
            guard let self else { return }
            switch s {
            case .ready:
                self.reported = true
                DispatchQueue.main.async { onState(true, nil) }
                self.receive(onData, onState)
            case .failed(let e), .waiting(let e):
                self.conn.cancel()
                let msg = self.reported ? "Mất kết nối Wi-Fi với mạch ESP32" : "Không kết nối được mạch ESP32 ở \(host) (\(e.localizedDescription)) — cùng mạng Wi-Fi chưa?"
                DispatchQueue.main.async { onState(false, msg) }
            default: break
            }
        }
        conn.start(queue: q)
    }

    private func receive(_ onData: @escaping (Data) -> Void, _ onState: @escaping (Bool, String?) -> Void) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data, !data.isEmpty { DispatchQueue.main.async { onData(data) } }
            if complete || error != nil {
                self.conn.cancel()
                DispatchQueue.main.async { onState(false, "Mất kết nối Wi-Fi với mạch ESP32") }
            } else {
                self.receive(onData, onState)
            }
        }
    }

    func send(_ data: Data) { conn.send(content: data, completion: .contentProcessed { _ in }) }

    func close() { conn.cancel() }

    #if os(macOS)
    /// LED Piano Strip boards on this Wi-Fi: [(name, "strip:IP")]. Blocks for about `timeout` seconds.
    static func discover(timeout: TimeInterval = 1.5) -> [(name: String, address: String)] {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return [] }
        defer { Darwin.close(fd) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = in_port_t(discoveryPort).bigEndian
        to.sin_addr.s_addr = 0xFFFF_FFFF // 255.255.255.255
        let probe = Array("LEDPIANO?".utf8)
        var found: [String: String] = [:]
        let end = Date().addingTimeInterval(timeout)
        var nextProbe = Date.distantPast
        while Date() < end {
            if Date() >= nextProbe {
                _ = withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, probe, probe.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                nextProbe = Date().addingTimeInterval(0.5)
            }
            var buf = [UInt8](repeating: 0, count: 256)
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, buf.count, 0, $0, &len) }
            }
            if n <= 0 { continue }
            let text = String(decoding: buf[0..<n], as: UTF8.self)
            guard text.hasPrefix("LEDPIANO ") else { continue }
            let ip = String(cString: inet_ntoa(from.sin_addr))
            let name = text.dropFirst(9).trimmingCharacters(in: .whitespacesAndNewlines)
            found[ip] = name.isEmpty ? "LED Piano Strip" : name
        }
        return found.map { (name: "\($0.value) (Wi-Fi)", address: prefix + $0.key) }.sorted { $0.address < $1.address }
    }
    #endif
}
