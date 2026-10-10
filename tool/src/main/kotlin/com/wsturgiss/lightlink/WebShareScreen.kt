package com.wsturgiss.lightlink

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
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
import com.wsturgiss.lightlink.localsend.LocalSend

/** The upload page is served only while this screen is in front. */
class WebShareViewModel(store: LinkStore) : LinkViewModel<Unit>(store) {
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        Link.setWebShare(true)
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        Link.setWebShare(false)
    }
}

/**
 * LocalSend's "Receive via link": a browser on the same Wi-Fi opens the URL
 * (or scans the QR) and gets LocalSend's own upload page, served by the
 * phone. Plain HTTP, as LocalSend's link is by default, so there is no
 * certificate warning; HTTPS for LocalSend devices carries on alongside.
 */
class WebShareScreen(
    sealedActivity: SealedLightActivity,
    private val store: LinkStore,
) : LightScreen<Unit, WebShareViewModel>(sealedActivity) {

    override val viewModelClass: Class<WebShareViewModel>
        get() = WebShareViewModel::class.java

    override fun createViewModel(): WebShareViewModel = WebShareViewModel(store)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val address by Link.address.collectAsState()
        val self by Link.self.collectAsState()
        val url = address?.let { "http://$it:${LocalSend.DEFAULT_PORT}" }
        // The code carries the fingerprint too, so a scanning Link can pin this phone.
        val link = address?.let { deviceLink(it, self?.fingerprint) }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text("Receive via link"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (url == null) {
                        LightText(text = "Offline. Connect to Wi-Fi first.", variant = LightTextVariant.Copy)
                        return@LightScrollView
                    }
                    LightText(
                        text = "Open this link in a browser on the same Wi-Fi:",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightText(
                        text = url,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                    val qr = remember(link) { qrCode(link!!) }
                    Image(
                        bitmap = qr,
                        contentDescription = url,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .aspectRatio(1f)
                            .align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

/**
 * Dark modules on white with a quiet zone, whatever the theme: scanners expect
 * that, and many fail on an inverted code.
 */
private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
