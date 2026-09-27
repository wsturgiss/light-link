package com.wsturgiss.lightlink

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wsturgiss.lightlink.localsend.Computer
import com.wsturgiss.lightlink.localsend.ClientIdentity
import com.wsturgiss.lightlink.localsend.DeviceInfo
import com.wsturgiss.lightlink.localsend.LocalSendClient
import kotlinx.coroutines.flow.first

/** The one paired computer, and this phone's own LocalSend identity. */
class LinkStore(private val dataStore: DataStore<Preferences>) {

    suspend fun computer(): Computer? {
        val p = dataStore.data.first()
        return Computer(
            host = p[HOST] ?: return null,
            port = p[PORT] ?: return null,
            alias = p[ALIAS] ?: return null,
            fingerprint = p[FINGERPRINT] ?: return null,
        )
    }

    suspend fun pair(computer: Computer) {
        dataStore.edit {
            it[HOST] = computer.host
            it[PORT] = computer.port
            it[ALIAS] = computer.alias
            it[FINGERPRINT] = computer.fingerprint
        }
    }

    suspend fun forget() {
        dataStore.edit {
            it.remove(HOST); it.remove(PORT); it.remove(ALIAS); it.remove(FINGERPRINT)
        }
    }

    /** A client that introduces this phone with its keystore-backed certificate. */
    fun client(): LocalSendClient = LocalSendClient(
        sender = DeviceInfo(
            alias = "Light Phone",
            deviceModel = "Light Phone III",
            deviceType = "mobile",
            // In HTTPS mode LocalSend reads this from the client certificate anyway.
            fingerprint = ClientIdentity.fingerprint(),
        ),
        identity = ClientIdentity.keyManager(),
    )

    private companion object {
        val HOST = stringPreferencesKey("computer_host")
        val PORT = intPreferencesKey("computer_port")
        val ALIAS = stringPreferencesKey("computer_alias")
        val FINGERPRINT = stringPreferencesKey("computer_fingerprint")
    }
}
