package com.wsturgiss.lightlink.localsend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * The slice of LocalSend protocol v2 this tool speaks: `info` to pair, and
 * `prepare-upload` / `upload` to send. Spec: https://github.com/localsend/protocol
 *
 * The phone is only ever the sender. It runs no server, so it never needs to be
 * reachable — the computer's LocalSend is the one listening on 53317.
 */
object LocalSend {
    const val DEFAULT_PORT = 53317
    const val PROTOCOL_VERSION = "2.1"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun infoPath() = "/api/localsend/v2/info"
    fun preparePath() = "/api/localsend/v2/prepare-upload"
    fun uploadPath(sessionId: String, fileId: String, token: String) =
        "/api/localsend/v2/upload?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * In HTTPS mode a LocalSend fingerprint is the SHA-256 of the certificate.
     * Lowercase hex, compared case-insensitively, since the spec does not fix a case.
     */
    fun fingerprintOf(certificateDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificateDer)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * A text message is a one-file session: `text/plain`, with the text itself in
     * `preview`. The receiver shows it as a message and may answer 204 — nothing
     * to upload — or hand back a token, in which case the same bytes go up as a file.
     */
    fun textSession(sender: DeviceInfo, text: String, fileId: String = UUID.randomUUID().toString()): PrepareUploadRequest {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return PrepareUploadRequest(
            info = sender,
            files = mapOf(
                fileId to FileMeta(
                    id = fileId,
                    fileName = "$fileId.txt",
                    size = bytes.size.toLong(),
                    fileType = "text/plain",
                    preview = text,
                ),
            ),
        )
    }
}

@Serializable
data class DeviceInfo(
    val alias: String,
    val version: String = LocalSend.PROTOCOL_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String = "",
    val port: Int = LocalSend.DEFAULT_PORT,
    val protocol: String = "https",
    val download: Boolean = false,
)

@Serializable
data class FileMeta(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
)

@Serializable
data class PrepareUploadRequest(
    val info: DeviceInfo,
    val files: Map<String, FileMeta>,
)

@Serializable
data class PrepareUploadResponse(
    val sessionId: String,
    val files: Map<String, String> = emptyMap(),
)

/** What happened to a send, in the words the screen shows. */
sealed interface SendOutcome {
    data object Delivered : SendOutcome
    data class Failed(val reason: SendFailure) : SendOutcome
}

enum class SendFailure(val message: String) {
    NotFound("Computer not found. Connect to home Wi-Fi and open LocalSend."),
    Declined("Declined on the computer."),
    NotAccepted("Not accepted on the computer in time."),
    PinRequired("LocalSend wants a PIN. Turn off \"Require PIN\" on the computer."),
    Busy("LocalSend is busy with another transfer."),
    CertificateChanged("The computer's certificate changed. Pair again."),
    Unexpected("LocalSend returned an error."),
    ;

    companion object {
        /** Maps a `prepare-upload` status that is not a success. */
        fun fromPrepareStatus(code: Int): SendFailure = when (code) {
            401 -> PinRequired
            403 -> Declined
            409, 429 -> Busy
            else -> Unexpected
        }
    }
}
