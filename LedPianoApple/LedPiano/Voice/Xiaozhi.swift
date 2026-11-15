import AVFoundation
import CryptoKit
import Foundation
import Copus

// Xiaozhi voice channel, ported from FriendBot (XiaozhiActivator / XiaozhiSession) and kept in
// step with the Android version in com.ledpiano.app.voice.

// MARK: - Pairing

/// Pairs the app with xiaozhi.me like a real Xiaozhi device: POST the OTA endpoint, show the
/// 6-digit code, post an HMAC of the challenge to /activate until the server says 200, then
/// save the websocket url + token. Callbacks on the main queue.
final class XiaozhiActivator {
    static let otaURL = "https://api.tenclass.net/xiaozhi/ota/"
    static let defaultWsURL = "wss://api.tenclass.net/xiaozhi/v1/"

    private static let d = UserDefaults.standard
    private static func getOrCreate(_ key: String, _ make: () -> String) -> String {
        if let v = d.string(forKey: key), !v.isEmpty { return v }
        let v = make(); d.set(v, forKey: key); return v
    }

    static var deviceId: String {
        getOrCreate("xz_device_id") {
            var b = (0..<6).map { _ in UInt8.random(in: 0...255) }
            b[0] = (b[0] & 0xFC) | 0x02 // locally administered, MAC-shaped
            return b.map { String(format: "%02x", $0) }.joined(separator: ":")
        }
    }
    static var clientId: String { getOrCreate("xz_client_id") { UUID().uuidString.lowercased() } }
    private static var hmacKey: String {
        getOrCreate("xz_hmac_key") { sha256Hex("\(deviceId)|\(clientId)|\(Date().timeIntervalSince1970)") }
    }

    /// Saved (url, token), or nil when not paired yet.
    static var saved: (String, String)? {
        guard let t = d.string(forKey: "xz_ws_token"), !t.isEmpty else { return nil }
        let u = d.string(forKey: "xz_ws_url") ?? ""
        return (u.isEmpty ? defaultWsURL : u, t)
    }
    static func forget() { d.removeObject(forKey: "xz_ws_token"); d.removeObject(forKey: "xz_ws_url") }

