package com.wsturgiss.lightlink.localsend

import com.wsturgiss.lightlink.parseAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {

    private val sender = DeviceInfo(alias = "Light Phone", deviceType = "mobile", fingerprint = "abc")

    @Test
    fun textSessionCarriesTheTextAsPreviewAndSize() {
        val request = LocalSend.textSession(sender, "héllo", fileId = "f1")
        val file = request.files.getValue("f1")
        assertEquals("text/plain", file.fileType)
        assertEquals("héllo", file.preview)
        assertEquals(6, file.size) // UTF-8 bytes, not chars
        assertEquals("f1.txt", file.fileName)
    }

    @Test
    fun prepareRequestSerializesWithoutNulls() {
        val encoded = LocalSend.json.encodeToString(PrepareUploadRequest.serializer(), LocalSend.textSession(sender, "hi", "f1"))
        assert("\"sha256\"" !in encoded) { encoded }
        assert("\"protocol\":\"https\"" in encoded) { encoded }
    }

    @Test
    fun fingerprintIsLowercaseSha256Hex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalSend.fingerprintOf("abc".toByteArray()),
        )
    }

    @Test
    fun prepareStatusesMapToWhatTheUserCanDo() {
        assertEquals(SendFailure.PinRequired, SendFailure.fromPrepareStatus(401))
        assertEquals(SendFailure.Declined, SendFailure.fromPrepareStatus(403))
        assertEquals(SendFailure.Busy, SendFailure.fromPrepareStatus(409))
        assertEquals(SendFailure.Unexpected, SendFailure.fromPrepareStatus(500))
    }

    @Test
    fun addressesParseWithDefaultPort() {
        assertEquals("192.168.1.20" to 53317, parseAddress(" 192.168.1.20 "))
        assertEquals("192.168.1.20" to 5000, parseAddress("192.168.1.20:5000"))
        assertEquals("bacon.local" to 53317, parseAddress("bacon.local"))
    }

    @Test
    fun junkAddressesAreRejected() {
        assertNull(parseAddress(""))
        assertNull(parseAddress("192.168.1.20:99999"))
        assertNull(parseAddress("192.168.1.20/evil"))
        assertNull(parseAddress("a b"))
        assertNull(parseAddress("host:port"))
    }
}
