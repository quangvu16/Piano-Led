import SwiftUI

/// Practice screen. Tall window / portrait = controls under the keyboard;
/// wide / landscape = controls in a rail on the right (wider keyboard).
struct PlayerView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var m: PlayerModel
    @EnvironmentObject var s: Settings
    @EnvironmentObject var led: LedController
    @EnvironmentObject var hub: InputHub
    @Environment(\.dismiss) private var dismiss
    @State private var showOptions = false
    @State private var showBle = false
    @State private var scrubStart: Double?
    /// Landscape: the right-side controls slide away while practising.
    @State private var panelHidden = false
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        GeometryReader { geo in
            let land = geo.size.width > geo.size.height * 1.1
            VStack(spacing: 0) {
                topBar(land: land)
                if !land { progress.padding(.horizontal, 16) }
                if m.looping, let r = m.loopRange, let song = m.song {
                    LoopBar(duration: song.duration, snaps: m.measures, range: r, hot: m.hotSpans) { a, b in m.setLoop(a, b) }
                        .padding(.horizontal, 8)
                }
                if land {
                    HStack(spacing: 0) {
                        music(keyboardHeight: 112)
                            .overlay(alignment: .trailing) {
                                if panelHidden {
                                    Button { showPanel(thenHideAfter: 4) } label: {
                                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .bold))
                                            .frame(width: 44, height: 64).background(Color.surf2, in: RoundedRectangle(cornerRadius: 12))
                                    }
                                    .buttonStyle(.plain).padding(.trailing, 6)
                                    .accessibilityLabel("Hiện bảng điều khiển")
                                }
                            }
                        if !panelHidden {
                            controls(land: true).frame(width: 270).transition(.move(edge: .trailing))
                        }
                    }
                    .animation(.easeInOut(duration: 0.25), value: panelHidden)
                } else {
                    music(keyboardHeight: 150)
                    controls(land: false)
                }
            }
            .onAppear { m.landscape = land }
            .onChange(of: land) { _, v in
                m.landscape = v
                if !v { panelHidden = false }
            }
            .onChange(of: m.playing) { _, playing in
                if playing { scheduleHide(after: 2) } else { hideTask?.cancel(); panelHidden = false }
            }
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .sheet(isPresented: $showOptions) { OptionsSheet() }
        .sheet(isPresented: $showBle) { BlePicker { id, name in led.connect(id: id, name: name) } }
        .alert("Chế độ Chờ phím cần nối đàn", isPresented: $m.needsWaitChoice) {
            Button("Dùng Từng bước") { m.useStepInstead() }
            Button("Tùy chọn") { showOptions = true }
            Button("Hủy bỏ", role: .cancel) {}
        } message: {
            Text("Cắm đàn điện bằng cáp USB hoặc Bluetooth MIDI, hoặc bật nghe qua micro. Bạn có muốn dùng chế độ Từng bước (chạm để đi tiếp) thay thế?")
        }
        .alert("Hoàn thành bài!", isPresented: Binding(get: { m.result != nil }, set: { if !$0 { m.result = nil } })) {
            Button("Chơi lại") { m.restart(); m.togglePlay() }
            if let next = m.result?.next {
                Button("Bài tiếp theo") {
                    s.addRecent(next.key, next.title)
                    _ = m.open(key: next.key, title: next.title)
                }
            }
            if m.coach.lights != "off" { Button("Thử không cần đèn") { m.lightsOffRun() } }
            Button("Đóng", role: .cancel) {}
        } message: { Text(m.result?.message ?? "") }
        .alert(m.alert ?? "", isPresented: Binding(get: { m.alert != nil }, set: { if !$0 { m.alert = nil } })) {
            Button("Đồng ý", role: .cancel) {}
        }
        .alert("Đã ghi âm", isPresented: Binding(get: { m.savedRecording != nil }, set: { if !$0 { m.savedRecording = nil } })) {
            if let r = m.savedRecording {
                Button("Mở để nghe lại") { _ = m.open(key: r.key, title: r.title) }
            }
            Button("Đóng", role: .cancel) {}
        } message: {
            Text("Đã lưu \(m.savedRecording?.notes ?? 0) nốt bạn chơi thành tệp MIDI. Bản ghi có trong danh sách bài của bạn.")
        }
        .onDisappear { m.close() }
        .focusable()
        .onKeyPress(.space) { m.engine?.waitingChord != nil && s.practiceMode == "step" ? m.stepForward() : m.togglePlay(); return .handled }
        .onKeyPress(.rightArrow) { m.tapOrPedal(); return .handled }
        .onKeyPress(.downArrow) { m.tapOrPedal(); return .handled }
        .onKeyPress(.return) { m.tapOrPedal(); return .handled }
    }

    // MARK: auto-hiding side panel

    private func scheduleHide(after seconds: Double) {
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
            if !Task.isCancelled && m.playing && m.landscape { panelHidden = true }
        }
    }

    private func showPanel(thenHideAfter seconds: Double) {
        panelHidden = false
        if m.playing { scheduleHide(after: seconds) }
    }

    // MARK: top bar

    private func topBar(land: Bool) -> some View {
        HStack(spacing: 8) {
            Button { m.close(); dismiss() } label: { Image(systemName: "chevron.left").font(.system(size: 20, weight: .bold)).frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Quay lại")
            Text(m.title).font(.system(size: 18, weight: .heavy)).lineLimit(1)
                .frame(maxWidth: land ? 200 : .infinity, alignment: .leading)
            if land { progress }
            Button { showOptions = true } label: {
                Image(systemName: "slider.horizontal.3").frame(width: 44, height: 44).background(Color.surf2, in: Circle())
            }.buttonStyle(.plain).accessibilityLabel("Tùy chọn luyện tập")
            Button { if led.state != .connected { showBle = true } } label: {
                HStack(spacing: 6) {
                    Circle().fill(led.state == .connected ? Color(hex: "34D399") : .textMid).frame(width: 8, height: 8)
                    Text(led.state == .connected ? "LED bật" : led.state == .connecting ? "Đang kết nối…" : "LED tắt")
                        .font(.system(size: 13, weight: .bold))
                }
                .foregroundColor(led.state == .connected ? .okFg : .textMid)
                .padding(.horizontal, 14).frame(height: 44)
                .background(led.state == .connected ? Color.okBg : Color.surf2, in: Capsule())
            }.buttonStyle(.plain)
        }
        .padding(.horizontal, 8).padding(.top, 6)
    }

    private var progress: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            HStack(spacing: 10) {
                Text(fmt(m.position)).font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
                Slider(value: Binding(get: { m.position }, set: { m.seek($0) }), in: 0...max(0.1, m.song?.duration ?? 1))
                Text(fmt(m.song?.duration ?? 0)).font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
            }
        }
    }

    // MARK: music area

    private func drawState(_ pos: Double) -> DrawState {
        DrawState(song: m.song, pos: pos, geom: KeyGeom(low: m.range.low, high: m.range.high),
                  colors: [.left: Color(hex: s.colorLeft), .right: Color(hex: s.colorRight)],
                  showLeft: m.practice != "right", showRight: m.practice != "left", guidance: m.guidance,
                  labelMode: s.labelMode, fingering: s.showFingers ? m.fingering : [:], loop: m.loopRange, measures: m.measures)
    }

    private func music(keyboardHeight: CGFloat) -> some View {
        TimelineView(.animation) { _ in
            let st = drawState(m.position)
            let (up, fingers) = m.upcomingAndFingers(at: st.pos)
            VStack(spacing: 0) {
                if s.showStaff {
                    Canvas { ctx, size in Draw.staff(ctx, size, st) }.frame(height: keyboardHeight < 140 ? 88 : 120)
                }
                Canvas { ctx, size in Draw.roll(ctx, size, st) }
                    .overlay(alignment: .topLeading) { modeChip }
                    .overlay(alignment: .topTrailing) {
                        if let f = m.feedback {
                            Text(f.text).font(.system(size: 12, weight: .bold)).padding(.horizontal, 10).padding(.vertical, 4)
                                .background(f.good ? Color.okBg : Color(hex: "3A1416"), in: Capsule()).padding(8)
                        }
                    }
                    .overlay {
                        if let o = m.overlay {
                            Text(o).font(.system(size: m.overlayBig ? 56 : 22, weight: .heavy))
                                .padding(.horizontal, 16).padding(.vertical, 8)
                                .background(Color.ink.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 4)
                        .onChanged { v in
                            if scrubStart == nil { scrubStart = m.position }
                            m.seek(max(0, (scrubStart ?? 0) + Double(v.translation.height / Draw.pxPerSec)))
                        }
                        .onEnded { _ in scrubStart = nil })
                    .simultaneousGesture(TapGesture().onEnded { m.tapOrPedal() })
                Canvas { ctx, size in
                    Draw.keyboard(ctx, size, st, active: m.active, upcoming: up, pressed: m.pressed, fingers: fingers)
                }
                .frame(height: keyboardHeight)
                .accessibilityLabel("Bàn phím đàn")
            }
        }
    }

    @ViewBuilder private var modeChip: some View {
        let t = m.effectiveMode == "wait" ? "Chờ phím" : m.effectiveMode == "step" ? "Từng bước · chạm để đi tiếp" : nil
        if let t {
            Text(t).font(.system(size: 12, weight: .bold)).padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color.surf2, in: Capsule()).padding(8)
        }
    }

    // MARK: controls

    private func controls(land: Bool) -> some View {
        let hands = Segmented(items: [("left", "● Trái"), ("both", "Cả hai"), ("right", "● Phải")], selected: m.practice) { m.setPractice($0) }
        let speed = HStack(spacing: 4) {
            stepButton("−", "Chậm hơn", enabled: m.speed > Engine.minSpeed) { m.setSpeed(m.speed - 5) }
            Text("\(m.speed)%").font(.system(size: 16, weight: .heavy)).frame(maxWidth: .infinity)
            stepButton("+", "Nhanh hơn", enabled: m.speed < Engine.maxSpeed) { m.setSpeed(m.speed + 5) }
        }.padding(4).background(Color.ink, in: RoundedRectangle(cornerRadius: 14))
        let transport = HStack(alignment: .top, spacing: land ? 14 : 30) {
            round("arrow.counterclockwise", "Về đầu", size: land ? 46 : 52, on: false) { m.restart() }
            VStack(spacing: 6) {
                Button { m.togglePlay() } label: {
                    Image(systemName: m.playing ? "pause.fill" : "play.fill").font(.system(size: 28)).foregroundColor(.ink)
                        .frame(width: land ? 68 : 72, height: land ? 68 : 72).background(Color.textHi, in: Circle())
                }.buttonStyle(.plain).accessibilityLabel(m.playing ? "Tạm dừng" : "Phát")
                Text(m.playing ? "Tạm dừng" : "Phát").font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
            }
            round("repeat", "Lặp lại", size: land ? 46 : 52, on: m.looping) { m.toggleRepeat() }
        }
        // Learning tools: loop the hardest bar slowly, record what you play.
        let tools = HStack(spacing: 10) {
            PillButton(title: "🎯 Chỗ khó") { m.practiseHotSpot() }
                .accessibilityLabel("Luyện chỗ khó: lặp lại ô nhịp hay sai nhất, chậm lại")
            PillButton(title: m.recording ? "⏹ Dừng ghi" : "⏺ Ghi âm", primary: m.recording) { m.toggleRecord() }
                .accessibilityLabel("Ghi âm phần bạn chơi thành tệp MIDI")
        }
        return Group {
            if land {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Tay").font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
                    hands
                    Text("Tốc độ").font(.system(size: 12, weight: .bold)).foregroundColor(.textMid).padding(.top, 6)
                    speed
                    Spacer()
                    transport.frame(maxWidth: .infinity)
                    tools
                }
                .padding(12)
            } else {
                VStack(spacing: 10) {
                    HStack { Text("Tay").font(.system(size: 13, weight: .bold)).foregroundColor(.textMid).frame(width: 52, alignment: .leading); hands }
                    HStack { Text("Tốc độ").font(.system(size: 13, weight: .bold)).foregroundColor(.textMid).frame(width: 52, alignment: .leading); speed }
                    transport.padding(.top, 8)
                    tools
                }
                .padding(16)
            }
        }
        .frame(maxHeight: land ? .infinity : nil)
        .background(Color.surf1)
    }

    private func stepButton(_ t: String, _ label: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(t).font(.system(size: 20, weight: .bold)).frame(maxWidth: .infinity, minHeight: 40)
        }
        .buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.4).accessibilityLabel(label)
    }

    private func round(_ icon: String, _ label: String, size: CGFloat, on: Bool, _ action: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: icon).font(.system(size: 20, weight: .bold)).foregroundColor(on ? .ink : .textHi)
                    .frame(width: size, height: size).background(on ? Color.textHi : Color.surf2, in: Circle())
            }.buttonStyle(.plain).accessibilityLabel(label)
            Text(label).font(.system(size: 12, weight: .bold)).foregroundColor(.textMid)
        }
        .padding(.top, 10)
    }

    private func fmt(_ t: Double) -> String { let s = max(0, Int(t)); return "\(s / 60):" + String(format: "%02d", s % 60) }
}

