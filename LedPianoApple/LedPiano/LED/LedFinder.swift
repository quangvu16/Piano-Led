import Foundation
import Network

/// Finds LED controllers on the same Wi-Fi by themselves, so nobody has to type an IP address
/// (iPhone, iPad and Mac):
///  - LED Piano Strip boards (ESP32 / S3 / C3 firmware): Bonjour service "_ledpiano._tcp" (firmware 2.1+);
///    the Mac also sends the "LEDPIANO?" broadcast (StripLink.discover), which older firmware answers.
///  - WLED controllers: Bonjour service "_wled._tcp" (every WLED version advertises it).
/// Same as WifiFinder.kt (Android) and wled.find_controllers() (Python).
///
/// iPhone/iPad: needs NSBonjourServices (_ledpiano._tcp, _wled._tcp) and NSLocalNetworkUsageDescription in
/// Info.plist; the first search shows iOS's "find devices on your local network" question.
final class LedFinder {
    struct Found: Hashable { let name: String; let address: String }

    static let stripService = "_ledpiano._tcp"
    static let wledService = "_wled._tcp"

    private let q = DispatchQueue(label: "led.finder")
    private var browsers: [NWBrowser] = []
    private var resolving: [NWConnection] = []
    private var found: [String: String] = [:] // address → name
    private var finished = false

    /// Searches for `timeout` seconds, then calls `completion` once on the main queue (boards first).
    /// Keep the LedFinder alive until then.
    func find(timeout: TimeInterval = 2.5, completion: @escaping ([Found]) -> Void) {
        for type in [Self.stripService, Self.wledService] {
            let b = NWBrowser(for: .bonjour(type: type, domain: nil), using: NWParameters())
            b.browseResultsChangedHandler = { [weak self] results, _ in
                for r in results { self?.resolve(r.endpoint, wled: type == Self.wledService) }
            }
            b.start(queue: q)
            browsers.append(b)
        }
        #if os(macOS)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let strips = StripLink.discover(timeout: min(timeout, 1.5))
            self?.q.async { for s in strips { self?.found[s.address] = s.name } } // exact board names
        }
        #endif
        q.asyncAfter(deadline: .now() + timeout) { [weak self] in
            guard let self, !self.finished else { return }
            self.finished = true
            self.browsers.forEach { $0.cancel() }
            self.resolving.forEach { $0.cancel() }
            let list = self.found.map { Found(name: $0.value, address: $0.key) }
                .sorted { ($0.address.hasPrefix(StripLink.prefix) ? 0 : 1, $0.name) < ($1.address.hasPrefix(StripLink.prefix) ? 0 : 1, $1.name) }
            DispatchQueue.main.async { completion(list) }
        }
    }

    /// Bonjour name → IPv4 address. A UDP "connection" resolves the name without sending anything,
    /// so the board does not see a second app connecting.
    private func resolve(_ endpoint: NWEndpoint, wled: Bool) {
        guard case let .service(name, _, _, _) = endpoint else { return }
        let params = NWParameters.udp
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let c = NWConnection(to: endpoint, using: params)
        resolving.append(c)
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c else { return }
            switch state {
            case .ready:
                if case let .hostPort(host, _)? = c.currentPath?.remoteEndpoint, case let .ipv4(addr) = host {
                    let ip = "\(addr)".components(separatedBy: "%")[0]
                    if wled {
                        self.found[WledLink.prefix + ip] = "WLED · \(name) (Wi-Fi)"
                    } else if self.found[StripLink.prefix + ip] == nil {
                        self.found[StripLink.prefix + ip] = "\(name) (Wi-Fi)"
                    }
                }
                c.cancel()
            case .failed, .waiting:
                c.cancel()
            default: break
            }
        }
        c.start(queue: q)
    }
}
