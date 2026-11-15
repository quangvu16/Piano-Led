import AVFoundation
import SwiftUI

/// "Chọn bài bằng giọng nói": talk to Xiaozhi and it opens a song, like FriendBot's Voice Control
/// page. Paired once at xiaozhi.me; the mic streams opus to the Xiaozhi server (speech recognition +
/// thinking), Xiaozhi answers in its own voice and drives the app through two MCP tools:
/// `self.piano.list_songs` and `self.piano.open_song {title, autoplay}`.
/// Hands-free: listening restarts after every reply; tap the mic to stop, or to interrupt Xiaozhi.
final class VoiceModel: ObservableObject, XiaozhiSessionDelegate {
    enum UiState { case connecting, pairing, ready, listening, thinking, speaking, offline }
    struct Row: Identifiable { let id = UUID(); let text: String; let kind: Kind; enum Kind { case user, bot, action } }

    @Published var state: UiState = .connecting
    @Published var code = "······"
    @Published var rows: [Row] = []
    @Published var level: Float = 0
    @Published var paired = XiaozhiActivator.saved != nil

    /// Called with (song key, title, autoplay) once Xiaozhi has finished saying it opens the song.
    var onOpen: ((String, String, Bool) -> Void)?
    var settings: Settings?

    private var activator: XiaozhiActivator?
    private var session: XiaozhiSession?
    private var audio: VoiceAudio?
    private var codec: OpusCodec?
    private var wantListening = true
    private var ttsActive = false
    private var pending: (SongMatcher.Song, Bool)?
    private var lastUser = ""
    private var speaking: Bool { ttsActive || audio?.busy == true }

    // MARK: lifecycle

    func begin() {
        guard session == nil, activator == nil else { return }
        requestMic { [weak self] ok in
            guard let self else { return }
            guard ok else { self.add("Cần quyền micro để nói chuyện với Xiaozhi.", .bot); self.state = .offline; return }
            if let saved = XiaozhiActivator.saved { self.startSession(saved.0, saved.1) } else { self.startPairing() }
        }
    }

    func end() {
        activator?.cancel(); activator = nil
        audio?.stop(); audio = nil
        session?.disconnect(); session = nil
        codec = nil
    }

    func unpair() {
        end()
        XiaozhiActivator.forget()
        paired = false
        code = "······"
        startPairing()
    }

    private func requestMic(_ done: @escaping (Bool) -> Void) {
        #if os(iOS)
        AVAudioApplication.requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
        #else
        AVCaptureDevice.requestAccess(for: .audio) { ok in DispatchQueue.main.async { done(ok) } }
        #endif
    }

    private func startPairing() {
        state = .pairing
        let a = XiaozhiActivator()
        activator = a
        a.start(onCode: { [weak self] c in self?.code = c.isEmpty ? "······" : c },
                onBound: { [weak self] url, token in
                    self?.activator = nil
                    self?.paired = true
                    self?.startSession(url, token)
                },
                onError: { [weak self] m in
                    self?.activator = nil
                    self?.add("Ghép nối lỗi: \(m)", .bot)
                    self?.state = .offline
                })
    }

    private func startSession(_ url: String, _ token: String) {
        state = .connecting
        let s = XiaozhiSession(url: url, token: token)
        s.delegate = self
        session = s
        s.connect()
    }

    // MARK: mic button

    func micTapped() {
        guard let s = session else { if activator == nil { begin() }; return }
        if speaking {
            audio?.flush()
            s.abort()
            ttsActive = false
            wantListening = true
            listen()
        } else if audio?.micOn == true {
            wantListening = false
            audio?.micOn = false
            s.listenStop()
            state = .ready
            level = 0
        } else {
            wantListening = true
            listen()
        }
    }

    private func listen() {
        guard let s = session, s.isConnected, let a = audio else { return }
        a.micOn = true
        s.listenStart(handsFree: true)
        state = .listening
    }

    private func afterSpeech() {
        guard state == .speaking else { return }
        if let p = pending { open(p.0, p.1); return }
        if wantListening { listen() } else { state = .ready }
    }

    // MARK: XiaozhiSessionDelegate

