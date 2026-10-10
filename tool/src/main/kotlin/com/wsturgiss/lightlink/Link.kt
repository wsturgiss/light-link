package com.wsturgiss.lightlink

import android.util.Log
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightModalManager
import com.wsturgiss.lightlink.localsend.ClientIdentity
import com.wsturgiss.lightlink.localsend.Decision
import com.wsturgiss.lightlink.localsend.Device
import com.wsturgiss.lightlink.localsend.DeviceInfo
import com.wsturgiss.lightlink.localsend.Discovery
import com.wsturgiss.lightlink.localsend.Lan
import com.wsturgiss.lightlink.localsend.LocalSend
import com.wsturgiss.lightlink.localsend.LocalSendClient
import com.wsturgiss.lightlink.localsend.LocalSendServer
import com.wsturgiss.lightlink.localsend.Offer
import com.wsturgiss.lightlink.localsend.ReceiveHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface LinkStatus {
    data object Starting : LinkStatus
    data object Ready : LinkStatus
    data object NoWifi : LinkStatus
    data class Failed(val message: String) : LinkStatus
}

/** A device as the list shows it: saved, nearby, or both. */
data class Listing(val device: Device, val saved: Boolean, val nearby: Boolean)

/**
 * The phone as a LocalSend peer, for as long as the tool is in front.
 *
 * [resume] starts the server and discovery; [pause] stops them. Every screen
 * calls both (see [LinkViewModel]), because the one in front when the app comes
 * back is the one that hears about it.
 *
 * Devices are found three ways, all ending in a TLS exchange that fixes the
 * device's fingerprint: they register with us after an announcement; we
 * register with them after hearing theirs, or directly if saved; or a subnet
 * scan finds them when multicast does not get through.
 */
