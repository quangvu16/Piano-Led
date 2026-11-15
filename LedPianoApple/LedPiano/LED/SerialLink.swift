#if os(macOS)
import Darwin
import Foundation

/// The ESP32 "LED Piano Strip" on a USB cable (Mac only). It uses the same "A5 op len … chk"
/// messages as Bluetooth, at 115200 baud 8N1 (like the Python app and FriendBot's USB link).
///
/// iPhone / iPad cannot do this: iOS does not let apps open USB serial chips (CP210x, CH340, the
/// ESP32's own USB), so there the strip is connected by Bluetooth.
///
/// DTR and RTS are switched OFF after opening: on ESP32 dev boards they drive the reset (EN) and
/// BOOT (GPIO0) pins, and GPIO0 is also the firmware's practice button. Opening the port can still
/// restart some boards, so the link waits 1.5 s before it reports "connected".
final class SerialLink {
    struct Port: Identifiable { let path: String; let name: String; var id: String { path } }

    static let prefix = "serial:"
    /// TIOCMSET = _IOW('t', 109, int): set the modem lines (the C macro is not visible to Swift).
    private static let tiocmset: UInt = 0x8004_746D
    private var fd: Int32 = -1
    private let io = DispatchQueue(label: "led.serial")
    private var reading = false
    private let onData: (Data) -> Void
    private let onState: (Bool, String?) -> Void

    /// USB serial ports that could be an ESP32 (CP210x, CH340/CH9102, ESP32-S3 native USB …).
    /// `bluetooth`: also paired Bluetooth Classic serial modules (HC-05 / HC-06 appear as /dev/cu.HC-05 …).
    static func ports(bluetooth: Bool = false) -> [Port] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: "/dev")) ?? []
        let usb = names.filter { n in
            n.hasPrefix("cu.") && ["usbserial", "SLAB_USBtoUART", "wchusbserial", "usbmodem"].contains { n.contains($0) }
        }.sorted().map { n in
            // ESP32-S3-DevKitC N16R8: "USB" port = cu.usbmodem…, "COM" port = CP2102N (cu.SLAB… / cu.usbserial…) or CH343 (cu.wchusbserial…)
            let chip = n.contains("SLAB") ? "CP210x (ESP32 / cổng COM của S3)" : n.contains("wch") ? "CH340 / CH343 (cổng COM của S3)"
                : n.contains("usbmodem") ? "ESP32-S3 / C3 cổng USB / Arduino" : "USB serial"
            return Port(path: "/dev/" + n, name: "USB · \(chip) · \(n.dropFirst(3))")
        }
        guard bluetooth else { return usb }
        let skip = ["usbserial", "SLAB_USBtoUART", "wchusbserial", "usbmodem", "Bluetooth-Incoming-Port", "debug-console", "wlan"]
        let bt = names.filter { n in n.hasPrefix("cu.") && !skip.contains { n.contains($0) } }.sorted().map { n in
            Port(path: "/dev/" + n, name: "Bluetooth · \(n.dropFirst(3))")
        }
        return usb + bt
    }

    /// `onData` and `onState(connected, error)` are called on the main queue.
    init(path: String, onData: @escaping (Data) -> Void, onState: @escaping (Bool, String?) -> Void) {
        self.onData = onData
        self.onState = onState
        io.async { self.open(path) }
    }

    private func open(_ path: String) {
        let f = Darwin.open(path, O_RDWR | O_NOCTTY | O_NONBLOCK)
        guard f >= 0 else { fail("Không mở được cổng \(path) — cổng đang được dùng (Arduino IDE?) hoặc đã rút cáp"); return }
        var t = termios()
        tcgetattr(f, &t)
        cfmakeraw(&t)
        cfsetspeed(&t, speed_t(B115200))
        t.c_cflag |= tcflag_t(CLOCAL | CREAD)
        t.c_cflag &= ~tcflag_t(CCTS_OFLOW | CRTS_IFLOW) // no hardware flow control
        // Read returns at once with whatever has arrived (VMIN = 0, VTIME = 1 → 0.1 s).
        withUnsafeMutableBytes(of: &t.c_cc) { cc in
            cc[Int(VMIN)] = 0
            cc[Int(VTIME)] = 1
        }
        guard tcsetattr(f, TCSANOW, &t) == 0 else { Darwin.close(f); fail("Không cài đặt được cổng USB"); return }
        var lines: Int32 = 0 // DTR and RTS off (see above)
        _ = ioctl(f, Self.tiocmset, &lines)
        _ = fcntl(f, F_SETFL, fcntl(f, F_GETFL) & ~O_NONBLOCK)
        fd = f
        Thread.sleep(forTimeInterval: 1.5) // the ESP32 may have restarted
        guard fd >= 0 else { return }
        reading = true
        DispatchQueue.main.async { self.onState(true, nil) }
        Thread.detachNewThread { [weak self] in self?.readLoop(f) }
    }

    private func readLoop(_ f: Int32) {
        var buf = [UInt8](repeating: 0, count: 256)
        while reading {
            let n = read(f, &buf, buf.count)
            if n > 0 {
                let d = Data(buf[0..<n])
                DispatchQueue.main.async { self.onData(d) }
            } else if n < 0 && errno != EAGAIN && errno != EINTR {
                // Cable pulled out.
                io.async { if self.fd == f { self.closePort() } }
                DispatchQueue.main.async { self.onState(false, "Đã rút cáp USB") }
                return
            }
        }
    }

    func send(_ data: Data) {
        io.async {
            guard self.fd >= 0 else { return }
            let bytes = [UInt8](data)
            var off = 0
            while off < bytes.count {
                let n = bytes[off...].withUnsafeBufferPointer { buf -> Int in
                    guard let p = buf.baseAddress else { return 0 }
                    return write(self.fd, p, buf.count)
                }
                if n <= 0 { if errno == EAGAIN || errno == EINTR { usleep(1000); continue }; return }
                off += n
            }
        }
    }

    func close() { io.async { self.closePort() } }

    private func closePort() {
        reading = false
        if fd >= 0 { Darwin.close(fd) }
        fd = -1
    }

    private func fail(_ message: String) { DispatchQueue.main.async { self.onState(false, message) } }
}
#endif
