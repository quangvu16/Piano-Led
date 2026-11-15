"""Voice song picker: song matching, opus round trip, and the Xiaozhi protocol against a fake server."""
import asyncio
import json
import math
import os
import struct
import sys
import threading
import time

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from ledpiano import voice as V  # noqa: E402

TITLES = [
    ("Năm ngón tay phải", "Đô Rê Mi Fa Sol"), ("Năm ngón tay trái", "Đô Rê Mi Fa Sol"),
    ("Mary có một chú cừu non", "Mary Had a Little Lamb"), ("Kìa con bướm vàng", "Dân ca (giai điệu Frère Jacques)"),
    ("Ngôi sao lấp lánh", "Twinkle Twinkle Little Star"), ("Chúc mừng sinh nhật", "Happy Birthday"),
    ("Khúc hoan ca (tay phải)", "Beethoven — Ode to Joy"), ("Năm ngón hai tay", "Hai tay cùng đi lên, đi xuống"),
    ("Khúc hoan ca (hai tay)", "Beethoven — Ode to Joy"), ("Ngôi sao lấp lánh (hai tay)", "Tay trái giữ Đô – Fa – Sol"),
    ("Bài tập Hanon số 1", "Luyện ngón đều tay"), ("Đêm thánh vô cùng", "Silent Night — F. Gruber"),
    ("Tiếng chuông ngân (điệp khúc)", "Jingle Bells — J. Pierpont"), ("Minuet Fa trưởng", "Mozart"),
    ("Canon Rê trưởng (dễ)", "Pachelbel"), ("Greensleeves", "Dân ca Anh"),
]
SONGS = [{"key": "asset://" + t, "title": t, "subtitle": s} for t, s in TITLES]

CASES = {
    "mở bài ngôi sao lấp lánh": "Ngôi sao lấp lánh",
    "ngôi sao lấp lánh hai tay": "Ngôi sao lấp lánh (hai tay)",
    "play twinkle twinkle little star": "Ngôi sao lấp lánh",
    "chúc mừng sinh nhật": "Chúc mừng sinh nhật",
    "happy birthday": "Chúc mừng sinh nhật",
    "cho mình nghe bài jingle bells nhé": "Tiếng chuông ngân (điệp khúc)",
    "silent night": "Đêm thánh vô cùng",
    "đêm thánh vô cùng": "Đêm thánh vô cùng",
    "kìa con bướm vàng": "Kìa con bướm vàng",
    "mary had a little lamb": "Mary có một chú cừu non",
    "canon": "Canon Rê trưởng (dễ)",
    "minuet": "Minuet Fa trưởng",
    "greensleeve": "Greensleeves",
    "hanon": "Bài tập Hanon số 1",
    "ode to joy hai tay": "Khúc hoan ca (hai tay)",
    "năm ngón tay trái": "Năm ngón tay trái",
    "bài gì đó không có": None,
}


@pytest.mark.parametrize("query,want", list(CASES.items()))
def test_song_matcher(query, want):
    got = V.best_song(query, SONGS)
    assert (got["title"] if got else None) == want


def test_normalize():
    assert V.normalize("Đêm Thánh  Vô Cùng!") == "dem thanh vo cung"


def test_open_song_tool():
    opened = []
    text, err = V.call_tool("self.piano.open_song", {"title": "happy birthday", "autoplay": True}, SONGS,
                            lambda s, a: opened.append((s["title"], a)))
    assert not err and opened == [("Chúc mừng sinh nhật", True)] and "bắt đầu chơi" in text
    text, err = V.call_tool("self.piano.open_song", {"title": "xyz không có"}, SONGS, lambda s, a: opened.append(s))
    assert err and "Không tìm thấy" in text and len(opened) == 1
    text, err = V.call_tool("self.piano.list_songs", {}, SONGS, None)
    assert not err and text.count("\n") == len(SONGS) - 1


@pytest.mark.skipif(V._OPUS is None, reason="libopus not installed")
def test_opus_round_trip():
    c = V.OpusCodec(16000)
    pcm = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(V.FRAME_SAMPLES))
    pkt = c.encode(pcm)
    assert pkt and 10 < len(pkt) < 1276
    out = c.decode(pkt)
    assert len(out) == V.FRAME_SAMPLES * 2


