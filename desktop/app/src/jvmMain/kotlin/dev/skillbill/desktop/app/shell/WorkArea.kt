package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.shell.DOCKED_WORK_AREA_MIN_WIDTH
import dev.skillbill.desktop.core.ui.shell.LocalRightPaneBounds
import dev.skillbill.desktop.core.ui.shell.RightPaneBounds

/**
 * The panel's content: the main column with the [bottom] pane docked under the route, and the [right] pane beside it. The right
 * pane never changes what the main column shows. A [rightWidth] of null docks the right pane, as the chat pane is: it sizes itself
 * from the [RightPaneBounds] in [LocalRightPaneBounds] and sits beside the main column at every width, and while the panel is
 * narrower than [DOCKED_WORK_AREA_MIN_WIDTH] the content, bottom pane included, is that wide and scrolls horizontally. A fixed
 * [rightWidth] sits beside the main column, and in [drawerMode] covers the panel instead.
 */
@Composable
fun WorkArea(
  drawerMode: Boolean,
  right: (@Composable () -> Unit)?,
  bottom: (@Composable () -> Unit)?,
  rightWidth: Dp? = SkillBillSize.specsPane,
  main: @Composable () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val workAreaWidth = maxWidth
    val docked = right != null && rightWidth == null
    val contentWidth = if (docked) maxOf(workAreaWidth, DOCKED_WORK_AREA_MIN_WIDTH) else workAreaWidth
    val scroll = rememberScrollState()
    Row(
      Modifier.fillMaxHeight()
        .then(if (contentWidth > workAreaWidth) Modifier.horizontalScroll(scroll) else Modifier)
        .width(contentWidth),
    ) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { main() }
        if (bottom != null) Box(Modifier.fillMaxWidth()) { bottom() }
      }
      // A fixed [rightWidth] (Specs' 400 dp) includes the pane's own start hairline (SidePane draws it), as `.sb-sidepane` is
      // border-box.
      if (right != null && (docked || !drawerMode)) {
        Box((if (rightWidth != null) Modifier.width(rightWidth) else Modifier).fillMaxHeight()) {
          CompositionLocalProvider(LocalRightPaneBounds provides RightPaneBounds(workAreaWidth = contentWidth)) { right() }
        }
      }
    }
    if (right != null && !docked && drawerMode) {
      Box(Modifier.fillMaxSize().background(SkillBillTheme.colors.bgPanel)) { right() }
    }
  }
}
