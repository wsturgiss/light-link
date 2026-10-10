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

/**
 * Blocking LocalSend client. Call from a background dispatcher.
 *
 * LocalSend serves HTTPS with a self-signed certificate on a bare LAN IP, so
 * neither the CA chain nor the hostname can be checked. Trust is the
 * certificate fingerprint instead: learned by [register] when a device is
 * discovered or added, and pinned on every send after. The phone presents
 * [identity] in return — LocalSend refuses a client without a certificate.
 */
class LocalSendClient(
    private val sender: DeviceInfo,
    private val identity: X509KeyManager,
) {
    // One pool for every request; each call only swaps the trust it needs.
    private val base = OkHttpClient.Builder()
        // A LAN IP never matches a self-signed cert's name; the fingerprint is the check.
        .hostnameVerifier { _, _ -> true }
        .build()

    /**
     * Register with whatever LocalSend answers at [host]:[port] and learn who it
     * is: this is discovery, and it is also what makes the other side list the
     * phone. With [expected] the certificate must have that fingerprint (it was
     * claimed in an announcement, or the device is saved); without, whatever is
     * presented is captured — only discovery and adding by address may do that.
     */
    fun register(host: String, port: Int, expected: String? = null, quick: Boolean = false): Result<Device> = runCatching {
        val http = client(expected?.let(::PinnedTrust) ?: CapturingTrust, quick)
        http.newCall(
            Request.Builder()
                .url(url(host, port, LocalSend.registerPath()))
                .post(LocalSend.json.encodeToString(DeviceInfo.serializer(), sender).toRequestBody(JSON))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("register HTTP ${response.code}")
            val info = LocalSend.json.decodeFromString(DeviceInfo.serializer(), response.body!!.string())
            val certificate = response.handshake?.peerCertificates?.firstOrNull()
                ?: throw IOException("no certificate presented")
            val fingerprint = LocalSend.fingerprintOf(certificate.encoded)
            if (fingerprint.equals(sender.fingerprint, ignoreCase = true)) throw IOException("that is this phone")
            Device(
                host = host,
                port = port,
                alias = info.alias,
                fingerprint = fingerprint,
                deviceType = info.deviceType,
                deviceModel = info.deviceModel,
            )
        }
    }

    fun sendText(device: Device, text: String): SendOutcome {
        val request = LocalSend.textSession(sender, text)
        val http = client(PinnedTrust(device.fingerprint), quick = false)
        return try {
            val prepared = http.newCall(
                Request.Builder()
                    .url(url(device.host, device.port, LocalSend.preparePath()))
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
                    .url(url(device.host, device.port, LocalSend.uploadPath(prepared.sessionId, fileId, token)))
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

    private fun client(trust: X509TrustManager, quick: Boolean): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(identity), arrayOf(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .apply {
                if (quick) {
                    // Discovery and scans: LAN peers answer fast or not at all.
                    connectTimeout(1, TimeUnit.SECONDS)
                    readTimeout(2, TimeUnit.SECONDS)
                } else {
                    // Short: an unreachable device should say so quickly, not hang.
                    connectTimeout(3, TimeUnit.SECONDS)
                    // Long: LocalSend holds prepare-upload open until someone clicks Accept.
                    readTimeout(60, TimeUnit.SECONDS)
                }
            }
            .build()
    }

    private fun url(host: String, port: Int, path: String) = "https://$host:$port$path"

    private companion object {
        const val TAG = "LocalSendClient"
        val JSON = "application/json".toMediaType()
        val OCTETS = "application/octet-stream".toMediaType()
    }
}

private class PinMismatch : CertificateException("certificate fingerprint does not match the known device")

private abstract class ServerOnlyTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts whatever the server shows, so [LocalSendClient.register] can read it
 * off the handshake. Never used for a send.
 */
private object CapturingTrust : ServerOnlyTrust() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) = Unit
}

private class PinnedTrust(private val expected: String) : ServerOnlyTrust() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        if (!LocalSend.fingerprintOf(chain.first().encoded).equals(expected, ignoreCase = true)) throw PinMismatch()
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()
