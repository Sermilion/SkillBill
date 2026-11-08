package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.ui.input.ShellLayer

/** The `[Image #N]` label of the image at 1-based position [number] in its message. */
internal fun imageLabel(number: Int): String = "[Image #$number]"

/** A pending image's `[Image #N]` chip with a button that removes it. */
@Composable
internal fun ImageChip(number: Int, image: ChatImage, onRemove: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    ImagePreviewChip(number, image)
    SbIconButton(LucideIcons.x, "Remove image $number", onRemove, size = SbControlSize.Small)
  }
}

/** A sent message's image chip; clicking it opens a thumbnail of [image]. */
@Composable
internal fun ImagePreviewChip(number: Int, image: ChatImage) {
  var open by remember { mutableStateOf(false) }
  val focus = remember { FocusRequester() }
  val close = { open = false }
  Box {
    SbLabel(
      imageLabel(number),
      modifier = Modifier.focusRequester(focus).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClickLabel = "Preview image $number") { open = true },
    )
    if (open) ShellLayer(close)
    SbPopover(expanded = open, onDismissRequest = close, returnFocusTo = focus) { ImagePreview(number, image) }
  }
}

@Composable
private fun ImagePreview(number: Int, image: ChatImage) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(focus) { focus.requestFocus() }
  val bitmap = remember(image) {
    try {
      org.jetbrains.skia.Image.makeFromEncoded(image.bytes).toComposeImageBitmap()
    } catch (_: IllegalArgumentException) {
      null
    }
  }
  val type = SkillBillTheme.type
  val colors = SkillBillTheme.colors
  Column(Modifier.padding(8.dp).focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (bitmap != null) {
      Image(bitmap, contentDescription = imageLabel(number), modifier = Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 240.dp), contentScale = ContentScale.Fit)
    } else {
      BasicText("Preview unavailable", style = type.tSm.copy(color = colors.fgMuted))
    }
    BasicText(imageLabel(number), style = type.tSm.copy(color = colors.fgMuted))
  }
}
