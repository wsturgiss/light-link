package com.wsturgiss.lightlink.localsend

/**
 * Who is on the other end of a request: its address, and its certificate's
 * fingerprint. No fingerprint means a web browser on the "Receive via link"
 * page — browsers have no client certificate.
 */
data class Peer(val ip: String, val fingerprint: String?)

/** What someone wants to send. */
sealed interface Offer {
    data class Text(val text: String) : Offer
    data class Files(val files: List<FileMeta>) : Offer
}

/** What became of an offer. */
enum class Decision { Accepted, Declined, Busy }

/**
 * What the phone does with a request once TLS has shown who sent it. Pure — no
 * sockets — so it can be tested on the JVM.
 *
 * A text message is taken: LocalSend sends one as a one-file session whose
 * `preview` holds the text, and answering 204 says nothing needs uploading,
 * which is exactly how its own receiver treats a message. Files are offered to
 * [onOffer] but can only be declined for now — there is no upload path yet.
 *
 * While [webPage] is non-null the phone also serves LocalSend's "Receive via
 * link" page at `/`, and browsers may offer files through the same
 * `prepare-upload` a LocalSend app uses.
 */
class ReceiveHandler(
    private val self: () -> DeviceInfo,
    /** A device registered: it heard an announcement, scanned, or was added by address. */
    private val onRegister: (Device) -> Unit,
    /** Blocks until the offer is accepted or declined. */
    private val onOffer: (from: Device, offer: Offer) -> Decision,
    /** The upload page while "Receive via link" is open, else null. */
    private val webPage: () -> String? = { null },
) {

    data class Response(val status: Int, val body: String? = null, val contentType: String = JSON)

    fun handle(method: String, path: String, body: String, peer: Peer): Response {
        val route = path.substringBefore('?')
        // A browser may use the link page and nothing else.
        if (peer.fingerprint == null && !(webPage() != null && route in BROWSER_ROUTES)) return Response(403)
        return when {
            method == "GET" && route == "/" -> webPage()?.let { Response(200, it, HTML) } ?: Response(404)
            method == "GET" && route == "/i18n.json" -> if (webPage() != null) Response(200, WEB_I18N) else Response(404)
            method == "POST" && route == REGISTER -> register(body, peer)
            method == "GET" && route == INFO -> info()
            method == "POST" && route == PREPARE_UPLOAD -> prepareUpload(body, peer)
            // We never hand out upload tokens, so there is never an upload to take.
            method == "POST" && route == UPLOAD -> Response(403)
            method == "POST" && route == CANCEL -> Response(200)
            else -> Response(404)
        }
    }

    private fun info() = Response(200, LocalSend.json.encodeToString(DeviceInfo.serializer(), self()))

    private fun register(body: String, peer: Peer): Response {
        val info = runCatching { LocalSend.json.decodeFromString(DeviceInfo.serializer(), body) }
            .getOrElse { return Response(400) }
        // As LocalSend does: a registration counts only if the fingerprint it
        // claims is the certificate it connected with. Answered either way.
        if (peer.fingerprint != null && info.fingerprint.equals(peer.fingerprint, ignoreCase = true) && info.protocol == "https") {
            onRegister(device(peer, info))
        }
        return info()
    }

    private fun prepareUpload(body: String, peer: Peer): Response {
        val request = runCatching { LocalSend.json.decodeFromString(PrepareUploadRequest.serializer(), body) }
            .getOrElse { return Response(400) }
        if (request.files.isEmpty()) return Response(400)
        val offer = LocalSend.messageText(request)?.let(Offer::Text) ?: Offer.Files(request.files.values.toList())
        return when (onOffer(device(peer, request.info), offer)) {
            Decision.Accepted -> Response(204)
            Decision.Declined -> Response(403)
            Decision.Busy -> Response(409)
        }
    }

    // The address is where the request came from; the identity is the
    // certificate, never the fingerprint the body claims. A browser has none,
    // so it is marked as one and can never be mistaken for a saved device.
    private fun device(peer: Peer, info: DeviceInfo) = Device(
        host = peer.ip,
        port = info.port,
        alias = info.alias,
        fingerprint = peer.fingerprint ?: BROWSER,
        deviceType = if (peer.fingerprint == null) "web" else info.deviceType,
        deviceModel = info.deviceModel,
    )

    companion object {
        /** The fingerprint given to every browser; no certificate hashes to it. */
        const val BROWSER = "browser"

        private const val JSON = "application/json"
        private const val HTML = "text/html; charset=utf-8"
        private const val BASE = "/api/localsend/v2"
        private const val REGISTER = "$BASE/register"
        private const val INFO = "$BASE/info"
        private const val PREPARE_UPLOAD = "$BASE/prepare-upload"
        private const val UPLOAD = "$BASE/upload"
        private const val CANCEL = "$BASE/cancel"
        private val BROWSER_ROUTES = setOf("/", "/i18n.json", PREPARE_UPLOAD, UPLOAD, CANCEL)

        // The strings upload.html asks for — LocalSend's English defaults (WebI18n in its core).
        private val WEB_I18N = """
            {"waiting":"Waiting for response…","enterPin":"Enter PIN","invalidPin":"Invalid PIN",
            "tooManyAttempts":"Too many attempts","rejected":"Rejected",
            "uploadRejected":"The recipient has rejected the request.",
            "busy":"The recipient is busy with another request.","files":"Files","fileName":"File name",
            "size":"Size","dropHint":"Place items to share."}
        """.trimIndent().replace("\n", "")
    }
}
