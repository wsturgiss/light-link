package com.wsturgiss.lightlink.localsend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * The slice of LocalSend protocol v2 this tool speaks, as a full peer: multicast
 * announcements and `register` to find devices, `prepare-upload` / `upload` to
 * send, and a text-only `prepare-upload` of its own to receive.
 * Spec: https://github.com/localsend/protocol
 *
 * While the tool is open the phone listens on 53317 ([LocalSendServer]) — TCP
 * for requests, UDP for announcements ([Discovery]).
 */
object LocalSend {
    const val DEFAULT_PORT = 53317
    const val PROTOCOL_VERSION = "2.1"
    const val MULTICAST_GROUP = "224.0.0.167"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun preparePath() = "/api/localsend/v2/prepare-upload"
    fun registerPath() = "/api/localsend/v2/register"
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

    /**
     * The text of a message session, or null if it is anything else. The mirror
     * of [textSession]: one file, a `text/` type, the text in `preview` — the
     * shape LocalSend's own "Text" button sends.
     */
    fun messageText(request: PrepareUploadRequest): String? {
        val file = request.files.values.singleOrNull() ?: return null
        if (!file.fileType.startsWith("text/")) return null
        return file.preview
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
    /**
     * Only in multicast messages: true asks everyone who hears it to register
     * back. Absent (null) everywhere else.
     */
    val announce: Boolean? = null,
)

/**
 * A LocalSend device the phone can send to. [fingerprint] is the SHA-256 of the
 * certificate it presented over TLS — learned when it was discovered or added,
 * and pinned on every send after.
 */
@Serializable
data class Device(
    val host: String,
    val port: Int,
    val alias: String,
    val fingerprint: String,
    val deviceType: String? = null,
    val deviceModel: String? = null,
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
    NotFound("Not found. Is it on the same Wi-Fi, with LocalSend open?"),
    Declined("Declined."),
    NotAccepted("Not accepted in time."),
    PinRequired("It wants a PIN. Turn off \"Require PIN\" in its LocalSend."),
    Busy("It is busy with another transfer."),
    CertificateChanged("Its certificate changed. Forget it and add it again."),
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
