package dev.skillbill.desktop.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.Sidebar
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.navigation.SidebarSection
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute

@Composable
fun SettingsScreen(
  route: SettingsRoute,
  state: SettingsUiState,
  sessionState: DatabaseSessionState,
  pages: SettingsPages,
  shell: SettingsShellActions,
  onAction: (SettingsAction) -> Unit,
  onSelectPage: (SettingsPageId) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val groups = pages.pages.groupBy { it.contribution.group }
  val expanded = remember { mutableStateMapOf<String, Boolean>().apply { groups.keys.forEach { put(it, true) } } }
  val selected = pages.pages.firstOrNull { it.contribution.id == route.page }
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  Row(Modifier.fillMaxSize().background(colors.bgFrame)) {
    Sidebar(
      top = {
        SbButton("Back to app", shell::backToApp, variant = SbButtonVariant.Ghost, icon = dev.skillbill.desktop.core.designsystem.icon.LucideIcons.arrowLeft, shortcut = "esc")
      },
      footer = { SidebarItem("Keyboard shortcuts", shell::openKeyboardShortcuts, shortcut = "?") },
      items = {
        groups.forEach { (group, groupPages) ->
          SidebarSection(group, expanded[group] != false, { expanded[group] = !(expanded[group] ?: true) }) {
            groupPages.forEach { page ->
              SidebarItem(page.contribution.label, { onSelectPage(page.contribution.id) }, on = page.contribution.id == route.page)
            }
          }
        }
      },
    )
    Box(
      Modifier.weight(1f).fillMaxHeight().padding(8.dp).clip(shape).background(colors.bgPanel)
        .border(1.dp, colors.borderSubtle, shape),
    ) {
      Column(Modifier.fillMaxSize()) {
        ViewHeader(
          listOf(
            Crumb("Settings", onClick = { onSelectPage(SettingsPageId.App) }),
            Crumb(selected?.contribution?.label ?: "General"),
          ),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
          when (route.page) {
            SettingsPageId.App -> GeneralPage(state, sessionState, onAction)
            SettingsPageId.Notifications -> NotificationsPage(state, onAction)
            else -> selected?.owner?.Content(route)
          }
        }
      }
    }
  }
}
