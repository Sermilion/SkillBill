package dev.skillbill.desktop.core.designsystem.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private object WindowOriginPosition : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@Composable
internal fun ModalLayer(onDismiss: () -> Unit, topFraction: Float, content: @Composable () -> Unit) {
  Popup(popupPositionProvider = WindowOriginPosition, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
    BoxWithConstraints(
      Modifier.fillMaxSize().background(SkillBillTheme.colors.scrim)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
      Box(
        Modifier.align(Alignment.TopCenter).padding(top = maxHeight * topFraction)
          .clickable(remember { MutableInteractionSource() }, indication = null) {},
      ) { content() }
    }
  }
}
