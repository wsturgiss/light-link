package com.wsturgiss.lightlink

import com.wsturgiss.lightlink.localsend.Device
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkTest {

    private fun device(alias: String, fingerprint: String, host: String = "10.0.0.1") =
        Device(host = host, port = 53317, alias = alias, fingerprint = fingerprint)

    @Test
    fun nearbyComesFirstAndSavedDevicesShowWhereTheyAreNow() {
        val computer = device("bacon", "AA", host = "10.0.0.9")
        val saved = listOf(computer, device("office", "BB"))
        val nearby = mapOf(
            "aa" to computer.copy(host = "10.0.0.42"),
            "cc" to device("zed phone", "cc"),
            "dd" to device("Alpha phone", "dd"),
        )

        val listings = Link.listings(saved, nearby)

        assertEquals(listOf("bacon", "Alpha phone", "zed phone", "office"), listings.map { it.device.alias })
        // The saved computer moved; the list offers its new address.
        assertEquals("10.0.0.42", listings.first().device.host)
        assertEquals(listOf(true, false, false, true), listings.map { it.saved })
        assertEquals(listOf(true, true, true, false), listings.map { it.nearby })
    }
}

class VisualIdTest {
    @Test
    fun isTheLastPartOfTheAddress() {
        assertEquals("214", visualId("192.168.200.214"))
    }
}

class ScanTest {
    private val fp = "307fc4784a6254d43773c313f63eca8b988e94bf99539fcea71eabe0ac46f5d5"

    @Test
    fun readsOurOwnLinkWithItsFingerprint() {
        assertEquals(Scanned("192.168.1.20", 53317, fp), parseScan(deviceLink("192.168.1.20", fp)))
        assertEquals(Scanned("192.168.1.20", 53317, fp), parseScan("https://192.168.1.20:53317/?fp=${fp.uppercase()}"))
    }

    @Test
    fun ourLinkIsPlainHttpButStillMeansHttps() {
        assertEquals("http://192.168.1.20:53317/?fp=$fp", deviceLink("192.168.1.20", fp))
        // Link serves HTTPS beside it, so there is nothing to wait for.
        assertEquals(true, parseScan(deviceLink("192.168.1.20", fp))!!.encrypted)
    }

    @Test
    fun readsLocalSendsLinkWithoutOne() {
        // LocalSend's default: unencrypted while its link page is open.
        assertEquals(Scanned("192.168.1.20", 53317, null, encrypted = false), parseScan("http://192.168.1.20:53317"))
        assertEquals(Scanned("192.168.1.20", 5000, null, encrypted = false), parseScan("http://192.168.1.20:5000"))
        // With its encryption switch on.
        assertEquals(Scanned("192.168.1.20", 53317, null), parseScan("https://192.168.1.20:53317"))
        assertEquals(Scanned("192.168.1.20", 53317, null), parseScan("https://192.168.1.20:53317/?pin=1234"))
        assertEquals(Scanned("192.168.1.20", 53317, null), parseScan("192.168.1.20"))
    }

    @Test
    fun junkIsRefusedAndABadFingerprintIsDropped() {
        kotlin.test.assertNull(parseScan("WIFI:S:home;T:WPA;P:secret;;"))
        kotlin.test.assertNull(parseScan("mailto:someone@example.com"))
        kotlin.test.assertNull(parseScan("hello there"))
        assertEquals(Scanned("192.168.1.20", 53317, null), parseScan("https://192.168.1.20:53317/?fp=nothex"))
    }
}
