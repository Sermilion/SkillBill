package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

internal fun RepositoryEnclosingRootPort.governedSpecPathForCli(
  projectStore: ProjectStorePort,
  repositoryRoot: Path,
  specPath: Path,
): String =
  when (
    val result = resolveFeatureTaskGovernedSpecPath(this, projectStore, repositoryRoot, specPath)
  ) {
    is FeatureTaskGovernedSpecPathResult.Ok -> result.relativePath
    is FeatureTaskGovernedSpecPathResult.OutsideRepository ->
      throw UsageError(
        "Governed spec path must be beneath the project store specs root " +
          "${projectStore.resolve(repositoryRoot).specsRoot} (`skill-bill project path` prints the project store), " +
          "or beneath a legacy ${LegacyProjectStatePaths.FEATURE_SPECS_PREFIX} in the checkout " +
          "('${result.repositoryRoot}').",
      )
    FeatureTaskGovernedSpecPathResult.InvalidGovernedPath ->
      throw UsageError(
        "Governed spec path must be Markdown beneath the project store specs root " +
          "${projectStore.resolve(repositoryRoot).specsRoot} (`skill-bill project path` prints the project store).",
      )
  }