class FakeStore:
    def __init__(self):
        self.data = {}

    def save(self):
        pass


class Recorder:
    """Session listener that records everything (called on the session thread)."""

    def __init__(self):
        self.events = []
        self.done = threading.Event()

    def connected(self): self.events.append("connected")
    def disconnected(self, r, a): self.events.append(("disconnected", r, a))
    def stt(self, t): self.events.append(("stt", t))
    def assistant(self, t): self.events.append(("assistant", t))
    def speaking(self, on): self.events.append(("speaking", on))
    def audio(self, b): self.events.append(("audio", len(b)))
    def tool_list(self): return V.TOOLS

    def tool_call(self, name, args):
        return V.call_tool(name, args, SONGS, lambda s, a: self.events.append(("open", s["title"], a)))


def test_session_against_fake_xiaozhi():
    websockets = pytest.importorskip("websockets")
    got = {}

    async def server(ws):
        got["auth"] = ws.request.headers.get("Authorization")
        got["device"] = ws.request.headers.get("Device-Id")
        hello = json.loads(await ws.recv())
        got["hello"] = hello
        await ws.send(json.dumps({"type": "hello", "session_id": "s1", "audio_params": {"sample_rate": 24000}}))
        await ws.send(json.dumps({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}}))
        got["init"] = json.loads(await ws.recv())
        await ws.send(json.dumps({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}}))
        got["list"] = json.loads(await ws.recv())
        await ws.send(json.dumps({"type": "stt", "text": "mở bài chúc mừng sinh nhật"}))
        await ws.send(json.dumps({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                  "params": {"name": "self.piano.open_song", "arguments": {"title": "Chúc mừng sinh nhật"}}}}))
        got["call"] = json.loads(await ws.recv())
        await ws.send(json.dumps({"type": "tts", "state": "start"}))
        await ws.send(json.dumps({"type": "tts", "state": "sentence_start", "text": "Mình mở bài nhé"}))
        await ws.send(b"\x01\x02\x03")
        await ws.send(json.dumps({"type": "tts", "state": "stop"}))
        got["listen"] = json.loads(await ws.recv())  # the client's listen start
        got["audio_up"] = await ws.recv()
        await asyncio.sleep(0.3)

    ready = threading.Event()
    port = {}

    def run_server():
        async def main():
            async with websockets.serve(server, "127.0.0.1", 0) as srv:
                port["p"] = srv.sockets[0].getsockname()[1]
                ready.set()
                await asyncio.sleep(8)
        asyncio.run(main())

    threading.Thread(target=run_server, daemon=True).start()
    assert ready.wait(5)
    rec = Recorder()
    ident = V.Identity(FakeStore())
    s = V.Session(f"ws://127.0.0.1:{port['p']}/xiaozhi/v1/", "tok123", ident, rec)
    s.connect()
    deadline = time.time() + 6
    while time.time() < deadline and ("speaking", False) not in rec.events:
        time.sleep(0.05)
    s.listen_start(True)
    s.send_audio(b"\xaa\xbb")
    deadline = time.time() + 4
    while time.time() < deadline and "audio_up" not in got:
        time.sleep(0.05)
    s.disconnect()

    assert got["auth"] == "Bearer tok123" and got["device"] == ident.device_id
    assert got["hello"]["features"]["mcp"] is True and got["hello"]["audio_params"]["frame_duration"] == 60
    assert s.playback_rate == 24000
    assert got["init"]["payload"]["result"]["serverInfo"]["name"] == "LED Piano"
    names = [t["name"] for t in got["list"]["payload"]["result"]["tools"]]
    assert names == ["self.piano.list_songs", "self.piano.open_song"]
    assert got["call"]["session_id"] == "s1" and got["call"]["payload"]["result"]["isError"] is False
    assert ("open", "Chúc mừng sinh nhật", False) in rec.events
    assert ("stt", "mở bài chúc mừng sinh nhật") in rec.events
    assert ("assistant", "Mình mở bài nhé") in rec.events and ("audio", 3) in rec.events
    assert got["listen"] == {"type": "listen", "state": "start", "mode": "auto", "session_id": "s1"}
    assert got["audio_up"] == b"\xaa\xbb"
