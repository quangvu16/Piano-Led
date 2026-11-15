package com.ledpiano.app.voice

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small WebSocket client (RFC 6455) for the Xiaozhi voice channel, so the
 * app needs no extra library. Supports ws:// and wss://, custom handshake
 * headers (Authorization, Device-Id …), text and binary messages,
 * fragmented messages, ping/pong and a keep-alive ping every 30 s.
 *
 * Callbacks arrive on the reader thread; [XiaozhiSession] moves them to the
 * main thread.
 */
class WsClient(private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(data: ByteArray)
        /** Socket closed or failed; [error] is null for a clean close. */
        fun onClosed(code: Int, reason: String, error: Throwable?)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ws-writer").apply { isDaemon = true } }
    private val random = SecureRandom()
    @Volatile private var lastRxMs = System.currentTimeMillis()

    val isOpen: Boolean get() = output != null && !closed

    /** Connects on a background thread; [onOpen] follows a successful handshake. */
    fun connect(url: String, headers: Map<String, String>) {
        closed = false
        Thread({ run(url, headers) }, "ws-reader").apply { isDaemon = true }.start()
    }

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = sendFrame(OP_BINARY, data)

    /** Clean close; no further callbacks except one [Listener.onClosed]. */
    fun close(code: Int = 1000, reason: String = "") {
        if (closed) return
        val payload = ByteArrayOutputStream().apply {
            write(code shr 8 and 0xFF); write(code and 0xFF); write(reason.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        sendFrame(OP_CLOSE, payload)
        writer.execute {
            closed = true
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    // ---- connection ----

    private fun run(url: String, headers: Map<String, String>) {
        var error: Throwable? = null
        var code = 1006
        var reason = ""
        try {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", true) || uri.scheme.equals("https", true)
            val host = uri.host ?: throw IOException("bad url: $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 15000)
            val s: Socket = if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                ssl.startHandshake()
                ssl
            } else raw
            s.soTimeout = 0
            socket = s
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            handshake(uri, host, port, secure, headers, input, out)
            output = out
            lastRxMs = System.currentTimeMillis()
            startKeepAlive()
            listener.onOpen()
            val result = readLoop(input)
            code = result.first
            reason = result.second
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            val wasClosed = closed
            closed = true
            output = null
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            listener.onClosed(if (wasClosed && error == null) 1000 else code, reason, error)
        }
    }

    private fun handshake(
        uri: URI, host: String, port: Int, secure: Boolean,
        headers: Map<String, String>, input: InputStream, out: OutputStream,
    ) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val defaultPort = if (secure) 443 else 80
        val sb = StringBuilder()
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append(if (port != defaultPort) ":$port" else "").append("\r\n")
        sb.append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        val status = readLine(input)
        val parts = status.split(' ')
        if (parts.size < 2 || parts[1] != "101") throw IOException("WebSocket handshake refused: $status")
        var accept: String? = null
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Sec-WebSocket-Accept", true)) {
                accept = line.substring(i + 1).trim()
            }
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.UTF_8)),
        )
        if (accept != expected) throw IOException("WebSocket handshake: bad accept key")
    }

    private fun readLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed during handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("handshake line too long")
        }
        return buf.toString("UTF-8")
    }

    /** Reads frames until close; returns the close code and reason. */
    private fun readLoop(input: InputStream): Pair<Int, String> {
        val message = ByteArrayOutputStream()
        var messageOp = 0
        while (!closed) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("connection closed")
            val b1 = readByte(input)
            lastRxMs = System.currentTimeMillis()
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            }
            if (len > MAX_MESSAGE) throw IOException("frame too large: $len")
            val mask = if (masked) ByteArray(4) { readByte(input).toByte() } else null
            val payload = readFully(input, len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (op) {
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                    if (!closed) {
                        sendFrame(OP_CLOSE, payload.copyOf(minOf(2, payload.size)))
                    }
                    return code to reason
                }
                OP_TEXT, OP_BINARY, OP_CONT -> {
                    if (op != OP_CONT) { message.reset(); messageOp = op }
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("message too large")
                    if (fin) {
                        val data = message.toByteArray()
                        message.reset()
                        if (messageOp == OP_TEXT) listener.onText(String(data, Charsets.UTF_8))
                        else listener.onBinary(data)
                    }
                }
            }
        }
        return 1000 to ""
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("connection closed")
        return b
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("connection closed")
            off += r
        }
        return out
    }

    /** Client frames are always masked (RFC 6455 §5.3). Writes are serialized. */
    private fun sendFrame(op: Int, payload: ByteArray) {
        if (closed) return
        writer.execute {
            val out = output ?: return@execute
            try {
                val header = ByteArrayOutputStream()
                header.write(0x80 or op)
                val n = payload.size
                when {
                    n < 126 -> header.write(0x80 or n)
                    n < 65536 -> { header.write(0x80 or 126); header.write(n shr 8 and 0xFF); header.write(n and 0xFF) }
                    else -> {
                        header.write(0x80 or 127)
                        for (i in 7 downTo 0) header.write((n.toLong() shr (8 * i) and 0xFF).toInt())
                    }
                }
                val mask = ByteArray(4).also { random.nextBytes(it) }
                header.write(mask)
                val body = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                synchronized(out) {
                    out.write(header.toByteArray())
                    out.write(body)
                    out.flush()
                }
            } catch (_: IOException) {
                try { socket?.close() } catch (_: IOException) {}
            }
        }
    }

    private fun startKeepAlive() {
        Thread({
            while (!closed && output != null) {
                try { Thread.sleep(PING_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                if (closed) return@Thread
                if (System.currentTimeMillis() - lastRxMs > PING_INTERVAL_MS * 3) {
                    try { socket?.close() } catch (_: IOException) {}
                    return@Thread
                }
                sendFrame(OP_PING, ByteArray(0))
            }
        }, "ws-ping").apply { isDaemon = true }.start()
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 4L * 1024 * 1024
        private const val PING_INTERVAL_MS = 30_000L
    }
}
