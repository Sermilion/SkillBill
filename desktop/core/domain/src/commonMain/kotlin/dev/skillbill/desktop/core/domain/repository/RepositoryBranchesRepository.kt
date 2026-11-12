package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.common.Outcome

/** Local branch reads and operator-requested checkout changes through the runtime. No operation contacts a remote. */
interface RepositoryBranchesRepository {
  suspend fun branches(path: String): RepositoryBranchesRead

  /** Returns the root of a checkout on [branch], reusing a worktree that already holds it or switching without discarding changes. */
  suspend fun switchToBranch(path: String, branch: String, expectedCurrentBranch: String?): Outcome<String, ReadProblem>
}