    private var cancelled = false
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }

    func cancel() { cancelled = true }

    func start(onCode: @escaping (String) -> Void, onBound: @escaping (String, String) -> Void,
               onError: @escaping (String) -> Void) {
        cancelled = false
        Task.detached { [weak self] in
            guard let self else { return }
            do {
                let deadline = Date().addingTimeInterval(300)
                var state = try await self.parse(self.checkVersion())
                if state.bound { return self.finish(state, onBound, onError) }
                var shown = state.code
                let c0 = shown
                await MainActor.run { onCode(c0) }
                while !self.cancelled && Date() < deadline {
                    try await Task.sleep(nanoseconds: 2_500_000_000)
                    if !state.challenge.isEmpty, try await self.activate(state.challenge) != 200 { continue }
                    state = try await self.parse(self.checkVersion())
                    if state.bound { return self.finish(state, onBound, onError) }
                    if !state.code.isEmpty && state.code != shown {
                        shown = state.code
                        let c = shown
                        await MainActor.run { onCode(c) }
                    }
                }
                if !self.cancelled { await MainActor.run { onError("Hết thời gian chờ ghép nối (5 phút)") } }
            } catch {
                if !self.cancelled { let m = error.localizedDescription; await MainActor.run { onError(m) } }
            }
        }
    }

    private struct State { var bound = false, token = "", url = "", code = "", challenge = "" }

    private func finish(_ s: State, _ onBound: @escaping (String, String) -> Void, _ onError: @escaping (String) -> Void) {
        guard !s.token.isEmpty else { DispatchQueue.main.async { onError("Máy chủ không gửi mã truy cập") }; return }
        Self.d.set(s.url, forKey: "xz_ws_url")
        Self.d.set(s.token, forKey: "xz_ws_token")
        DispatchQueue.main.async { onBound(s.url.isEmpty ? Self.defaultWsURL : s.url, s.token) }
    }

    private func parse(_ data: Data) throws -> State {
        let root = (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        var s = State()
        if let ws = root["websocket"] as? [String: Any] {
            s.token = ws["token"] as? String ?? ""
            s.url = ws["url"] as? String ?? ""
        }
        let mqttUser = (root["mqtt"] as? [String: Any])?["username"] as? String ?? ""
        if let act = root["activation"] as? [String: Any] {
            s.code = act["code"] as? String ?? ""
            s.challenge = act["challenge"] as? String ?? ""
        }
        s.bound = (!s.token.isEmpty || !mqttUser.isEmpty) && s.code.isEmpty && s.challenge.isEmpty
        return s
    }

    private func checkVersion() async throws -> Data {
        #if os(iOS)
        let name = "LED Piano iOS"
        #else
        let name = "LED Piano macOS"
        #endif
        let body: [String: Any] = [
            "application": ["version": version, "elf_sha256": Self.hmacKey],
            "board": ["type": "ledpiano-apple", "name": name, "mac": Self.deviceId],
        ]
        let (data, code) = try await post(Self.otaURL, body)
        guard (200...299).contains(code) else { throw NSError(domain: "Xiaozhi", code: code, userInfo: [NSLocalizedDescriptionKey: "Máy chủ Xiaozhi trả lỗi HTTP \(code)"]) }
        return data
    }

    private func activate(_ challenge: String) async throws -> Int {
        let serial = "SN-" + String(Self.sha256Hex(Self.deviceId).prefix(8)) + "-" + Self.deviceId
        let key = SymmetricKey(data: Data(Self.hmacKey.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: Data(challenge.utf8), using: key)
        let payload: [String: Any] = ["algorithm": "hmac-sha256", "serial_number": serial, "challenge": challenge,
                                      "hmac": mac.map { String(format: "%02x", $0) }.joined()]
        return try await post(Self.otaURL + "activate", ["Payload": payload]).1
    }

    private func post(_ url: String, _ body: [String: Any]) async throws -> (Data, Int) {
        var r = URLRequest(url: URL(string: url)!, timeoutInterval: 15)
        r.httpMethod = "POST"
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue(Self.deviceId, forHTTPHeaderField: "Device-Id")
        r.setValue(Self.clientId, forHTTPHeaderField: "Client-Id")
        r.setValue("apple/ledpiano-\(version)", forHTTPHeaderField: "User-Agent")
        r.setValue(Locale.current.identifier, forHTTPHeaderField: "Accept-Language")
        r.setValue(version, forHTTPHeaderField: "Activation-Version")
        let (data, resp) = try await URLSession.shared.data(for: r)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }

    static func sha256Hex(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Session

/// One live Xiaozhi conversation (websocket protocol of 78/xiaozhi-esp32): hello with opus
/// 16 kHz / 60 ms and MCP on; listen start/stop/detect; abort; tts start/sentence_start/stop;
/// binary frames are raw opus both ways; MCP tools answered as JSON-RPC 2.0. Reconnects after
/// 1 s / 2 s / 5 s. Delegate calls arrive on the main queue.
protocol XiaozhiSessionDelegate: AnyObject {
    func xzConnected()
    func xzDisconnected(reconnecting: Bool, authFailed: Bool)
    func xzStt(_ text: String)
    func xzAssistant(_ text: String)
    func xzSpeaking(_ on: Bool)
    func xzAudio(_ opus: Data)
    func xzToolList() -> [[String: Any]]
    func xzToolCall(_ name: String, _ args: [String: Any]) -> (String, Bool)
}

final class XiaozhiSession: NSObject, URLSessionWebSocketDelegate {
    weak var delegate: XiaozhiSessionDelegate?
    private let url: String, token: String
    private var urlSession: URLSession!
    private var task: URLSessionWebSocketTask?
    private var sessionId = ""
    private var intentional = false
    private var attempts = 0
    private(set) var playbackRate = 16000
    var isConnected: Bool { task != nil && !sessionId.isEmpty }

    init(url: String, token: String) {
        self.url = url
        self.token = token
        super.init()
        urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue.main)
    }

    func connect() { intentional = false; attempts = 0; open() }

    func disconnect() {
        intentional = true
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        urlSession.finishTasksAndInvalidate() // the session holds us strongly until invalidated
    }

    func listenStart(handsFree: Bool) { send(["type": "listen", "state": "start", "mode": handsFree ? "auto" : "manual"]) }
    func listenStop() { send(["type": "listen", "state": "stop"]) }
    func abort() { send(["type": "abort", "reason": "wake_word_detected"]) }
    func sendAudio(_ opus: Data) { task?.send(.data(opus)) { _ in } }

    private func send(_ msg: [String: Any]) {
        var m = msg
        m["session_id"] = sessionId
        guard let data = try? JSONSerialization.data(withJSONObject: m), let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    private func open() {
        guard !intentional, let u = URL(string: url) else { return }
        sessionId = ""
        var r = URLRequest(url: u)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("1", forHTTPHeaderField: "Protocol-Version")
        r.setValue(XiaozhiActivator.deviceId, forHTTPHeaderField: "Device-Id")
        r.setValue(XiaozhiActivator.clientId, forHTTPHeaderField: "Client-Id")
        let t = urlSession.webSocketTask(with: r)
        task = t
        t.resume()
        receive(t)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol proto: String?) {
        guard webSocketTask === task else { return }
        let hello: [String: Any] = [
            "type": "hello", "version": 1, "transport": "websocket", "features": ["mcp": true],
            "audio_params": ["format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60],
        ]
        if let d = try? JSONSerialization.data(withJSONObject: hello), let s = String(data: d, encoding: .utf8) {
            webSocketTask.send(.string(s)) { _ in }
        }
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, t === self.task else { return }
                switch result {
                case .success(.string(let s)): self.dispatch(s)
                case .success(.data(let d)): self.delegate?.xzAudio(d)
                case .success: break
                case .failure: self.closed(t); return
                }
                self.receive(t)
            }
        }
    }

    private func closed(_ t: URLSessionWebSocketTask) {
        guard !intentional, t === task else { return }
        task = nil
        let code = (t.response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 || code == 403 {
            delegate?.xzDisconnected(reconnecting: false, authFailed: true)
            return
        }
        let delays: [Double] = [1, 2, 5]
        let delay = delays[min(attempts, delays.count - 1)]
        attempts += 1
        delegate?.xzDisconnected(reconnecting: true, authFailed: false)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.open() }
    }

    private func dispatch(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        switch msg["type"] as? String ?? "" {
        case "hello":
            sessionId = msg["session_id"] as? String ?? ""
            if let a = msg["audio_params"] as? [String: Any], let r = a["sample_rate"] as? Int, r > 0 { playbackRate = r }
            attempts = 0
            delegate?.xzConnected()
        case "stt":
            if let t = msg["text"] as? String, !t.isEmpty { delegate?.xzStt(t) }
        case "tts":
            switch msg["state"] as? String ?? "" {
            case "start": delegate?.xzSpeaking(true)
            case "stop": delegate?.xzSpeaking(false)
            case "sentence_start": if let t = msg["text"] as? String, !t.isEmpty { delegate?.xzAssistant(t) }
            default: break
            }
        case "mcp":
            if let p = msg["payload"] as? [String: Any] { mcp(p) }
        case "goodbye":
            task?.cancel(with: .normalClosure, reason: nil)
        default: break
        }
    }

    private func mcp(_ rpc: [String: Any]) {
        guard let id = rpc["id"], !(id is NSNull) else { return }
        switch rpc["method"] as? String ?? "" {
        case "initialize":
            reply(id, ["protocolVersion": "2024-11-05", "capabilities": ["tools": [String: Any]()],
                       "serverInfo": ["name": "LED Piano", "version": "1.0"]])
        case "tools/list":
            reply(id, ["tools": delegate?.xzToolList() ?? [], "nextCursor": ""])
        case "tools/call":
            let params = rpc["params"] as? [String: Any] ?? [:]
            let name = params["name"] as? String ?? ""
            let args = params["arguments"] as? [String: Any] ?? [:]
            let (text, isError) = delegate?.xzToolCall(name, args) ?? ("Unknown tool", true)
            reply(id, ["content": [["type": "text", "text": text]], "isError": isError])
        default:
            send(["type": "mcp", "payload": ["jsonrpc": "2.0", "id": id,
                                             "error": ["code": -32601, "message": "Unknown method"]]])
        }
    }

    private func reply(_ id: Any, _ result: [String: Any]) {
        send(["type": "mcp", "payload": ["jsonrpc": "2.0", "id": id, "result": result]])
    }
}

// MARK: - Audio (mic → opus up, opus down → speaker)

/// libopus via the swift-opus package (module Copus): 16 kHz mono 60 ms frames up, server rate down.
final class OpusCodec {
    private let enc: OpaquePointer
    private let dec: OpaquePointer
    let playbackRate: Int

    init?(playbackRate: Int) {
        var e: Int32 = 0
        guard let en = opus_encoder_create(16000, 1, 2048 /* OPUS_APPLICATION_VOIP */, &e), e == 0 else { return nil }
        guard let de = opus_decoder_create(Int32(playbackRate), 1, &e), e == 0 else { opus_encoder_destroy(en); return nil }
        enc = en
        dec = de
        self.playbackRate = playbackRate
    }

    deinit { opus_encoder_destroy(enc); opus_decoder_destroy(dec) }

    func encode(_ pcm: [Int16]) -> Data? {
        var out = [UInt8](repeating: 0, count: 1276)
        let cap = Int32(out.count)
        let n = pcm.withUnsafeBufferPointer { buf -> Int32 in
            guard let p = buf.baseAddress else { return 0 } // empty audio: nothing to encode
            return opus_encode(enc, p, Int32(pcm.count), &out, cap)
        }
        return n > 0 ? Data(out[0..<Int(n)]) : nil
    }

    func decode(_ packet: Data) -> [Int16] {
        var out = [Int16](repeating: 0, count: playbackRate * 120 / 1000 + 16)
        let cap = Int32(out.count)
        let n = packet.withUnsafeBytes { raw -> Int32 in
            guard let p = raw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return opus_decode(dec, p, Int32(packet.count), &out, cap, 0)
        }
        return n > 0 ? Array(out[0..<Int(n)]) : []
    }
}

/// One AVAudioEngine for the conversation: an input tap encodes the mic, a player node speaks.
/// The graph is built once before the engine starts (changing it while running crashes).
final class VoiceAudio {
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let codec: OpusCodec
    private let playFormat: AVAudioFormat
    private var converter: AVAudioConverter?
    private var pcm: [Int16] = []
    private var queued = 0
    private var drainWork: DispatchWorkItem?
    /// Mic frames are only sent while true.
    var micOn = false
    private(set) var busy = false
    var onFrame: ((Data, Float) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onDrained: (() -> Void)?

    init?(codec: OpusCodec) {
        self.codec = codec
        guard let f = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(codec.playbackRate), channels: 1, interleaved: false)
        else { return nil }
        playFormat = f
    }

    func start() -> Bool {
        #if os(iOS)
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
        try? s.setActive(true)
        #endif
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: false),
              let conv = AVAudioConverter(from: inFormat, to: target) else { return false }
        converter = conv
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: playFormat)
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buf, _ in self?.consume(buf) }
        engine.prepare()
        do { try engine.start() } catch { input.removeTap(onBus: 0); return false }
        playerNode.play()
        return true
    }

    func stop() {
        micOn = false
        engine.inputNode.removeTap(onBus: 0)
        playerNode.stop()
        engine.stop()
        #if os(iOS)
        // Back to the piano's own audio setup (loudspeaker, plays in silent mode).
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
        #endif
    }

    private func consume(_ buffer: AVAudioPCMBuffer) {
        guard micOn, let conv = converter else { return }
        var supplied = false
        let cap = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / buffer.format.sampleRate) + 960
        guard let out = AVAudioPCMBuffer(pcmFormat: conv.outputFormat, frameCapacity: cap) else { return }
        var err: NSError?
        conv.convert(to: out, error: &err) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        guard err == nil, out.frameLength > 0, let ch = out.int16ChannelData?[0] else { return }
        pcm.append(contentsOf: UnsafeBufferPointer(start: ch, count: Int(out.frameLength)))
        while pcm.count >= 960 {
            let frame = Array(pcm.prefix(960))
            pcm.removeFirst(960)
            var sum = 0.0
            for v in frame { sum += Double(v) * Double(v) }
            let level = Float((sum / 960).squareRoot() / 32768)
            if let packet = codec.encode(frame) { onFrame?(packet, level) }
        }
    }

    /// Main queue only.
    func play(_ opus: Data) {
        let samples = codec.decode(opus)
        guard !samples.isEmpty, let buf = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(samples.count)),
              let dst = buf.floatChannelData?[0] else { return }
        var sum: Float = 0
        for (i, v) in samples.enumerated() { let f = Float(v) / 32768; dst[i] = f; sum += f * f }
        buf.frameLength = AVAudioFrameCount(samples.count)
        onLevel?((sum / Float(samples.count)).squareRoot())
        busy = true
        drainWork?.cancel()
        queued += 1
        playerNode.scheduleBuffer(buf) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.queued = max(0, self.queued - 1)
                if self.queued == 0 { self.scheduleDrain() }
            }
        }
        if engine.isRunning && !playerNode.isPlaying { playerNode.play() }
    }

    /// Barge-in: drop the rest of the reply.
    func flush() {
        drainWork?.cancel()
        playerNode.stop()
        queued = 0
        busy = false
        if engine.isRunning { playerNode.play() }
    }

    private func scheduleDrain() {
        drainWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.queued == 0, self.busy else { return }
            self.busy = false
            self.onDrained?()
        }
        drainWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: w)
    }
}
