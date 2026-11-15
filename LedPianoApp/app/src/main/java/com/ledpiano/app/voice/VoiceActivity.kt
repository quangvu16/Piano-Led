package com.ledpiano.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ledpiano.app.LedPianoApp
import com.ledpiano.app.PlayerActivity
import com.ledpiano.app.R
import com.ledpiano.app.databinding.ActivityVoiceBinding
import com.ledpiano.app.practice.Lessons
import com.ledpiano.app.util.padForSystemBars
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Chọn bài bằng giọng nói": talk to Xiaozhi and it opens a song.
 *
 * Works like FriendBot's Voice Control page: the phone is paired once with
 * xiaozhi.me, the microphone streams opus to the Xiaozhi server (which does
 * the speech recognition and the thinking), Xiaozhi answers with its own voice,
 * and it drives the app through MCP tools:
 *
 * - `self.piano.list_songs` – every song the learner can open,
 * - `self.piano.open_song {title, autoplay}` – opens the best match.
 *
 * Hands-free: listening restarts by itself after each reply. Tapping the mic
 * stops listening, or interrupts Xiaozhi while it speaks.
 */
class VoiceActivity : AppCompatActivity(), XiaozhiSession.Listener, XiaozhiSession.Tools {

    private enum class UiState { CONNECTING, PAIRING, READY, LISTENING, THINKING, SPEAKING, OFFLINE }

    private lateinit var binding: ActivityVoiceBinding
    private val main = Handler(Looper.getMainLooper())
    private val settings get() = (application as LedPianoApp).settings

    private var activator: XiaozhiActivator? = null
    private var session: XiaozhiSession? = null
    private var codec: OpusCodec? = null
    private var mic: MicStreamer? = null
    private var player: OpusPlayer? = null

