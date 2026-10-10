package com.wsturgiss.lightlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** LocalSend's three tabs, in the bottom bar's order. */
enum class Tab { Send, Receive, Settings }

class HomeViewModel(store: LinkStore) : LinkViewModel<Unit>(store) {
    // Receive first, as LocalSend opens.
    private val _tab = MutableStateFlow(Tab.Receive)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    fun select(tab: Tab) {
        _tab.value = tab
    }
}

/**
 * The tool's home: LocalSend's tab bar as the bottom bar — send, receive
 * (Wi-Fi), settings (gear) — with the tab's name at the top. Switching tabs
 * stays on this one screen, so Back leaves the tool rather than stepping
 * through tabs; everything a tab opens is pushed on top.
 */
@InitialScreen
class HomeScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val store by lazy { LinkStore(lightContext) }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val tab by viewModel.tab.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (tab) {
                    Tab.Send -> SendTab(
                        onDevice = { listing -> navigateTo(screenFactory = { ComposeScreen(it, store, listing.device) }) },
                        onAdd = { navigateTo(screenFactory = { AddDeviceScreen(it, store) }) },
                    )
                    Tab.Receive -> ReceiveTab(
                        onHistory = { navigateTo(screenFactory = { HistoryScreen(it, store) }) },
                        onWebShare = { navigateTo(screenFactory = { WebShareScreen(it, store) }) },
                    )
                    Tab.Settings -> SettingsTab()
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(icon = LightIcons.SEND, contentDescription = "Send", onClick = { viewModel.select(Tab.Send) }),
                        LightBarButton.LightIcon(icon = LightIcons.WIFI, contentDescription = "Receive", onClick = { viewModel.select(Tab.Receive) }),
                        LightBarButton.LightIcon(icon = LightIcons.SETTINGS, contentDescription = "Settings", onClick = { viewModel.select(Tab.Settings) }),
                    ),
                )
            }
        }
    }
}
