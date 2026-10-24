package dev.skillbill.tray.core.domain.repository

import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import kotlinx.coroutines.flow.Flow

/** The runtime's spec browser: projects, their spec trees, per-bundle run verdicts, and goal start and purge. */
interface SpecBrowserRepository {
  suspend fun projects(): Outcome<List<SpecProject>>

  suspend fun projectFor(repositoryRoot: String): Outcome<SpecProject>

  /** Emits a fresh snapshot first, then again whenever the spec directory or the runtime store changes. */
  fun observeSnapshot(projectId: String, preferredRepoRoot: String?): Flow<Outcome<SpecSnapshot>>

  suspend fun control(projectId: String, bundlePath: String, preferredRepoRoot: String?): Outcome<SpecBundleControl>

  suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): Outcome<Unit>

  suspend fun purgeGoal(repoRoot: String, issueKey: String): Outcome<Unit>
}
