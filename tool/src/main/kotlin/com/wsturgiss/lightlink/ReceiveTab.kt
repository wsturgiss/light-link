package com.wsturgiss.lightlink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * LocalSend's Receive tab: the name others see, the `#` id from the last part
 * of the IP (LocalSend's "visual id"), and "Receive via link". History sits
 * top-left, as LocalSend's corner button.
 */
@Composable
fun ColumnScope.ReceiveTab(onHistory: () -> Unit, onWebShare: () -> Unit) {
    val self by Link.self.collectAsState()
    val address by Link.address.collectAsState()
    val status by Link.status.collectAsState()

    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = onHistory),
        center = LightTopBarCenter.Text("Receive"),
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = self?.alias.orEmpty(),
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = when (val s = status) {
                    LinkStatus.Starting -> "…"
                    LinkStatus.Ready -> address?.let { "#${visualId(it)}" } ?: "Offline"
                    LinkStatus.NoWifi -> "Offline"
                    is LinkStatus.Failed -> s.message
                },
                variant = if (status is LinkStatus.Failed) LightTextVariant.Detail else LightTextVariant.Subtitle,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Receive via link",
                variant = LightTextVariant.Copy,
                underline = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 2f.gridUnitsAsDp())
                    .lightClickable(onClick = onWebShare),
            )
        }
    }
}

/** LocalSend's short id for an address: its last part, e.g. 214 for 192.168.200.214. */
fun visualId(ip: String): String = ip.substringAfterLast('.')
