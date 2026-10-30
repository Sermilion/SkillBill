package dev.skillbill.desktop.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.SettingsPageContribution
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SettingsPagesTest {
  @Test
  fun registryKeepsEveryContributedPageWithItsRenderingOwner() {
    val configEntry = Entry(
      listOf(
        SettingsPageContribution(SettingsPageId("models"), "Models", "Runtime config"),
        SettingsPageContribution(SettingsPageId("compaction"), "Compaction", "Runtime config"),
        SettingsPageContribution(SettingsPageId("project"), "Project", "Runtime config"),
        SettingsPageContribution(SettingsPageId("telemetry"), "Telemetry and agents", "Runtime config"),
      ),
    )
    val settingsEntry = Entry(listOf(SettingsPageContribution(SettingsPageId.App, "General", "App")))

    val pages = SettingsPages(lazy { setOf(configEntry, settingsEntry) }).pages

    assertEquals(listOf("General", "Models", "Compaction", "Project", "Telemetry and agents"), pages.map { it.contribution.label })
    assertEquals(listOf(settingsEntry, configEntry, configEntry, configEntry, configEntry), pages.map { it.owner })
    assertSame(configEntry, pages.first { it.contribution.id == SettingsPageId("project") }.owner)
  }

  @Test
  @OptIn(ExperimentalTestApi::class)
  fun selectingEachContributedPageRendersItsOwnersBody() = runComposeUiTest {
    val configEntry = Entry(
      listOf(
        SettingsPageContribution(SettingsPageId("models"), "Models", "Runtime config"),
        SettingsPageContribution(SettingsPageId("compaction"), "Compaction", "Runtime config"),
        SettingsPageContribution(SettingsPageId("project"), "Project", "Runtime config"),
        SettingsPageContribution(SettingsPageId("telemetry"), "Telemetry and agents", "Runtime config"),
      ),
    )
    val pages = SettingsPages(lazy { setOf(configEntry) })
    val selectedPage = mutableStateOf(SettingsPageId("models"))
    setContent {
      SkillBillTheme(reducedMotion = false) {
        SettingsScreen(SettingsRoute(selectedPage.value), SettingsUiState(), DatabaseSessionState.Opening(null), pages, EmptyShellActions, {}, {})
      }
    }

    listOf("models", "compaction", "project", "telemetry").forEach { page ->
      runOnIdle { selectedPage.value = SettingsPageId(page) }
      onNodeWithText("Config body: $page").assertExists()
    }
  }

  private class Entry(override val settingsPages: List<SettingsPageContribution>) : FeatureEntry {
    override fun renders(route: AppRoute) = false

    @Composable
    override fun Content(route: AppRoute) {
      androidx.compose.foundation.text.BasicText("Config body: ${(route as SettingsRoute).page.id}")
    }
  }

  private object EmptyShellActions : SettingsShellActions {
    override fun backToApp() = Unit
  }
}
