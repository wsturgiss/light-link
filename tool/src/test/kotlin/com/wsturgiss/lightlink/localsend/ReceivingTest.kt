package com.wsturgiss.lightlink.localsend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceivingTest {

    private val phone = DeviceInfo(alias = "Light Phone", deviceType = "mobile", fingerprint = "abc")
    private val peer = Peer(ip = "192.168.1.20", fingerprint = "f00d")
    private val received = mutableListOf<Pair<Device, String>>()
    private val registered = mutableListOf<Device>()
    private val offeredFiles = mutableListOf<Offer.Files>()
    private var decision = Decision.Accepted
    private var page: String? = null
    private val handler = ReceiveHandler(
        self = { phone },
        onRegister = { registered += it },
        onOffer = { from, offer ->
            when (offer) {
                is Offer.Text -> received += from to offer.text
                is Offer.Files -> offeredFiles += offer
            }
            decision
        },
        webPage = { page },
    )

    private fun handle(method: String, path: String, body: String = "", from: Peer = peer) =
        handler.handle(method, path, body, from)

    private val browser = Peer(ip = "192.168.1.30", fingerprint = null)

    // What LocalSend's upload.html posts: no certificate, a made-up fingerprint.
    private val browserFiles = """
        {"info":{"alias":"Web Browser","version":"2.1","deviceType":"web","fingerprint":"web-1234",
          "port":0,"protocol":"https","download":false},
         "files":{"0":{"id":"0","fileName":"cat.jpg","size":1234,"fileType":"image/jpeg"}}}
    """.trimIndent()

    // Shaped like LocalSend 1.18's own text send: fileType from the .txt name,
    // a checksum, and fields this tool does not model.
    private val desktopText = """
        {"info":{"alias":"bacon","version":"2.1","deviceModel":"Linux","deviceType":"desktop",
          "fingerprint":"F00D","port":53317,"protocol":"https","download":false},
         "files":{"f1":{"id":"f1","fileName":"f1.txt","size":5,"fileType":"text/plain",
          "sha256":"x","preview":"hello","metadata":{"modified":"2026-01-01T00:00:00Z"}}}}
    """.trimIndent()

    @Test
    fun textIsTakenAndNeedsNoUpload() {
        val response = handle("POST", "/api/localsend/v2/prepare-upload", desktopText)
        assertEquals(204, response.status)
        val (from, text) = received.single()
        assertEquals("hello", text)
        // Identity and address come from the connection, not from what the body claims.
        assertEquals(Device("192.168.1.20", 53317, "bacon", "f00d", "desktop", "Linux"), from)
    }

    @Test
    fun declinedAndBusyAreTheProtocolCodes() {
        decision = Decision.Declined
        assertEquals(403, handle("POST", "/api/localsend/v2/prepare-upload", desktopText).status)
        decision = Decision.Busy
        assertEquals(409, handle("POST", "/api/localsend/v2/prepare-upload", desktopText).status)
    }

    @Test
    fun pinQueryDoesNotChangeTheRoute() {
        assertEquals(204, handle("POST", "/api/localsend/v2/prepare-upload?pin=1", desktopText).status)
    }

    @Test
    fun filesAreOfferedAndDeclined() {
        decision = Decision.Declined
        val photo = desktopText.replace("\"text/plain\"", "\"image/jpeg\"").replace("\"preview\":\"hello\",", "")
        assertEquals(403, handle("POST", "/api/localsend/v2/prepare-upload", photo).status)
        assertTrue(received.isEmpty())
        assertEquals("f1.txt", offeredFiles.single().files.single().fileName)
    }

    @Test
    fun withoutTheLinkOpenABrowserGetsNothing() {
        assertEquals(403, handle("GET", "/", from = browser).status)
        assertEquals(403, handle("POST", "/api/localsend/v2/prepare-upload", browserFiles, from = browser).status)
        assertTrue(offeredFiles.isEmpty())
    }

    @Test
    fun withTheLinkOpenABrowserGetsThePageAndCanOffer() {
        page = "<html>upload</html>"
        val index = handle("GET", "/", from = browser)
        assertEquals(200, index.status)
        assertTrue(index.contentType.startsWith("text/html"))
        assertTrue("uploadRejected" in handle("GET", "/i18n.json", from = browser).body!!)

        decision = Decision.Declined
        assertEquals(403, handle("POST", "/api/localsend/v2/prepare-upload", browserFiles, from = browser).status)
        assertEquals("cat.jpg", offeredFiles.single().files.single().fileName)
    }

    @Test
    fun aBrowserCannotRegisterOrPassForADevice() {
        page = "<html>upload</html>"
        val body = """{"alias":"x","version":"2.1","fingerprint":"browser","port":53317,"protocol":"https"}"""
        assertEquals(403, handle("POST", "/api/localsend/v2/register", body, from = browser).status)
        assertTrue(registered.isEmpty())

        handle("POST", "/api/localsend/v2/prepare-upload", desktopText.replace("bacon", "sneaky"), from = browser)
        // Even claiming a real fingerprint in the body, it is marked as a browser.
        assertEquals(ReceiveHandler.BROWSER, received.single().first.fingerprint)
        assertEquals("web", received.single().first.deviceType)
    }

    @Test
    fun theVendoredUploadPageIsPackaged() {
        // Unit tests run in the module directory.
        val html = java.io.File("src/main/assets/web/upload.html").readText()
        assertTrue("/api/localsend/v2" in html)
        assertTrue("Apache License 2.0" in html)
    }

    @Test
    fun registerAnswersWithThePhoneAndRecordsTheDevice() {
        val body = """{"alias":"bacon","version":"2.1","fingerprint":"F00D","port":53318,"protocol":"https"}"""
        val response = handle("POST", "/api/localsend/v2/register", body)
        assertEquals(200, response.status)
        assertEquals("Light Phone", LocalSend.json.decodeFromString(DeviceInfo.serializer(), response.body!!).alias)
        assertEquals(Device("192.168.1.20", 53318, "bacon", "f00d"), registered.single())
    }

    @Test
    fun registerClaimingSomeoneElsesFingerprintIsNotRecorded() {
        val body = """{"alias":"spoof","version":"2.1","fingerprint":"beef","port":53317,"protocol":"https"}"""
        assertEquals(200, handle("POST", "/api/localsend/v2/register", body).status)
        assertTrue(registered.isEmpty())
    }

    @Test
    fun junkAndUnknownRoutes() {
        assertEquals(400, handle("POST", "/api/localsend/v2/prepare-upload", "not json").status)
        assertEquals(403, handle("POST", "/api/localsend/v2/upload?sessionId=s&fileId=f&token=t").status)
        assertEquals(404, handle("GET", "/").status)
    }

    @Test
    fun announcementCarriesTheFlagButRegisterBodiesDoNot() {
        assertTrue("\"announce\":true" in LocalSend.json.encodeToString(DeviceInfo.serializer(), phone.copy(announce = true)))
        assertTrue("announce" !in LocalSend.json.encodeToString(DeviceInfo.serializer(), phone))
    }

    @Test
    fun subnetScanCoversTheSlash24WithoutUs() {
        val hosts = Lan.subnetHosts("192.168.200.214", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.200.1", hosts.first())
        assertEquals("192.168.200.254", hosts.last())
        assertTrue("192.168.200.214" !in hosts)
        // Wider networks are cut to the /24 around the phone.
        assertEquals(hosts, Lan.subnetHosts("192.168.200.214", 16))
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.5", "10.0.0.6"), Lan.subnetHosts("10.0.0.4", 29))
    }

    @Test
    fun messageTextIsTheMirrorOfTextSession() {
        assertEquals("héllo", LocalSend.messageText(LocalSend.textSession(phone, "héllo")))
        val two = LocalSend.textSession(phone, "a", "1").let { it.copy(files = it.files + LocalSend.textSession(phone, "b", "2").files) }
        assertNull(LocalSend.messageText(two))
    }

    @Test
    fun requestIsReadByContentLength() {
        val raw = "POST /api/localsend/v2/register HTTP/1.1\r\nHost: x\r\ncontent-length: 7\r\n\r\n{\"a\":1}trailing"
        val request = readRequest(raw.byteInputStream())!!
        assertEquals(HttpRequest("POST", "/api/localsend/v2/register", "{\"a\":1}"), request)
    }

    @Test
    fun malformedRequestsAreRefused() {
        assertNull(readRequest("nonsense\r\n\r\n".byteInputStream()))
        assertNull(readRequest("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort".byteInputStream()))
        assertNull(readRequest("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".byteInputStream()))
    }

    @Test
    fun noContentCarriesNoLength() {
        val text = String(encode(ReceiveHandler.Response(204)))
        assertEquals("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n", text)
        assertTrue("Content-Length: 2\r\n" in String(encode(ReceiveHandler.Response(200, "{}"))))
    }
}
