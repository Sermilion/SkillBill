package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.menuSpec

private val POPOVER_MIN_WIDTH = SkillBillSize.popoverMin
private val POPOVER_MAX_WIDTH = SkillBillSize.popoverMax
// max-width: min(340px, 100vw - 24px)
private val POPOVER_WIDTH_MARGIN = 12.dp
// placePopover keeps the panel 8px inside the window.
private val POPOVER_WINDOW_MARGIN = 8.dp

@Composable
fun SbPopover(expanded: Boolean, onDismissRequest: () -> Unit, offset: DpOffset = DpOffset.Zero, width: Dp? = null, returnFocusTo: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
  val density = LocalDensity.current
  val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
  val maxWidth = (windowWidth - POPOVER_WIDTH_MARGIN * 2).coerceAtLeast(0.dp)
  val maxHeight = (windowHeight - POPOVER_WINDOW_MARGIN * 2).coerceAtLeast(0.dp)
  val panelMaxWidth = (width ?: POPOVER_MAX_WIDTH).coerceAtLeast(0.dp).coerceAtMost(maxWidth)
  val panelMinWidth = if (width != null) panelMaxWidth else POPOVER_MIN_WIDTH.coerceAtMost(panelMaxWidth)
  val offsetPx = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
  val gapPx = with(density) { 4.dp.roundToPx() }
  val marginPx = with(density) { POPOVER_WINDOW_MARGIN.roundToPx() }
  var wasExpanded by remember { mutableStateOf(false) }
  val shouldReturnFocus = !expanded && wasExpanded
  SideEffect { wasExpanded = expanded }
  LaunchedEffect(expanded, shouldReturnFocus, returnFocusTo) {
    if (shouldReturnFocus) returnFocusTo?.requestFocus()
  }
  if (expanded) {
    val shape = RoundedCornerShape(SkillBillRadius.md)
    Popup(
      popupPositionProvider = PopoverPositionProvider(offsetPx, gapPx, marginPx),
      onDismissRequest = onDismissRequest,
      properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
    ) {
      val focusManager = LocalFocusManager.current
      LaunchedEffect(focusManager) { focusManager.moveFocus(FocusDirection.Next) }
      // A visible = true AnimatedVisibility skips its enter transition, so start from a hidden state.
      val appear = remember { MutableTransitionState(false).apply { targetState = true } }
      val liftPx = with(density) { 2.dp.roundToPx() }
      AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(menuSpec()) +
          slideInVertically(menuSpec()) { -liftPx } +
          scaleIn(menuSpec(), initialScale = .98f),
      ) {
        Column(
          Modifier.widthIn(min = panelMinWidth, max = panelMaxWidth).heightIn(max = maxHeight)
            .layeredShadow(SkillBillTheme.shadows.popover, shape)
            .background(SkillBillTheme.colors.bgOverlay, shape)
            .border(1.dp, SkillBillTheme.colors.borderStrong, shape)
            // The menu's 4px padding sits inside this 1px border.
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .semantics { paneTitle = "Menu" }
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
            .onPreviewKeyEvent {
              if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              when (it.key) {
                Key.Escape -> { onDismissRequest(); true }
                Key.DirectionDown -> {
                  if (!focusManager.moveFocus(FocusDirection.Next)) {
                    focusManager.clearFocus(force = true)
                    focusManager.moveFocus(FocusDirection.Next)
                  }
                  true
                }
                Key.DirectionUp -> {
                  if (!focusManager.moveFocus(FocusDirection.Previous)) {
                    focusManager.clearFocus(force = true)
                    focusManager.moveFocus(FocusDirection.Previous)
                  }
                  true
                }
                else -> false
              }
            },
        ) { content() }
      }
    }
  }
}

private class PopoverPositionProvider(private val offset: IntOffset, private val gap: Int, private val margin: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
    val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
    val below = anchorBounds.bottom + gap + offset.y
    val top = if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - popupContentSize.height - gap - offset.y
    return IntOffset((left + offset.x).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)), top.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
  }
}
