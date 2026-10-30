package dev.skillbill.desktop.feature.config

import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.SettingsPageContribution
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
class ConfigFeatureEntry(
  private val repository: RuntimeConfigRepository,
  private val specs: SpecRepository,
  private val selection: SelectionHolder,
  private val ledger: CommentWarningLedger,
  private val toasts: ToastQueue,
  private val sessions: ConfigEditorSessions,
  private val windowScope: WindowCoroutineScope,
) : FeatureEntry {
  override fun renders(route: AppRoute) = false

  override val settingsPages = listOf(
    SettingsPageContribution(SettingsPageId.Models, "Models", "Runtime config"),
    SettingsPageContribution(SettingsPageId.Compaction, "Compaction", "Runtime config"),
    SettingsPageContribution(SettingsPageId("project"), "Project", "Runtime config"),
    SettingsPageContribution(SettingsPageId("telemetry"), "Telemetry and agents", "Runtime config"),
  )

  @Composable
  override fun Content(route: AppRoute) {
    val page = (route as SettingsRoute).page.id
    val preferredRepo = selection.item.value?.repositoryRoot
    val session = sessions.session(page, preferredRepo)
    ConfigPage(page, repository, specs, preferredRepo, ledger, toasts, session, sessions, windowScope.scope)
  }
}

@ContributesTo(WindowScope::class)
interface ConfigEntryBinding {
  @Provides
  @IntoSet
  fun configEntry(entry: ConfigFeatureEntry): FeatureEntry = entry
}
