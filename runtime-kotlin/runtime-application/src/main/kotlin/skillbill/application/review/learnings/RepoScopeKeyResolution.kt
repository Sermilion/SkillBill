package skillbill.application.review.learnings

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryOriginScopeKeyPort
import skillbill.ports.repository.model.OriginScopeKey
import java.nio.file.Path

internal fun RepositoryOriginScopeKeyPort.repoScopeKeyOrNull(
  repoRoot: Path,
  diagnostics: RuntimeDiagnostics,
  degradationWarning: (String) -> String,
): String? =
  when (val origin = resolveOriginScopeKey(repoRoot)) {
    is OriginScopeKey.Resolved -> origin.key
    is OriginScopeKey.Unavailable -> {
      diagnostics.warning(degradationWarning(origin.reason))
      null
    }
  }