    func xzConnected() {
        guard let s = session else { return }
        audio?.stop()
        guard let c = OpusCodec(playbackRate: s.playbackRate), let a = VoiceAudio(codec: c) else {
            add("Không mở được bộ mã hoá âm thanh Opus.", .bot); state = .offline; return
        }
        codec = c
        a.onFrame = { [weak self] packet, lv in
            // Runs on the audio thread. Never send our own loudspeaker back to the server.
            DispatchQueue.main.async {
                guard let self, !self.speaking else { return }
                self.session?.sendAudio(packet)
                self.level = lv
            }
        }
        a.onLevel = { [weak self] lv in self?.level = lv * 0.6 }
        a.onDrained = { [weak self] in if self?.ttsActive == false { self?.afterSpeech() } }
        guard a.start() else { add("Không mở được micro.", .bot); state = .offline; return }
        audio = a
        state = .ready
        if wantListening { listen() }
    }

    func xzDisconnected(reconnecting: Bool, authFailed: Bool) {
        audio?.micOn = false
        if authFailed {
            add("Thiết bị đã bị gỡ khỏi xiaozhi.me. Hãy ghép nối lại.", .bot)
            unpair()
            return
        }
        state = reconnecting ? .offline : .ready
    }

    func xzStt(_ text: String) {
        guard text != lastUser else { return }
        lastUser = text
        add(text, .user)
        state = .thinking
    }

    func xzAssistant(_ text: String) { add(text, .bot) }

    func xzSpeaking(_ on: Bool) {
        ttsActive = on
        if on { state = .speaking } else if audio?.busy != true { afterSpeech() }
    }

    func xzAudio(_ opus: Data) { audio?.play(opus) }

    // MARK: MCP tools

    private func catalog() -> [SongMatcher.Song] {
        let lessons = Lessons.all.map { SongMatcher.Song(key: $0.key, title: $0.title, subtitle: $0.subtitle) }
        let samples = Lessons.otherSamples.map { SongMatcher.Song(key: $0.key, title: $0.title) }
        let mine = (settings?.recent ?? []).compactMap { e -> SongMatcher.Song? in
            guard e.count == 2 else { return nil }
            return SongMatcher.Song(key: e[0], title: e[1])
        }
        var seen = Set<String>()
        return (lessons + samples + mine).filter { seen.insert($0.key).inserted }
    }

    func xzToolList() -> [[String: Any]] {
        [
            ["name": "self.piano.list_songs",
             "description": "List every song the learner can practise on the LED piano (Vietnamese title and English/original name). Use it when the user asks which songs exist, or before open_song when you are not sure of the title.",
             "inputSchema": ["type": "object", "properties": [String: Any]()]],
            ["name": "self.piano.open_song",
             "description": "Open a song on the LED piano so the learner can practise it; the LED strip lights the keys. Use when the user asks to open, play, learn or practise a song. `title`: the song title as said by the user or from list_songs (Vietnamese or English). `autoplay`: true when the user wants it to start playing right away.",
             "inputSchema": ["type": "object",
                             "properties": ["title": ["type": "string"], "autoplay": ["type": "boolean", "default": false]],
                             "required": ["title"]]],
        ]
    }

    func xzToolCall(_ name: String, _ args: [String: Any]) -> (String, Bool) {
        let songs = catalog()
        switch name {
        case "self.piano.list_songs":
            return (songs.map { "- " + $0.title + ($0.subtitle.isEmpty ? "" : " (\($0.subtitle))") }.joined(separator: "\n"), false)
        case "self.piano.open_song":
            let title = args["title"] as? String ?? ""
            let ranked = SongMatcher.rank(title, songs)
            guard let best = ranked.first, best.score >= SongMatcher.minScore else {
                let near = ranked.prefix(3).map(\.song.title).joined(separator: ", ")
                return ("Không tìm thấy bài \"\(title)\". Các bài gần giống: \(near.isEmpty ? "(không có)" : near)", true)
            }
            if SongMatcher.isAmbiguous(ranked) {
                return ("Có nhiều bài giống nhau, hãy hỏi người học muốn bài nào: " + ranked.prefix(3).map(\.song.title).joined(separator: ", "), true)
            }
            let autoplay = args["autoplay"] as? Bool ?? false
            pending = (best.song, autoplay)
            add("Đang mở “\(best.song.title)”…", .action)
            // Open after Xiaozhi has said so; never wait more than 6 s.
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
                if let p = self?.pending { self?.open(p.0, p.1) }
            }
            return ("Đã mở bài \"\(best.song.title)\"" + (autoplay ? " và bắt đầu chơi." : "."), false)
        default:
            return ("Unknown tool: \(name)", true)
        }
    }

    private func open(_ song: SongMatcher.Song, _ autoplay: Bool) {
        pending = nil
        end()
        onOpen?(song.key, song.title, autoplay)
    }

    private func add(_ text: String, _ kind: Row.Kind) {
        rows.append(Row(text: text, kind: kind))
        if rows.count > 40 { rows.removeFirst(rows.count - 40) }
    }
}

