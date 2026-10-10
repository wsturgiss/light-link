package com.wsturgiss.lightlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.wsturgiss.lightlink.localsend.Device
import com.wsturgiss.lightlink.localsend.FileMeta
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CompletableFuture

/** Base for the tool's full-screen overlays, shown over whatever screen is in front. */
internal abstract class FullScreenModal : LightModal {
    private val done = CompletableDeferred<Unit>()

    override fun dismiss() {
        done.complete(Unit)
    }

    override suspend fun awaitDismiss() = done.await()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    // Swallow taps, so none reach the screen underneath.
                    .lightClickable(hapticsEnabled = false) {},
            ) { Body() }
        }
    }

    @Composable
    abstract fun ColumnScope.Body()
}

/** Someone who is not saved wants to send a message. */
internal class PromptModal(
    private val from: Device,
    private val answer: CompletableFuture<Boolean>,
) : FullScreenModal() {

    override val onExpired: () -> Unit = { answer.complete(false) }

    private fun decide(accept: Boolean) {
        answer.complete(accept)
        dismiss()
    }

    @Composable
    override fun ColumnScope.Body() {
        LightTopBar(center = LightTopBarCenter.Text("Incoming"), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = from.alias, variant = LightTextVariant.Heading)
            LightText(
                text = "wants to send you a message.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = listOfNotNull(from.deviceModel, from.host).joinToString(" · "),
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "Decline", onClick = { decide(false) }),
                LightBarButton.Text(text = "Accept", onClick = { decide(true) }),
            ),
        )
    }
}

/** A message that just arrived. It is in the inbox too, so closing loses nothing. */
internal class MessageModal(private val message: ReceivedMessage) : FullScreenModal() {

    override val onExpired: () -> Unit = {}

    @Composable
    override fun ColumnScope.Body() {
        LightTopBar(center = LightTopBarCenter.Text(message.from), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = message.text, variant = LightTextVariant.Copy)
        }
        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = ::dismiss)))
    }
}

/**
 * Placeholder until files can be received: says what was offered, and that it
 * was declined. The sender sees LocalSend's "rejected".
 */
internal class FilesModal(private val from: Device, private val files: List<FileMeta>) : FullScreenModal() {

    override val onExpired: () -> Unit = {}

    @Composable
    override fun ColumnScope.Body() {
        LightTopBar(center = LightTopBarCenter.Text("Incoming"), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = from.alias, variant = LightTextVariant.Heading)
            LightText(
                text = if (files.size == 1) "tried to send a file." else "tried to send ${files.size} files.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            files.take(MAX_LISTED).forEach {
                LightText(text = it.fileName, variant = LightTextVariant.Detail, lighten = true)
            }
            if (files.size > MAX_LISTED) {
                LightText(text = "and ${files.size - MAX_LISTED} more", variant = LightTextVariant.Detail, lighten = true)
            }
            LightText(
                text = "Receiving files isn't supported yet, so it was declined.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = ::dismiss)))
    }

    private companion object {
        const val MAX_LISTED = 5
    }
}