    private var state = UiState.CONNECTING
    /** True while the mic should stream (hands-free on, not muted by the learner). */
    private var wantListening = true
    /** Between `tts start` and `tts stop`. */
    private var ttsActive = false
    private val speaking: Boolean get() = ttsActive || player?.busy == true
    /** Song chosen by Xiaozhi; opened once it has finished saying so. */
    private var pending: Pair<SongMatcher.Song, Boolean>? = null
    private var lastUserText = ""

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) begin() else {
                Toast.makeText(this, R.string.voice_need_mic, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMic.setOnClickListener { onMicTapped() }
        binding.btnUnpair.setOnClickListener { unpair() }
        setState(UiState.CONNECTING)
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        // Leaving the screen frees the microphone and closes the conversation.
        end()
        super.onStop()
    }

    // ---- connection / pairing ----

    private fun begin() {
        if (session != null || activator != null) return
        val saved = XiaozhiActivator.saved(this)
        if (saved == null) startPairing() else startSession(saved.first, saved.second)
    }

    private fun end() {
        main.removeCallbacksAndMessages(null)
        activator?.cancel(); activator = null
        mic?.stop(); mic = null
        player?.release(); player = null
        session?.disconnect(); session = null
        codec = null
    }

    private fun startPairing() {
        setState(UiState.PAIRING)
        binding.pairPanel.visibility = View.VISIBLE
        val a = XiaozhiActivator(this)
        activator = a
        a.start(object : XiaozhiActivator.Listener {
            override fun onCode(code: String) {
                binding.txtPairCode.text = code.ifEmpty { "······" }
            }

            override fun onBound(wsUrl: String, token: String) {
                activator = null
                binding.pairPanel.visibility = View.GONE
                startSession(wsUrl.ifEmpty { XiaozhiActivator.DEFAULT_WS_URL }, token)
            }

            override fun onError(message: String) {
                activator = null
                addRow(getString(R.string.voice_pair_error, message), user = false)
                setState(UiState.OFFLINE)
            }
        })
    }

    private fun startSession(url: String, token: String) {
        binding.pairPanel.visibility = View.GONE
        setState(UiState.CONNECTING)
        session = XiaozhiSession(
            url, token,
            XiaozhiActivator.deviceId(this), XiaozhiActivator.clientId(this),
            this, this,
        ).also { it.connect() }
    }

    private fun unpair() {
        end()
        XiaozhiActivator.forget(this)
        binding.txtPairCode.text = "······"
        startPairing()
    }

    // ---- microphone ----

    private fun onMicTapped() {
        val s = session ?: run { if (activator == null) begin(); return }
        when {
            speaking -> {
                // Barge-in: stop Xiaozhi and listen again.
                player?.flush()
                s.abort()
                ttsActive = false
                wantListening = true
                startListening()
            }
            mic?.isRunning == true -> {
                wantListening = false
                stopListening()
                setState(UiState.READY)
            }
            else -> {
                wantListening = true
                startListening()
            }
        }
    }

    private fun startListening() {
        val s = session ?: return
        if (!s.isConnected) return
        val c = codec ?: OpusCodec(s.playbackRate).also { codec = it }
        val m = mic ?: MicStreamer(c) { opus, level ->
            // Never send our own loudspeaker back to the server.
            if (!speaking) session?.sendAudio(opus)
            main.post { showLevel(level) }
        }.also { mic = it }
        if (!m.start()) {
            Toast.makeText(this, R.string.voice_need_mic, Toast.LENGTH_LONG).show()
            return
        }
        s.listenStart(handsFree = true)
        setState(UiState.LISTENING)
    }

    private fun stopListening() {
        mic?.stop()
        session?.listenStop()
        showLevel(0f)
    }

    private fun showLevel(level: Float) {
        val scale = 1f + (level * 6f).coerceIn(0f, 0.35f)
        binding.micHalo.scaleX = scale
        binding.micHalo.scaleY = scale
    }

    // ---- XiaozhiSession.Listener ----

    override fun onConnected() {
        codec = OpusCodec(session?.playbackRate ?: 16000)
        player?.release()
        player = OpusPlayer(
            codec!!,
            onLevel = { lv -> main.post { showLevel(lv) } },
            onDrained = { main.post { if (!ttsActive) afterSpeech() } },
        )
        mic?.stop(); mic = null // re-create with the new codec
        setState(UiState.READY)
        if (wantListening) startListening()
    }

    override fun onDisconnected(reconnecting: Boolean, reason: String?) {
        mic?.stop()
        if (reason == XiaozhiSession.AUTH_FAILED) {
            addRow(getString(R.string.voice_auth_failed), user = false)
            unpair()
            return
        }
        setState(if (reconnecting) UiState.OFFLINE else UiState.READY)
    }

    override fun onStt(text: String) {
        if (text == lastUserText) return
        lastUserText = text
        addRow(text, user = true)
        setState(UiState.THINKING)
    }

    override fun onAssistantText(text: String) = addRow(text, user = false)

    override fun onSpeaking(speaking: Boolean) {
        ttsActive = speaking
        if (speaking) setState(UiState.SPEAKING)
        else if (player?.busy != true) afterSpeech()
    }

    override fun onAudio(opus: ByteArray) {
        player?.play(opus)
    }

    override fun onEmotion(emotion: String) = Unit

    /** Xiaozhi finished talking (and its audio has played out): open the chosen song, or listen again. */
    private fun afterSpeech() {
        if (state != UiState.SPEAKING) return
        pending?.let { openPending(); return }
        if (wantListening && session?.isConnected == true) {
            session?.listenStart(handsFree = true)
            if (mic?.isRunning != true) startListening() else setState(UiState.LISTENING)
        } else {
            setState(UiState.READY)
        }
    }

    // ---- MCP tools ----

    private fun catalog(): List<SongMatcher.Song> {
        val lessons = Lessons.levels(this).flatMap { lv -> lv.lessons.map { SongMatcher.Song(it.uri, it.title, it.subtitle) } }
        val mine = settings.recentSongs.mapNotNull { e ->
            val i = e.indexOf('|')
            if (i < 0) null else SongMatcher.Song(e.take(i), e.substring(i + 1).removeSuffix(".mid").removeSuffix(".midi"))
        }.filter { m -> lessons.none { it.uri == m.uri } }
        return lessons + mine
    }

    override fun list(): JSONArray = JSONArray()
        .put(
            tool(
                "self.piano.list_songs",
                "List every song the learner can practise on the LED piano (Vietnamese title and " +
                    "English/original name). Use it when the user asks which songs exist, or before " +
                    "open_song when you are not sure of the title.",
                JSONObject(),
                emptyList(),
            ),
        )
        .put(
            tool(
                "self.piano.open_song",
                "Open a song on the LED piano so the learner can practise it; the LED strip lights " +
                    "the keys. Use when the user asks to open, play, learn or practise a song. `title`: " +
                    "the song title as said by the user or from list_songs (Vietnamese or English). " +
                    "`autoplay`: true when the user wants it to start playing right away.",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("autoplay", JSONObject().put("type", "boolean").put("default", false)),
                listOf("title"),
            ),
        )

    private fun tool(name: String, description: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "inputSchema",
            JSONObject().put("type", "object").put("properties", props)
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) },
        )

    override fun call(name: String, args: JSONObject): XiaozhiSession.ToolResult {
        val songs = catalog()
        return when (name) {
            "self.piano.list_songs" -> XiaozhiSession.ToolResult(
                songs.joinToString("\n") { s -> "- " + s.title + (if (s.subtitle.isNotBlank()) " (${s.subtitle})" else "") },
            )
            "self.piano.open_song" -> {
                val title = args.optString("title")
                val ranked = SongMatcher.rank(title, songs)
                val best = ranked.firstOrNull()?.takeIf { it.score >= SongMatcher.MIN_SCORE }
                when {
                    best == null -> XiaozhiSession.ToolResult(
                        "Không tìm thấy bài \"$title\". Các bài gần giống: " +
                            ranked.take(3).joinToString(", ") { it.song.title }.ifEmpty { "(không có)" },
                        isError = true,
                    )
                    SongMatcher.isAmbiguous(ranked) -> XiaozhiSession.ToolResult(
                        "Có nhiều bài giống nhau, hãy hỏi người học muốn bài nào: " +
                            ranked.take(3).joinToString(", ") { it.song.title },
                        isError = true,
                    )
                    else -> {
                        val autoplay = args.optBoolean("autoplay", false)
                        pending = best.song to autoplay
                        addRow(getString(R.string.voice_opening, best.song.title), user = false, action = true)
                        // Open after Xiaozhi has said so; never wait more than 6 s.
                        main.postDelayed({ if (pending != null) openPending() }, 6000)
                        XiaozhiSession.ToolResult("Đã mở bài \"${best.song.title}\"" + if (autoplay) " và bắt đầu chơi." else ".")
                    }
                }
            }
            else -> XiaozhiSession.ToolResult("Unknown tool: $name", isError = true)
        }
    }

    private fun openPending() {
        val (song, autoplay) = pending ?: return
        pending = null
        settings.addRecentSong(song.uri, song.title)
        startActivity(
            PlayerActivity.newIntent(this, song.uri, song.title)
                .putExtra(PlayerActivity.EXTRA_AUTOPLAY, autoplay),
        )
        finish()
    }

    // ---- UI ----

    private fun setState(s: UiState) {
        state = s
        binding.txtState.setText(
            when (s) {
                UiState.CONNECTING -> R.string.voice_state_connecting
                UiState.PAIRING -> R.string.voice_state_pairing
                UiState.READY -> R.string.voice_state_ready
                UiState.LISTENING -> R.string.voice_state_listening
                UiState.THINKING -> R.string.voice_state_thinking
                UiState.SPEAKING -> R.string.voice_state_speaking
                UiState.OFFLINE -> R.string.voice_state_offline
            },
        )
        binding.txtHint.setText(
            when (s) {
                UiState.LISTENING -> R.string.voice_hint_stop
                UiState.SPEAKING -> R.string.voice_hint_barge
                else -> R.string.voice_hint_tap
            },
        )
        val live = s == UiState.LISTENING || s == UiState.SPEAKING || s == UiState.READY || s == UiState.THINKING
        binding.btnMic.alpha = if (live) 1f else 0.4f
        binding.btnUnpair.visibility = if (XiaozhiActivator.saved(this) != null) View.VISIBLE else View.GONE
        if (s != UiState.LISTENING && s != UiState.SPEAKING) showLevel(0f)
    }

    private fun addRow(text: String, user: Boolean, action: Boolean = false) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = if (action) 13f else 15f
            setTextColor(ContextCompat.getColor(context, if (action) R.color.blue_soft else R.color.text_hi))
            if (!action) setBackgroundResource(if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = when {
                action -> Gravity.CENTER_HORIZONTAL
                user -> Gravity.END
                else -> Gravity.START
            }
            topMargin = (6 * dp).toInt()
        }
        binding.transcript.addView(tv, lp)
        while (binding.transcript.childCount > 41) binding.transcript.removeViewAt(1)
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
