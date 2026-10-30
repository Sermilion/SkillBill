package dev.skillbill.desktop.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
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
import dev.skillbill.desktop.feature.config.CommentWarningLedger
import dev.skillbill.desktop.feature.config.ConfigEditorSessions
import dev.skillbill.desktop.feature.config.ConfigFeatureEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class SettingsPagesTest {
  @Test
  fun realConfigEntryContributesAllItsPagesToTheRegistry() {
    val configEntry = configEntry()

    val pages = SettingsPages(lazy { setOf(configEntry) }).pages

    assertEquals(listOf("Models", "Compaction", "Project", "Telemetry and agents"), pages.map { it.contribution.label })
    assertTrue(pages.all { it.owner === configEntry })
  }

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

  private fun configEntry() = ConfigFeatureEntry(
    unusedRepository(RuntimeConfigRepository::class.java),
    unusedRepository(SpecRepository::class.java),
    SelectionHolder(),
    CommentWarningLedger(),
    ToastQueue(),
    ConfigEditorSessions(),
    WindowCoroutineScope(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
  )

  @Suppress("UNCHECKED_CAST")
  private fun <T> unusedRepository(type: Class<T>): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> error("Unexpected repository call: $method") } as T

  private object EmptyShellActions : SettingsShellActions {
    override fun backToApp() = Unit
  }
}
