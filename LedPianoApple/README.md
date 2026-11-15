# LED Piano — iPhone, iPad and Mac (SwiftUI)

One Xcode project, one app target that builds for **iOS 17+** and **macOS 14+**.
Same features as the Android app; turn the iPhone/iPad (or make the Mac window wide)
for the landscape layout.

## Build

1. Open `LedPiano.xcodeproj` in Xcode 16 or newer.
2. Select the **LedPiano** target › Signing & Capabilities › choose your Team.
3. Pick a destination: your iPhone/iPad, or **My Mac** — then press Run (⌘R).

If Xcode cannot open the project file, run `brew install xcodegen && xcodegen` in this
folder to regenerate it from `project.yml`.

## Notes

- **LED strip:** CoreBluetooth; SP611E/BanlanX and the older per-key (NUS) controller.
- **Digital piano:** USB (iPhone/iPad: USB‑C or Lightning camera adapter; Mac: USB) is found
  automatically. Bluetooth MIDI: iOS — Options › "Tìm đàn Bluetooth MIDI";
  Mac — Audio MIDI Setup › MIDI Studio › Bluetooth.
- **Microphone:** single notes only (experimental).
- Lesson songs and samples live in `LedPiano/Resources`.

## Chọn bài bằng giọng nói (Xiaozhi)

Thẻ **Nói tên bài** ở màn hình chính mở trang trò chuyện với Xiaozhi (giống trang Voice Control của FriendBot):
lần đầu nhập mã 6 số ở **xiaozhi.me → Thêm thiết bị**, sau đó chỉ cần nói tên bài.

Âm thanh Opus dùng gói Swift **swift-opus** (github.com/alta/swift-opus, có sẵn trong project).
Lần mở đầu tiên Xcode sẽ tự tải gói này (cần Internet). Nếu Xcode báo lỗi gói: *File → Packages → Resolve Package Versions*.
