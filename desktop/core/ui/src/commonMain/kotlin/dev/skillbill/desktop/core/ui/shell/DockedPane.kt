package dev.skillbill.desktop.core.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.generated.resources.Res
import dev.skillbill.desktop.core.ui.generated.resources.pane_reset_width
import dev.skillbill.desktop.core.ui.generated.resources.pane_resize_separator
import dev.skillbill.desktop.core.ui.generated.resources.pane_set_width
import dev.skillbill.desktop.core.ui.input.ShellLayer
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val MainColumnFloor = 300.dp
private val MinimumWidth = 300.dp

/** A work area narrower than this cannot hold a docked pane and the main column side by side. */
val DOCKED_WORK_AREA_MIN_WIDTH: Dp = MinimumWidth + MainColumnFloor

/** What the work area tells the right pane: the [workAreaWidth] it lays the pane and the main column out in. */
data class RightPaneBounds(val workAreaWidth: Dp)

val LocalRightPaneBounds = compositionLocalOf<RightPaneBounds?> { null }

/** The rendered width of a docked pane: [requested] kept at least 300.dp and leaving the main column 300.dp where it can. */
fun dockedPaneWidth(requested: Dp, workAreaWidth: Dp): Dp =
  requested.coerceIn(MinimumWidth, maxOf(MinimumWidth, workAreaWidth - MainColumnFloor))

/**
 * A pane docked in the work area's right slot, beside the main column at every width. Clicks outside never dismiss it, and Esc
 * closes it ([onDismiss]) only while focus is inside the pane. [widthDp] is the requested width; the always enabled grip reports
 * the new width, clamped from the rendered one, to [onResize], and Left and Right step it by 24.dp. A double-click on the grip,
 * Home or the grip's reset accessibility action drops any drag width and calls [onReset]. While a drag is in progress the pane
 * renders the width it last reported, so it follows the pointer before [widthDp] catches up.
 */
@Composable
fun DockedPane(
  title: String,
  widthDp: Int,
  onResize: (Int) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = SkillBillTheme.colors
  var focusInside by remember { mutableStateOf(false) }
  var dragged by remember { mutableStateOf<Dp?>(null) }
  val resizeDescription = stringResource(Res.string.pane_resize_separator, title)
  val resizeAction = stringResource(Res.string.pane_set_width, title)
  val resetAction = stringResource(Res.string.pane_reset_width, title)
  BoxWithConstraints(modifier) {
    val bounds = LocalRightPaneBounds.current ?: RightPaneBounds(workAreaWidth = maxWidth)
    val width = dockedPaneWidth(dragged ?: widthDp.dp, bounds.workAreaWidth)
    val maximum = maxOf(MinimumWidth, bounds.workAreaWidth - MainColumnFloor)
    val reset = { dragged = null; onReset() }
    Row(
      Modifier.width(width).fillMaxHeight().background(colors.bgPanel)
        .semantics { paneTitle = title }
        .onFocusChanged { focusInside = it.hasFocus },
    ) {
      PaneResizeGrip(
        title = title,
        enabled = true,
        keyStep = 24.dp,
        onDoubleClick = reset,
        additionalSemantics = {
          contentDescription = resizeDescription
          progressBarRangeInfo = ProgressBarRangeInfo(width.value, MinimumWidth.value..maximum.value)
          setProgress(label = resizeAction) { requested ->
            if (!requested.isFinite()) false else {
              onResize(dockedPaneWidth(requested.dp, bounds.workAreaWidth).value.roundToInt())
              true
            }
          }
          customActions = listOf(CustomAccessibilityAction(resetAction) { reset(); true })
        },
        onDrag = { delta ->
          // Read the live drag width, not [width]: several deltas can land before the pane recomposes, and a stale base would
          // drop all but the last of them. It keeps fractions, so slow sub-dp movement still adds up.
          val next = dockedPaneWidth(dockedPaneWidth(dragged ?: widthDp.dp, bounds.workAreaWidth) - delta, bounds.workAreaWidth)
          dragged = next
          onResize(next.value.roundToInt())
        },
        onDragStopped = { dragged = null },
        onStep = { step -> onResize(dockedPaneWidth(width + step, bounds.workAreaWidth).value.roundToInt()) },
      )
      Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
    if (focusInside) ShellLayer(onDismiss)
  }
}
