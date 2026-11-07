package dev.skillbill.desktop.core.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.input.ShellLayer
import kotlin.math.roundToInt

private val MainColumnFloor = 420.dp
private val MinimumWidth = 360.dp

/** What the work area tells the right pane: its own [workAreaWidth], and whether the pane [cover]s the area in drawer mode. */
data class RightPaneBounds(val cover: Boolean, val workAreaWidth: Dp)

val LocalRightPaneBounds = compositionLocalOf<RightPaneBounds?> { null }

/** The rendered width of a docked pane: [requested] kept at least 360.dp and leaving the main column 420.dp where it can. */
fun dockedPaneWidth(requested: Dp, workAreaWidth: Dp): Dp =
  requested.coerceIn(MinimumWidth, maxOf(MinimumWidth, workAreaWidth - MainColumnFloor))

/**
 * A pane docked in the work area's right slot, beside the main column. Clicks outside never dismiss it, and Esc closes it
 * ([onDismiss]) only while focus is inside the pane. In drawer mode it covers the work area, with the grip disabled. [widthDp] is the
 * requested width; the grip reports the new width, clamped from the rendered one, to [onResize]. While a drag is in progress the
 * pane renders the width it last reported, so it follows the pointer before [widthDp] catches up.
 */
@Composable
fun DockedPane(
  title: String,
  widthDp: Int,
  onResize: (Int) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = SkillBillTheme.colors
  var focusInside by remember { mutableStateOf(false) }
  var dragged by remember { mutableStateOf<Dp?>(null) }
  BoxWithConstraints(modifier) {
    val bounds = LocalRightPaneBounds.current ?: RightPaneBounds(cover = false, workAreaWidth = maxWidth)
    val width = dockedPaneWidth(dragged ?: widthDp.dp, bounds.workAreaWidth)
    val sizing = if (bounds.cover) Modifier.fillMaxSize() else Modifier.width(width).fillMaxHeight()
    Row(
      sizing.background(colors.bgPanel)
        .semantics { paneTitle = title }
        .onFocusChanged { focusInside = it.hasFocus },
    ) {
      PaneResizeGrip(
        title = title,
        enabled = !bounds.cover,
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
