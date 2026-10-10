package com.wsturgiss.lightlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.wsturgiss.lightlink.localsend.LocalSend

/**
 * LocalSend's Settings tab, cut to what applies here. For now it shows how the
 * tool behaves; changing it comes later (the device name is first).
 */
@Composable
fun ColumnScope.SettingsTab() {
    val self by Link.self.collectAsState()
    val address by Link.address.collectAsState()

    LightTopBar(
        center = LightTopBarCenter.Text("Settings"),
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )

    LightScrollView(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        Section("Receive")
        Setting("Quick Save", "Saved devices")
        Setting("Require PIN", "Off")
        Setting("Destination", "Not yet: files can't be received")

        Section("Network")
        Setting("Device name", self?.alias.orEmpty())
        Setting("IP address", address ?: "Offline")
        Setting("Port", LocalSend.DEFAULT_PORT.toString())
        Setting("Encryption", "On")

        LightText(
            text = "These can't be changed yet.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun Section(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun Setting(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp())) {
        LightText(text = label, variant = LightTextVariant.Copy)
        LightText(text = value, variant = LightTextVariant.Detail, lighten = true)
    }
}
