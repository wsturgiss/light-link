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
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class HistoryViewModel(store: LinkStore) : LinkViewModel<Unit>(store) {
    fun clear() {
        viewModelScope.launch { Link.clearInbox() }
    }
}

/** LocalSend's receive history: everything received, newest first. Messages arrive on any screen; this is where they stay. */
class HistoryScreen(
    sealedActivity: SealedLightActivity,
    private val store: LinkStore,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel(): HistoryViewModel = HistoryViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by Link.inbox.collectAsState()
        val self by Link.self.collectAsState()
        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text("History"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (messages.isEmpty()) {
                        LightText(
                            text = "Nothing yet. Send to ${self?.alias ?: "this phone"} from LocalSend while Link is open.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                    messages.forEach { message ->
                        LightText(text = message.text, variant = LightTextVariant.Copy)
                        LightText(
                            text = "${message.from} · ${timeFormat.format(Date(message.atMillis))}",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                LightBottomBar(
                    items = if (messages.isEmpty()) emptyList()
                    else listOf(LightBarButton.Text(text = "Clear", onClick = viewModel::clear)),
                )
            }
        }
    }
}
