"""Choose a song by voice with Xiaozhi, like FriendBot's Voice Control page.

The app pairs once with xiaozhi.me (6-digit code), then keeps a websocket
conversation open: the microphone goes up as raw opus (16 kHz mono, 60 ms
frames), Xiaozhi does the speech recognition and the thinking, answers with
its own voice (opus down) and drives the app through two MCP tools:

* ``self.piano.list_songs`` – every song the learner can open
* ``self.piano.open_song {title, autoplay}`` – opens the best match

Protocol: 78/xiaozhi-esp32 docs/websocket.md + mcp-protocol.md, as used by
FriendBot (XiaozhiActivator / XiaozhiSession). Same logic as the Android and
iPhone/Mac apps.

Needs ``websockets`` (pip) and the libopus library
(macOS: ``brew install opus``, Windows: opus.dll next to the app or on PATH,
Linux: ``apt install libopus0``).
"""
from __future__ import annotations

import asyncio
import ctypes
import ctypes.util
import hashlib
import hmac
import json
import os
import random
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from collections import deque

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
MIC_RATE = 16000
FRAME_MS = 60
FRAME_SAMPLES = MIC_RATE * FRAME_MS // 1000  # 960

# ---------------------------------------------------------------- song matching

MIN_SCORE = 0.55
_FILLER = {
    "mo", "bai", "hat", "choi", "phat", "cho", "toi", "minh", "em", "nhe", "di", "voi", "nhac", "ban", "nghe",
    "hay", "giup", "bat", "dau", "tap", "luyen", "song", "play", "open", "the", "a", "please", "start",
    "practice", "piece", "lesson",
}


def normalize(text: str) -> str:
    """Lower-case, no accents (đ → d), only letters/digits separated by single spaces."""
    t = unicodedata.normalize("NFD", text.replace("đ", "d").replace("Đ", "D"))
    t = "".join(c for c in t if unicodedata.category(c) != "Mn").lower()
    out = "".join(c if c.isalnum() else " " for c in t)
    return " ".join(out.split())


def _tokens(text: str, drop_filler: bool) -> list[str]:
    t = normalize(text).split()
    if not drop_filler:
        return t
    kept = [w for w in t if w not in _FILLER]
    return kept or t


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return len(a) or len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def _word_sim(a: str, b: str) -> float:
    if a == b:
        return 1.0
    if len(a) < 3 or len(b) < 3:
        return 0.0
    return 1 - levenshtein(a, b) / max(len(a), len(b))


def _score(q: list[str], c: list[str]) -> float:
    if not c:
        return 0.0
    qs, cs = " ".join(q), " ".join(c)
    if qs == cs:
        return 1.0
    hits, used = 0.0, [False] * len(c)
    for w in q:
        best_j, best = -1, 0.0
        for j, cw in enumerate(c):
            if not used[j]:
                s = _word_sim(w, cw)
                if s > best:
                    best, best_j = s, j
        if best_j >= 0 and best >= 0.75:
            used[best_j] = True
            hits += best
    precision, recall = hits / len(q), hits / len(c)
    f1 = 0.0 if hits == 0 else 2 * precision * recall / (precision + recall)
    covered = 0.7 + 0.3 * recall if precision >= 0.999 else 0.0
    contained = 0.97 if len(cs) >= 4 and f" {cs} " in f" {qs} " else 0.0
    chars = 1 - levenshtein(qs, cs) / max(len(qs), len(cs))
    return max(f1, covered, contained, chars * 0.9)


def rank_songs(query: str, songs: list[dict]) -> list[tuple[dict, float]]:
    """songs: [{"key", "title", "subtitle"}]; best first, zero scores dropped."""
    q = _tokens(query, True)
    if not q:
        return []
    out = []
    for s in songs:
        title, sub = _tokens(s["title"], False), _tokens(s.get("subtitle", ""), False)
        sc = max(_score(q, title), _score(q, sub) * 0.95, _score(q, title + sub) * 0.97)
        if sc > 0:
            out.append((s, sc))
    return sorted(out, key=lambda m: -m[1])


def best_song(query: str, songs: list[dict]) -> dict | None:
    r = rank_songs(query, songs)
    return r[0][0] if r and r[0][1] >= MIN_SCORE else None


def is_ambiguous(ranked) -> bool:
    return len(ranked) >= 2 and ranked[0][1] - ranked[1][1] < 0.02 and ranked[0][1] < 0.999


# ---------------------------------------------------------------- opus (ctypes)

