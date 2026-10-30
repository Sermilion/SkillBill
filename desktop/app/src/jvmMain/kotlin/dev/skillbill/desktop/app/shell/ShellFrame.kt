package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow

private val PANEL_INSET = 8.dp

/**
 * The window frame: the sidebar on `bgFrame` and one content panel inset 8 dp with a 12 dp radius and a hairline border. In
 * [drawerMode] the panel fills the window with no inset, radius or border, and the sidebar slides over it with the modal shadow
 * while [drawerOpen]; a click outside it closes it. A null [sidebar] (Settings) gives [content] the whole window.
 */
@Composable
fun ShellFrame(
  sidebar: (@Composable () -> Unit)?,
  drawerMode: Boolean,
  drawerOpen: Boolean,
  onDismissDrawer: () -> Unit,
  content: @Composable () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  val inlineSidebar = sidebar != null && !drawerMode
  Box(Modifier.fillMaxSize().background(colors.bgFrame)) {
    if (sidebar == null) {
      content()
    } else {
      Row(Modifier.fillMaxSize()) {
        if (inlineSidebar) sidebar.invoke()
        val panel = if (drawerMode) Modifier.background(colors.bgPanel)
        else Modifier.padding(start = 0.dp, top = PANEL_INSET, end = PANEL_INSET, bottom = PANEL_INSET)
          .clip(shape).background(colors.bgPanel).border(1.dp, colors.borderSubtle, shape)
        Box(Modifier.weight(1f).fillMaxHeight().then(panel)) { content() }
      }
    }
    if (sidebar != null && drawerMode && drawerOpen) {
      // The prototype's drawer has no scrim, only the modal shadow; the transparent layer catches the outside click.
      Box(
        Modifier.fillMaxSize()
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissDrawer),
      )
      Box(Modifier.fillMaxHeight().layeredShadow(SkillBillTheme.shadows.modal, RectangleShape).background(colors.bgFrame)) { sidebar() }
    }
  }
}
