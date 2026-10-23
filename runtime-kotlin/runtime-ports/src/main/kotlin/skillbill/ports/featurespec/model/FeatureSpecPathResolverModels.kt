package skillbill.ports.featurespec.model

import java.nio.file.Path

data class FeatureSpecPathResolveInput(
  val issueKey: String,
  val explicitSpecPath: String?,
  val repoRoot: Path,
)

enum class FeatureSpecOrigin { STORE, LEGACY }

sealed interface FeatureSpecPathResolveResult {
  val specPath: String?

  data class Explicit(override val specPath: String, val origin: FeatureSpecOrigin) : FeatureSpecPathResolveResult

  data class SingleMatch(override val specPath: String, val origin: FeatureSpecOrigin) : FeatureSpecPathResolveResult

  data class NoMatch(val issueKey: String, val specsRoot: Path) : FeatureSpecPathResolveResult {
    override val specPath: String? = null
  }

  data class Ambiguous(val issueKey: String, val matches: List<Path>) : FeatureSpecPathResolveResult {
    override val specPath: String? = null
  }
}
