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
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import com.wsturgiss.lightlink.localsend.Computer
import com.wsturgiss.lightlink.localsend.LocalSend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairState {
    data object Idle : PairState
    data object Looking : PairState
    /** Reached it; waiting for the user to say this is the right computer. */
    data class Found(val computer: Computer) : PairState
    data class Failed(val message: String) : PairState
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

class PairViewModel(private val store: LinkStore) : LightViewModel<Boolean>() {

    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address.asStateFlow()

    private val _state = MutableStateFlow<PairState>(PairState.Idle)
    val state: StateFlow<PairState> = _state.asStateFlow()

    private val _current = MutableStateFlow<Computer?>(null)
    val current: StateFlow<Computer?> = _current.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            val paired = store.computer()
            _current.value = paired
            if (_address.value.isEmpty() && paired != null) {
                _address.value = if (paired.port == LocalSend.DEFAULT_PORT) paired.host else "${paired.host}:${paired.port}"
            }
        }
    }

    fun setAddress(value: String) {
        _address.value = value
        _state.value = PairState.Idle
    }

    fun look() {
        val (host, port) = parseAddress(_address.value) ?: run {
            _state.value = PairState.Failed("That doesn't look like an IP address.")
            return
        }
        viewModelScope.launch {
            _state.value = PairState.Looking
            val result = withContext(Dispatchers.IO) { runCatching { store.client() }.mapCatching { it.probe(host, port).getOrThrow() } }
            _state.value = result.fold(
                onSuccess = { PairState.Found(it) },
                onFailure = {
                    Log.w("PairScreen", "probe $host:$port failed", it)
                    PairState.Failed("No LocalSend at $host:$port. Is it open, and are you on the same Wi-Fi?")
                },
            )
        }
    }

    fun confirm(onDone: () -> Unit) {
        val found = _state.value as? PairState.Found ?: return
        viewModelScope.launch {
            store.pair(found.computer)
            onDone()
        }
    }

    fun forget(onDone: () -> Unit) {
        viewModelScope.launch {
            store.forget()
            onDone()
        }
    }
}

class PairScreen(
    sealedActivity: SealedLightActivity,
    private val store: LinkStore,
) : LightScreen<Boolean, PairViewModel>(sealedActivity) {

    override val viewModelClass: Class<PairViewModel>
        get() = PairViewModel::class.java

    override fun createViewModel(): PairViewModel = PairViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val address by viewModel.address.collectAsState()
        val state by viewModel.state.collectAsState()
        val current by viewModel.current.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(false) }),
                    center = LightTopBarCenter.Text("Computer"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    current?.let {
                        LightText(
                            text = "Paired with ${it.alias}",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                    LightTextField(
                        label = "Computer IP",
                        value = address,
                        placeholder = "192.168.1.20",
                        onClick = {
                            navigateTo(
                                screenFactory = {
                                    TextEditorScreen(it, EditorRequest(title = "Computer IP", initialValue = address))
                                },
                                resultCallback = { result -> viewModel.setAddress(result) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val status = when (val s = state) {
                        PairState.Idle -> null
                        PairState.Looking -> "Looking…"
                        is PairState.Found -> "Found ${s.computer.alias}. Pair with it?"
                        is PairState.Failed -> s.message
                    }
                    if (status != null) {
                        LightText(
                            text = status,
                            variant = LightTextVariant.Copy,
                            lighten = state is PairState.Looking,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                LightBottomBar(
                    items = buildList {
                        if (current != null) add(LightBarButton.Text(text = "Forget", onClick = { viewModel.forget { goBack(true) } }))
                        add(
                            if (state is PairState.Found) LightBarButton.Text(text = "Pair", onClick = { viewModel.confirm { goBack(true) } })
                            else LightBarButton.Text(text = "Find", onClick = viewModel::look),
                        )
                    },
                )
            }
        }
    }
}
