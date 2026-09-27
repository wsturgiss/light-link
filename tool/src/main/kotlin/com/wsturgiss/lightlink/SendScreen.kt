package com.wsturgiss.lightlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
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
import com.wsturgiss.lightlink.localsend.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data object Sent : SendState
    data class Failed(val message: String) : SendState
}

class SendViewModel(private val store: LinkStore) : LightViewModel<Unit>() {

    /** Null until loaded; [Paired.computer] null means nothing is paired yet. */
    private val _paired = MutableStateFlow<Paired?>(null)
    val paired: StateFlow<Paired?> = _paired.asStateFlow()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    /** Re-read the pairing, e.g. after coming back from [PairScreen]. */
    fun reload() {
        viewModelScope.launch { _paired.value = Paired(store.computer()) }
    }

    fun setText(value: String) {
        _text.value = value
        if (_sendState.value !is SendState.Sending) _sendState.value = SendState.Idle
    }

    fun send() {
        val computer = _paired.value?.computer ?: return
        val body = _text.value.trim()
        if (body.isEmpty() || _sendState.value is SendState.Sending) return

        viewModelScope.launch {
            _sendState.value = SendState.Sending
            val outcome = withContext(Dispatchers.IO) {
                store.client().sendText(computer, body)
            }
            _sendState.value = when (outcome) {
                SendOutcome.Delivered -> SendState.Sent.also { _text.value = "" }
                is SendOutcome.Failed -> SendState.Failed(outcome.reason.message)
            }
        }
    }

    data class Paired(val computer: Computer?)
}

@InitialScreen
class SendScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SendViewModel>(sealedActivity) {

    override val viewModelClass: Class<SendViewModel>
        get() = SendViewModel::class.java

    override fun createViewModel(): SendViewModel = SendViewModel(LinkStore(lightContext.dataStore))

    private fun openPairing() {
        navigateTo(screenFactory = { PairScreen(it, LinkStore(lightContext.dataStore)) }) {
            viewModel.reload()
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val paired by viewModel.paired.collectAsState()
        val text by viewModel.text.collectAsState()
        val sendState by viewModel.sendState.collectAsState()
        val computer = paired?.computer

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Link"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = ::openPairing,
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when {
                    paired == null -> Box(Modifier.weight(1f))
                    computer == null -> UnpairedBody(Modifier.weight(1f))
                    else -> Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = "To ${computer.alias}",
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
                            SendState.Sending -> "Sending… accept it on the computer."
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
                }

                LightBottomBar(
                    items = when {
                        paired == null -> emptyList()
                        computer == null -> listOf(LightBarButton.Text(text = "Pair", onClick = ::openPairing))
                        else -> listOf(LightBarButton.Text(text = "Send", onClick = viewModel::send))
                    },
                )
            }
        }
    }
}

@Composable
private fun UnpairedBody(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = "No computer yet.",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = "Open LocalSend on your computer, then pair with its IP address.",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}
