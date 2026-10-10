package com.wsturgiss.lightlink.localsend

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * The phone's LocalSend server, run while the tool is open.
 *
 * Mutual TLS. The phone presents [identity] — the same certificate it sends
 * with, because that fingerprint is what other devices learn from its
 * announcements and pin. It demands a client certificate in return: any will
 * do, since any LocalSend device may register or offer a message, but its
 * fingerprint is the peer's identity from then on and goes to [handler] with
 * every request.
 *
 * Deliberately small: requests are a few kilobytes of JSON, every response
 * closes the connection, and there is no upload path (see [ReceiveHandler]).
 */
class LocalSendServer(
    private val identity: X509KeyManager,
    private val handler: ReceiveHandler,
    /**
     * True while "Receive via link" is open. A browser may then connect without
     * a client certificate, over plain HTTP (as LocalSend's link does by
     * default) or HTTPS. [ReceiveHandler] keeps such a connection to the link
     * page's routes. HTTPS for LocalSend devices carries on alongside.
     */
    private val allowBrowsers: () -> Boolean = { false },
) {
    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var closed = false

    /**
     * Binds and starts accepting in the background. Throws if the port is taken.
     * A no-op once stopped, so a stop that races a start still wins.
     */
    @Synchronized
    fun start(port: Int = LocalSend.DEFAULT_PORT) {
        if (closed) return
        check(socket == null) { "already running" }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(identity), arrayOf(AnyClient), SecureRandom()) }
        // A plain socket: each connection is looked at first, and TLS layered on
        // when it starts a handshake. That is what lets HTTP and HTTPS share 53317.
        val server = ServerSocket().apply {
            // Reopening the tool right after leaving it must not trip over TIME_WAIT.
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS)
        socket = server
        workers = pool
        Thread({ acceptLoop(server, pool, ssl.socketFactory) }, "localsend-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        closed = true
        runCatching { socket?.close() }
        workers?.shutdownNow()
        socket = null
        workers = null
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService, tls: SSLSocketFactory) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) Log.w(TAG, "accept failed", e)
                break
            }
            try {
                pool.execute { serve(client, tls) }
            } catch (e: RejectedExecutionException) {
                client.close()
            }
        }
    }

    private fun serve(raw: Socket, tls: SSLSocketFactory) = raw.use {
        try {
            raw.soTimeout = READ_TIMEOUT_MS
            val first = raw.getInputStream().read()
            if (first < 0) return@use
            val ip = raw.inetAddress.hostAddress.orEmpty()
            val browsers = allowBrowsers()

            val (peer, input, output) = if (first == TLS_HANDSHAKE) {
                // Layer TLS over the connection, with the byte already read put back in front.
                val secure = tls.createSocket(Replayed(raw, first), ip, raw.port, true) as SSLSocket
                secure.useClientMode = false
                if (browsers) secure.wantClientAuth = true else secure.needClientAuth = true
                // Explicit, so a connection without a certificate is refused before anything is read.
                secure.startHandshake()
                val certificate = try {
                    secure.session.peerCertificates.firstOrNull()
                } catch (e: SSLPeerUnverifiedException) {
                    null
                }
                Triple(
                    Peer(ip, certificate?.let { cert -> LocalSend.fingerprintOf(cert.encoded) }),
                    secure.inputStream,
                    secure.outputStream,
                )
            } else {
                // Plain HTTP: only a browser on the open link page.
                if (!browsers) return@use
                Triple(
                    Peer(ip, fingerprint = null),
                    SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), raw.getInputStream()),
                    raw.getOutputStream(),
                )
            }

            val response = readRequest(BufferedInputStream(input))
                ?.let { (method, path, body) -> handler.handle(method, path, body, peer) }
                ?: ReceiveHandler.Response(400)
            output.apply { write(encode(response)); flush() }
        } catch (e: SocketException) {
            Log.d(TAG, "connection dropped", e)
        } catch (e: IOException) {
            Log.w(TAG, "request from ${raw.inetAddress?.hostAddress} failed", e)
        }
    }

    private companion object {
        const val TAG = "LocalSendServer"
        // An open accept prompt holds one for up to a minute; announcements bring bursts.
        const val MAX_CONNECTIONS = 8
        const val READ_TIMEOUT_MS = 15_000
        // Every TLS connection opens with a handshake record; HTTP opens with a method name.
        const val TLS_HANDSHAKE = 0x16
    }
}

