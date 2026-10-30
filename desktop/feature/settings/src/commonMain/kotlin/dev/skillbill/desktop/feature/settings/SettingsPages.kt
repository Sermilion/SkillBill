package dev.skillbill.desktop.feature.settings

import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.SettingsPageContribution
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

data class SettingsPage(val contribution: SettingsPageContribution, val owner: FeatureEntry)

@SingleIn(WindowScope::class)
class SettingsPages(entries: Lazy<Set<FeatureEntry>>) {
  val pages: List<SettingsPage> by lazy {
    val contributions = entries.value.flatMap { entry -> entry.settingsPages.map { SettingsPage(it, entry) } }
    contributions.filter { it.contribution.group == "App" } +
      contributions.filter { it.contribution.group == "Runtime config" } +
      contributions.filter { it.contribution.group != "App" && it.contribution.group != "Runtime config" }
  }
}
