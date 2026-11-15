# Tools

- `gen_lessons.py` — writes the lesson-path MIDI files and `lessons.json` into
  `app/src/main/assets/lessons/`. Run `python3 tools/gen_lessons.py app/src/main/assets`
  after adding or changing a lesson. Melodies must be public domain.
- `gen_player_layouts.py` — regenerates `res/layout/activity_player.xml` and
  `res/layout-land/activity_player.xml` (both share view IDs). Run
  `python3 tools/gen_player_layouts.py app/src/main/res`.
- `make_soundbank.py` — builds `soundbank.json` + `soundbank.bin` (24 recorded instruments:
  8 pianos, 8 organs, 8 bells/mallets) from the free GeneralUser GS SoundFont
  (https://www.schristiancollins.com). Run
  `python3 tools/make_soundbank.py GeneralUser-GS.sf2 app/src/main/assets`.
  The same two files are used by the iOS/Mac and Python apps.

## Voice song picker (Xiaozhi)

`app/src/main/java/com/ledpiano/app/voice/` — pairing at xiaozhi.me (XiaozhiActivator), the websocket
conversation (XiaozhiSession + a small built-in WsClient), Opus via the vendored pure-Java Concentus
(`org/concentus`, same copy as FriendBot), and the MCP tools `self.piano.list_songs` /
`self.piano.open_song` (VoiceActivity). SongMatcher picks the song from what was said.
