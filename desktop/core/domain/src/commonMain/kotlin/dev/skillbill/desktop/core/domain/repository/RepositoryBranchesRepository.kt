package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.common.Outcome

/** Local branch reads and operator-requested checkout changes through the runtime. No operation contacts a remote. */
interface RepositoryBranchesRepository {
  suspend fun branches(path: String): RepositoryBranchesRead

  /** Returns the selected default-branch checkout root, reusing an existing worktree or switching without discarding changes. */
  suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<String, ReadProblem>
}
