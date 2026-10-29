package dev.skillbill.desktop.core.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** What the window width and the open panes leave room for. */
data class ShellLayout(val showDetailsPane: Boolean, val drawerMode: Boolean)

val LocalShellLayout = compositionLocalOf { ShellLayout(showDetailsPane = true, drawerMode = false) }

/** The detail view's details pane: a 272 dp column with a left hairline, shown only while the layout has room for it. */
@Composable
fun DetailsPaneSlot(content: @Composable ColumnScope.() -> Unit) {
  if (!LocalShellLayout.current.showDetailsPane) return
  Row(Modifier.width(SkillBillSize.detailsPane).fillMaxHeight()) {
    Box(Modifier.width(1.dp).fillMaxHeight().background(SkillBillTheme.colors.borderSubtle))
    Column(Modifier.weight(1f).fillMaxHeight(), content = content)
  }
}
