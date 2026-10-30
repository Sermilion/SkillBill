package dev.skillbill.desktop.core.designsystem.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.menuSpec

private object WindowOriginPosition : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ModalLayer(onDismiss: () -> Unit, topFraction: Float, content: @Composable () -> Unit) {
  Popup(popupPositionProvider = WindowOriginPosition, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
    // `.sb-modal-layer` fades in (sb-fade) and its panel runs sb-pop-in; a visible = true
    // AnimatedVisibility skips its enter transition, so start from a hidden state.
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    val liftPx = with(LocalDensity.current) { 2.dp.roundToPx() }
    AnimatedVisibility(appear, enter = fadeIn(menuSpec())) {
      BoxWithConstraints(
        Modifier.fillMaxSize().background(SkillBillTheme.colors.scrim)
          .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
      ) {
        Box(
          Modifier.align(Alignment.TopCenter).padding(top = maxHeight * topFraction)
            .animateEnterExit(enter = slideInVertically(menuSpec()) { -liftPx } + scaleIn(menuSpec(), initialScale = .98f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
      }
    }
  }
}