/**
 * [socket] with [first] — already read to tell TLS from HTTP — put back in
 * front of its input, so the TLS layer sees the whole handshake. Everything
 * else is the socket's own.
 */
private class Replayed(private val socket: Socket, first: Int) : Socket() {
    private val input = SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), socket.getInputStream())

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): java.io.OutputStream = socket.getOutputStream()
    override fun close() = socket.close()
    override fun isClosed() = socket.isClosed
    override fun isConnected() = socket.isConnected
    override fun isBound() = socket.isBound
    override fun isInputShutdown() = socket.isInputShutdown
    override fun isOutputShutdown() = socket.isOutputShutdown
    override fun shutdownInput() = socket.shutdownInput()
    override fun shutdownOutput() = socket.shutdownOutput()
    override fun getInetAddress(): java.net.InetAddress? = socket.inetAddress
    override fun getPort() = socket.port
    override fun getLocalAddress(): java.net.InetAddress? = socket.localAddress
    override fun getLocalPort() = socket.localPort
    override fun getRemoteSocketAddress(): java.net.SocketAddress? = socket.remoteSocketAddress
    override fun getLocalSocketAddress(): java.net.SocketAddress? = socket.localSocketAddress
    override fun getSoTimeout() = socket.soTimeout
    override fun setSoTimeout(timeout: Int) { socket.soTimeout = timeout }
    override fun getTcpNoDelay() = socket.tcpNoDelay
    override fun setTcpNoDelay(on: Boolean) { socket.tcpNoDelay = on }
    override fun getKeepAlive() = socket.keepAlive
    override fun setKeepAlive(on: Boolean) { socket.keepAlive = on }
    override fun getSoLinger() = socket.soLinger
    override fun setSoLinger(on: Boolean, linger: Int) = socket.setSoLinger(on, linger)
}

/** One parsed request: method, path with query, and the body as text. */
internal data class HttpRequest(val method: String, val path: String, val body: String)

private const val MAX_HEAD_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 1024 * 1024

/**
 * Reads one HTTP/1.1 request. Null for anything malformed or oversized. A
 * chunked body is not read: the only chunked request LocalSend makes is a file
 * upload, which is refused without looking at it.
 */
internal fun readRequest(input: InputStream): HttpRequest? {
    val head = readHead(input) ?: return null
    val lines = head.split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
    val headers = lines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
        line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()

    val length = headers["content-length"]?.let { it.toIntOrNull() ?: return null } ?: 0
    if (length !in 0..MAX_BODY_BYTES) return null
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) return null
        read += n
    }
    return HttpRequest(parts[0], parts[1], String(body, Charsets.UTF_8))
}

private fun readHead(input: InputStream): String? {
    val bytes = java.io.ByteArrayOutputStream()
    var matched = 0
    val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (matched < end.size) {
        val b = input.read()
        if (b < 0 || bytes.size() >= MAX_HEAD_BYTES) return null
        bytes.write(b)
        matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
    }
    return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
}

internal fun encode(response: ReceiveHandler.Response): ByteArray {
    val body = response.body?.toByteArray(Charsets.UTF_8)
    val head = buildString {
        append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        // A 204 must not carry a Content-Length.
        if (response.status != 204) append("Content-Length: ${body?.size ?: 0}\r\n")
        if (body != null) append("Content-Type: ${response.contentType}\r\n")
        append("Connection: close\r\n\r\n")
    }
    return head.toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0))
}

private fun reason(status: Int) = when (status) {
    200 -> "OK"
    204 -> "No Content"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    else -> "Status"
}

/** Any client certificate is let in; it is the identity, not a credential. */
private object AnyClient : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
        if (chain.isEmpty()) throw CertificateException("a client certificate is required")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("this is a server only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
