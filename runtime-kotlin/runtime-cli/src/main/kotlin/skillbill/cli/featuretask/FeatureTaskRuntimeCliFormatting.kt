package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

internal fun RepositoryEnclosingRootPort.governedSpecPathForCli(
  repositoryRoot: Path,
  specPath: Path,
): String =
  when (
    val result = resolveFeatureTaskGovernedSpecPath(this, repositoryRoot, specPath)
  ) {
    is FeatureTaskGovernedSpecPathResult.Ok -> result.relativePath
    is FeatureTaskGovernedSpecPathResult.OutsideRepository ->
      throw UsageError("Governed spec path must remain inside repository '${result.repositoryRoot}'.")
    FeatureTaskGovernedSpecPathResult.InvalidGovernedPath ->
      throw UsageError("Governed spec path must be Markdown beneath .feature-specs/.")
  }
