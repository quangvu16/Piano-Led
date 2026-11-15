package com.ledpiano.app.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairs the app with xiaozhi.me like a real Xiaozhi device (same flow as
 * FriendBot's XiaozhiActivator, after PiSugar/whisplay-xiaozhi):
 *
 * 1. `POST <ota>/` with Device-Id / Client-Id headers. Unbound → the reply
 *    has `activation {code, challenge}`; the app shows the 6-digit code.
 * 2. The learner adds a device at xiaozhi.me and types that code.
 * 3. The app posts `<ota>/activate` with an HMAC of the challenge every
 *    2.5 s (200 = bound, 202 = still waiting), then reads the
 *    `websocket {url, token}` credentials and saves them.
 *
 * Callbacks arrive on the main thread.
 */
class XiaozhiActivator(context: Context) {

    interface Listener {
        fun onCode(code: String)
        fun onBound(wsUrl: String, token: String)
        fun onError(message: String)
    }

    private val prefs = prefs(context)
    private val version = appVersion(context)
    private val main = Handler(Looper.getMainLooper())
    private val deviceId = deviceId(context)
    private val clientId = clientId(context)
    private val hmacKey = getOrCreate(prefs, KEY_HMAC) { sha256Hex("$deviceId|$clientId|${System.currentTimeMillis()}") }
    private val serial = "SN-" + sha256Hex(deviceId).take(8) + "-" + deviceId
    @Volatile private var cancelled = false

    fun start(listener: Listener) {
        cancelled = false
        Thread({ run(listener) }, "xiaozhi-pair").start()
    }

    fun cancel() { cancelled = true }

    private fun run(listener: Listener) {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        try {
            var state = parse(checkVersion())
            if (state.bound) return finish(listener, state)
            var shownCode = state.code
            main.post { listener.onCode(shownCode) }
            while (!cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                if (state.challenge.isNotEmpty() && activate(state.challenge) != 200) continue
                state = parse(checkVersion())
                if (state.bound) return finish(listener, state)
                if (state.code.isNotEmpty() && state.code != shownCode) {
                    shownCode = state.code
                    val c = shownCode
                    main.post { listener.onCode(c) }
                }
            }
            if (!cancelled) main.post { listener.onError("Hết thời gian chờ ghép nối (5 phút)") }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!cancelled) main.post { listener.onError(e.message ?: e.toString()) }
        }
    }

    private fun finish(listener: Listener, state: State) {
        if (state.token.isEmpty()) {
            main.post { listener.onError("Máy chủ không gửi mã truy cập") }
            return
        }
        prefs.edit().putString(KEY_WS_URL, state.wsUrl).putString(KEY_WS_TOKEN, state.token).apply()
        main.post { listener.onBound(state.wsUrl, state.token) }
    }

    private class State(
        val bound: Boolean, val token: String, val wsUrl: String, val code: String, val challenge: String,
    )

    private fun parse(payload: String): State {
        val root = JSONObject(payload)
        val ws = root.optJSONObject("websocket")
        val token = ws?.optString("token").orEmpty()
        val url = ws?.optString("url").orEmpty()
        val mqttUser = root.optJSONObject("mqtt")?.optString("username").orEmpty()
        val act = root.optJSONObject("activation")
        val code = act?.optString("code").orEmpty()
        val challenge = act?.optString("challenge").orEmpty()
        val bound = (token.isNotEmpty() || mqttUser.isNotEmpty()) && code.isEmpty() && challenge.isEmpty()
        return State(bound, token, url, code, challenge)
    }

    private fun checkVersion(): String {
        val body = JSONObject()
            .put("application", JSONObject().put("version", version).put("elf_sha256", hmacKey))
            .put("board", JSONObject().put("type", "ledpiano-phone").put("name", "LED Piano Android").put("mac", deviceId))
        val (code, text) = post(OTA_URL, body.toString())
        if (code !in 200..299) throw IOException("Máy chủ Xiaozhi trả lỗi HTTP $code")
        return text
    }

    private fun activate(challenge: String): Int {
        val payload = JSONObject()
            .put("algorithm", "hmac-sha256")
            .put("serial_number", serial)
            .put("challenge", challenge)
            .put("hmac", hmacSha256Hex(hmacKey, challenge))
        return post(OTA_URL + "activate", JSONObject().put("Payload", payload).toString()).first
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Device-Id", deviceId)
            c.setRequestProperty("Client-Id", clientId)
            c.setRequestProperty("User-Agent", "android/ledpiano-" + version)
            c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            c.setRequestProperty("Activation-Version", version)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return code to text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        private const val PREFS = "xiaozhi_voice"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_HMAC = "hmac_key"
        const val KEY_WS_URL = "ws_url"
        const val KEY_WS_TOKEN = "ws_token"
        private const val POLL_MS = 2500L

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun deviceId(context: Context): String = getOrCreate(prefs(context), KEY_DEVICE_ID) {
            // Random locally-administered MAC-shaped id; the platform treats it as opaque.
            val b = ByteArray(6).also { java.util.Random().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
            b.joinToString(":") { "%02x".format(it) }
        }

        fun appVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        fun clientId(context: Context): String = getOrCreate(prefs(context), KEY_CLIENT_ID) { UUID.randomUUID().toString() }

        /** Saved credentials, or null when the app is not paired yet. */
        fun saved(context: Context): Pair<String, String>? {
            val p = prefs(context)
            val token = p.getString(KEY_WS_TOKEN, "").orEmpty()
            if (token.isEmpty()) return null
            return (p.getString(KEY_WS_URL, "").orEmpty().ifEmpty { DEFAULT_WS_URL }) to token
        }

        /** Forget the pairing (e.g. the device was removed at xiaozhi.me). */
        fun forget(context: Context) {
            prefs(context).edit().remove(KEY_WS_URL).remove(KEY_WS_TOKEN).apply()
        }

        private fun getOrCreate(p: SharedPreferences, key: String, make: () -> String): String {
            p.getString(key, "")?.takeIf { it.isNotEmpty() }?.let { return it }
            return make().also { p.edit().putString(key, it).apply() }
        }

        private fun sha256Hex(s: String) =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun hmacSha256Hex(key: String, msg: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
