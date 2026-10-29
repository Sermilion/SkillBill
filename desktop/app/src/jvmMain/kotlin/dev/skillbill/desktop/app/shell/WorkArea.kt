package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/**
 * The panel's content: the main column with the [bottom] pane docked under the route, and the [right] pane beside it. The right
 * pane never changes what the main column shows; in [drawerMode] it covers the panel instead.
 */
@Composable
fun WorkArea(
  drawerMode: Boolean,
  right: (@Composable () -> Unit)?,
  bottom: (@Composable () -> Unit)?,
  main: @Composable () -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { main() }
        if (bottom != null) Box(Modifier.fillMaxWidth()) { bottom() }
      }
      if (right != null && !drawerMode) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(SkillBillTheme.colors.borderSubtle))
        Box(Modifier.width(SkillBillSize.specsPane).fillMaxHeight()) { right() }
      }
    }
    if (right != null && drawerMode) Box(Modifier.fillMaxSize().background(SkillBillTheme.colors.bgPanel)) { right() }
  }
}