def _load_opus():
    names = [ctypes.util.find_library("opus"), "libopus.so.0", "libopus.0.dylib",
             "/opt/homebrew/lib/libopus.dylib", "/usr/local/lib/libopus.dylib",
             "/opt/homebrew/opt/opus/lib/libopus.dylib", "opus.dll", "libopus-0.dll",
             os.path.join(os.path.dirname(os.path.abspath(sys.argv[0] or ".")), "opus.dll")]
    for n in names:
        if not n:
            continue
        try:
            lib = ctypes.CDLL(n)
        except OSError:
            continue
        lib.opus_encoder_create.restype = ctypes.c_void_p
        lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        lib.opus_encode.restype = ctypes.c_int32
        lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int,
                                    ctypes.c_char_p, ctypes.c_int32]
        lib.opus_decoder_create.restype = ctypes.c_void_p
        lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        lib.opus_decode.restype = ctypes.c_int
        lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                                    ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
        lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
        lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
        return lib
    return None


_OPUS = _load_opus()
OPUS_HELP = ("Thiếu thư viện Opus. macOS: chạy `brew install opus`; "
             "Windows: đặt opus.dll cạnh ứng dụng; Linux: `sudo apt install libopus0`.")


class OpusCodec:
    """16 kHz mono 60 ms frames up; server rate down."""

    def __init__(self, playback_rate: int = 16000):
        if _OPUS is None:
            raise RuntimeError(OPUS_HELP)
        err = ctypes.c_int(0)
        self._enc = _OPUS.opus_encoder_create(MIC_RATE, 1, 2048, ctypes.byref(err))  # OPUS_APPLICATION_VOIP
        if err.value != 0 or not self._enc:
            raise RuntimeError("opus_encoder_create failed")
        self._dec = _OPUS.opus_decoder_create(playback_rate, 1, ctypes.byref(err))
        if err.value != 0 or not self._dec:
            raise RuntimeError("opus_decoder_create failed")
        self.playback_rate = playback_rate
        self._out = ctypes.create_string_buffer(1276)
        self._pcm = (ctypes.c_int16 * (playback_rate * 120 // 1000 + 16))()

    def encode(self, pcm_bytes: bytes) -> bytes | None:
        """pcm_bytes: FRAME_SAMPLES int16 little-endian samples."""
        buf = (ctypes.c_int16 * FRAME_SAMPLES).from_buffer_copy(pcm_bytes[:FRAME_SAMPLES * 2])
        n = _OPUS.opus_encode(self._enc, buf, FRAME_SAMPLES, self._out, 1276)
        return self._out.raw[:n] if n > 0 else None

    def decode(self, packet: bytes) -> bytes:
        n = _OPUS.opus_decode(self._dec, packet, len(packet), self._pcm, len(self._pcm), 0)
        return bytes(memoryview(self._pcm).cast("B")[:n * 2]) if n > 0 else b""

    def __del__(self):
        try:
            _OPUS.opus_encoder_destroy(self._enc)
            _OPUS.opus_decoder_destroy(self._dec)
        except Exception:
            pass


# ---------------------------------------------------------------- pairing

class Identity:
    """Device identity + saved websocket credentials, kept in the app's settings store."""

    def __init__(self, store):
        self.s = store

    def _get(self, key, make):
        v = self.s.data.get(key)
        if not v:
            v = make()
            self.s.data[key] = v
            self.s.save()
        return v

    @property
    def device_id(self):
        def make():
            b = [random.randrange(256) for _ in range(6)]
            b[0] = (b[0] & 0xFC) | 0x02
            return ":".join(f"{x:02x}" for x in b)
        return self._get("xz_device_id", make)

    @property
    def client_id(self):
        return self._get("xz_client_id", lambda: str(uuid.uuid4()))

    @property
    def hmac_key(self):
        return self._get("xz_hmac_key", lambda: hashlib.sha256(
            f"{self.device_id}|{self.client_id}|{time.time()}".encode()).hexdigest())

    @property
    def saved(self):
        t = self.s.data.get("xz_ws_token")
        return ((self.s.data.get("xz_ws_url") or DEFAULT_WS_URL), t) if t else None

    def save(self, url, token):
        self.s.data["xz_ws_url"], self.s.data["xz_ws_token"] = url, token
        self.s.save()

    def forget(self):
        self.s.data.pop("xz_ws_url", None)
        self.s.data.pop("xz_ws_token", None)
        self.s.save()


class Activator:
    """Runs the xiaozhi.me pairing in a thread; callbacks run on that thread (post them to Qt)."""

    def __init__(self, ident: Identity, version="1.1.0", ota_url=OTA_URL):
        self.ident, self.version, self.ota = ident, version, ota_url
        self.cancelled = False

    def start(self, on_code, on_bound, on_error):
        threading.Thread(target=self._run, args=(on_code, on_bound, on_error), daemon=True).start()

    def cancel(self):
        self.cancelled = True

    def _post(self, url, body):
        i = self.ident
        req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers={
            "Content-Type": "application/json", "Device-Id": i.device_id, "Client-Id": i.client_id,
            "User-Agent": f"python/ledpiano-{self.version}", "Accept-Language": "vi-VN",
            "Activation-Version": self.version})
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def _check(self):
        i = self.ident
        code, data = self._post(self.ota, {
            "application": {"version": self.version, "elf_sha256": i.hmac_key},
            "board": {"type": "ledpiano-desktop", "name": "LED Piano Desktop", "mac": i.device_id}})
        if not 200 <= code < 300:
            raise RuntimeError(f"Máy chủ Xiaozhi trả lỗi HTTP {code}")
        root = json.loads(data or b"{}")
        ws = root.get("websocket") or {}
        act = root.get("activation") or {}
        st = {"token": ws.get("token", ""), "url": ws.get("url", ""),
              "code": act.get("code", ""), "challenge": act.get("challenge", "")}
        mqtt_user = (root.get("mqtt") or {}).get("username", "")
        st["bound"] = bool(st["token"] or mqtt_user) and not st["code"] and not st["challenge"]
        return st

    def _activate(self, challenge):
        i = self.ident
        serial = "SN-" + hashlib.sha256(i.device_id.encode()).hexdigest()[:8] + "-" + i.device_id
        mac = hmac.new(i.hmac_key.encode(), challenge.encode(), hashlib.sha256).hexdigest()
        return self._post(self.ota + "activate", {"Payload": {
            "algorithm": "hmac-sha256", "serial_number": serial, "challenge": challenge, "hmac": mac}})[0]

    def _run(self, on_code, on_bound, on_error):
        try:
            deadline = time.time() + 300
            st = self._check()
            if not st["bound"]:
                shown = st["code"]
                on_code(shown)
                while not self.cancelled and time.time() < deadline:
                    time.sleep(2.5)
                    if st["challenge"] and self._activate(st["challenge"]) != 200:
                        continue
                    st = self._check()
                    if st["bound"]:
                        break
                    if st["code"] and st["code"] != shown:
                        shown = st["code"]
                        on_code(shown)
                else:
                    if not self.cancelled:
                        on_error("Hết thời gian chờ ghép nối (5 phút)")
                    return
            if self.cancelled:
                return
            if not st["token"]:
                on_error("Máy chủ không gửi mã truy cập")
                return
            url = st["url"] or DEFAULT_WS_URL
            self.ident.save(url, st["token"])
            on_bound(url, st["token"])
        except Exception as e:  # network down, bad JSON …
            if not self.cancelled:
                on_error(str(e))


# ---------------------------------------------------------------- session

class Session:
    """One Xiaozhi conversation on its own asyncio thread.

    ``listener`` methods are called on that thread: connected(), disconnected(reconnecting, auth_failed),
    stt(text), assistant(text), speaking(on), audio(opus_bytes), tool_list() -> list,
    tool_call(name, args) -> (text, is_error). Post them to the UI thread yourself.
    """

    BACKOFF = (1, 2, 5)

    def __init__(self, url, token, ident: Identity, listener):
        self.url, self.token, self.ident, self.l = url, token, ident, listener
        self.session_id = ""
        self.playback_rate = 16000
        self._ws = None
        self._stop = False
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)

    @property
    def connected(self):
        return self._ws is not None and bool(self.session_id)

    def connect(self):
        self._thread.start()
        asyncio.run_coroutine_threadsafe(self._main(), self._loop)

    def disconnect(self):
        self._stop = True

        async def close():
            if self._ws is not None:
                try:
                    await self._ws.close()
                except Exception:
                    pass
            me = asyncio.current_task()
            for t in asyncio.all_tasks():
                if t is not me:
                    t.cancel()
            self._loop.call_soon(self._loop.stop)
        if self._thread.is_alive():
            asyncio.run_coroutine_threadsafe(close(), self._loop)

    # outgoing (any thread)
    def listen_start(self, hands_free=True):
        self._send({"type": "listen", "state": "start", "mode": "auto" if hands_free else "manual"})

    def listen_stop(self):
        self._send({"type": "listen", "state": "stop"})

    def listen_text(self, text):
        self._send({"type": "listen", "state": "detect", "text": text})

    def abort(self):
        self._send({"type": "abort", "reason": "wake_word_detected"})

    def send_audio(self, opus: bytes):
        ws = self._ws
        if ws is not None:
            asyncio.run_coroutine_threadsafe(self._safe_send(ws, opus), self._loop)

    def _send(self, msg):
        msg["session_id"] = self.session_id
        ws = self._ws
        if ws is not None:
            asyncio.run_coroutine_threadsafe(self._safe_send(ws, json.dumps(msg, ensure_ascii=False)), self._loop)

    @staticmethod
    async def _safe_send(ws, data):
        try:
            await ws.send(data)
        except Exception:
            pass

    async def _main(self):
        import websockets
        attempts = 0
        headers = {"Authorization": f"Bearer {self.token}", "Protocol-Version": "1",
                   "Device-Id": self.ident.device_id, "Client-Id": self.ident.client_id}
        while not self._stop:
            try:
                try:
                    conn = websockets.connect(self.url, additional_headers=headers, max_size=None, ping_interval=30)
                except TypeError:  # websockets < 14
                    conn = websockets.connect(self.url, extra_headers=headers, max_size=None, ping_interval=30)
                async with conn as ws:
                    self._ws = ws
                    self.session_id = ""
                    await ws.send(json.dumps({
                        "type": "hello", "version": 1, "transport": "websocket", "features": {"mcp": True},
                        "audio_params": {"format": "opus", "sample_rate": MIC_RATE, "channels": 1,
                                         "frame_duration": FRAME_MS}}))
                    async for m in ws:
                        if isinstance(m, bytes):
                            self.l.audio(m)
                        else:
                            if self._dispatch(m):
                                attempts = 0
            except Exception as e:
                status = getattr(getattr(e, "response", None), "status_code", None) or getattr(e, "status_code", None)
                if status in (401, 403):
                    self._ws = None
                    self.l.disconnected(False, True)
                    return
            self._ws = None
            self.session_id = ""
            if self._stop:
                return
            self.l.disconnected(True, False)
            await asyncio.sleep(self.BACKOFF[min(attempts, len(self.BACKOFF) - 1)])
            attempts += 1

    def _dispatch(self, text) -> bool:
        try:
            msg = json.loads(text)
        except ValueError:
            return False
        t = msg.get("type")
        if t == "hello":
            self.session_id = msg.get("session_id", "")
            self.playback_rate = int((msg.get("audio_params") or {}).get("sample_rate") or 16000)
            self.l.connected()
            return True
        if t == "stt" and msg.get("text"):
            self.l.stt(msg["text"])
        elif t == "tts":
            st = msg.get("state")
            if st == "start":
                self.l.speaking(True)
            elif st == "stop":
                self.l.speaking(False)
            elif st == "sentence_start" and msg.get("text"):
                self.l.assistant(msg["text"])
        elif t == "mcp" and isinstance(msg.get("payload"), dict):
            self._mcp(msg["payload"])
        elif t == "goodbye" and self._ws is not None:
            asyncio.ensure_future(self._ws.close())
        return False

    def _mcp(self, rpc):
        if rpc.get("id") is None:
            return
        rid, method = rpc["id"], rpc.get("method")
        if method == "initialize":
            self._reply(rid, {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                              "serverInfo": {"name": "LED Piano", "version": "1.0"}})
        elif method == "tools/list":
            self._reply(rid, {"tools": self.l.tool_list(), "nextCursor": ""})
        elif method == "tools/call":
            p = rpc.get("params") or {}
            try:
                text, err = self.l.tool_call(p.get("name", ""), p.get("arguments") or {})
            except Exception as e:
                text, err = f"Lỗi: {e}", True
            self._reply(rid, {"content": [{"type": "text", "text": text}], "isError": err})
        else:
            self._send({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": rid,
                                                   "error": {"code": -32601, "message": f"Unknown method: {method}"}}})

    def _reply(self, rid, result):
        self._send({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": rid, "result": result}})


# ---------------------------------------------------------------- tools

TOOLS = [
    {"name": "self.piano.list_songs",
     "description": "List every song the learner can practise on the LED piano (Vietnamese title and "
                    "English/original name). Use it when the user asks which songs exist, or before "
                    "open_song when you are not sure of the title.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "self.piano.open_song",
     "description": "Open a song on the LED piano so the learner can practise it; the LED strip lights the "
                    "keys. Use when the user asks to open, play, learn or practise a song. `title`: the song "
                    "title as said by the user or from list_songs (Vietnamese or English). `autoplay`: true "
                    "when the user wants it to start playing right away.",
     "inputSchema": {"type": "object",
                     "properties": {"title": {"type": "string"},
                                    "autoplay": {"type": "boolean", "default": False}},
                     "required": ["title"]}},
]


def call_tool(name, args, songs, on_open):
    """Shared tool logic; on_open(song_dict, autoplay) is called for a found song."""
    if name == "self.piano.list_songs":
        return "\n".join("- " + s["title"] + (f" ({s['subtitle']})" if s.get("subtitle") else "") for s in songs), False
    if name == "self.piano.open_song":
        title = str(args.get("title", ""))
        ranked = rank_songs(title, songs)
        if not ranked or ranked[0][1] < MIN_SCORE:
            near = ", ".join(s["title"] for s, _ in ranked[:3]) or "(không có)"
            return f"Không tìm thấy bài \"{title}\". Các bài gần giống: {near}", True
        if is_ambiguous(ranked):
            return "Có nhiều bài giống nhau, hãy hỏi người học muốn bài nào: " + \
                ", ".join(s["title"] for s, _ in ranked[:3]), True
        song, autoplay = ranked[0][0], bool(args.get("autoplay", False))
        on_open(song, autoplay)
        return f"Đã mở bài \"{song['title']}\"" + (" và bắt đầu chơi." if autoplay else "."), False
    return f"Unknown tool: {name}", True


# ---------------------------------------------------------------- audio

class VoiceAudio:
    """sounddevice mic (16 kHz mono int16, 960-sample blocks → opus) and speaker (decoded speech)."""

    def __init__(self, codec: OpusCodec, on_frame, on_drained):
        import sounddevice as sd
        self.codec, self.on_frame, self.on_drained = codec, on_frame, on_drained
        self.mic_on = False
        self._q = deque()
        self._lock = threading.Lock()
        self._busy = False
        self._last_audio = 0.0
        self._in = sd.RawInputStream(samplerate=MIC_RATE, channels=1, dtype="int16",
                                     blocksize=FRAME_SAMPLES, callback=self._mic)
        self._out = sd.RawOutputStream(samplerate=codec.playback_rate, channels=1, dtype="int16",
                                       blocksize=0, callback=self._spk)
        self._in.start()
        self._out.start()
        self._watch = True
        threading.Thread(target=self._drain_watch, daemon=True).start()

    @property
    def busy(self):
        return self._busy

    def _mic(self, data, frames, t, status):
        if not self.mic_on:
            return
        b = bytes(data)
        if len(b) < FRAME_SAMPLES * 2:
            return
        pkt = self.codec.encode(b)
        if pkt:
            import array
            a = array.array("h", b)
            lv = (sum(x * x for x in a[::8]) / (len(a) / 8)) ** 0.5 / 32768
            self.on_frame(pkt, lv)

    def play(self, opus: bytes):
        pcm = self.codec.decode(opus)
        if pcm:
            with self._lock:
                self._q.append(pcm)
                self._busy = True
                self._last_audio = time.time()

    def flush(self):
        with self._lock:
            self._q.clear()
            self._busy = False

    def _spk(self, out, frames, t, status):
        need = len(out)
        buf = bytearray()
        with self._lock:
            while len(buf) < need and self._q:
                chunk = self._q.popleft()
                take = need - len(buf)
                buf += chunk[:take]
                if len(chunk) > take:
                    self._q.appendleft(chunk[take:])
            if buf:
                self._last_audio = time.time()
        out[:len(buf)] = bytes(buf)
        if len(buf) < need:
            out[len(buf):] = b"\x00" * (need - len(buf))

    def _drain_watch(self):
        while self._watch:
            time.sleep(0.12)
            fire = False
            with self._lock:
                if self._busy and not self._q and time.time() - self._last_audio > 0.35:
                    self._busy = False
                    fire = True
            if fire:
                self.on_drained()

    def stop(self):
        self._watch = False
        self.mic_on = False
        for s in (self._in, self._out):
            try:
                s.stop()
                s.close()
            except Exception:
                pass