/// Tùy chọn luyện tập: modes, lights, note names, extras, piano connection.
struct OptionsSheet: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var m: PlayerModel
    @EnvironmentObject var s: Settings
    @EnvironmentObject var hub: InputHub
    @Environment(\.dismiss) var dismiss
    @State private var pairing = false

    private let hints = [
        "normal": "Bài chạy theo nhịp. Nếu nối đàn, ứng dụng chấm điểm từng nốt.",
        "wait": "Bài dừng ở mỗi nốt cho tới khi bạn bấm đúng phím trên đàn (cần nối đàn hoặc bật micro).",
        "step": "Bài dừng ở mỗi nốt; chạm vào màn hình hoặc bấm bàn đạp lật trang để đi tiếp.",
        "full": "Luôn hiện phím cần bấm.",
        "fade": "Đèn mờ dần khi bạn chơi đúng và sáng lại khi sai, để bạn không phụ thuộc vào đèn.",
        "off": "Bài kiểm tra: tự chơi không cần đèn.",
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    section("Âm thanh nhạc cụ")
                    InstrumentPicker { m.optionChanged("instrument") }
                    section("Cách luyện")
                    Segmented(items: [("normal", "Tự chạy"), ("wait", "Chờ phím"), ("step", "Từng bước")], selected: s.practiceMode) {
                        s.practiceMode = $0; m.optionChanged("mode")
                    }
                    Text(hints[s.practiceMode] ?? "").font(.system(size: 13)).foregroundColor(.textMid)
                    section("Đèn hướng dẫn")
                    Segmented(items: [("full", "Đầy đủ"), ("fade", "Mờ dần"), ("off", "Tắt")], selected: s.lightsMode) {
                        s.lightsMode = $0; m.optionChanged("lights")
                    }
                    Text(hints[s.lightsMode] ?? "").font(.system(size: 13)).foregroundColor(.textMid)
                    section("Tên nốt")
                    Segmented(items: [("none", "Không"), ("c", "Chỉ Đô"), ("letters", "C D E"), ("solfege", "Đô Rê Mi")], selected: s.labelMode) {
                        s.labelMode = $0; m.optionChanged("labels")
                    }
                    section("Khác")
                    Toggle("Đếm nhịp trước khi chơi", isOn: $s.countIn)
                    Toggle("Máy đếm nhịp", isOn: Binding(get: { s.metronome }, set: { s.metronome = $0; m.optionChanged("metronome") }))
                    Toggle("Tự tăng tốc sau 3 lần lặp đúng", isOn: $s.autoSpeed)
                    Toggle("Hiện khuông nhạc", isOn: $s.showStaff)
                    Toggle("Hiện số ngón tay", isOn: $s.showFingers)
                    section("Đèn LED")
                    Toggle("Nháy màu khi bấm: xanh = đúng lúc, cam = sớm/trễ, đỏ = sai phím",
                           isOn: Binding(get: { s.ledFeedback }, set: { s.ledFeedback = $0; m.optionChanged("led") }))
                    Toggle("Phím kế tiếp sáng mờ trước (nhiều hơn 1 đèn mỗi tay)",
                           isOn: Binding(get: { s.ledPreview }, set: { s.ledPreview = $0; m.optionChanged("led") }))
                    Toggle("Nốt mạnh sáng hơn, nốt nhẹ mờ hơn",
                           isOn: Binding(get: { s.ledDynamics }, set: { s.ledDynamics = $0; m.optionChanged("led") }))
                    PillButton(title: "Gửi bài này vào ESP32 (tập không cần điện thoại)") { m.sendSongToEsp32(); dismiss() }
                    if let song = m.song, HandAssigner.usesPitchSplit(song) {
                        Stepper("Chia tay trái / phải tại \(NoteNames.name(s.handSplit, "letters") ?? "")\(NoteNames.octave(s.handSplit))",
                                value: Binding(get: { s.handSplit }, set: { s.handSplit = $0; m.optionChanged("split") }), in: 36...84)
                    }
                    section("Đàn của bạn")
                    Text(hub.source == .midi ? "Đã nối đàn: \(hub.deviceName ?? "MIDI")" : hub.source == .mic ? "Đang nghe qua micro" : "Chưa nối đàn")
                    #if os(iOS)
                    Text("Cắm đàn điện vào iPhone/iPad bằng cáp USB — ứng dụng tự nhận. Hoặc ghép đàn Bluetooth MIDI:").font(.system(size: 13)).foregroundColor(.textMid)
                    PillButton(title: "Tìm đàn Bluetooth MIDI") { pairing = true }
                    #else
                    Text("Cắm đàn điện vào máy Mac bằng cáp USB — ứng dụng tự nhận. Đàn Bluetooth MIDI: ghép trong Audio MIDI Setup › MIDI Studio › Bluetooth.")
                        .font(.system(size: 13)).foregroundColor(.textMid)
                    #endif
                    Toggle("Nghe qua micro (đàn cơ, từng nốt đơn, thử nghiệm)", isOn: Binding(get: { s.micInput }, set: { on in
                        s.micInput = on
                        if on { app.mic.start { ok in if !ok { s.micInput = false } } } else { app.mic.stop() }
                    }))
                }
                .padding(20)
            }
            .background(Color.surf1)
            .navigationTitle("Tùy chọn luyện tập")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
            #if os(iOS)
            .sheet(isPresented: $pairing, onDismiss: { app.midi.reconnect() }) { BluetoothMidiPairing() }
            #endif
        }
        .frame(minWidth: 400, minHeight: 600)
    }

    private func section(_ t: String) -> some View {
        Text(t).font(.system(size: 13, weight: .bold)).foregroundColor(.textMid).padding(.top, 8)
    }
}
