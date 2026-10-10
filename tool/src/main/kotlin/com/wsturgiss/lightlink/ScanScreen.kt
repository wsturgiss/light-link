package com.wsturgiss.lightlink

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/** The SDK's camera scanner; returns the first code it reads. */
class ScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {

    // No view model, so it keeps Link running itself (see LinkViewModel).
    override fun willShow() = Link.resume()

    override fun onAppPause() = Link.pause()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var scanned by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan a Receive link",
                onScanned = { scanned = it },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        // Out of the scanner's callback, as the SDK's demo does: it can fire more than once.
        LaunchedEffect(scanned) {
            val value = scanned ?: return@LaunchedEffect
            scanned = null
            goBack(value)
        }
    }
}
