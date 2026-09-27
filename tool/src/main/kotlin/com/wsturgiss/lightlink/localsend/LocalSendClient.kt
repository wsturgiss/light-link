package com.wsturgiss.lightlink.localsend

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** A computer the phone has paired with: where it is, and the certificate it showed. */
data class Computer(
    val host: String,
    val port: Int,
    val alias: String,
    val fingerprint: String,
)

/**
 * Blocking LocalSend client. Call from a background dispatcher.
 *
 * LocalSend serves HTTPS with a self-signed certificate on a bare LAN IP, so
 * neither the CA chain nor the hostname can be checked. Trust is the pinned
 * certificate fingerprint instead: captured once when pairing, required on
 * every send after. The phone presents [identity] in return — LocalSend
 * refuses a client without a certificate.
 */
class LocalSendClient(
    private val sender: DeviceInfo,
    private val identity: X509KeyManager,
) {

    /**
     * Pair step: reach the computer, record the certificate it presents, and read
     * its name. Nothing is trusted yet — the caller shows the name and asks.
     */
    fun probe(host: String, port: Int): Result<Computer> = runCatching {
        val trust = CapturingTrust()
        val response = client(trust).newCall(
            Request.Builder().url(url(host, port, LocalSend.infoPath())).get().build(),
        ).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("info HTTP ${it.code}")
            val info = LocalSend.json.decodeFromString<DeviceInfo>(it.body!!.string())
            val seen = trust.fingerprint ?: throw IOException("no certificate presented")
            Computer(host = host, port = port, alias = info.alias, fingerprint = seen)
        }
    }

    fun sendText(computer: Computer, text: String): SendOutcome {
        val request = LocalSend.textSession(sender, text)
        val http = client(PinnedTrust(computer.fingerprint))
        return try {
            val prepared = http.newCall(
                Request.Builder()
                    .url(url(computer.host, computer.port, LocalSend.preparePath()))
                    .post(LocalSend.json.encodeToString(PrepareUploadRequest.serializer(), request).toRequestBody(JSON))
                    .build(),
            ).execute().use { response ->
                when {
                    // Shown as a message; there is no file to transfer.
                    response.code == 204 -> return SendOutcome.Delivered
                    !response.isSuccessful -> return SendOutcome.Failed(SendFailure.fromPrepareStatus(response.code))
                    else -> LocalSend.json.decodeFromString<PrepareUploadResponse>(response.body!!.string())
                }
            }

            val (fileId, meta) = request.files.entries.single()
            val token = prepared.files[fileId] ?: return SendOutcome.Delivered
            http.newCall(
                Request.Builder()
                    .url(url(computer.host, computer.port, LocalSend.uploadPath(prepared.sessionId, fileId, token)))
                    .post(meta.preview.orEmpty().toByteArray(Charsets.UTF_8).toRequestBody(OCTETS))
                    .build(),
            ).execute().use { response ->
                if (response.isSuccessful) SendOutcome.Delivered
                else SendOutcome.Failed(SendFailure.Unexpected)
            }
        } catch (e: SSLHandshakeException) {
            Log.w(TAG, "send: TLS handshake failed", e)
            if (e.findCause<PinMismatch>() != null) SendOutcome.Failed(SendFailure.CertificateChanged)
            else SendOutcome.Failed(SendFailure.NotFound)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "send timed out", e)
            // Connected, then nobody answered LocalSend's accept prompt in time.
            val connected = e.message?.contains("connect", ignoreCase = true) != true
            SendOutcome.Failed(if (connected) SendFailure.NotAccepted else SendFailure.NotFound)
        } catch (e: IOException) {
            Log.w(TAG, "send failed", e)
            SendOutcome.Failed(SendFailure.NotFound)
        }
    }

    private fun client(trust: X509TrustManager): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(identity), arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // A LAN IP never matches a self-signed cert's name; the pin is the check.
            .hostnameVerifier { _, _ -> true }
            // Short: an unreachable computer should say so quickly, not hang.
            .connectTimeout(3, TimeUnit.SECONDS)
            // Long: LocalSend holds prepare-upload open until someone clicks Accept.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun url(host: String, port: Int, path: String) = "https://$host:$port$path"

    private companion object {
        const val TAG = "LocalSendClient"
        val JSON = "application/json".toMediaType()
        val OCTETS = "application/octet-stream".toMediaType()
    }
}

private class PinMismatch : CertificateException("certificate fingerprint does not match the paired computer")

private abstract class ServerOnlyTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Accepts whatever the server shows, once, so pairing can record it. */
private class CapturingTrust : ServerOnlyTrust() {
    @Volatile var fingerprint: String? = null
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        fingerprint = LocalSend.fingerprintOf(chain.first().encoded)
    }
}

private class PinnedTrust(private val expected: String) : ServerOnlyTrust() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        if (!LocalSend.fingerprintOf(chain.first().encoded).equals(expected, ignoreCase = true)) throw PinMismatch()
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()
