package com.wsturgiss.lightlink

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
import com.wsturgiss.lightlink.localsend.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data object Sent : SendState
    data class Failed(val message: String) : SendState
}

class ComposeViewModel(store: LinkStore, private val device: Device) : LinkViewModel<Unit>(store) {

    private val key = device.fingerprint.lowercase()

    /** The device as the list currently has it: where it is, and whether it is saved. */
    val listing: StateFlow<Listing> = Link.devices
        .map { all -> all.firstOrNull { it.device.fingerprint.lowercase() == key } ?: Listing(device, saved = false, nearby = false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Listing(device, saved = false, nearby = true))

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    fun setText(value: String) {
        _text.value = value
        if (_sendState.value !is SendState.Sending) _sendState.value = SendState.Idle
    }

    fun send() {
        val body = _text.value.trim()
        if (body.isEmpty() || _sendState.value is SendState.Sending) return

        viewModelScope.launch {
            _sendState.value = SendState.Sending
            // Where it is now, in case its address changed since this screen opened.
            val target = Link.current(listing.value.device)
            val outcome = withContext(Dispatchers.IO) { Link.client().sendText(target, body) }
            _sendState.value = when (outcome) {
                SendOutcome.Delivered -> SendState.Sent.also { _text.value = "" }
                is SendOutcome.Failed -> SendState.Failed(outcome.reason.message)
            }
        }
    }

    fun toggleSaved() {
        val current = listing.value
        viewModelScope.launch {
            if (current.saved) Link.forget(current.device.fingerprint) else Link.save(current.device)
        }
    }
}

/** Send text to one device. */
class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val store: LinkStore,
    private val device: Device,
) : LightScreen<Unit, ComposeViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposeViewModel>
        get() = ComposeViewModel::class.java

    override fun createViewModel(): ComposeViewModel = ComposeViewModel(store, device)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val listing by viewModel.listing.collectAsState()
        val text by viewModel.text.collectAsState()
        val sendState by viewModel.sendState.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text(listing.device.alias),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = listOfNotNull(
                            listing.device.host,
                            "saved".takeIf { listing.saved },
                            "not nearby".takeIf { !listing.nearby },
                        ).joinToString(" · "),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightTextField(
                        label = "Text or link",
                        value = text,
                        placeholder = "…",
                        onClick = {
                            navigateTo(
                                screenFactory = {
                                    TextEditorScreen(
                                        it,
                                        EditorRequest(
                                            title = "Send",
                                            initialValue = text,
                                            initialCaps = text.isBlank(),
                                        ),
                                    )
                                },
                                resultCallback = { result -> viewModel.setText(result) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val status = when (val s = sendState) {
                        SendState.Idle -> null
                        SendState.Sending -> "Sending… accept it on ${listing.device.alias}."
                        SendState.Sent -> "Sent."
                        is SendState.Failed -> s.message
                    }
                    if (status != null) {
                        LightText(
                            text = status,
                            variant = LightTextVariant.Detail,
                            lighten = sendState !is SendState.Failed,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = if (listing.saved) "Forget" else "Save", onClick = viewModel::toggleSaved),
                        LightBarButton.Text(text = "Send", onClick = viewModel::send),
                    ),
                )
            }
        }
    }
}
