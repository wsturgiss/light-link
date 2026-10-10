package com.wsturgiss.lightlink

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.wsturgiss.lightlink.localsend.Device
import com.wsturgiss.lightlink.localsend.LocalSend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AddState {
    data object Idle : AddState
    data object Looking : AddState
    /** Scanned an unencrypted LocalSend link: waiting for that device to go back to HTTPS. */
    data object WaitingForHttps : AddState
    /** Reached it; waiting for the user to say this is the right device. */
    data class Found(val device: Device) : AddState
    data class Failed(val message: String) : AddState
}

/**
 * `host` or `host:port`. Returns null for anything that is not plausibly an
 * address — the value ends up in a URL, so it is checked, not trusted.
 */
fun parseAddress(input: String): Pair<String, Int>? {
    val trimmed = input.trim()
    val host = trimmed.substringBefore(':')
    val port = if (':' in trimmed) trimmed.substringAfter(':').toIntOrNull() else LocalSend.DEFAULT_PORT
    if (port == null || port !in 1..65535) return null
    if (!HOST_RE.matches(host)) return null
    return host to port
}

private val HOST_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

/**
 * A device read off a QR code: where it is, and — if the code says — which
 * certificate to expect. [encrypted] is false for an `http://` link, which
 * LocalSend shows by default: its whole server is plain HTTP while that page is
 * open, and goes back to HTTPS when it closes.
 */
data class Scanned(val host: String, val port: Int, val fingerprint: String?, val encrypted: Boolean = true)

/**
 * The link on a "Receive via link" QR code. LocalSend's is `http://<ip>:<port>`
 * by default (`https://` with its encryption switch on, maybe `/?pin=…`). Ours is
 * plain HTTP too, for browsers, plus `fp=<fingerprint>`, which browsers and
 * LocalSend's page ignore but lets a scanning Link pin the device instead of
 * trusting whatever answers.
 */
fun deviceLink(ip: String, fingerprint: String?): String =
    "http://$ip:${LocalSend.DEFAULT_PORT}" + (fingerprint?.let { "/?fp=$it" } ?: "")

/**
 * Reads a scanned code: such a link, or a bare `host[:port]`. The address still
 * goes through [parseAddress]; a fingerprint that is not SHA-256 hex is dropped.
 */
fun parseScan(text: String): Scanned? {
    val trimmed = text.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
    if (uri?.scheme == null) {
        return parseAddress(trimmed)?.let { (host, port) -> Scanned(host, port, null) }
    }
    if (uri.scheme !in setOf("https", "http")) return null
    val host = uri.host ?: return null
    val (checkedHost, port) = parseAddress(if (uri.port == -1) host else "$host:${uri.port}") ?: return null
    val fingerprint = uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.startsWith("fp=") }?.removePrefix("fp=")
        ?.takeIf { FINGERPRINT_RE.matches(it) }
        ?.lowercase()
    // A code with a fingerprint is Link's, and Link serves HTTPS beside its HTTP link.
    return Scanned(checkedHost, port, fingerprint, encrypted = uri.scheme == "https" || fingerprint != null)
}

private val FINGERPRINT_RE = Regex("^[0-9A-Fa-f]{64}$")

/**
 * Adding by address, for when discovery cannot see a device (another subnet,
 * multicast blocked): typed, or scanned off its "Receive via link" QR code. The
 * device's certificate is captured here — or checked, if the code carried its
 * fingerprint — and pinned from then on, as with a discovered one.
 */
class AddDeviceViewModel(store: LinkStore) : LinkViewModel<Unit>(store) {

    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address.asStateFlow()

    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state: StateFlow<AddState> = _state.asStateFlow()

    fun setAddress(value: String) {
        finding?.cancel()
        _address.value = value
        _state.value = AddState.Idle
    }

    fun look() {
        val (host, port) = parseAddress(_address.value) ?: run {
            _state.value = AddState.Failed("That doesn't look like an IP address.")
            return
        }
        find(host, port, expected = null)
    }

