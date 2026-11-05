package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead

/** The branches a repository already knows about, read from git through the runtime without contacting a remote. */
interface RepositoryBranchesRepository {
  suspend fun branches(path: String): RepositoryBranchesRead
}
