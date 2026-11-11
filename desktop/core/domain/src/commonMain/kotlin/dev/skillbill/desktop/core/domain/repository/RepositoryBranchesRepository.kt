package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.common.Outcome

/** Local branch reads and operator-requested checkout changes through the runtime. No operation contacts a remote. */
interface RepositoryBranchesRepository {
  suspend fun branches(path: String): RepositoryBranchesRead

  /** Switches to the default branch on operator request, preserving local changes or returning the checkout refusal. */
  suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<Unit, ReadProblem>
}
