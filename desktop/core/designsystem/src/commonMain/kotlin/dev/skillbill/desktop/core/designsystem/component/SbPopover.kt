package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.composite.runAfterCurrentEvent
import dev.skillbill.desktop.core.designsystem.menuSpec
import kotlinx.coroutines.isActive

private val POPOVER_MIN_WIDTH = SkillBillSize.popoverMin
private val POPOVER_MAX_WIDTH = SkillBillSize.popoverMax
// max-width: min(340px, 100vw - 24px)
private val POPOVER_WIDTH_MARGIN = 12.dp
// placePopover keeps the panel 8px inside the window.
private val POPOVER_WINDOW_MARGIN = 8.dp

enum class SbPopoverPlacement { Auto, Above }

@Composable
fun SbPopover(expanded: Boolean, onDismissRequest: () -> Unit, offset: DpOffset = DpOffset.Zero, width: Dp? = null, returnFocusTo: FocusRequester? = null, maxWidth: Dp = POPOVER_MAX_WIDTH, placement: SbPopoverPlacement = SbPopoverPlacement.Auto, arrowKeysMoveFocus: Boolean = true, specMenuKeyboard: Boolean = false, keyboardShortcuts: Map<Key, () -> Unit> = emptyMap(), title: String = "Menu", content: @Composable ColumnScope.() -> Unit) {
  val density = LocalDensity.current
  val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
  val windowMaxWidth = (windowWidth - POPOVER_WIDTH_MARGIN * 2).coerceAtLeast(0.dp)
  val maxHeight = (windowHeight - POPOVER_WINDOW_MARGIN * 2).coerceAtLeast(0.dp)
  // An inline width still answers to min-width: 220px and max-width: min(maxWidth, 100vw - 24px).
  val capWidth = maxWidth.coerceAtMost(windowMaxWidth)
  val floorWidth = POPOVER_MIN_WIDTH.coerceAtMost(capWidth)
  val panelMaxWidth = width?.coerceIn(floorWidth, capWidth) ?: capWidth
  val panelMinWidth = if (width != null) panelMaxWidth else floorWidth
  val offsetPx = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
  val gapPx = with(density) { 4.dp.roundToPx() }
  val marginPx = with(density) { POPOVER_WINDOW_MARGIN.roundToPx() }
  var wasExpanded by remember { mutableStateOf(false) }
  val focusReturnScope = rememberCoroutineScope()
  val currentReturnFocusTo by rememberUpdatedState(returnFocusTo)
  val currentExpanded by rememberUpdatedState(expanded)
  val shouldReturnFocus = !expanded && wasExpanded
  SideEffect { wasExpanded = expanded }
  LaunchedEffect(expanded, shouldReturnFocus, returnFocusTo) {
    if (shouldReturnFocus) returnFocusTo?.let { requester ->
      runAfterCurrentEvent {
        if (focusReturnScope.isActive && !currentExpanded && currentReturnFocusTo === requester) {
          runCatching { requester.requestFocus() }
        }
      }
    }
  }
  if (expanded) {
    Popup(
      popupPositionProvider = PopoverPositionProvider(offsetPx, gapPx, marginPx, placement),
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
            .sbOverlaySurface()
            // The menu's 4px padding sits inside this 1px border.
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .semantics { paneTitle = title }
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
            .onPreviewKeyEvent {
              if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              if (specMenuKeyboard && (it.isCtrlPressed || it.isMetaPressed || it.isAltPressed)) return@onPreviewKeyEvent false
              if (it.key == Key.Escape) { onDismissRequest(); return@onPreviewKeyEvent true }
              if (specMenuKeyboard && it.key == Key.Tab) { onDismissRequest(); return@onPreviewKeyEvent true }
              if (specMenuKeyboard && it.isShiftPressed) return@onPreviewKeyEvent false
              keyboardShortcuts[it.key]?.let { action -> action(); return@onPreviewKeyEvent true }
              when (it.key) {
                Key.Escape -> true
                Key.Tab -> false
                Key.MoveHome -> if (specMenuKeyboard) { focusManager.clearFocus(force = true); focusManager.moveFocus(FocusDirection.Next); true } else false
                Key.MoveEnd -> if (specMenuKeyboard) { focusManager.clearFocus(force = true); focusManager.moveFocus(FocusDirection.Previous); true } else false
                Key.DirectionDown -> {
                  if (!arrowKeysMoveFocus) return@onPreviewKeyEvent false
                  if (!focusManager.moveFocus(FocusDirection.Next)) {
                    focusManager.clearFocus(force = true)
                    focusManager.moveFocus(FocusDirection.Next)
                  }
                  true
                }
                Key.DirectionUp -> {
                  if (!arrowKeysMoveFocus) return@onPreviewKeyEvent false
                  if (!focusManager.moveFocus(FocusDirection.Previous)) {
                    focusManager.clearFocus(force = true)
                    focusManager.moveFocus(FocusDirection.Previous)
                  }
                  true
                }
                Key.J -> if (specMenuKeyboard) { focusManager.moveFocus(FocusDirection.Next); true } else false
                Key.K -> if (specMenuKeyboard) { focusManager.moveFocus(FocusDirection.Previous); true } else false
                else -> false
              }
            },
        ) { content() }
      }
    }
  }
}

internal class PopoverPositionProvider(private val offset: IntOffset, private val gap: Int, private val margin: Int, private val placement: SbPopoverPlacement) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
    val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
    val below = anchorBounds.bottom + gap + offset.y
    val top = when (placement) {
      // placePopover flips to `top - height - 8`: the anchor's top edge, 8px clear, without the downward gap or offset.
      SbPopoverPlacement.Auto -> if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - popupContentSize.height - margin
      SbPopoverPlacement.Above -> (anchorBounds.top - popupContentSize.height - gap + offset.y).takeIf { it >= margin } ?: below
    }
    return IntOffset((left + offset.x).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)), top.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
  }
}
