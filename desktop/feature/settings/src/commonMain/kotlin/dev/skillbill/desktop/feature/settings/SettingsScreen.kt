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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
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
  val selected = pages.pages.firstOrNull { it.contribution.id == route.page }
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  Row(Modifier.fillMaxSize().background(colors.bgFrame)) {
    Sidebar(
      top = {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
          SbButton("Back to app", shell::backToApp, Modifier.fillMaxWidth(), variant = SbButtonVariant.Ghost, icon = LucideIcons.arrowLeft, shortcut = "esc")
        }
      },
      footer = {},
      items = {
        groups.forEach { (group, groupPages) ->
          SidebarSection(group, expanded = true, onToggle = {}, collapsible = false) {
            groupPages.forEach { page ->
              SidebarItem(page.contribution.label, { onSelectPage(page.contribution.id) }, icon = settingsPageIcon(page.contribution.id), on = page.contribution.id == route.page)
            }
          }
        }
      },
    )
    Box(
      Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp, bottom = 8.dp).clip(shape).background(colors.bgPanel)
        // `.sb-main` content starts inside its 1px border; Compose's border does not inset content.
        .border(1.dp, colors.borderSubtle, shape).padding(1.dp),
    ) {
      Column(Modifier.fillMaxSize()) {
        ViewHeader(
          listOf(
            Crumb("Settings", onClick = { onSelectPage(SettingsPageId.App) }),
            Crumb(selected?.contribution?.label ?: "General"),
          ),
        )
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
          Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = SETTINGS_PAGE_MAX_WIDTH).fillMaxWidth()
              .padding(start = 40.dp, top = 40.dp, end = 40.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
          ) {
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
}

private val SETTINGS_PAGE_MAX_WIDTH = 720.dp

private fun settingsPageIcon(id: SettingsPageId): ImageVector? = when (id.id) {
  SettingsPageId.App.id -> LucideIcons.user
  SettingsPageId.Notifications.id -> LucideIcons.bell
  "models" -> LucideIcons.cpu
  "compaction" -> LucideIcons.gauge
  "project" -> LucideIcons.repo
  "telemetry" -> LucideIcons.shield
  else -> null
}
