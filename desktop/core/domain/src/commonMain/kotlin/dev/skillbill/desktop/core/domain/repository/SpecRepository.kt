package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import kotlinx.coroutines.flow.Flow

/** Spec folders as the runtime reads them: one tree read per project, refreshed when the folder or the `specs` topic changes. */
interface SpecRepository {
  suspend fun projects(): Outcome<List<SpecProject>, ReadProblem>

  suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem>

  fun observeTree(projectId: String): Flow<SpecTreeState>

  /** Re-reads the project's spec snapshot, also after a failed read. */
  fun refreshTree(projectId: String)

  /** The bundle's run control, read in the background so selecting the bundle never waits on it. */
  fun control(bundle: SpecBundleRef): Flow<SpecControlState>

  suspend fun openFile(path: String): OpenOutcome

  /** Opens a project phase instruction with the operating system's default application. */
  suspend fun openFileInDefaultApp(path: String): OpenOutcome

  /** Shows a folder in the system file manager. */
  suspend fun revealFolder(path: String): OpenOutcome
}