struct VoiceView: View {
    @EnvironmentObject var settings: Settings
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = VoiceModel()
    let onOpen: (String, String, Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel("Quay lại")
                Text("Chọn bài bằng giọng nói").font(.system(size: 20, weight: .heavy))
                Spacer()
                if vm.paired {
                    Button("Huỷ ghép nối") { vm.unpair() }.buttonStyle(.plain).foregroundColor(.textMid).font(.system(size: 14))
                }
            }
            .padding(.horizontal, 8).frame(height: 60)

            if vm.state == .pairing {
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Ghép nối với Xiaozhi (một lần)").font(.system(size: 17, weight: .bold))
                        Text(vm.code).font(.system(size: 44, weight: .heavy, design: .rounded)).kerning(8)
                            .foregroundColor(Color(hex: "7FB0FF")).frame(maxWidth: .infinity).textSelection(.enabled)
                        Text("1. Mở trang xiaozhi.me và đăng nhập.\n2. Chọn “Thêm thiết bị”.\n3. Nhập mã ở trên. Ứng dụng sẽ tự kết nối.")
                            .font(.system(size: 14)).foregroundColor(.textMid)
                    }
                }
                .padding(.horizontal, 16)
            }

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 6) {
                        Card {
                            Text("Thử nói:\n• “Mở bài Ngôi sao lấp lánh”\n• “Có những bài nào?”\n• “Chơi bài Chúc mừng sinh nhật luôn nhé”")
                                .font(.system(size: 14)).foregroundColor(.textMid)
                        }
                        ForEach(vm.rows) { r in bubble(r).id(r.id) }
                    }
                    .padding(16)
                }
                .onChange(of: vm.rows.count) { _, _ in
                    if let last = vm.rows.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }

            VStack(spacing: 10) {
                Text(stateText).font(.system(size: 16, weight: .bold))
                ZStack {
                    Circle().fill(Color(hex: "0A66D6").opacity(0.25))
                        .frame(width: 120, height: 120)
                        .scaleEffect(1 + CGFloat(min(0.35, vm.level * 6)))
                        .animation(.easeOut(duration: 0.1), value: vm.level)
                    Button { vm.micTapped() } label: {
                        Image(systemName: "mic.fill").font(.system(size: 30, weight: .bold)).foregroundColor(.white)
                            .frame(width: 84, height: 84).background(Color(hex: "0A66D6"), in: Circle())
                    }
                    .buttonStyle(.plain).accessibilityLabel("Micro")
                    .opacity([.connecting, .pairing, .offline].contains(vm.state) ? 0.4 : 1)
                }
                Text(hintText).font(.system(size: 13)).foregroundColor(.textMid)
            }
            .padding(.top, 8).padding(.bottom, 20)
        }
        .background(Color.ink.ignoresSafeArea())
        .foregroundColor(.textHi)
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 680)
        #endif
        .onAppear {
            vm.settings = settings
            vm.onOpen = { key, title, autoplay in
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { onOpen(key, title, autoplay) }
            }
            vm.begin()
        }
        .onDisappear { vm.end() }
    }

    private var stateText: String {
        switch vm.state {
        case .connecting: return "Đang kết nối Xiaozhi…"
        case .pairing: return "Chờ ghép nối…"
        case .ready: return "Sẵn sàng"
        case .listening: return "Đang nghe… mời bạn nói tên bài"
        case .thinking: return "Xiaozhi đang nghĩ…"
        case .speaking: return "Xiaozhi đang nói…"
        case .offline: return "Mất kết nối, đang thử lại…"
        }
    }

    private var hintText: String {
        switch vm.state {
        case .listening: return "Chạm để dừng nghe"
        case .speaking: return "Chạm để ngắt lời và nói tiếp"
        default: return "Chạm micro để nói"
        }
    }

    @ViewBuilder private func bubble(_ r: VoiceModel.Row) -> some View {
        switch r.kind {
        case .action:
            Text(r.text).font(.system(size: 13)).foregroundColor(Color(hex: "7FB0FF")).frame(maxWidth: .infinity)
        case .user:
            HStack { Spacer(minLength: 40)
                Text(r.text).font(.system(size: 15)).padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color(hex: "0A66D6"), in: RoundedRectangle(cornerRadius: 16)) }
        case .bot:
            HStack {
                Text(r.text).font(.system(size: 15)).padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.surf2, in: RoundedRectangle(cornerRadius: 16))
                Spacer(minLength: 40) }
        }
    }
}
