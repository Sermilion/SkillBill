package dev.skillbill.desktop.app.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.menuSpec
import dev.skillbill.desktop.core.designsystem.navigation.ContentPanel

private val PANEL_INSET = 8.dp
private const val DRAWER_HIDDEN_SHIFT = 1.05f

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
  val inlineSidebar = sidebar != null && !drawerMode
  val panelContent = remember(content) { movableContentOf(content) }
  Box(Modifier.fillMaxSize().background(colors.bgFrame)) {
    if (sidebar == null) {
      content()
    } else {
      Row(Modifier.fillMaxSize()) {
        if (inlineSidebar) sidebar.invoke()
        if (drawerMode) {
          Box(Modifier.weight(1f).fillMaxHeight().background(colors.bgPanel)) { panelContent() }
        } else {
          ContentPanel(
            Modifier.weight(1f).fillMaxHeight().padding(top = PANEL_INSET, end = PANEL_INSET, bottom = PANEL_INSET),
          ) { panelContent() }
        }
      }
    }
    // `.sb-side` slides between translateX(-105%) and none over --dur, so it stays drawn while it slides out.
    val shown = animateFloatAsState(if (drawerMode && drawerOpen) 1f else 0f, menuSpec(), label = "drawer-slide").value
    if (sidebar != null && drawerMode && (drawerOpen || shown > 0f)) {
      // The prototype's drawer has no scrim, only the modal shadow; the transparent layer catches the outside click.
      if (drawerOpen) Box(
        Modifier.fillMaxSize()
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissDrawer),
      )
      Box(
        Modifier.fillMaxHeight().graphicsLayer { translationX = -(1f - shown) * size.width * DRAWER_HIDDEN_SHIFT }
          .layeredShadow(SkillBillTheme.shadows.modal, RectangleShape).background(colors.bgFrame),
      ) { sidebar() }
    }
  }
}