object Link {
    private const val TAG = "Link"
    private const val ANNOUNCE_EVERY_MS = 15_000L
    private const val SCAN_AFTER_MS = 3_000L
    private const val SCAN_CONCURRENCY = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Starting)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _self = MutableStateFlow<DeviceInfo?>(null)
    val self: StateFlow<DeviceInfo?> = _self.asStateFlow()

    private val _saved = MutableStateFlow<List<Device>>(emptyList())
    /** Keyed by lowercase fingerprint. */
    private val _nearby = MutableStateFlow<Map<String, Device>>(emptyMap())

    val devices: StateFlow<List<Listing>> = combine(_saved, _nearby, ::listings)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _inbox = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val inbox: StateFlow<List<ReceivedMessage>> = _inbox.asStateFlow()

    /** This phone's LAN address while it is on one, for the receive screen and the link. */
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    /** "Receive via link": browsers may use the upload page while this is on. */
    private val _webShare = MutableStateFlow(false)
    val webShare: StateFlow<Boolean> = _webShare.asStateFlow()

    @Volatile private var store: LinkStore? = null
    @Volatile private var client: LocalSendClient? = null
    @Volatile private var discovery: Discovery? = null
    @Volatile private var sessionScope: CoroutineScope? = null
    @Volatile private var prompt: CompletableFuture<Boolean>? = null
    private var session: Job? = null
    private var parts: Parts? = null
    private val answered = ConcurrentHashMap<String, Long>()

    /** Starts listening and discovering. [store] is needed the first time only. */
    fun resume(store: LinkStore? = null) {
        if (store != null) this.store = store
        val links = this.store ?: return
        if (session != null) return
        val owned = Parts().also { parts = it }
        session = scope.launch {
            sessionScope = this
            _status.value = LinkStatus.Starting
            _saved.value = links.saved()
            _inbox.value = links.inbox()
            val self = links.self().also { _self.value = it }
            val uploadPage = links.uploadPage()
            val client = links.client().also { this@Link.client = it }

            val server = owned.own(
                LocalSendServer(ClientIdentity.keyManager(), handler(self, uploadPage), allowBrowsers = { _webShare.value }),
            ) { it.stop() } ?: return@launch
            try {
                server.start()
            } catch (e: IOException) {
                Log.w(TAG, "cannot listen", e)
                _status.value = LinkStatus.Failed("Can't listen for LocalSend. Is another LocalSend app open on this phone?")
                return@launch
            }
            val discovery = owned.own(Discovery({ self }, ::heard)) { it.stop() } ?: return@launch
            discovery.start()
            this@Link.discovery = discovery
            updateNetwork()

            launch { registerSaved(client) }
            // A burst, as LocalSend does: the first may go out before the network is ready.
            launch { for (wait in listOf(100L, 400L, 1500L)) { delay(wait); discovery.announce() } }
            // Once per opening, whatever else turned up: some phones (an LG here) never
            // answer announcements, and the scan is the only way to see them.
            launch { delay(SCAN_AFTER_MS); scan(client) }
            while (isActive) {
                delay(ANNOUNCE_EVERY_MS)
                updateNetwork()
                discovery.announce()
                registerSaved(client)
            }
        }
    }

    fun pause() {
        session?.cancel()
        session = null
        sessionScope = null
        discovery = null
        // Stopped here, not left to the coroutine: a request in flight can hold
        // that up, and the port has to be free when the tool comes back.
        parts?.close()
        parts = null
        prompt?.complete(false)
        _nearby.value = emptyMap()
        _webShare.value = false
    }

    /** Opens or closes "Receive via link". Closed again whenever the app pauses. */
    fun setWebShare(on: Boolean) {
        _webShare.value = on
    }

    /** Look again: announce, re-register saved devices, and scan the subnet. */
    fun refresh() {
        val client = client ?: return
        sessionScope?.launch {
            discovery?.announce()
            registerSaved(client)
            scan(client)
        }
    }

    suspend fun save(device: Device) {
        _saved.value = store?.save(device) ?: return
    }

    suspend fun forget(fingerprint: String) {
        _saved.value = store?.forget(fingerprint) ?: return
    }

    suspend fun clearInbox() {
        store?.clearInbox()
        _inbox.value = emptyList()
    }

    /** The client, once the session has made it. For adding by address and sending. */
    suspend fun client(): LocalSendClient = client ?: store!!.client()

    /** The freshest address known for a device: where it was last seen nearby, else where it was saved. */
    fun current(device: Device): Device = _nearby.value[device.fingerprint.lowercase()] ?: device

    internal fun listings(saved: List<Device>, nearby: Map<String, Device>): List<Listing> {
        val savedKeys = saved.map { it.fingerprint.lowercase() }.toSet()
        val savedListings = saved.map { d ->
            val here = nearby[d.fingerprint.lowercase()]
            Listing(device = here ?: d, saved = true, nearby = here != null)
        }
        val others = nearby.filterKeys { it !in savedKeys }.values
            .sortedBy { it.alias.lowercase() }
            .map { Listing(device = it, saved = false, nearby = true) }
        // Nearby first; within that, saved before the rest.
        return savedListings.filter { it.nearby } + others + savedListings.filterNot { it.nearby }
    }

    private fun handler(self: DeviceInfo, uploadPage: String) = ReceiveHandler(
        self = { self },
        onRegister = { Log.d(TAG, "registered by ${it.alias} at ${it.host}"); found(it) },
        onOffer = ::incoming,
        webPage = { if (_webShare.value) uploadPage else null },
    )

    private fun updateNetwork() {
        val lan = Lan.address()
        _address.value = lan?.ip
        _status.value = if (lan == null) LinkStatus.NoWifi else LinkStatus.Ready
    }

    private fun found(device: Device) {
        val key = device.fingerprint.lowercase()
        _nearby.update { it + (key to device) }
        val saved = _saved.value.firstOrNull { it.fingerprint.lowercase() == key } ?: return
        if (saved.host != device.host || saved.port != device.port || saved.alias != device.alias) {
            // A saved device moved (new DHCP lease): the fingerprint says it is the same one.
            scope.launch { save(device) }
        }
    }

    private fun heard(ip: String, message: DeviceInfo) {
        if (message.protocol != "https") return
        val key = message.fingerprint.lowercase()
        // Announcements come in bursts of three; answer once.
        val now = System.currentTimeMillis()
        if ((answered[key] ?: 0L) > now - 5_000) return
        answered[key] = now
        val client = client ?: return
        sessionScope?.launch {
            client.register(ip, message.port, expected = message.fingerprint, quick = true).onSuccess(::found)
        }
    }

    private suspend fun registerSaved(client: LocalSendClient) = coroutineScope {
        for (device in _saved.value) {
            launch { client.register(device.host, device.port, expected = device.fingerprint, quick = true).onSuccess(::found) }
        }
    }

    private suspend fun scan(client: LocalSendClient) {
        if (!_scanning.compareAndSet(expect = false, update = true)) return
        try {
            val lan = Lan.address() ?: return
            val known = _nearby.value.values.map { it.host }.toSet()
            val gate = Semaphore(SCAN_CONCURRENCY)
            coroutineScope {
                for (ip in Lan.subnetHosts(lan.ip, lan.prefix) - known) {
                    launch {
                        gate.withPermit {
                            client.register(ip, LocalSend.DEFAULT_PORT, quick = true)
                                .onSuccess { Log.d(TAG, "scan found ${it.alias} at $ip"); found(it) }
                        }
                    }
                }
            }
        } finally {
            _scanning.value = false
        }
    }

    /**
     * Something was offered (on a server thread; blocks until decided). Text
     * from a saved device is let straight in; from anyone else it waits for
     * Accept. Files cannot be received yet: the phone says what was offered
     * and declines.
     */
    private fun incoming(from: Device, offer: Offer): Decision {
        val links = store ?: return Decision.Declined
        val browser = from.fingerprint == ReceiveHandler.BROWSER
        if (!browser) found(from)

        val text = when (offer) {
            is Offer.Files -> {
                LightModalManager.show(FilesModal(from, offer.files), duration = 1.minutes)
                return Decision.Declined
            }
            is Offer.Text -> offer.text
        }

        val known = !browser && _saved.value.any { it.fingerprint.equals(from.fingerprint, ignoreCase = true) }
        if (!known) {
            val answer = CompletableFuture<Boolean>()
            synchronized(this) {
                if (prompt != null) return Decision.Busy
                prompt = answer
            }
            try {
                LightModalManager.show(PromptModal(from, answer), duration = 60.seconds)
                val accepted = runCatching { answer.get(60, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!accepted) return Decision.Declined
            } finally {
                synchronized(this) { prompt = null }
            }
        }
        val message = ReceivedMessage(from.alias, text, System.currentTimeMillis())
        _inbox.value = runBlocking { links.receive(message) }
        LightModalManager.show(MessageModal(message), duration = 10.minutes)
        return Decision.Accepted
    }

    /** The server and discovery of one session. Closing stops whatever has been made, and whatever is made after. */
    private class Parts {
        private var closed = false
        private val stops = mutableListOf<() -> Unit>()

        @Synchronized
        fun <T> own(part: T, stop: (T) -> Unit): T? {
            if (closed) {
                stop(part)
                return null
            }
            stops += { stop(part) }
            return part
        }

        @Synchronized
        fun close() {
            closed = true
            stops.forEach { it() }
            stops.clear()
        }
    }
}

/** Keeps [Link] running while any of the tool's screens is in front. */
abstract class LinkViewModel<T>(protected val store: LinkStore) : LightViewModel<T>() {
    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        super.onScreenShow(screen)
        Link.resume(store)
    }

    override fun onAppPause() {
        super.onAppPause()
        Link.pause()
    }
}
