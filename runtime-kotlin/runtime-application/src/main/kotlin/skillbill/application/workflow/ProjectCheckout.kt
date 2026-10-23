package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

@Inject
class ProjectCheckout(
  private val repositories: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
) {
  fun repositoryIdentity(repoRoot: Path): String = repositories.repositoryIdentity(repoRoot)

  fun store(repoRoot: Path): ProjectStoreLocation = projectStore.resolve(repoRoot)
}
