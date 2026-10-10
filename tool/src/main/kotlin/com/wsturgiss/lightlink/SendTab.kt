package com.wsturgiss.lightlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * LocalSend's Send tab: every device in reach, plus the saved ones (LocalSend's
 * favorites). Tap one to send to it; Add is LocalSend's "send to address".
 */
@Composable
fun ColumnScope.SendTab(onDevice: (Listing) -> Unit, onAdd: () -> Unit) {
    val devices by Link.devices.collectAsState()
    val status by Link.status.collectAsState()
    val scanning by Link.scanning.collectAsState()
    val self by Link.self.collectAsState()

    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = onAdd),
        center = LightTopBarCenter.Text("Send"),
        rightButton = LightBarButton.LightIcon(icon = LightIcons.REFRESH, onClick = Link::refresh),
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = when (val s = status) {
                LinkStatus.Starting -> "Starting…"
                LinkStatus.NoWifi -> "Not on Wi-Fi. LocalSend only works on the same network."
                is LinkStatus.Failed -> s.message
                LinkStatus.Ready -> when {
                    scanning -> "Looking…"
                    devices.none { it.nearby } -> "Nothing nearby yet. Open LocalSend on the other device, on the same Wi-Fi."
                    else -> "Visible as ${self?.alias.orEmpty()}"
                }
            },
            variant = LightTextVariant.Detail,
            lighten = status !is LinkStatus.Failed,
        )

        LightScrollView(modifier = Modifier.padding(top = 1f.gridUnitsAsDp())) {
            devices.forEach { listing -> DeviceRow(listing) { onDevice(listing) } }
        }
    }
}

@Composable
private fun DeviceRow(listing: Listing, onClick: () -> Unit) {
    val device = listing.device
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = device.alias, variant = LightTextVariant.Copy, lighten = !listing.nearby)
        LightText(
            text = listOfNotNull(
                device.deviceModel ?: device.deviceType,
                "saved".takeIf { listing.saved },
                "not nearby".takeIf { !listing.nearby },
            ).joinToString(" · "),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