    /** A QR code was scanned: fill in its address and go straight to finding it. */
    fun scanned(text: String) {
        val scan = parseScan(text) ?: run {
            _state.value = AddState.Failed("That QR code isn't a LocalSend link.")
            return
        }
        _address.value = if (scan.port == LocalSend.DEFAULT_PORT) scan.host else "${scan.host}:${scan.port}"
        // Android keeps apps off plain HTTP, and Link only trusts what TLS shows it
        // anyway: wait for the device to leave its link page and serve HTTPS again.
        find(scan.host, scan.port, expected = scan.fingerprint, waitForHttps = !scan.encrypted)
    }

    private var finding: Job? = null

    private fun find(host: String, port: Int, expected: String?, waitForHttps: Boolean = false) {
        finding?.cancel()
        finding = viewModelScope.launch {
            _state.value = if (waitForHttps) AddState.WaitingForHttps else AddState.Looking
            val deadline = System.currentTimeMillis() + if (waitForHttps) WAIT_FOR_HTTPS_MS else 0
            var result: Result<Device>
            while (true) {
                result = withContext(Dispatchers.IO) {
                    runCatching { Link.client() }.mapCatching { it.register(host, port, expected, quick = waitForHttps).getOrThrow() }
                }
                if (result.isSuccess || System.currentTimeMillis() >= deadline) break
                delay(RETRY_MS)
            }
            _state.value = result.fold(
                onSuccess = { AddState.Found(it) },
                onFailure = {
                    Log.w("AddDeviceScreen", "register $host:$port failed", it)
                    AddState.Failed(
                        if (expected != null && it is javax.net.ssl.SSLHandshakeException) {
                            "Something else answered at $host:$port, not the device on that code."
                        } else {
                            "No LocalSend at $host:$port. Is it open, and on the same Wi-Fi?"
                        },
                    )
                },
            )
        }
    }

    private companion object {
        const val WAIT_FOR_HTTPS_MS = 60_000L
        const val RETRY_MS = 2_000L
    }

    fun save(onDone: () -> Unit) {
        val found = _state.value as? AddState.Found ?: return
        viewModelScope.launch {
            Link.save(found.device)
            onDone()
        }
    }
}

class AddDeviceScreen(
    sealedActivity: SealedLightActivity,
    private val store: LinkStore,
) : LightScreen<Unit, AddDeviceViewModel>(sealedActivity) {

    private fun openScanner() {
        navigateTo(screenFactory = { ScanScreen(it) }, resultCallback = { viewModel.scanned(it) })
    }

    override val viewModelClass: Class<AddDeviceViewModel>
        get() = AddDeviceViewModel::class.java

    override fun createViewModel(): AddDeviceViewModel = AddDeviceViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val address by viewModel.address.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text("Add device"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "For a device that doesn't show up on its own. Scan the QR code on its \"Receive via link\", or type its IP.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightTextField(
                        label = "IP address",
                        value = address,
                        placeholder = "192.168.1.20",
                        onClick = {
                            navigateTo(
                                screenFactory = {
                                    TextEditorScreen(it, EditorRequest(title = "IP address", initialValue = address))
                                },
                                resultCallback = { result -> viewModel.setAddress(result) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val status = when (val s = state) {
                        AddState.Idle -> null
                        AddState.Looking -> "Looking…"
                        AddState.WaitingForHttps -> "That link isn't encrypted, so Link can't use it. Close \"Receive via link\" on that device, and Link will find it."
                        is AddState.Found -> "Found ${s.device.alias}. Save it?"
                        is AddState.Failed -> s.message
                    }
                    if (status != null) {
                        LightText(
                            text = status,
                            variant = LightTextVariant.Copy,
                            lighten = state is AddState.Looking || state is AddState.WaitingForHttps,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                LightBottomBar(
                    items = if (state is AddState.Found) {
                        listOf(LightBarButton.Text(text = "Save", onClick = { viewModel.save { goBack(Unit) } }))
                    } else {
                        listOf(
                            LightBarButton.Text(text = "Scan", onClick = ::openScanner),
                            LightBarButton.Text(text = "Find", onClick = viewModel::look),
                        )
                    },
                )
            }
        }
    }
}
