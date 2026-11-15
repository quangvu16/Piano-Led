# LED Piano — desktop (Python)

Same app as the Android version, for Windows, macOS and Linux.
Make the window tall for the portrait layout or wide for the landscape layout.

## Run

```bash
cd LedPianoPy
python3 -m pip install -r requirements.txt
python3 -m ledpiano
```

- **Digital piano:** plug it in with USB; it is found automatically. Bluetooth MIDI pianos:
  pair them first (macOS: Audio MIDI Setup › MIDI Studio › Bluetooth).
- **LED strip:** Bluetooth LE through `bleak`; SP611E/BanlanX and the older per-key (NUS) controller.
- **Keys:** Space = play/pause (or next note in Step mode); → / Page Down / Enter = next note
  (Bluetooth page-turner pedals send these). Mouse wheel on the falling notes = zoom.
- Settings, stats and progress are saved in `~/.ledpiano/`.

## Tests

```bash
python3 -m pip install pytest && python3 -m pytest -q tests
```

## Chọn bài bằng giọng nói (Xiaozhi)

Nút **🎤 Nói tên bài** ở màn hình chính mở trang trò chuyện với Xiaozhi (giống trang Voice Control của FriendBot).

1. Lần đầu: ứng dụng hiện mã 6 số → vào **xiaozhi.me** → *Thêm thiết bị* → nhập mã.
2. Nói: “Mở bài Ngôi sao lấp lánh”, “Có những bài nào?”, “Chơi bài Chúc mừng sinh nhật luôn nhé”.
3. Xiaozhi trả lời bằng giọng nói và mở bài (công cụ MCP `self.piano.list_songs`, `self.piano.open_song`).

Cần thêm: `pip install websockets` và thư viện Opus (macOS: `brew install opus`; Windows: đặt `opus.dll` cạnh ứng dụng; Linux: `sudo apt install libopus0`).
