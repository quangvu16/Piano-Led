import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import CoreAudioKit
#endif

@main
struct LedPianoApp: App {
    @StateObject private var app = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .environmentObject(app.settings)
                .environmentObject(app.stats)
                .environmentObject(app.led)
                .environmentObject(app.hub)
                .environmentObject(app.player)
                .preferredColorScheme(.dark)
        }
        #if os(macOS)
        .defaultSize(width: 480, height: 900)
        #endif
    }
}

enum Route: Hashable { case lessons, player, wizard }

struct RootView: View {
    @EnvironmentObject var app: AppModel
    @State private var path: [Route] = []
    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
                .navigationDestination(for: Route.self) { r in
                    switch r {
                    case .lessons: LessonsView(path: $path)
                    case .player: PlayerView()
                    case .wizard: LedWizardView()
                    }
                }
        }
        .tint(.textHi)
    }
}

// MARK: - Small building blocks

struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surf1, in: RoundedRectangle(cornerRadius: 18))
    }
}

struct PillButton: View {
    let title: String
    var primary = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .bold)).padding(.horizontal, 16).frame(minHeight: 44)
                .frame(maxWidth: .infinity)
                .foregroundColor(primary ? .ink : .textHi)
                .background(primary ? Color.textHi : Color.surf2, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct Segmented: View {
    let items: [(String, String)]
    let selected: String
    let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 4) {
            ForEach(items.indices, id: \.self) { i in
                let key = items[i].0, text = items[i].1
                Button { pick(key) } label: {
                    Text(text).font(.system(size: 14, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .foregroundColor(selected == key ? .ink : Color(hex: "C9CED8"))
                        .background(selected == key ? Color.textHi : .clear, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected == key ? .isSelected : [])
            }
        }
        .padding(4).background(Color.ink, in: RoundedRectangle(cornerRadius: 14))
    }
}

// MARK: - Home

struct HomeView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var stats: Stats
    @EnvironmentObject var led: LedController
    @Binding var path: [Route]
    @State private var picking = false
    @State private var showSettings = false
    @State private var showBle = false
    @State private var showVoice = false

    var body: some View {
        GeometryReader { geo in
            let land = geo.size.width > geo.size.height * 1.1
            Group {
                if land {
                    HStack(alignment: .top, spacing: 24) {
                        ScrollView { actions }.frame(width: 340)
                        songs
                    }
                } else {
                    VStack(spacing: 16) { actions; songs }
                }
            }
            .padding(20)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationTitle("")
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .fileImporter(isPresented: $picking, allowedContentTypes: [UTType(filenameExtension: "mid") ?? .data, .data]) { r in
            if case .success(let url) = r { importSong(url) }
        }
        .sheet(isPresented: $showSettings) { SettingsSheet() }
        .sheet(isPresented: $showBle) { BlePicker { id, name in led.connect(id: id, name: name) } }
        .sheet(isPresented: $showVoice) {
            VoiceView { key, title, autoplay in
                open(key, title)
                if autoplay { DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { app.player.togglePlay() } }
            }
        }
        .onAppear { led.autoConnect() }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            HStack {
                Text("LED Piano").font(.system(size: 28, weight: .heavy))
                Spacer()
                Button { showSettings = true } label: {
                    Image(systemName: "slider.horizontal.3").frame(width: 44, height: 44).background(Color.surf1, in: Circle())
                }.buttonStyle(.plain).accessibilityLabel("Cài đặt")
            }
            Card {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(led.state == .connected ? "Đã kết nối đèn LED" : led.state == .connecting ? "Đang kết nối…" : "Chưa kết nối đèn LED")
                            .font(.system(size: 15, weight: .bold))
                        Text(led.state == .connected ? "\(led.deviceName ?? "") · Phím sẽ sáng khi bạn chơi" : "Bật dải đèn, rồi nhấn Kết nối")
                            .font(.system(size: 13)).foregroundColor(.textMid).lineLimit(1)
                    }
                    Spacer()
                    PillButton(title: led.state == .connected ? "Ngắt" : "Kết nối", primary: true) {
                        if led.state == .connected { led.disconnect() } else { showBle = true }
                    }.frame(width: 110)
                }
            }
            HStack(spacing: 12) {
                PillButton(title: "Thử đèn") { led.testLights() }
                PillButton(title: "Căn đèn với phím") { path.append(.wizard) }
            }
            Card {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(stats.streak > 0 ? "\(stats.streak) ngày liên tiếp" : "Bắt đầu chuỗi ngày luyện tập hôm nay")
                                .font(.system(size: 15, weight: .bold))
                            Text("Hôm nay: \(stats.todaySeconds / 60) / \(settings.dailyGoal) phút").font(.system(size: 13)).foregroundColor(.textMid)
                        }
                        Spacer()
                        WeekBars(seconds: stats.lastSeven, goalMinutes: settings.dailyGoal)
                    }
                    if let last = settings.recent.first, last.count == 2 {
                        Button { open(last[0], last[1]) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("Tiếp tục bài").font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
                                    Text(last[1]).font(.system(size: 15, weight: .bold)).lineLimit(1)
                                }
                                Spacer()
                                Image(systemName: "play.fill").foregroundColor(.ink).frame(width: 36, height: 36).background(Color.textHi, in: Circle())
                            }
                            .padding(.horizontal, 12).frame(height: 56).background(Color.surf2, in: RoundedRectangle(cornerRadius: 14))
                        }.buttonStyle(.plain)
                    }
                }
            }
            planCard
            HStack(spacing: 12) {
                tile("square.and.arrow.down", "Thêm bài hát", "MIDI hoặc MusicXML", Color(hex: "7FB0FF"), Color(hex: "1C2A44")) { picking = true }
                tile("hand.raised", "Bài học", "Lộ trình 5 cấp độ", Color(hex: "F8B84E"), Color(hex: "3A2A12")) { path.append(.lessons) }
            }
            Button { showVoice = true } label: {
                HStack(spacing: 12) {
                    Image(systemName: "mic.fill").foregroundColor(Color(hex: "7FB0FF")).frame(width: 40, height: 40)
                        .background(Color(hex: "1C2A44"), in: RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Nói tên bài").font(.system(size: 16, weight: .heavy))
                        Text("Xiaozhi mở bài cho bạn").font(.system(size: 13)).foregroundColor(.textMid)
                    }
                    Spacer()
                }
                .padding(.horizontal, 14).frame(height: 64).background(Color.surf1, in: RoundedRectangle(cornerRadius: 18))
            }.buttonStyle(.plain)
        }
    }

    /// "Kế hoạch hôm nay": warm-up, review the song not played for longest, one new lesson (5–10 min).
    @ViewBuilder private var planCard: some View {
        let plan = Learning.dailyPlan(Lessons.all, today: Stats.day(), lastDay: { stats.lastDay($0) }, done: { stats.record($0).done })
        if !plan.isEmpty {
            Card {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Kế hoạch hôm nay · 5–10 phút").font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
                    ForEach(Array(plan.enumerated()), id: \.offset) { _, item in
                        Button { open(item.1.key, item.1.title) } label: {
                            HStack {
                                Text("\(item.0.rawValue):  \(item.1.title)").font(.system(size: 15, weight: .bold)).lineLimit(1)
                                Spacer()
                                Image(systemName: "play.fill").foregroundColor(.textMid)
                            }
                            .frame(minHeight: 40).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func tile(_ icon: String, _ title: String, _ hint: String, _ fg: Color, _ bg: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: icon).foregroundColor(fg).frame(width: 40, height: 40).background(bg, in: RoundedRectangle(cornerRadius: 12))
                Text(title).font(.system(size: 16, weight: .heavy))
                Text(hint).font(.system(size: 13)).foregroundColor(.textMid)
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Color.surf1, in: RoundedRectangle(cornerRadius: 18))
        }.buttonStyle(.plain)
    }

    private var songs: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Bài hát của bạn").font(.system(size: 18, weight: .heavy))
                Spacer()
                Text("Chạm để luyện tập").font(.system(size: 13)).foregroundColor(.textMid)
            }
            if settings.recent.isEmpty {
                Spacer()
                Text("Chưa có bài hát nào.\nNhấn \"Thêm bài hát\" để mở tệp .mid hoặc MusicXML").multilineTextAlignment(.center)
                    .foregroundColor(.textMid).frame(maxWidth: .infinity)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(settings.recent, id: \.self) { e in
                            if e.count == 2 {
                                Button { open(e[0], e[1]) } label: { songRow(e[1], e[0].hasPrefix("asset://") ? "Bài học / bài mẫu" : e[1].hasPrefix("Bản ghi") ? "Bản ghi của bạn" : "Tệp bài hát") }
                                    .buttonStyle(.plain)
                                    .contextMenu {
                                        Button(role: .destructive) { settings.recent.removeAll { $0 == e } } label: { Label("Xóa khỏi danh sách", systemImage: "trash") }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    private func songRow(_ title: String, _ sub: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "music.note").foregroundColor(.textMid).frame(width: 40, height: 40).background(Color.surf2, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 16, weight: .bold)).lineLimit(1)
                Text(sub).font(.system(size: 13)).foregroundColor(.textMid)
            }
            Spacer()
            Image(systemName: "play.fill").foregroundColor(.ink).frame(width: 40, height: 40).background(Color.textHi, in: Circle())
        }
        .padding(.horizontal, 12).frame(height: 64).background(Color.surf1, in: RoundedRectangle(cornerRadius: 16))
    }

    private func importSong(_ url: URL) {
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dest = dir.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return }
        open(dest.path, url.deletingPathExtension().lastPathComponent)
    }

    private func open(_ key: String, _ title: String) {
        settings.addRecent(key, title)
        if app.player.open(key: key, title: title) { path.append(.player) }
    }
}

