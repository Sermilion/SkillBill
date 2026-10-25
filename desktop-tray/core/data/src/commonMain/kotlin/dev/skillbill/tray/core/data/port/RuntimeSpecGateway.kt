package dev.skillbill.tray.core.data.port

import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot

/** What a goal purge reported: a refusal, or the run data it could not remove. */
data class SpecPurgeOutcome(val refusalReason: String?, val leftovers: List<String>)

/** The runtime's spec browser and the goal start and purge it backs. Every call blocks; the repository moves them to IO. */
interface RuntimeSpecGateway {
  /** The resolved store path, or null when the database selection is not a valid path. */
  fun databasePath(): String?

  fun projects(): List<SpecProject>

  fun projectFor(repositoryRoot: String): SpecProject

  fun snapshot(projectId: String, preferredRepoRoot: String?): SpecSnapshot

  fun control(projectId: String, bundlePath: String, preferredRepoRoot: String?): SpecBundleControl

  fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlResult

  fun purgeGoal(repoRoot: String, issueKey: String): SpecPurgeOutcome
}
