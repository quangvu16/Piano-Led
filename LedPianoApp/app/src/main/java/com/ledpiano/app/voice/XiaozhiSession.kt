package com.ledpiano.app.voice

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * One live Xiaozhi conversation over WebSocket, ported from FriendBot's
 * XiaozhiSession (protocol of 78/xiaozhi-esp32 docs/websocket.md and
 * mcp-protocol.md):
 *
 * - Connect with `Authorization: Bearer <token>`, `Protocol-Version: 1`,
 *   `Device-Id`, `Client-Id`; send `hello` (opus 16 kHz mono 60 ms, MCP on);
 *   keep the server's `session_id` for every message.
 * - Voice in: `listen start` (mode auto = hands-free, server VAD ends each
 *   sentence), then raw opus frames as binary messages; `abort` barges in.
 * - Voice out: `tts start/sentence_start/stop` plus raw opus binary frames.
 * - `stt` is what the server heard; `llm` carries an emotion hint.
 * - The app is an MCP server: `initialize`, `tools/list`, `tools/call`
 *   answered as JSON-RPC 2.0 inside `{"type":"mcp","payload":…}`.
 *
 * Reconnects after 1 s / 2 s / 5 s on unexpected closes. All callbacks run on
 * the main thread.
 */
class XiaozhiSession(
    private val wsUrl: String,
    private val token: String,
    private val deviceId: String,
    private val clientId: String,
    private val tools: Tools,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reconnecting: Boolean, reason: String?)
        fun onStt(text: String)
        fun onAssistantText(text: String)
        fun onSpeaking(speaking: Boolean)
        fun onAudio(opus: ByteArray)
        fun onEmotion(emotion: String)
    }

    /** The app's MCP tools (see VoiceTools). [call] runs on the main thread. */
    interface Tools {
        fun list(): JSONArray
        fun call(name: String, args: JSONObject): ToolResult
    }

    data class ToolResult(val text: String, val isError: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WsClient? = null
    @Volatile private var sessionId = ""
    @Volatile var playbackRate = 16000
        private set
    @Volatile private var intentional = false
    private var attempts = 0

    val isConnected: Boolean get() = ws?.isOpen == true && sessionId.isNotEmpty()

    fun connect() {
        intentional = false
        attempts = 0
        open()
    }

    fun disconnect() {
        intentional = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
    }

    fun listenStart(handsFree: Boolean) =
        send(JSONObject().put("type", "listen").put("state", "start").put("mode", if (handsFree) "auto" else "manual"))

    fun listenStop() = send(JSONObject().put("type", "listen").put("state", "stop"))

    /** Typed text instead of speech. */
    fun listenText(text: String) = send(JSONObject().put("type", "listen").put("state", "detect").put("text", text))

    fun abort() = send(JSONObject().put("type", "abort").put("reason", "wake_word_detected"))

    fun sendAudio(opus: ByteArray) {
        ws?.sendBinary(opus)
    }

    private fun send(msg: JSONObject) {
        msg.put("session_id", sessionId)
        ws?.sendText(msg.toString())
    }

    private fun open() {
        if (intentional) return
        sessionId = ""
        val client = WsClient(object : WsClient.Listener {
            override fun onOpen() {
                ws?.sendText(hello().toString())
            }

            override fun onText(text: String) = dispatch(text)

            override fun onBinary(data: ByteArray) {
                main.post { listener.onAudio(data) }
            }

            override fun onClosed(code: Int, reason: String, error: Throwable?) {
                if (intentional) return
                val why = error?.message ?: reason.ifEmpty { null }
                if (why != null && (why.contains(" 401") || why.contains(" 403"))) {
                    // The pairing was removed at xiaozhi.me: no point retrying.
                    main.post { listener.onDisconnected(false, AUTH_FAILED) }
                    return
                }
                val delay = BACKOFF[minOf(attempts, BACKOFF.size - 1)]
                attempts++
                main.post { listener.onDisconnected(true, why) }
                main.postDelayed({ open() }, delay)
            }
        })
        ws = client
        client.connect(
            wsUrl,
            mapOf(
                "Authorization" to "Bearer $token",
                "Protocol-Version" to "1",
                "Device-Id" to deviceId,
                "Client-Id" to clientId,
            ),
        )
    }

    private fun hello() = JSONObject()
        .put("type", "hello")
        .put("version", 1)
        .put("transport", "websocket")
        .put("features", JSONObject().put("mcp", true))
        .put(
            "audio_params",
            JSONObject().put("format", "opus").put("sample_rate", OpusCodec.MIC_RATE)
                .put("channels", 1).put("frame_duration", OpusCodec.FRAME_MS),
        )

    private fun dispatch(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        when (msg.optString("type")) {
            "hello" -> {
                sessionId = msg.optString("session_id")
                playbackRate = msg.optJSONObject("audio_params")?.optInt("sample_rate", 16000)?.takeIf { it > 0 } ?: 16000
                attempts = 0
                main.post { listener.onConnected() }
            }
            "stt" -> msg.optString("text").takeIf { it.isNotBlank() }?.let { t -> main.post { listener.onStt(t) } }
            "tts" -> when (msg.optString("state")) {
                "start" -> main.post { listener.onSpeaking(true) }
                "stop" -> main.post { listener.onSpeaking(false) }
                "sentence_start" -> msg.optString("text").takeIf { it.isNotBlank() }
                    ?.let { t -> main.post { listener.onAssistantText(t) } }
            }
            "llm" -> msg.optString("emotion").takeIf { it.isNotBlank() }?.let { e -> main.post { listener.onEmotion(e) } }
            "mcp" -> msg.optJSONObject("payload")?.let { mcp(it) }
            "goodbye" -> ws?.close(1000, "goodbye")
        }
    }

    private fun mcp(rpc: JSONObject) {
        if (!rpc.has("id") || rpc.isNull("id")) return // notification
        val id = rpc.get("id")
        when (val method = rpc.optString("method")) {
            "initialize" -> reply(
                id,
                JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "LED Piano").put("version", "1.0")),
            )
            "tools/list" -> main.post { reply(id, JSONObject().put("tools", tools.list()).put("nextCursor", "")) }
            "tools/call" -> {
                val params = rpc.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                main.post {
                    val r = try { tools.call(name, args) } catch (e: Exception) { ToolResult("Lỗi: ${e.message}", true) }
                    reply(
                        id,
                        JSONObject()
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.text)))
                            .put("isError", r.isError),
                    )
                }
            }
            else -> error(id, -32601, "Unknown method: $method")
        }
    }

    private fun reply(id: Any, result: JSONObject) =
        send(JSONObject().put("type", "mcp").put("payload", JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)))

    private fun error(id: Any, code: Int, message: String) =
        send(
            JSONObject().put("type", "mcp").put(
                "payload",
                JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", code).put("message", message)),
            ),
        )

    companion object {
        private val BACKOFF = longArrayOf(1000, 2000, 5000)
        const val AUTH_FAILED = "auth_failed"
    }
}
