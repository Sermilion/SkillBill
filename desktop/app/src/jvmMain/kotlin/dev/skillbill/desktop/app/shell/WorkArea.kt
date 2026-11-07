package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.shell.LocalRightPaneBounds
import dev.skillbill.desktop.core.ui.shell.RightPaneBounds

/**
 * The panel's content: the main column with the [bottom] pane docked under the route, and the [right] pane beside it. The right
 * pane never changes what the main column shows; in [drawerMode] it covers the panel instead. A [rightWidth] of null leaves the
 * right pane to size itself, as the docked chat pane does; either way it reads its [RightPaneBounds] from [LocalRightPaneBounds].
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
    Row(Modifier.fillMaxSize()) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { main() }
        if (bottom != null) Box(Modifier.fillMaxWidth()) { bottom() }
      }
      // A fixed [rightWidth] (Specs' 400 dp) includes the pane's own start hairline (SidePane draws it), as `.sb-sidepane` is
      // border-box.
      if (right != null && !drawerMode) {
        Box((if (rightWidth != null) Modifier.width(rightWidth) else Modifier).fillMaxHeight()) {
          CompositionLocalProvider(LocalRightPaneBounds provides RightPaneBounds(cover = false, workAreaWidth = workAreaWidth)) { right() }
        }
      }
    }
    if (right != null && drawerMode) {
      Box(Modifier.fillMaxSize().background(SkillBillTheme.colors.bgPanel)) {
        CompositionLocalProvider(LocalRightPaneBounds provides RightPaneBounds(cover = true, workAreaWidth = workAreaWidth)) { right() }
      }
    }
  }
}
