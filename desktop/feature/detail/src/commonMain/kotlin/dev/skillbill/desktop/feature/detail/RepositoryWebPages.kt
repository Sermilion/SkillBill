package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.OpenOutcome

/** Opens a branch's page on the repository's web host in the default browser. */
interface RepositoryWebPages {
  suspend fun openBranch(repositoryRoot: String, branch: String): OpenOutcome
}