// MARK: - Lessons

struct LessonsView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var stats: Stats
    @EnvironmentObject var settings: Settings
    @Binding var path: [Route]

    var body: some View {
        List {
            ForEach(Lessons.levels) { lv in
                let done = lv.lessons.filter { stats.record($0.key).done }.count
                Section {
                    ForEach(lv.lessons) { l in
                        let rec = stats.record(l.key)
                        Button { open(l.key, l.title) } label: {
                            row(rec.done ? "✓" : "\(l.level)", l.title,
                                [l.hands == "R" ? "Tay phải" : l.hands == "L" ? "Tay trái" : "Hai tay", l.subtitle,
                                 rec.best >= 0 ? "Tốt nhất \(rec.best)%" : rec.done ? "Đã xong" : ""].filter { !$0.isEmpty }.joined(separator: " · "),
                                rec.done)
                        }.buttonStyle(.plain)
                    }
                } header: {
                    HStack { Text("Cấp \(lv.number) · \(lv.title)").font(.system(size: 17, weight: .heavy)); Spacer(); Text("\(done)/\(lv.lessons.count) xong") }
                }
            }
            let others = Lessons.otherSamples
            if !others.isEmpty {
                Section("Bài mẫu khác") {
                    ForEach(others) { o in
                        Button { open(o.key, o.title) } label: { row(stats.record(o.key).done ? "✓" : "♪", o.title, "", stats.record(o.key).done) }
                            .buttonStyle(.plain)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.ink)
        .navigationTitle("Bài học")
    }

    private func row(_ badge: String, _ title: String, _ info: String, _ done: Bool) -> some View {
        HStack(spacing: 12) {
            Text(badge).font(.system(size: 14, weight: .bold)).frame(width: 40, height: 40)
                .background(done ? Color.okBg : Color.surf2, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading) {
                Text(title).font(.system(size: 16, weight: .bold)).lineLimit(1)
                if !info.isEmpty { Text(info).font(.system(size: 13)).foregroundColor(.textMid) }
            }
            Spacer()
            Image(systemName: "play.fill").foregroundColor(.ink).frame(width: 36, height: 36).background(Color.textHi, in: Circle())
        }
        .contentShape(Rectangle())
    }

    private func open(_ key: String, _ title: String) {
        settings.addRecent(key, title)
        if app.player.open(key: key, title: title) { path.append(.player) }
    }
}

// MARK: - Settings / BLE

/// Grouped instrument menu: pianos, organs, bells & mallets.
struct InstrumentPicker: View {
    @EnvironmentObject var s: Settings
    let onPick: () -> Void
    var body: some View {
        if let bank = SoundBank.shared {
            Menu {
                ForEach(SoundBank.groups.indices, id: \.self) { gi in
                    Section(SoundBank.groups[gi].1) {
                        ForEach(bank.instruments.filter { $0.group == SoundBank.groups[gi].0 }) { i in
                            Button { s.instrument = i.program; onPick() } label: {
                                if i.program == s.instrument { Label(i.name, systemImage: "checkmark") } else { Text(i.name) }
                            }
                        }
                    }
                }
            } label: {
                HStack {
                    Image(systemName: "pianokeys")
                    Text(bank.name(s.instrument)).bold()
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down").foregroundColor(.textMid)
                }
                .padding(.horizontal, 16).frame(minHeight: 48).background(Color.surf2, in: RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
        } else {
            Text("Âm thanh tổng hợp (thiếu gói âm thanh)").foregroundColor(.textMid)
        }
    }
}

struct SettingsSheet: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var s: Settings
    @Environment(\.dismiss) var dismiss
    let colors = [("#3B82F6", "Xanh dương"), ("#EF4444", "Đỏ"), ("#22C55E", "Xanh lá"), ("#F59E0B", "Vàng"),
                  ("#A855F7", "Tím"), ("#06B6D4", "Cyan"), ("#F97316", "Cam")]
    var body: some View {
        NavigationStack {
            Form {
                Section("Đèn LED") {
                    Picker("Giao thức", selection: $s.ledTransport) {
                        Text("SP611E (BanlanX) – sáng theo vùng").tag("sp611e"); Text("Led Organ – sáng từng phím").tag("legacy_nus")
                        Text("ESP32 (WROOM-32, S3 N16R8 hoặc C3 Super Mini) + WS2812B").tag("ws2812")
                        Text("WLED (Wi-Fi) – điện thoại điều khiển từng đèn").tag("wled")
                        Text("SP801E (BanlanX, Wi-Fi Art-Net) – điều khiển từng đèn").tag("sp801e")
                        Text("SP802E (BanlanX, Bluetooth) – cả dải đổi màu theo tay (thử nghiệm)").tag("sp802e")
                        Text("SP630E (BanlanX, Bluetooth) – cả dải đổi màu theo tay").tag("sp630e")
                        Text("E1.31 / sACN (Wi-Fi) – điều khiển từng đèn").tag("e131")
                        Text("DDP (Wi-Fi) – điều khiển từng đèn").tag("ddp")
                        #if os(macOS)
                        Text("Arduino Uno/Nano – Adalight (USB / HC-05)").tag("adalight")
                        #endif
                    }
                    Picker("Hiệu ứng", selection: $s.ledEffect) {
                        Text("Phổ nốt nhạc").tag("spectrum"); Text("Màu đơn sắc").tag("single"); Text("Phổ nhiều màu").tag("full")
                    }
                    Toggle("Tự kết nối lại đèn LED", isOn: $s.autoReconnect)
                }
                Section {
                    Picker("Mật độ đèn của dải", selection: $s.wsDensity) {
                        Text("144 LED/mét (khuyên dùng)").tag(144); Text("96 LED/mét").tag(96); Text("74 LED/mét").tag(74); Text("60 LED/mét").tag(60); Text("30 LED/mét (nhiều phím dùng chung 1 đèn)").tag(30)
                    }
                    Picker("Số phím của đàn", selection: $s.wsKeys) {
                        Text("88 phím (A0–C8)").tag(88); Text("76 phím (E1–G7)").tag(76); Text("61 phím (C2–C7)").tag(61); Text("49 phím (C2–C6)").tag(49)
                    }
                    Toggle("Đầu cắm dây (DIN) ở phía phím cao", isOn: $s.wsReversed)
                } header: {
                    Text("Dải WS2812B (ESP32-WROOM-32 / S3 / C3 / WLED / Art-Net)")
                } footer: {
                    Text("Dùng với mạch ESP32 nạp chương trình LedPianoStrip. Số đèn và vị trí căn chỉnh nằm trong “Căn đèn LED”.")
                }
                Section("Màu sắc") {
                    Picker("Tay trái", selection: $s.colorLeft) { ForEach(colors.indices, id: \.self) { Text(colors[$0].1).tag(colors[$0].0) } }
                    Picker("Tay phải", selection: $s.colorRight) { ForEach(colors.indices, id: \.self) { Text(colors[$0].1).tag(colors[$0].0) } }
                }
                Section("Nhạc cụ") {
                    InstrumentPicker { app.player.preview() }
                    if let c = SoundBank.shared?.credit { Text("Âm thanh: " + c).font(.caption).foregroundColor(.textMid) }
                }
                Section("Âm thanh & mục tiêu") {
                    Slider(value: Binding(get: { Double(s.volume) }, set: { s.volume = Int($0) }), in: 0...100) { Text("Âm lượng") }
                    Stepper("Mục tiêu mỗi ngày: \(s.dailyGoal) phút", value: $s.dailyGoal, in: 5...60, step: 5)
                }
            }
            .navigationTitle("Cài đặt")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
        .frame(minWidth: 380, minHeight: 460)
    }
}

struct BlePicker: View {
    /// Name advertised by the LedPianoStrip firmware ("LED Piano Strip", "LED Piano ESP32" …).
    static func isEsp32(_ name: String) -> Bool { name.lowercased().hasPrefix("led piano") }
    /// The firmware advertises "LED Piano Strip S3" on an ESP32-S3 (e.g. ESP32-S3-DevKitC N16R8).
    static func isEsp32S3(_ name: String) -> Bool { name.range(of: #"\bS3\b"#, options: [.regularExpression, .caseInsensitive]) != nil }
    /// "LED Piano Strip C3" on an ESP32-C3 (e.g. ESP32-C3 Super Mini).
    static func isEsp32C3(_ name: String) -> Bool { name.range(of: #"\bC3\b"#, options: [.regularExpression, .caseInsensitive]) != nil }
    /// The board behind a "LED Piano Strip…" name.
    static func boardName(_ name: String) -> String {
        isEsp32S3(name) ? "ESP32-S3-DevKitC N16R8" : isEsp32C3(name) ? "ESP32-C3 Super Mini" : "ESP32-WROOM-32"
    }
    @EnvironmentObject var led: LedController
    @EnvironmentObject var settings: Settings
    @Environment(\.dismiss) var dismiss
    let onPick: (UUID, String) -> Void
    @State private var wledIp = ""
    @State private var artNetIp = ""
    @State private var stripHost = "ledpiano.local"
    @State private var pixelIp = ""
    /// ESP32 boards and WLED controllers found on the Wi-Fi (Bonjour, and the broadcast on a Mac).
    @State private var wifiFound: [LedFinder.Found] = []
    @State private var finder = LedFinder()
    @State private var searchingWifi = false
    private var stripFound: [LedFinder.Found] { wifiFound.filter { $0.address.hasPrefix(StripLink.prefix) } }
    private var wledFound: [LedFinder.Found] { wifiFound.filter { $0.address.hasPrefix(WledLink.prefix) } }
    #if os(macOS)
    @State private var usbPorts: [SerialLink.Port] = []
    @State private var adaPorts: [SerialLink.Port] = []
    #endif
    var body: some View {
        NavigationStack {
            List {
                #if os(macOS)
                // ESP32 on a USB cable (like FriendBot's USB link). iPhone/iPad: Bluetooth only.
                Section {
                    if usbPorts.isEmpty {
                        Text("Chưa thấy mạch ESP32 nào cắm qua USB").foregroundColor(.textMid)
                    }
                    ForEach(usbPorts) { p in
                        Button {
                            led.stopScan()
                            led.connectSerial(p.path)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(p.name).bold()
                                Text("LED Piano Strip · WS2812B – điều khiển từng đèn").font(.caption).foregroundColor(Color(hex: "7FB0FF"))
                            }
                        }
                    }
                    Button("Tìm lại cổng USB") { usbPorts = SerialLink.ports() }
                } header: {
                    Text("USB (cáp)")
                } footer: {
                    Text("Cắm mạch ESP32 bằng cáp USB có truyền dữ liệu. Đóng Serial Monitor của Arduino IDE trước, vì chỉ một chương trình dùng được cổng.")
                }
                #endif
                Section {
                    ForEach(wledFound, id: \.address) { f in
                        Button {
                            led.stopScan()
                            led.connectWled(String(f.address.dropFirst(WledLink.prefix.count)))
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(f.name).bold()
                                Text(f.address.dropFirst(WledLink.prefix.count)).font(.caption).foregroundColor(.textMid)
                            }
                        }
                    }
                    if searchingWifi { ProgressView("Đang tìm trên Wi-Fi…") }
                    HStack {
                        TextField("192.168.1.50", text: $wledIp)
                            #if os(iOS)
                            .keyboardType(.decimalPad)
                            #endif
                            .autocorrectionDisabled()
                        Button("Kết nối") {
                            led.stopScan()
                            led.connectWled(wledIp)
                            dismiss()
                        }
                        .disabled(wledIp.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text("WLED (Wi-Fi)")
                } footer: {
                    Text("Bộ điều khiển WLED cùng mạng Wi-Fi tự hiện ở đây; điện thoại điều khiển từng đèn. Nếu không thấy, nhập địa chỉ IP (xem trong ứng dụng WLED).")
                }
                Section {
                    HStack {
                        TextField("192.168.4.1", text: $artNetIp)
                            .autocorrectionDisabled()
                        Button("Kết nối") {
                            led.stopScan()
                            settings.ledTransport = "sp801e" // Art-Net = BanlanX SP801E (any Art-Net node works)
                            led.connectArtNet(artNetIp)
                            dismiss()
                        }
                        .disabled(artNetIp.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text("Art-Net – BanlanX SP801E (Wi-Fi)")
                } footer: {
                    Text("Bật chế độ Art-Net cho SP801E trong ứng dụng BanlanX. Nối Wi-Fi “SP801E_…” (mật khẩu 12345678) thì IP thường là 192.168.4.1. Thêm “/1” nếu dải bắt đầu ở universe 1.")
                }
                // ESP32 LED Piano Strip over Wi-Fi: every feature, like Bluetooth (TCP 7777).
                Section {
                    ForEach(stripFound, id: \.address) { f in
                        Button {
                            led.stopScan()
                            led.connectStrip(String(f.address.dropFirst(StripLink.prefix.count)))
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(f.name).bold()
                                Text(f.address.dropFirst(StripLink.prefix.count)).font(.caption).foregroundColor(.textMid)
                            }
                        }
                    }
                    if searchingWifi { ProgressView("Đang tìm trên Wi-Fi…") }
                    Button("Tìm lại trên Wi-Fi") { searchWifi() }.disabled(searchingWifi)
                    HStack {
                        TextField("ledpiano.local", text: $stripHost).autocorrectionDisabled()
                        Button("Kết nối") {
                            led.stopScan()
                            led.connectStrip(stripHost)
                            dismiss()
                        }
                        .disabled(stripHost.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text("ESP32 LED Piano Strip qua Wi-Fi")
                } footer: {
                    Text("Cùng mạng Wi-Fi với mạch ESP32: mạch tự hiện ở đây. Nếu không thấy, nhập IP của mạch (xem http://ledpiano.local) hoặc để “ledpiano.local”. Qua Wi-Fi vẫn dùng được mọi tính năng: lưu bài, đàn Bluetooth, nháy xanh/đỏ.")
                }
                Section {
                    HStack {
                        TextField("192.168.1.50", text: $pixelIp).autocorrectionDisabled()
                        Button("E1.31") {
                            led.stopScan()
                            led.connectE131(pixelIp)
                            dismiss()
                        }
                        .disabled(pixelIp.trimmingCharacters(in: .whitespaces).isEmpty)
                        Button("DDP") {
                            led.stopScan()
                            led.connectDdp(pixelIp)
                            dismiss()
                        }
                        .disabled(pixelIp.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text("E1.31 / sACN hoặc DDP (Wi-Fi)")
                } footer: {
                    Text("Bộ điều khiển đèn Wi-Fi dùng chuẩn E1.31 hoặc DDP (WLED, ESPixelStick, Falcon, xLights…, cả mạch ESP32 LED Piano Strip). E1.31: thêm “/2” nếu đèn đầu tiên ở universe 2.")
                }
                #if os(macOS)
                // Arduino Uno/Nano with LedPianoAdalight.ino: USB cable or a paired HC-05 (Bluetooth Classic).
                Section {
                    if adaPorts.isEmpty { Text("Chưa thấy cổng nào").foregroundColor(.textMid) }
                    ForEach(adaPorts) { p in
                        Button {
                            led.stopScan()
                            led.connectAdalight(p.path)
                            dismiss()
                        } label: {
                            Text(p.name)
                        }
                    }
                    Button("Tìm lại cổng") { adaPorts = SerialLink.ports(bluetooth: true) }
                } header: {
                    Text("Arduino Uno/Nano (Adalight)")
                } footer: {
                    Text("Nạp LedPianoAdalight.ino vào Arduino. Cắm cáp USB, hoặc ghép đôi module HC-05/HC-06 trong Cài đặt Bluetooth của Mac (mã 1234), rồi chọn cổng ở đây.")
                }
                #endif
                Section("Bluetooth") {
                    if led.found.isEmpty { ProgressView("Đang quét thiết bị…") }
                    // ESP32-WROOM-32 / ESP32-S3-DevKitC / ESP32-C3 Super Mini boards running LedPianoStrip first, clearly named.
                    ForEach(led.found.sorted { Self.isEsp32($0.name) && !Self.isEsp32($1.name) }) { d in
                        Button {
                            led.stopScan()
                            if Self.isEsp32(d.name) { settings.ledTransport = "ws2812" }
                            onPick(d.id, d.name)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(d.name).bold()
                                if Self.isEsp32(d.name) {
                                    Text(Self.boardName(d.name) + " · WS2812B – điều khiển từng đèn")
                                        .font(.caption).foregroundColor(Color(hex: "7FB0FF"))
                                }
                                Text("Tín hiệu \(d.rssi) dBm").font(.caption).foregroundColor(.textMid)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Kết nối với thiết bị")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy bỏ") { led.stopScan(); dismiss() } } }
        }
        .frame(minWidth: 360, minHeight: 420)
        .onAppear {
            led.startScan()
            #if os(macOS)
            usbPorts = SerialLink.ports()
            adaPorts = SerialLink.ports(bluetooth: true)
            #endif
            searchWifi()
            if let a = settings.ledAddress, a.hasPrefix(WledLink.prefix) { wledIp = String(a.dropFirst(WledLink.prefix.count)) }
            if let a = settings.ledAddress, a.hasPrefix(WledLink.artNetPrefix) { artNetIp = String(a.dropFirst(WledLink.artNetPrefix.count)) }
            if let a = settings.ledAddress, a.hasPrefix(StripLink.prefix) { stripHost = String(a.dropFirst(StripLink.prefix.count)) }
            for p in [WledLink.e131Prefix, WledLink.ddpPrefix] {
                if let a = settings.ledAddress, a.hasPrefix(p) { pixelIp = String(a.dropFirst(p.count)) }
            }
        }
    }

    /// ESP32 boards and WLED controllers on the same Wi-Fi (about 2.5 s).
    private func searchWifi() {
        searchingWifi = true
        let f = LedFinder()
        finder = f
        f.find { list in
            wifiFound = list
            searchingWifi = false
        }
    }
}

#if os(iOS)
/// Apple's Bluetooth MIDI pairing screen (iPhone/iPad).
struct BluetoothMidiPairing: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UINavigationController {
        UINavigationController(rootViewController: CABTMIDICentralViewController())
    }
    func updateUIViewController(_ vc: UINavigationController, context: Context) {}
}
#endif

// MARK: - LED wizard

struct LedWizardView: View {
    @EnvironmentObject var led: LedController
    @EnvironmentObject var s: Settings
    @State private var showBle = false

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Card {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(led.state == .connected ? "Đã kết nối: \(led.deviceName ?? "")" : "Chưa kết nối đèn LED").font(.system(size: 17, weight: .heavy))
                        Text(led.state == .connected ? "Làm theo 3 bước bên dưới." : "Bật dải đèn rồi nhấn Kết nối.").foregroundColor(.textMid)
                        HStack {
                            if led.state != .connected { PillButton(title: "Kết nối", primary: true) { showBle = true } }
                            PillButton(title: "Thử đèn") { led.testLights() }
                        }
                    }
                }
                Card {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("1. Số đèn trên dải").font(.system(size: 17, weight: .heavy))
                        Text("Nhấn − / + cho tới khi đèn cuối cùng sáng đúng ở cuối dải.").foregroundColor(.textMid)
                        HStack {
                            PillButton(title: "−") { count(-1) }
                            Text("\(led.isWs ? s.wsNumLeds : s.ledLength)").font(.system(size: 20, weight: .heavy)).frame(maxWidth: .infinity)
                            PillButton(title: "+") { count(1) }
                        }
                    }
                }
                Card {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("2. Đặt đèn lên phím Đô giữa").font(.system(size: 17, weight: .heavy))
                        if led.isWs {
                            Text("Đèn đang sáng ở phím Đô giữa (C4). Nhấn ◀ hoặc ▶ để dời cả dải từng đèn một, tới khi đèn nằm ngay trên phím Đô giữa. “Thử đèn” chạy đúng ví dụ FastLED (đèn 0, 1, 2, 5, 9, 14, 19) rồi chạy qua từng phím.").foregroundColor(.textMid)
                            Text("Đèn đầu: \(s.wsFirstLed)").font(.system(size: 20, weight: .heavy))
                            HStack { PillButton(title: "◀ Sang trái") { shift(-1) }; PillButton(title: "Sang phải ▶") { shift(1) } }
                            HStack {
                                PillButton(title: "Sáng Đô giữa") { led.showKeys([Piano.middleC]) }
                                PillButton(title: "Thử đèn") { led.testLights() }
                            }
                        } else if led.perKey {
                            Text("Một đèn đang sáng cho phím Đô giữa (C4). Nhấn ◀ hoặc ▶ cho tới khi nó nằm ngay trên phím Đô giữa của đàn bạn. Nếu đèn chạy ngược chiều, hãy bấm nút còn lại.").foregroundColor(.textMid)
                            Text("Phím đầu tiên của dải: \(NoteNames.name(s.organOffset, "letters") ?? "")\(NoteNames.octave(s.organOffset))")
                                .font(.system(size: 20, weight: .heavy))
                            HStack { PillButton(title: "◀ Sang trái") { shift(-1) }; PillButton(title: "Sang phải ▶") { shift(1) } }
                            HStack {
                                PillButton(title: "Sáng Đô giữa") { led.showKeys([Piano.middleC]) }
                                PillButton(title: "Sáng 2 đầu") { led.showKeys([Piano.lowest, Piano.highest]) }
                            }
                        } else {
                            Text("Bộ điều khiển SP611E sáng theo 16 vùng chứ không theo từng phím, nên không cần căn từng phím. Hãy dán dải đèn phủ đều toàn bộ bàn phím.")
                                .foregroundColor(.textMid)
                        }
                    }
                }
                Card {
                    VStack(alignment: .leading) {
                        Text("3. Độ sáng").font(.system(size: 17, weight: .heavy))
                        Slider(value: Binding(get: { Double(s.ledBrightness) }, set: { led.setBrightness(Int($0)) }), in: 10...255)
                    }
                }
            }
            .padding(16)
        }
        .background(Color.ink)
        .navigationTitle("Căn đèn LED")
        .sheet(isPresented: $showBle) { BlePicker { id, name in led.connect(id: id, name: name) } }
        .onAppear { if led.state == .connected && led.perKey { led.showKeys([Piano.middleC]) } }
        .onDisappear { led.endManual() }
    }

    private func count(_ d: Int) {
        if led.isWs {
            // WS2812B: number of LEDs on the strip; light both ends so the last key can be checked.
            s.wsNumLeds = s.wsNumLeds + d
            let c = s.wsConfig
            led.showKeys([c.lowestPitch, c.lowestPitch + c.numKeys - 1])
            return
        }
        led.setLength(min(150, max(1, s.ledLength + d)))
        if !led.perKey { led.testLights() }
    }

    private func shift(_ d: Int) {
        if led.isWs {
            s.wsFirstLed = min(300, max(-100, s.wsFirstLed + (s.wsReversed ? -d : d)))
            led.showKeys([Piano.middleC])
            return
        }
        s.organOffset = min(Piano.highest, max(Piano.lowest - 12, s.organOffset + d))
        led.showKeys([Piano.middleC])
    }
}
