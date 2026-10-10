package com.wsturgiss.lightlink

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.SealedLightContext
import com.wsturgiss.lightlink.localsend.ClientIdentity
import com.wsturgiss.lightlink.localsend.Device
import com.wsturgiss.lightlink.localsend.DeviceInfo
import com.wsturgiss.lightlink.localsend.LocalSend
import com.wsturgiss.lightlink.localsend.LocalSendClient
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A text that arrived from LocalSend. */
@Serializable
data class ReceivedMessage(val from: String, val text: String, val atMillis: Long)

/**
 * Saved devices, what has arrived, and this phone's own LocalSend identity.
 *
 * A saved device is one the user chose to keep: it stays listed when it is not
 * nearby, the phone registers with it directly (so it is found even where
 * multicast is not), and its messages open without asking. Its fingerprint is
 * the pin for every send.
 */
class LinkStore(private val context: SealedLightContext) {

    private val dataStore: DataStore<Preferences> get() = context.dataStore


    suspend fun saved(): List<Device> {
        migratePairedComputer()
        return dataStore.data.first()[SAVED].decode(SAVED_SERIALIZER)
    }

    /** Adds [device], or updates it (e.g. a new address) if its fingerprint is already saved. */
    suspend fun save(device: Device): List<Device> = editList(SAVED, SAVED_SERIALIZER) { current ->
        val index = current.indexOfFirst { it.fingerprint.equals(device.fingerprint, ignoreCase = true) }
        if (index < 0) current + device else current.toMutableList().apply { set(index, device) }
    }

    suspend fun forget(fingerprint: String): List<Device> = editList(SAVED, SAVED_SERIALIZER) { current ->
        current.filterNot { it.fingerprint.equals(fingerprint, ignoreCase = true) }
    }

    /** Messages received, newest first. */
    suspend fun inbox(): List<ReceivedMessage> = dataStore.data.first()[INBOX].decode(INBOX_SERIALIZER)

    suspend fun receive(message: ReceivedMessage): List<ReceivedMessage> =
        editList(INBOX, INBOX_SERIALIZER) { (listOf(message) + it).take(INBOX_LIMIT) }

    suspend fun clearInbox() {
        dataStore.edit { it.remove(INBOX) }
    }

    /** How this phone introduces itself, as a sender and as a receiver. */
    fun self(): DeviceInfo {
        val fingerprint = ClientIdentity.fingerprint()
        return DeviceInfo(
            // Two Light Phones on one network must not both be "Light Phone".
            alias = "Light Phone ${fingerprint.take(4).uppercase()}",
            deviceModel = "Light Phone III",
            deviceType = "mobile",
            // In HTTPS mode LocalSend reads this from the client certificate anyway.
            fingerprint = fingerprint,
        )
    }

    /**
     * LocalSend's "Receive via link" upload page (Apache-2.0, vendored in
     * assets/web with its licence).
     */
    fun uploadPage(): String = context.readAsset("web/upload.html").toString(Charsets.UTF_8)

    /** A client that introduces this phone with its keystore-backed certificate. */
    fun client(): LocalSendClient = LocalSendClient(sender = self(), identity = ClientIdentity.keyManager())

    private suspend fun <T> editList(
        key: Preferences.Key<String>,
        serializer: KSerializer<List<T>>,
        change: (List<T>) -> List<T>,
    ): List<T> {
        var updated = emptyList<T>()
        dataStore.edit { prefs ->
            updated = change(prefs[key].decode(serializer))
            prefs[key] = LocalSend.json.encodeToString(serializer, updated)
        }
        return updated
    }

    private fun <T> String?.decode(serializer: KSerializer<List<T>>): List<T> =
        this?.let { runCatching { LocalSend.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** The first version paired with exactly one computer; it becomes the first saved device. */
    private suspend fun migratePairedComputer() {
        dataStore.edit { prefs ->
            val host = prefs[OLD_HOST] ?: return@edit
            val device = Device(
                host = host,
                port = prefs[OLD_PORT] ?: LocalSend.DEFAULT_PORT,
                alias = prefs[OLD_ALIAS] ?: host,
                fingerprint = prefs[OLD_FINGERPRINT] ?: return@edit,
                deviceType = "desktop",
            )
            val current = prefs[SAVED].decode(SAVED_SERIALIZER)
            if (current.none { it.fingerprint.equals(device.fingerprint, ignoreCase = true) }) {
                prefs[SAVED] = LocalSend.json.encodeToString(SAVED_SERIALIZER, current + device)
            }
            prefs.remove(OLD_HOST); prefs.remove(OLD_PORT); prefs.remove(OLD_ALIAS); prefs.remove(OLD_FINGERPRINT)
        }
    }

    private companion object {
        val SAVED = stringPreferencesKey("saved_devices")
        val SAVED_SERIALIZER = ListSerializer(Device.serializer())
        val INBOX = stringPreferencesKey("inbox")
        val INBOX_SERIALIZER = ListSerializer(ReceivedMessage.serializer())
        const val INBOX_LIMIT = 50

        val OLD_HOST = stringPreferencesKey("computer_host")
        val OLD_PORT = intPreferencesKey("computer_port")
        val OLD_ALIAS = stringPreferencesKey("computer_alias")
        val OLD_FINGERPRINT = stringPreferencesKey("computer_fingerprint")
    }
}
